#!/usr/bin/env bash
# Cài web-admin + web-khach thành service systemd trên Linux (Ubuntu / Debian / RHEL…).
#
#   sudo ./install.sh                 # dùng 2 file jar cạnh script, hoặc ../../web-admin/target, ../../web-khach/target
#   sudo ./install.sh /đường/dẫn/jar   # thư mục chứa vm-monitor-admin.jar + vm-monitor-khach.jar
#   sudo ADMIN_PORT=3001 KHACH_PORT=8081 ./install.sh   # chọn cổng (mặc định 3000 / 8080, cổng bận thì tự lấy cổng trống kế tiếp)
#
# Sau khi cài:
#   web admin : http://<server>:3000   (lần đầu tạo tài khoản admin ngay trên trang đăng nhập)
#   web khách : http://<server>:8080
#   dữ liệu   : /var/lib/vm-monitor (config.json, data/, backups/)  ·  cấu hình cổng khách: /etc/vm-monitor/khach.env
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="${1:-}"
[ "$(id -u)" -eq 0 ] || { echo "Chạy bằng sudo / root"; exit 1; }

find_jar() {
  for d in "$SRC" "$HERE" "$HERE/../../$2/target"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return; }
  done
  echo ""
}
ADMIN_JAR="$(find_jar vm-monitor-admin.jar web-admin)"
KHACH_JAR="$(find_jar vm-monitor-khach.jar web-khach)"
if [ -z "$ADMIN_JAR" ] || [ -z "$KHACH_JAR" ]; then
  echo "Không thấy vm-monitor-admin.jar / vm-monitor-khach.jar."
  echo "Tải ở trang Releases của repo, hoặc build: ../build.sh"
  exit 1
fi

# Java 21+
if ! command -v java >/dev/null; then
  echo "Chưa có Java. Cài Java 21, ví dụ:"
  echo "  Ubuntu / Debian : sudo apt install -y openjdk-21-jre-headless"
  echo "  RHEL / Rocky    : sudo dnf install -y java-21-openjdk-headless"
  exit 1
fi
JV="$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
[ "${JV:-0}" -ge 21 ] || { echo "Cần Java 21 trở lên (đang có Java $JV)"; exit 1; }

id vmmonitor >/dev/null 2>&1 || useradd --system --home /var/lib/vm-monitor --shell /usr/sbin/nologin vmmonitor
install -d -o root -g root -m 755 /opt/vm-monitor
install -d -o vmmonitor -g vmmonitor -m 750 /var/lib/vm-monitor /var/lib/vm-monitor/data
install -d -o root -g vmmonitor -m 750 /etc/vm-monitor
install -m 644 "$ADMIN_JAR" /opt/vm-monitor/vm-monitor-admin.jar
install -m 644 "$KHACH_JAR" /opt/vm-monitor/vm-monitor-khach.jar
[ -f "$HERE/VERSION" ] && install -m 644 "$HERE/VERSION" /opt/vm-monitor/VERSION
[ -f "$HERE/update.sh" ] && install -m 755 "$HERE/update.sh" /opt/vm-monitor/update.sh

# ---- cổng: mặc định 3000 (admin) / 8080 (khách); đặt ADMIN_PORT=… KHACH_PORT=… để chọn; cổng bận thì tự lấy cổng trống kế tiếp
systemctl stop vm-monitor-khach.service vm-monitor-admin.service 2>/dev/null || true
cur_admin="$(sed -n "s/^ADMIN_ARGS=.*--port[ =]\([0-9]*\).*/\1/p" /etc/vm-monitor/admin.env 2>/dev/null | tail -1 || true)"
cur_khach="$(sed -n "s/^PORT=\([0-9]*\).*/\1/p" /etc/vm-monitor/khach.env 2>/dev/null | tail -1 || true)"
busy() { ss -ltnH 2>/dev/null | awk '{print $4}' | grep -Eq "[:.]$1$"; }
pick() {  # pick <tên> <cổng người dùng chọn> <mặc định>
  local p="${2:-$3}"
  if busy "$p"; then
    if [ -n "$2" ]; then echo "Cổng $p ($1) đang bị chương trình khác dùng:" >&2; ss -ltnp 2>/dev/null | grep -E "[:.]$p " >&2; exit 1; fi
    echo "Cổng $p ($1) đang bị chương trình khác dùng:" >&2; ss -ltnp 2>/dev/null | grep -E "[:.]$p " >&2 || true
    while busy "$p"; do p=$((p + 1)); done
    echo "  -> dùng cổng $p cho $1" >&2
  fi
  echo "$p"
}
APORT="$(pick "web admin" "${ADMIN_PORT:-}" "${cur_admin:-3000}")"
KPORT="$(pick "web khách" "${KHACH_PORT:-}" "${cur_khach:-8080}")"
[ "$APORT" != "$KPORT" ] || { echo "ADMIN_PORT và KHACH_PORT trùng nhau"; exit 1; }

# set_kv <file> <KEY> <VALUE>: thay dòng KEY=…, chưa có thì thêm
set_kv() { if grep -q "^$2=" "$1"; then sed -i "s#^$2=.*#$2=$3#" "$1"; else echo "$2=$3" >> "$1"; fi; }
if [ ! -f /etc/vm-monitor/khach.env ]; then
  cat > /etc/vm-monitor/khach.env <<'EOF'
# Cổng khách hàng (sửa xong: systemctl restart vm-monitor-khach)
ADMIN_URL=http://127.0.0.1:3000
PORT=8080
BRAND_NAME=Cổng khách hàng
# 1 khi đặt sau nginx / Caddy có HTTPS
TRUST_PROXY=0
EOF
fi
[ -f /etc/vm-monitor/admin.env ] || echo "# Tham số thêm cho web admin (sửa xong: systemctl restart vm-monitor-admin)" > /etc/vm-monitor/admin.env
set_kv /etc/vm-monitor/admin.env ADMIN_ARGS "--port $APORT"
set_kv /etc/vm-monitor/khach.env ADMIN_URL "http://127.0.0.1:$APORT"
set_kv /etc/vm-monitor/khach.env PORT "$KPORT"
chmod 640 /etc/vm-monitor/*.env; chgrp vmmonitor /etc/vm-monitor/*.env

install -m 644 "$HERE/vm-monitor-admin.service" /etc/systemd/system/vm-monitor-admin.service
install -m 644 "$HERE/vm-monitor-khach.service" /etc/systemd/system/vm-monitor-khach.service
systemctl daemon-reload
systemctl enable --now vm-monitor-admin.service
echo -n "Đang khởi động web admin (cổng $APORT) "
ok=0; for i in $(seq 1 45); do
  if curl -sf -o /dev/null "http://127.0.0.1:$APORT/login.html" 2>/dev/null; then ok=1; break; fi
  echo -n "."; sleep 2
done; echo
systemctl enable --now vm-monitor-khach.service
for i in $(seq 1 30); do curl -sf -o /dev/null "http://127.0.0.1:$KPORT/healthz" 2>/dev/null && break; sleep 2; done
IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
echo
if [ "$ok" = 1 ]; then
  echo "Xong."
  echo "  Web admin : http://${IP:-<server>}:$APORT   (lần đầu vào tạo tài khoản admin)"
  echo "  Web khách : http://${IP:-<server>}:$KPORT"
  echo "  Kiểm tra  : $(curl -s http://127.0.0.1:$KPORT/healthz 2>/dev/null || echo "chưa trả lời - xem journalctl -u vm-monitor-khach")"
else
  echo "Web admin CHƯA chạy được. Lỗi gần nhất:"
  journalctl -u vm-monitor-admin -n 60 --no-pager 2>/dev/null | grep -Ev '^[[:space:]]*at |^[[:space:]]*\.\.\.' | tail -15
  exit 1
fi
echo "Log: journalctl -u vm-monitor-admin -f   ·   /var/lib/vm-monitor/data/portal.log"
echo "Đổi cổng: ADMIN_PORT=3001 KHACH_PORT=8081 bash install.sh   ·   Cập nhật bản mới: sudo /opt/vm-monitor/update.sh"
