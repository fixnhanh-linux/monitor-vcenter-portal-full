#!/usr/bin/env bash
# Cài web-admin + web-khach thành service systemd trên Linux (Ubuntu / Debian / RHEL…).
#
#   sudo ./install.sh                 # dùng 2 file jar cạnh script, hoặc ../../web-admin/target, ../../web-khach/target
#   sudo ./install.sh /đường/dẫn/jar   # thư mục chứa vm-monitor-admin.jar + vm-monitor-khach.jar
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

if [ ! -f /etc/vm-monitor/khach.env ]; then
  cat > /etc/vm-monitor/khach.env <<'EOF'
# Cổng khách hàng
ADMIN_URL=http://127.0.0.1:3000
PORT=8080
BRAND_NAME=Cổng khách hàng
# 1 khi đặt sau nginx / Caddy có HTTPS
TRUST_PROXY=0
EOF
  chmod 640 /etc/vm-monitor/khach.env; chgrp vmmonitor /etc/vm-monitor/khach.env
fi
[ -f /etc/vm-monitor/admin.env ] || { echo '# Tham số thêm cho web admin, vd: ADMIN_ARGS=--port 3000' > /etc/vm-monitor/admin.env; chmod 640 /etc/vm-monitor/admin.env; chgrp vmmonitor /etc/vm-monitor/admin.env; }

install -m 644 "$HERE/vm-monitor-admin.service" /etc/systemd/system/vm-monitor-admin.service
install -m 644 "$HERE/vm-monitor-khach.service" /etc/systemd/system/vm-monitor-khach.service
systemctl daemon-reload
systemctl enable --now vm-monitor-admin.service
sleep 5
systemctl enable --now vm-monitor-khach.service

echo
systemctl --no-pager --lines=0 status vm-monitor-admin.service vm-monitor-khach.service || true
echo
echo "Xong. Web admin: http://$(hostname -I 2>/dev/null | awk '{print $1}'):3000  ·  Web khách: cổng 8080"
echo "Log: journalctl -u vm-monitor-admin -f   ·   /var/lib/vm-monitor/data/portal.log"
