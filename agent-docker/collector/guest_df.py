"""
Đọc dung lượng ổ đĩa chính xác như `df` bên trong VM Linux qua vCenter Guest Operations
(VMware Tools chạy lệnh trong VM, không cần cài gì thêm).

Luồng cho mỗi VM:
  1. StartProgramInGuest:  /bin/sh -c "df -B1 -P ... > /tmp/.vmportal_df_<ngẫu nhiên>"
  2. ListProcessesInGuest: chờ lệnh chạy xong
  3. InitiateFileTransferFromGuest + tải file qua HTTPS (từ host ESXi đang chạy VM)
  4. DeleteFileInGuest:    xoá file tạm

Cần:
  - Tài khoản vCenter có thêm quyền: Virtual machine > Guest operations > Guest operation queries,
    Guest operation program execution, Guest operation modifications.
  - Một tài khoản hệ điều hành (không cần root) đăng nhập được trên các VM Linux.
  - Máy chạy collector tới được host ESXi qua cổng 443 (file kết quả tải từ ESXi).
"""

import secrets
import threading
import ssl
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from pyVmomi import vim

DF_CMD = "LC_ALL=C df -B1 -P -x tmpfs -x devtmpfs -x squashfs -x overlay -x efivarfs"
MEM_MARK = "===VMPORTAL_MEMINFO==="
AUTH_BACKOFF = 6 * 3600     # sai mật khẩu: 6 giờ mới thử lại VM đó (tránh khoá tài khoản)
ERROR_BACKOFF = 30 * 60     # lỗi khác: 30 phút


def parse_df(text):
    """Phân tích output `df -B1 -P`. Trả về danh sách mount giống agent (percent tính như df)."""
    mounts = []
    for line in text.splitlines()[1:]:
        p = line.split()
        if len(p) < 6:
            continue
        try:
            total, used, avail = int(p[1]), int(p[2]), int(p[3])
        except ValueError:
            continue
        if total <= 0:
            continue
        mount = " ".join(p[5:])
        base = used + avail
        mounts.append({"mount": mount, "total": total, "used": used, "avail": avail,
                       "percent": round(used / base * 100, 1) if base else 0.0})
    return mounts


def parse_meminfo(text):
    """/proc/meminfo -> RAM giống lệnh `free` bản mới: used = MemTotal - MemAvailable."""
    kv = {}
    for line in text.splitlines():
        k, _, v = line.partition(":")
        parts = v.split()
        if parts and parts[0].isdigit():
            kv[k.strip()] = int(parts[0]) * 1024
    total = kv.get("MemTotal")
    avail = kv.get("MemAvailable", kv.get("MemFree"))
    if not total or avail is None:
        return None
    used = total - avail
    return {"total": total, "used": used, "available": avail, "percent": round(used / total * 100, 1)}


class GuestDiskReader:
    def __init__(self, si, cfg, vc_host, insecure, log):
        self.si = si
        self.cfg = cfg
        self.vc_host = vc_host
        self.log = log
        self.creds = vim.vm.guest.NamePasswordAuthentication(username=cfg["user"], password=cfg["password"])
        self.ctx = ssl.create_default_context()
        if insecure:
            self.ctx.check_hostname = False
            self.ctx.verify_mode = ssl.CERT_NONE
        self.cache = {}       # vm moId -> {"mounts": [...], "mem": {...}, "ts": epoch}
        self.skip_until = {}  # vm moId -> epoch
        self.last_run = 0
        self.running = False
        self.last_stats = None

    # ---------- một VM ----------
    def read_vm(self, vm):
        gom = self.si.content.guestOperationsManager
        pm, fm = gom.processManager, gom.fileManager
        path = f"/tmp/.vmportal_df_{secrets.token_hex(6)}"
        spec = vim.vm.guest.ProcessManager.ProgramSpec(
            programPath="/bin/sh",
            arguments=f"-c '{{ {DF_CMD}; echo {MEM_MARK}; cat /proc/meminfo; }} > {path} 2>/dev/null'")
        pid = pm.StartProgramInGuest(vm, self.creds, spec)
        deadline = time.time() + float(self.cfg.get("timeout", 30))
        try:
            while True:
                procs = pm.ListProcessesInGuest(vm, self.creds, [pid])
                if procs and procs[0].endTime is not None:
                    break
                if time.time() > deadline:
                    raise TimeoutError("df chạy quá lâu trong VM")
                time.sleep(0.5)
            info = fm.InitiateFileTransferFromGuest(vm, self.creds, path)
            url = info.url
            # host "*" nghĩa là dùng chính máy chủ đang được gọi (vCenter)
            parsed = urllib.parse.urlsplit(url)
            if parsed.hostname == "*":
                url = url.replace("://*", f"://{self.vc_host}", 1)
            with urllib.request.urlopen(url, context=self.ctx, timeout=20) as r:
                text = r.read(1024 * 1024).decode("utf-8", "replace")
        finally:
            try:
                fm.DeleteFileInGuest(vm, self.creds, path)
            except Exception:
                pass
        df_text, _, mem_text = text.partition(MEM_MARK)
        mounts = parse_df(df_text)
        if not mounts:
            raise ValueError("output df rỗng")
        return {"mounts": mounts, "mem": parse_meminfo(mem_text)}

    # ---------- một lượt cho nhiều VM (chạy nền) ----------
    def run_batch(self, vms):
        """vms: danh sách (moId, vm_obj, name). Chạy song song, cập nhật cache."""
        self.running = True
        started = time.time()
        stats = {"ok": 0, "auth": 0, "err": 0, "skip": 0}
        errors = {}
        lock = threading.Lock()

        def bump(k, err=None, name=None):
            with lock:
                stats[k] += 1
                if err:
                    errors.setdefault(err, []).append(name)

        def work(item):
            moid, vm, name = item
            if self.skip_until.get(moid, 0) > time.time():
                bump("skip")
                return
            try:
                self.cache[moid] = {**self.read_vm(vm), "ts": time.time()}
                bump("ok")
            except vim.fault.InvalidGuestLogin:
                bump("auth", f"VM từ chối mật khẩu của '{self.cfg['user']}' — {self.password_hint()}", name)
                self.skip_until[moid] = time.time() + AUTH_BACKOFF
            except vim.fault.NoPermission as e:
                priv = getattr(e, "privilegeId", "") or "?"
                obj = getattr(getattr(e, "object", None), "_moId", "")
                bump("err", f"Tài khoản vCenter thiếu quyền '{priv}' trên {obj or 'VM'}{self.explain_roles(vm)}", name)
                self.skip_until[moid] = time.time() + ERROR_BACKOFF
            except Exception as e:
                bump("err", type(e).__name__ + (f": {getattr(e, 'msg', '') or e}"[:120]), name)
                self.skip_until[moid] = time.time() + ERROR_BACKOFF

        try:
            with ThreadPoolExecutor(max_workers=int(self.cfg.get("concurrency", 8))) as ex:
                list(ex.map(work, vms))
        finally:
            self.running = False
            self.last_run = time.time()
        self.last_stats = {**stats, "at": time.time(), "errors": {k: len(v) for k, v in errors.items()}}
        self.log(f"Guest df: {stats['ok']} VM đọc được, {stats['auth']} sai tài khoản HĐH, {stats['err']} lỗi, "
                 f"{stats['skip']} tạm bỏ qua — {time.time() - started:.0f}s")
        for k, names in list(errors.items())[:5]:
            self.log(f"  Guest df lỗi [{len(names)} VM] {k} (vd: {', '.join(names[:3])})")

    def password_hint(self):
        """Mô tả mật khẩu đang dùng mà KHÔNG lộ nội dung: độ dài, khoảng trắng, ký tự lạ."""
        pw = self.cfg.get("password", "")
        notes = [f"mật khẩu collector nhận được dài {len(pw)} ký tự"]
        if pw != pw.strip():
            notes.append("CÓ dấu cách ở đầu/cuối")
        if any(ord(ch) > 126 for ch in pw):
            notes.append("CÓ ký tự không phải ASCII (bộ gõ tiếng Việt?)")
        if any(ord(ch) < 32 for ch in pw):
            notes.append("CÓ ký tự điều khiển")
        return ", ".join(notes)

    def explain_roles(self, vm):
        """Role thực tế vCenter áp cho phiên đăng nhập này trên VM (để biết quyền gán sai chỗ nào)."""
        try:
            am = self.si.content.authorizationManager
            roles = {r.roleId: r for r in am.roleList}
            parts = []
            for rid in vm.effectiveRole or []:
                r = roles.get(rid)
                if not r:
                    continue
                guest = sorted(p.split(".")[-1] for p in (r.privilege or []) if p.startswith("VirtualMachine.GuestOperations."))
                parts.append(f"{r.info.label or r.name} [Guest ops: {', '.join(guest) or 'KHÔNG CÓ'}]")
            user = self.si.content.sessionManager.currentSession.userName
            return f" — role thực tế của {user} trên VM này: {'; '.join(parts) or '?'}"
        except Exception as ex:
            return f" — (không đọc được role: {type(ex).__name__})"

    def mem_for(self, moid, max_age):
        c = self.cache.get(moid)
        return c.get("mem") if c and time.time() - c["ts"] < max_age else None

    def mounts_for(self, moid, max_age):
        c = self.cache.get(moid)
        return c["mounts"] if c and time.time() - c["ts"] < max_age else None
