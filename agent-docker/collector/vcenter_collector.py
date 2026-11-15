#!/usr/bin/env python3
"""
vCenter collector — lấy CPU / RAM / Network / Disk của mọi VM từ một hoặc nhiều vCenter (nhiều site)
và đẩy vào VM Monitor Portal theo lô. Không cần cài agent trong VM.

Cài đặt:   pip install pyvmomi
Chạy:      python vcenter_collector.py                     (đọc config.json, mục "vcenters")
Thử:       python vcenter_collector.py --once --dry-run    (in 3 VM mỗi vCenter ra màn hình, không gửi)

Mật khẩu mỗi vCenter lấy theo thứ tự:
  biến môi trường VCENTER_PASSWORD_<SITE>  (vd VCENTER_PASSWORD_HCM; site "auto" dùng host, vd VCENTER_PASSWORD_10_10_0_10)
  -> VCENTER_PASSWORD (dùng chung) -> "password" trong config.json -> hỏi khi chạy.

Dung lượng ổ chính xác như `df` (VM Linux): bật "guestOps" trong từng vCenter, xem guest_df.py.
Mật khẩu tài khoản HĐH: VCENTER_GUEST_PASSWORD_<SITE> -> VCENTER_GUEST_PASSWORD -> guestOps.password -> hỏi khi chạy.
"""

import argparse
import fnmatch
import getpass
import json
import os
import re
import ssl
import sys
import threading
import time
import urllib.parse
import urllib.request
from pathlib import Path

try:
    from pyVim.connect import SmartConnect, Disconnect
    from pyVmomi import vim, vmodl
except ImportError:
    sys.exit("Thiếu thư viện pyVmomi. Cài bằng:  pip install pyvmomi")
from guest_df import GuestDiskReader

COUNTERS = {
    "cpu": "cpu.usage.average",            # đơn vị 1/100 %
    "mem": "mem.usage.average",            # đơn vị 1/100 %
    "rx": "net.received.average",          # KBps
    "tx": "net.transmitted.average",       # KBps
    "rd": "disk.read.average",             # KBps
    "wr": "disk.write.average",            # KBps
}
VM_PROPS = [
    "name", "runtime.powerState", "runtime.host", "config.hardware.numCPU", "config.hardware.memoryMB",
    "config.guestFullName", "guest.guestFullName", "guest.hostName", "guest.ipAddress", "guest.disk",
    "summary.quickStats.overallCpuUsage", "summary.quickStats.guestMemoryUsage",
    "summary.quickStats.uptimeSeconds", "summary.runtime.maxCpuUsage", "summary.storage.committed",
    "guest.toolsRunningStatus", "guest.guestFamily",
    "parent", "config.annotation", "customValue", "resourcePool",
]
TOPOLOGY_REFRESH = 600  # giây
HOST_PROPS = [
    "name", "runtime.connectionState", "runtime.powerState", "runtime.inMaintenanceMode", "summary.overallStatus",
    "summary.quickStats.overallCpuUsage", "summary.quickStats.overallMemoryUsage", "summary.quickStats.uptime",
    "summary.hardware.cpuMhz", "summary.hardware.numCpuCores", "summary.hardware.numCpuThreads", "summary.hardware.memorySize",
    "summary.hardware.vendor", "summary.hardware.model", "summary.config.product.fullName", "summary.managementServerIp",
]
DS_PROPS = ["name", "summary.capacity", "summary.freeSpace", "summary.uncommitted", "summary.type", "summary.accessible",
            "summary.maintenanceMode", "host", "vm"]


LOG_FILE = Path(os.environ.get("COLLECTOR_LOG") or Path(__file__).with_name("data") / "collector.log")
_log_lock = threading.Lock()


def log(site, msg):
    line = f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] [{site}] {msg}"
    print(line, flush=True)
    with _log_lock:  # ghi thêm ra data/collector.log (xoay vòng khi > 5 MB)
        try:
            LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
            if LOG_FILE.exists() and LOG_FILE.stat().st_size > 5 * 1024 * 1024:
                # collector.log -> .log.1 -> .log.2 -> .log.3 (cũ nhất bị xoá)
                for i in (2, 1):
                    old = LOG_FILE.with_suffix(f".log.{i}")
                    if old.exists():
                        old.replace(LOG_FILE.with_suffix(f".log.{i + 1}"))
                LOG_FILE.replace(LOG_FILE.with_suffix(".log.1"))
            with open(LOG_FILE, "a", encoding="utf-8") as f:
                print(line, file=f)
        except OSError:
            pass


def portal_ctx():
    """SSL khi gọi portal: MONITOR_INSECURE=1 bỏ kiểm tra chứng chỉ (portal dùng chứng chỉ tự ký)."""
    if os.environ.get("MONITOR_INSECURE", "").lower() in ("1", "true", "yes"):
        ctx = ssl.create_default_context()
        ctx.check_hostname = False
        ctx.verify_mode = ssl.CERT_NONE
        return ctx
    return None


def env_key(s):
    return re.sub(r"[^A-Za-z0-9]", "_", s).upper()


def read_local(path):
    data = {}
    if Path(path).exists():
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
    common = data.get("collector", {})
    portal = os.environ.get("MONITOR_SERVER") or common.get("portal") or f"http://localhost:{data.get('port', 3000)}"
    token = os.environ.get("MONITOR_TOKEN") or common.get("token") or ""
    vcs = data.get("vcenters") or ([data["vcenter"]] if data.get("vcenter") else [])
    return vcs, portal, token


def normalize_vc(vc, portal, token):
    """vCenter (từ config.json hoặc từ portal) -> cấu hình chạy. Mật khẩu: portal -> biến môi trường."""
    c = {
        "site": "auto", "port": 443, "insecure": True, "interval": 20,
        "include": ["*"], "exclude": ["vCLS-*"], "prefix": "", "sendPoweredOff": True,
        **vc,
    }
    c["portal"], c["token"] = portal, token
    label = c["site"] if c["site"] != "auto" else c["host"]
    c["label"] = label
    c.setdefault("id", "vc-" + "".join(ch if ch.isalnum() else "-" for ch in str(c["host"]).lower()))
    c["password"] = (vc.get("password") or os.environ.get(f"VCENTER_PASSWORD_{env_key(label)}")
                     or os.environ.get("VCENTER_PASSWORD") or "")
    c["skip"] = bool(os.environ.get(f"VCENTER_SKIP_{env_key(label)}")) and not vc.get("password")
    g = dict(c.get("guestOps") or {})
    if g.get("enabled") and not (os.environ.get(f"VCENTER_GUEST_SKIP_{env_key(label)}") and not g.get("password")):
        g = {"intervalMinutes": 15, "concurrency": 8, "timeout": 30, "include": ["*"], "exclude": [], **g}
        g["intervalSeconds"] = float(g.get("intervalSeconds") or g["intervalMinutes"] * 60)
        g["password"] = (g.get("password") or os.environ.get(f"VCENTER_GUEST_PASSWORD_{env_key(label)}")
                         or os.environ.get("VCENTER_GUEST_PASSWORD") or "")
        c["guestOps"] = g if g["password"] else None
    else:
        c["guestOps"] = None
    return c


def load_config(path):
    vcs, portal, token = read_local(path)
    return [c for c in (normalize_vc(v, portal, token) for v in vcs if v.get("enabled") is not False) if not c["skip"]]


def retrieve(content, container, obj_type, props):
    """Lấy thuộc tính của mọi object kiểu obj_type dưới container bằng 1 lần PropertyCollector."""
    view = content.viewManager.CreateContainerView(container, [obj_type], True)
    try:
        PC = vmodl.query.PropertyCollector
        spec = PC.FilterSpec(
            objectSet=[PC.ObjectSpec(obj=view, skip=True, selectSet=[
                PC.TraversalSpec(name="traverseView", path="view", skip=False, type=vim.view.ContainerView)])],
            propSet=[PC.PropertySpec(type=obj_type, pathSet=props)],
        )
        result = []
        res = content.propertyCollector.RetrievePropertiesEx([spec], PC.RetrieveOptions(maxObjects=1000))
        while res:
            for obj in res.objects:
                d = {p.name: p.val for p in obj.propSet}
                d["_obj"] = obj.obj
                result.append(d)
            res = content.propertyCollector.ContinueRetrievePropertiesEx(res.token) if res.token else None
        return result
    finally:
        view.Destroy()


class Collector:
    def __init__(self, cfg):
        self.cfg = cfg
        self.label = cfg["site"] if cfg["site"] != "auto" else cfg["host"]
        self.si = None
        self.counter_ids = {}
        self.hosts = {}          # host moId -> {name, cluster, datacenter}
        self.folders = {}        # folder moId -> (name, parent moId hoặc None nếu là thư mục gốc "vm")
        self.fields = {}         # custom attribute key -> tên
        self.pools = {}          # resource pool moId -> tên
        self.tags = {}           # vm moId -> ["Category:Tag", ...]
        self.tags_error = ""
        self.topology_at = 0
        self.guest = None        # GuestDiskReader khi bật guestOps
        self.stop = threading.Event()
        self.status = {"id": cfg.get("id"), "host": cfg["host"], "label": self.label, "state": "connecting",
                       "message": "Đang kết nối", "vms": 0, "running": 0, "sent": 0, "failed": 0, "lastCycle": 0}

    def set_status(self, state, message="", **kw):
        self.status.update(state=state, message=message, **kw)
        if self.guest and self.guest.last_stats:
            self.status["guest"] = self.guest.last_stats

    # ---------- kết nối ----------
    def connect(self):
        ctx = ssl.create_default_context()
        if self.cfg["insecure"]:
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
        self.si = SmartConnect(host=self.cfg["host"], user=self.cfg["user"], pwd=self.cfg["password"],
                               port=int(self.cfg["port"]), sslContext=ctx)
        self.vc_version = self.si.content.about.fullName
        log(self.label, f"Đã kết nối {self.cfg['host']} — {self.vc_version}")
        pm = self.si.content.perfManager
        by_name = {f"{c.groupInfo.key}.{c.nameInfo.key}.{c.rollupType}": c.key for c in pm.perfCounter}
        self.counter_ids = {k: by_name[v] for k, v in COUNTERS.items() if v in by_name}
        missing = set(COUNTERS) - set(self.counter_ids)
        if missing:
            log(self.label, f"Cảnh báo: thiếu counter {', '.join(COUNTERS[m] for m in missing)}")
        self.topology_at = 0
        g = self.cfg.get("guestOps")
        if g:
            old = self.guest
            self.guest = GuestDiskReader(self.si, g, self.cfg["host"], self.cfg["insecure"], lambda m: log(self.label, m))
            if old:  # giữ kết quả cũ khi kết nối lại
                self.guest.cache, self.guest.skip_until, self.guest.last_run = old.cache, old.skip_until, old.last_run
            log(self.label, f"Guest df+RAM: bật cho VM Linux, tài khoản HĐH '{g['user']}', mỗi {g['intervalSeconds']:.0f} giây")

    def disconnect(self):
        if self.si:
            try:
                Disconnect(self.si)
            except Exception:
                pass
            self.si = None

    # ---------- datacenter / cluster / host ----------
    def refresh_topology(self):
        content = self.si.content
        clusters = {c["_obj"]._moId: c.get("name", "") for c in retrieve(content, content.rootFolder, vim.ComputeResource, ["name"])}
        hosts = {}
        for dc in retrieve(content, content.rootFolder, vim.Datacenter, ["name"]):
            for h in retrieve(content, dc["_obj"], vim.HostSystem, ["name", "parent"]):
                parent = h.get("parent")
                cl = clusters.get(parent._moId, "") if parent is not None else ""
                hosts[h["_obj"]._moId] = {"name": h.get("name", ""), "cluster": cl if isinstance(parent, vim.ClusterComputeResource) else "", "datacenter": dc.get("name", "")}
        self.hosts = hosts
        self.topology_at = time.time()
        # thư mục VM, custom attribute, resource pool, vSphere tag (để nhận diện khách hàng)
        try:
            folders = {}
            for f in retrieve(content, content.rootFolder, vim.Folder, ["name", "parent", "childType"]):
                par = f.get("parent")
                # thư mục gốc "vm" của datacenter (cha là Datacenter) -> coi là gốc, không đưa vào đường dẫn
                root = par is not None and isinstance(par, vim.Datacenter)
                folders[f["_obj"]._moId] = (None if root else f.get("name", ""), None if root or par is None or not isinstance(par, vim.Folder) else par._moId)
            self.folders = folders
            self.fields = {f.key: f.name for f in (content.customFieldsManager.field or [])} if content.customFieldsManager else {}
            self.pools = {r["_obj"]._moId: r.get("name", "") for r in retrieve(content, content.rootFolder, vim.ResourcePool, ["name"])}
        except Exception as e:
            log(self.label, f"Không đọc được thư mục / custom attribute: {type(e).__name__}: {e}")
        self.refresh_tags()
        dcs = sorted({h["datacenter"] for h in hosts.values()})
        log(self.label, f"Topology: {len(dcs)} datacenter ({', '.join(dcs)}), {len(hosts)} host ESXi")

    def refresh_tags(self):
        """vSphere Tag qua vSphere Automation REST API (vCenter 7+: /api, cũ hơn: /rest)."""
        ctx = ssl.create_default_context()
        if self.cfg["insecure"]:
            ctx.check_hostname = False
            ctx.verify_mode = ssl.CERT_NONE
        base = f"https://{self.cfg['host']}:{self.cfg.get('port', 443)}"
        import base64

        def call(method, path, body=None, sid=None, basic=False):
            h = {"content-type": "application/json"}
            if sid:
                h["vmware-api-session-id"] = sid
            if basic:
                h["authorization"] = "Basic " + base64.b64encode(f"{self.cfg['user']}:{self.cfg['password']}".encode()).decode()
            req = urllib.request.Request(base + path, method=method, headers=h, data=json.dumps(body).encode() if body is not None else None)
            with urllib.request.urlopen(req, context=ctx, timeout=30) as r:
                raw = r.read()
                return json.loads(raw) if raw else None

        sid = None
        try:
            sid = call("POST", "/api/session", basic=True)
            cats = {c: (call("GET", f"/api/cis/tagging/category/{c}", sid=sid) or {}).get("name", c) for c in call("GET", "/api/cis/tagging/category", sid=sid) or []}
            tag_ids = call("GET", "/api/cis/tagging/tag", sid=sid) or []
            names = {}
            for t in tag_ids:
                d = call("GET", f"/api/cis/tagging/tag/{t}", sid=sid) or {}
                names[t] = f"{cats.get(d.get('category_id'), '?')}:{d.get('name', t)}"
            tags = {}
            for i in range(0, len(tag_ids), 100):
                res = call("POST", "/api/cis/tagging/tag-association?action=list-attached-objects-on-tags", {"tag_ids": tag_ids[i:i + 100]}, sid=sid) or []
                for item in res:
                    for obj in item.get("object_ids", []):
                        if obj.get("type") == "VirtualMachine":
                            tags.setdefault(obj["id"], []).append(names.get(item.get("tag_id"), item.get("tag_id")))
            self.tags = tags
            if self.tags_error:
                log(self.label, "Đọc được vSphere Tag trở lại")
            self.tags_error = ""
            log(self.label, f"vSphere Tag: {len(tag_ids)} tag trong {len(cats)} category, gắn trên {len(tags)} VM")
        except Exception as e:
            msg = f"{type(e).__name__}: {getattr(e, 'reason', '') or e}"
            if msg != self.tags_error:
                log(self.label, f"Không đọc được vSphere Tag ({msg}) — bỏ qua phần tag")
            self.tags_error = msg
        finally:
            if sid:
                try:
                    call("DELETE", "/api/session", sid=sid)
                except Exception:
                    pass

    def folder_path(self, ref):
        parts, cur, guard = [], ref._moId if ref is not None else None, 0
        while cur and cur in self.folders and guard < 30:
            name, par = self.folders[cur]
            if name:
                parts.append(name)
            cur, guard = par, guard + 1
        return "/".join(reversed(parts))

    def wanted(self, name):
        return (any(fnmatch.fnmatch(name, p) for p in self.cfg["include"])
                and not any(fnmatch.fnmatch(name, p) for p in self.cfg["exclude"]))

    # ---------- số liệu hiệu năng realtime (20 giây) ----------
    def fetch_perf(self, vm_objs):
        pm = self.si.content.perfManager
        metric_ids = [vim.PerformanceManager.MetricId(counterId=cid, instance="") for cid in self.counter_ids.values()]
        reverse = {cid: key for key, cid in self.counter_ids.items()}
        out = {}
        for i in range(0, len(vm_objs), 100):
            specs = [vim.PerformanceManager.QuerySpec(entity=o, metricId=metric_ids, intervalId=20, maxSample=1)
                     for o in vm_objs[i:i + 100]]
            try:
                results = pm.QueryPerf(querySpec=specs) or []
            except vmodl.fault.InvalidArgument as e:
                log(self.label, f"QueryPerf lỗi: {e.msg}")
                continue
            for r in results:
                vals = {}
                for s in r.value:
                    key = reverse.get(s.id.counterId)
                    if key and s.value:
                        vals[key] = s.value[-1]
                out[r.entity._moId] = vals
        return out

    # ---------- chuyển sang định dạng portal ----------
    def build(self, p, perf):
        mem_mb = p.get("config.hardware.memoryMB") or 0
        mem_total = mem_mb * 1024 * 1024
        power_state = str(p.get("runtime.powerState") or "")
        power = {"poweredOn": "on", "poweredOff": "off", "suspended": "suspended"}.get(power_state, "on")
        on = power == "on"

        if not on:
            cpu = mem_pct = 0
        elif "cpu" in perf:
            cpu = perf["cpu"] / 100
            mem_pct = perf["mem"] / 100 if "mem" in perf else 0
        else:
            max_mhz = p.get("summary.runtime.maxCpuUsage") or 0
            cpu = (p.get("summary.quickStats.overallCpuUsage") or 0) / max_mhz * 100 if max_mhz else 0
            mem_pct = 0
        if on and "mem" not in perf:
            mem_pct = (p.get("summary.quickStats.guestMemoryUsage") or 0) / mem_mb * 100 if mem_mb else 0

        mounts = []
        disk_source = "tools"
        g = self.cfg.get("guestOps")
        gm = self.guest.mounts_for(p["_obj"]._moId, max(g["intervalSeconds"] * 3, 120)) if self.guest and g else None
        gmem = self.guest.mem_for(p["_obj"]._moId, max(g["intervalSeconds"] * 3, 120)) if self.guest and g and on else None
        if gm:
            mounts, disk_source = gm, "guest"
        for d in ([] if gm else (p.get("guest.disk") or [])):
            if d.capacity:
                used = d.capacity - d.freeSpace
                mounts.append({"mount": d.diskPath, "total": d.capacity, "used": used,
                               "percent": round(used / d.capacity * 100, 1)})
        d_total = sum(m["total"] for m in mounts)
        d_used = sum(m["used"] for m in mounts)
        d_avail = sum(m.get("avail", m["total"] - m["used"]) for m in mounts)
        d_pct = d_used / (d_used + d_avail) * 100 if gm and d_used + d_avail else (d_used / d_total * 100 if d_total else 0)

        host_ref = p.get("runtime.host")
        topo = self.hosts.get(host_ref._moId, {}) if host_ref is not None else {}
        dcs = {h["datacenter"] for h in self.hosts.values() if h.get("datacenter")}
        only_dc = next(iter(dcs)) if len(dcs) == 1 else ""
        site = self.cfg["site"] if self.cfg["site"] != "auto" else (topo.get("datacenter") or only_dc or self.cfg["host"])

        kb = lambda k: max(0, perf.get(k, 0)) * 1024 if on else 0
        clamp = lambda x: max(0.0, min(100.0, round(x, 1)))
        return {
            "site": site,
            "name": self.cfg["prefix"] + p["name"],
            "hostname": p.get("guest.hostName") or p["name"],
            "ip": p.get("guest.ipAddress") or "",
            "os": p.get("guest.guestFullName") or p.get("config.guestFullName") or "",
            "cores": p.get("config.hardware.numCPU") or 0,
            "uptime": p.get("summary.quickStats.uptimeSeconds") or 0,
            "source": "vcenter",
            "power": power,
            "cluster": topo.get("cluster", ""),
            "esxiHost": topo.get("name", ""),
            "datacenter": topo.get("datacenter", "") or only_dc,
            "diskSource": disk_source,
            "vcenter": self.cfg["host"],
            "cpuMhz": (p.get("summary.quickStats.overallCpuUsage") or 0) if on else 0,
            "folder": self.folder_path(p.get("parent")),
            "notes": (p.get("config.annotation") or "")[:500],
            "attributes": {self.fields.get(cv.key, str(cv.key)): getattr(cv, "value", "") for cv in (p.get("customValue") or []) if getattr(cv, "value", "")},
            "tags": self.tags.get(p["_obj"]._moId, []),
            "resourcePool": self.pools.get(p["resourcePool"]._moId, "") if p.get("resourcePool") is not None else "",
            "cpu": {"percent": clamp(cpu)},
            "mem": ({"total": gmem["total"], "used": gmem["used"], "percent": clamp(gmem["percent"])} if gmem
                    else {"total": mem_total, "used": mem_total * clamp(mem_pct) / 100, "percent": clamp(mem_pct)}),
            "memSource": "guest" if gmem else "active",
            "disk": {"total": d_total, "used": d_used, "percent": clamp(d_pct),
                     "mounts": mounts, "readRate": kb("rd"), "writeRate": kb("wr")},
            "net": {"rxRate": kb("rx"), "txRate": kb("tx")},
        }

    def schedule_guest(self, on, dry_run):
        g = self.cfg.get("guestOps")
        if not self.guest or self.guest.running or time.time() - self.guest.last_run < g["intervalSeconds"]:
            return
        targets = [(p["_obj"]._moId, p["_obj"], p.get("name", "")) for p in on
                   if p.get("guest.guestFamily") == "linuxGuest" and p.get("guest.toolsRunningStatus") == "guestToolsRunning"
                   and any(fnmatch.fnmatch(p.get("name", ""), x) for x in g["include"])
                   and not any(fnmatch.fnmatch(p.get("name", ""), x) for x in g["exclude"])]
        if not targets:
            return
        log(self.label, f"Guest df: bắt đầu đọc {len(targets)} VM Linux (chạy nền)")
        if dry_run:  # chạy thử: đọc 3 VM, đợi xong để in kết quả
            self.guest.run_batch(targets[:3])
        else:
            threading.Thread(target=self.guest.run_batch, args=(targets,), daemon=True).start()

    # ---------- host ESXi + datastore ----------
    def site_for(self, topo):
        dcs = {h["datacenter"] for h in self.hosts.values() if h.get("datacenter")}
        only_dc = next(iter(dcs)) if len(dcs) == 1 else ""
        return self.cfg["site"] if self.cfg["site"] != "auto" else (topo.get("datacenter") or only_dc or self.cfg["host"])

    def collect_hosts(self, vms):
        content = self.si.content
        hosts = retrieve(content, content.rootFolder, vim.HostSystem, HOST_PROPS)
        vm_count, vm_on = {}, {}
        for p in vms:
            h = p.get("runtime.host")
            if h is None:
                continue
            vm_count[h._moId] = vm_count.get(h._moId, 0) + 1
            if p.get("runtime.powerState") == vim.VirtualMachinePowerState.poweredOn:
                vm_on[h._moId] = vm_on.get(h._moId, 0) + 1
        connected = [h["_obj"] for h in hosts if str(h.get("runtime.connectionState")) == "connected"]
        perf = self.fetch_perf(connected)
        out_hosts = []
        for h in hosts:
            moid = h["_obj"]._moId
            topo = self.hosts.get(moid, {})
            pf = perf.get(moid, {})
            mhz = (h.get("summary.hardware.cpuMhz") or 0) * (h.get("summary.hardware.numCpuCores") or 0)
            cpu_used = h.get("summary.quickStats.overallCpuUsage") or 0
            mem_total = h.get("summary.hardware.memorySize") or 0
            mem_used = (h.get("summary.quickStats.overallMemoryUsage") or 0) * 1024 * 1024
            conn = str(h.get("runtime.connectionState") or "")
            kb = lambda k: max(0, pf.get(k, 0)) * 1024
            out_hosts.append({
                "site": self.site_for(topo), "name": h.get("name", ""), "cluster": topo.get("cluster", ""),
                "datacenter": topo.get("datacenter", ""), "vcenter": self.cfg["host"],
                "connection": conn, "power": str(h.get("runtime.powerState") or ""),
                "maintenance": bool(h.get("runtime.inMaintenanceMode")), "health": str(h.get("summary.overallStatus") or ""),
                "cpu": round(cpu_used / mhz * 100, 1) if mhz else 0, "cpuUsedMhz": cpu_used, "cpuTotalMhz": mhz,
                "cores": h.get("summary.hardware.numCpuCores") or 0, "threads": h.get("summary.hardware.numCpuThreads") or 0,
                "mem": round(mem_used / mem_total * 100, 1) if mem_total else 0, "memUsed": mem_used, "memTotal": mem_total,
                "rx": kb("rx"), "tx": kb("tx"), "rd": kb("rd"), "wr": kb("wr"),
                "uptime": h.get("summary.quickStats.uptime") or 0, "version": h.get("summary.config.product.fullName") or "",
                "vendor": h.get("summary.hardware.vendor") or "", "model": h.get("summary.hardware.model") or "",
                "vms": vm_count.get(moid, 0), "vmsRunning": vm_on.get(moid, 0),
            })
        out_ds = []
        for d in retrieve(content, content.rootFolder, vim.Datastore, DS_PROPS):
            mounts = [m.key._moId for m in (d.get("host") or []) if getattr(m, "key", None) is not None]
            topo = next((self.hosts[m] for m in mounts if m in self.hosts), {})
            cap = d.get("summary.capacity") or 0
            free = d.get("summary.freeSpace") or 0
            uncommitted = d.get("summary.uncommitted") or 0
            out_ds.append({
                "site": self.site_for(topo), "name": d.get("name", ""), "datacenter": topo.get("datacenter", ""), "vcenter": self.cfg["host"],
                "type": d.get("summary.type") or "", "accessible": bool(d.get("summary.accessible")),
                "maintenance": str(d.get("summary.maintenanceMode") or ""),
                "capacity": cap, "free": free, "used": cap - free,
                "percent": round((cap - free) / cap * 100, 1) if cap else 0,
                "provisioned": cap - free + uncommitted, "hosts": len(mounts), "vms": len(d.get("vm") or []),
                "hostNames": sorted({self.hosts[m]["name"] for m in mounts if m in self.hosts})[:50],
            })
        headers = {"content-type": "application/json"}
        if self.cfg.get("token"):
            headers["authorization"] = f"Bearer {self.cfg['token']}"
        req = urllib.request.Request(self.cfg["portal"].rstrip("/") + "/api/v1/hosts/report", method="POST", headers=headers,
                                     data=json.dumps({"hosts": out_hosts, "datastores": out_ds}).encode())
        with urllib.request.urlopen(req, context=portal_ctx(), timeout=30) as r:
            r.read()
        self.status["hosts"] = len(out_hosts)
        self.status["datastores"] = len(out_ds)

    def post(self, batch):
        headers = {"content-type": "application/json"}
        if self.cfg.get("token"):
            headers["authorization"] = f"Bearer {self.cfg['token']}"
        req = urllib.request.Request(self.cfg["portal"].rstrip("/") + "/api/v1/report",
                                     data=json.dumps(batch).encode(), headers=headers, method="POST")
        with urllib.request.urlopen(req, context=portal_ctx(), timeout=30) as r:
            return json.loads(r.read() or b"{}").get("accepted", len(batch))

    def cycle(self, dry_run=False):
        if time.time() - self.topology_at > TOPOLOGY_REFRESH:
            self.refresh_topology()
        content = self.si.content
        vms = [p for p in retrieve(content, content.rootFolder, vim.VirtualMachine, VM_PROPS)
               if self.wanted(p.get("name", "")) and not (p.get("name", "").startswith("vCLS"))]
        on = [p for p in vms if p.get("runtime.powerState") == vim.VirtualMachinePowerState.poweredOn]
        self.schedule_guest(on, dry_run)
        perf = self.fetch_perf([p["_obj"] for p in on])
        payloads = [self.build(p, perf.get(p["_obj"]._moId, {})) for p in vms
                    if self.cfg["sendPoweredOff"] or p.get("runtime.powerState") == vim.VirtualMachinePowerState.poweredOn]
        if dry_run:
            for x in payloads[:3]:
                print(json.dumps(x, ensure_ascii=False))
            log(self.label, f"[dry-run] {len(vms)} VM ({len(on)} đang chạy), sites: {sorted({x['site'] for x in payloads})}")
            return
        sent = failed = 0
        for i in range(0, len(payloads), 500):
            chunk = payloads[i:i + 500]
            try:
                sent += self.post(chunk)
            except Exception as e:
                failed += len(chunk)
                log(self.label, f"Không gửi được tới portal {self.cfg['portal']}: {e}")
        log(self.label, f"{len(vms)} VM ({len(on)} đang chạy) — gửi {sent}, lỗi {failed}")
        try:
            self.collect_hosts(vms)
        except Exception as e:
            log(self.label, f"Không lấy / gửi được số liệu host ESXi: {type(e).__name__}: {e}")
        self.set_status("ok" if not failed else "portal_error",
                        "" if not failed else f"Không gửi được tới portal ({failed} VM)",
                        vms=len(vms), running=len(on), sent=sent, failed=failed, lastCycle=int(time.time() * 1000),
                        datacenters=sorted({h["datacenter"] for h in self.hosts.values() if h.get("datacenter")}),
                        version=getattr(self, "vc_version", ""))

    def run(self, once=False, dry_run=False):
        backoff = 5
        if not self.cfg.get("password"):
            self.set_status("no_password", "Chưa có mật khẩu vCenter — nhập trong trang Cài đặt")
            return
        while not self.stop.is_set():
            started = time.time()
            try:
                if self.si is None:
                    self.set_status("connecting", "Đang kết nối")
                    self.connect()
                self.cycle(dry_run)
                backoff = 5
            except vim.fault.InvalidLogin:
                log(self.label, "Sai tài khoản hoặc mật khẩu vCenter — dừng, chờ cập nhật mật khẩu")
                self.set_status("auth_failed", "Sai tài khoản hoặc mật khẩu vCenter")
                return
            except Exception as e:
                log(self.label, f"Lỗi ({type(e).__name__}: {e}) — thử lại sau {backoff}s")
                self.set_status("error", f"{type(e).__name__}: {e}"[:300])
                self.disconnect()
                if once:
                    return
                self.stop.wait(backoff)
                backoff = min(backoff * 2, 300)
                continue
            if once:
                break
            self.stop.wait(max(1, self.cfg["interval"] - (time.time() - started)))
        self.disconnect()


class Manager:
    """Lấy danh sách vCenter từ portal (trang Cài đặt), chạy / dừng / khởi động lại từng collector, báo trạng thái."""

    def __init__(self, local_path):
        self.local_path = local_path
        _, self.portal, self.token = read_local(local_path)
        try:
            with open(local_path, encoding="utf-8") as f:
                common = json.load(f).get("collector", {})
        except Exception:
            common = {}
        import socket
        self.hostname = socket.gethostname()
        self.name = os.environ.get("COLLECTOR_NAME") or common.get("name") or self.hostname
        self.running = {}   # id -> (Collector, Thread, signature)
        self.extra = {}     # id -> status cho vCenter không chạy (tắt / thiếu mật khẩu)

    def http(self, method, path, body=None):
        headers = {"content-type": "application/json"}
        if self.token:
            headers["authorization"] = f"Bearer {self.token}"
        req = urllib.request.Request(self.portal.rstrip("/") + path, method=method, headers=headers,
                                     data=json.dumps(body).encode() if body is not None else None)
        with urllib.request.urlopen(req, context=portal_ctx(), timeout=15) as r:
            return json.loads(r.read() or b"{}")

    @staticmethod
    def signature(c):
        keys = ["host", "port", "user", "password", "site", "insecure", "interval", "include", "exclude", "prefix", "sendPoweredOff", "guestOps"]
        return json.dumps({k: c.get(k) for k in keys}, sort_keys=True, default=str)

    def apply(self, raw_vcs):
        wanted = {}
        self.extra = {}
        for vc in raw_vcs:
            c = normalize_vc(vc, self.portal, self.token)
            if vc.get("enabled") is False:
                self.extra[c["id"]] = {"id": c["id"], "host": c["host"], "label": c["label"], "state": "disabled", "message": "Đã tắt"}
                continue
            if c["skip"] or not c["password"]:
                self.extra[c["id"]] = {"id": c["id"], "host": c["host"], "label": c["label"], "state": "no_password",
                                       "message": "Chưa có mật khẩu vCenter — nhập trong trang Cài đặt"}
                continue
            wanted[c["id"]] = c
        for vid in list(self.running):
            col, th, sig = self.running[vid]
            if vid not in wanted or self.signature(wanted[vid]) != sig:
                log(col.label, "Cấu hình thay đổi / bị xoá — dừng collector của vCenter này")
                col.stop.set()
                del self.running[vid]
        for vid, c in wanted.items():
            if vid not in self.running:
                col = Collector(c)
                th = threading.Thread(target=col.run, daemon=True)
                th.start()
                self.running[vid] = (col, th, self.signature(c))
                log(col.label, f"Bắt đầu theo dõi vCenter {c['host']}")

    def statuses(self):
        out = [col.status for col, th, _ in self.running.values()]
        return out + list(self.extra.values())

    def loop(self):
        last_cfg = None
        last_fetch = 0
        print(f"Collector '{self.name}': lấy danh sách vCenter từ portal {self.portal} (thêm / sửa trong trang Cài đặt)", flush=True)
        while True:
            if time.time() - last_fetch >= 30 or last_cfg is None:
                last_fetch = time.time()
                try:
                    cfg = self.http("GET", "/api/v1/collector/config?name=" + urllib.parse.quote(self.name))
                    if cfg.get("collector") and cfg["collector"] != self.name:
                        log("portal", f"Portal nhận diện collector này là '{cfg['collector']}' (theo API key)")
                        self.name = cfg["collector"]
                    last_cfg = cfg.get("vcenters", [])
                    self.apply(last_cfg)
                except Exception as e:
                    if last_cfg is None:
                        log("portal", f"Chưa lấy được cấu hình từ portal ({e}) — dùng config.json, thử lại sau 30s")
                        last_cfg = read_local(self.local_path)[0]
                        self.apply(last_cfg)
            try:
                self.http("POST", "/api/v1/collector/status", {"collector": self.name, "host": self.hostname,
                                                               "vcenters": self.statuses()})
            except Exception:
                pass
            time.sleep(10)


def main():
    ap = argparse.ArgumentParser(description="vCenter -> VM Monitor Portal")
    ap.add_argument("--config", default=str(Path(__file__).with_name("config.json")))
    ap.add_argument("--once", action="store_true", help="chạy 1 vòng rồi thoát")
    ap.add_argument("--dry-run", action="store_true", help="in số liệu ra màn hình, không gửi portal")
    ap.add_argument("--local", action="store_true", help="chỉ dùng config.json, không lấy danh sách vCenter từ portal")
    args = ap.parse_args()

    if not (args.once or args.dry_run or args.local):
        try:
            Manager(args.config).loop()
        except KeyboardInterrupt:
            pass
        return

    vcs = load_config(args.config)
    if not vcs:
        sys.exit('Chưa có vCenter nào trong config.json (mục "vcenters")')
    for c in vcs:
        if not c.get("host") or not c.get("user"):
            sys.exit(f"vCenter thiếu host / user: {c}")
        if not c["password"]:
            label = c["site"] if c["site"] != "auto" else c["host"]
            if not sys.stdin.isatty():
                sys.exit(f"Thiếu mật khẩu cho {label}: đặt biến môi trường VCENTER_PASSWORD_{env_key(label)}")
            c["password"] = getpass.getpass(f"Mật khẩu vCenter {c['host']} ({c['user']}): ")
        g = c.get("guestOps")
        if g and not g.get("password"):
            label = c["site"] if c["site"] != "auto" else c["host"]
            if not sys.stdin.isatty():
                sys.exit(f"Thiếu mật khẩu tài khoản HĐH cho guestOps: đặt VCENTER_GUEST_PASSWORD_{env_key(label)}")
            g["password"] = getpass.getpass(f"Mật khẩu tài khoản HĐH '{g['user']}' trên các VM Linux ({label}): ")

    print(f"Collector: {len(vcs)} vCenter -> {vcs[0]['portal']}", flush=True)
    threads = [threading.Thread(target=Collector(c).run, args=(args.once, args.dry_run), daemon=True) for c in vcs]
    for t in threads:
        t.start()
    try:
        while any(t.is_alive() for t in threads):
            time.sleep(1)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
