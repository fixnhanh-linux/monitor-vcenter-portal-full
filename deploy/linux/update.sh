#!/usr/bin/env bash
# Cập nhật web-admin + web-khach lên bản mới nhất trên GitHub Releases, khi đang chạy.
#   sudo /opt/vm-monitor/update.sh            # bản mới nhất
#   sudo /opt/vm-monitor/update.sh v1.0.1     # một phiên bản cụ thể
#   sudo /opt/vm-monitor/update.sh --rollback # quay lại bản trước lần cập nhật gần nhất
#
# Không đụng tới dữ liệu (/var/lib/vm-monitor) và cấu hình (/etc/vm-monitor): giữ nguyên cổng, tài khoản, vCenter, lịch sử.
# Gián đoạn ~20-30 giây khi khởi động lại. Bản mới không chạy được thì tự quay lại bản cũ.
set -euo pipefail
REPO="${VMMON_REPO:-fixnhanh-linux/monitor-vcenter-portal-full}"
APP=/opt/vm-monitor
PREV=$APP/prev
[ "$(id -u)" -eq 0 ] || { echo "Chạy bằng sudo / root"; exit 1; }

admin_port() { sed -n 's/^ADMIN_ARGS=.*--port[ =]\([0-9]*\).*/\1/p' /etc/vm-monitor/admin.env 2>/dev/null | tail -1 || true; }
healthy() {  # chờ web admin trả lời
  local p; p="$(admin_port)"; p="${p:-3000}"
  for i in $(seq 1 45); do curl -sf -o /dev/null "http://127.0.0.1:$p/login.html" && return 0; sleep 2; done
  return 1
}
khach_port() { sed -n 's/^PORT=\([0-9]*\).*/\1/p' /etc/vm-monitor/khach.env 2>/dev/null | tail -1 || true; }
restart() {
  systemctl restart vm-monitor-admin.service; healthy || return 1
  systemctl restart vm-monitor-khach.service
  local k; k="$(khach_port)"; for i in $(seq 1 30); do curl -sf -o /dev/null "http://127.0.0.1:${k:-8080}/healthz" && break; sleep 2; done
}
rollback() {
  [ -f "$PREV/vm-monitor-admin.jar" ] || { echo "Không có bản cũ để quay lại"; exit 1; }
  echo "Quay lại bản trước ($(cat "$PREV/VERSION" 2>/dev/null || echo '?'))..."
  install -m 644 "$PREV/vm-monitor-admin.jar" "$PREV/vm-monitor-khach.jar" "$APP/"
  [ -f "$PREV/VERSION" ] && cp "$PREV/VERSION" "$APP/VERSION"
  restart && echo "Đã quay lại bản trước, đang chạy bình thường." || { echo "Bản trước cũng không chạy được — xem: journalctl -u vm-monitor-admin -n 80"; exit 1; }
}
[ "${1:-}" = "--rollback" ] && { rollback; exit 0; }

TAG="${1:-latest}"
if [ -n "${VMMON_URL:-}" ]; then URL="$VMMON_URL"
elif [ "$TAG" = latest ]; then URL="https://github.com/$REPO/releases/latest/download/vm-monitor-linux.zip"
else URL="https://github.com/$REPO/releases/download/$TAG/vm-monitor-linux.zip"; fi
command -v unzip >/dev/null || { echo "Cần unzip: apt install -y unzip  /  dnf install -y unzip"; exit 1; }

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
echo "Tải $URL"
if command -v curl >/dev/null; then curl -fsSL --retry 3 -o "$TMP/pkg.zip" "$URL"; else wget -q -O "$TMP/pkg.zip" "$URL"; fi
unzip -q "$TMP/pkg.zip" -d "$TMP"
NEW="$TMP/vm-monitor-linux"
[ -f "$NEW/vm-monitor-admin.jar" ] && [ -f "$NEW/vm-monitor-khach.jar" ] || { echo "Gói tải về không đúng"; exit 1; }
NEWVER="$(cat "$NEW/VERSION" 2>/dev/null || echo "$TAG")"

same() { cmp -s "$NEW/$1" "$APP/$1"; }
if same vm-monitor-admin.jar && same vm-monitor-khach.jar; then
  echo "Đang là bản mới nhất ($(cat "$APP/VERSION" 2>/dev/null || echo "$NEWVER")) — không cần cập nhật."
  [ -f "$NEW/update.sh" ] && install -m 755 "$NEW/update.sh" "$APP/update.sh"
  exit 0
fi

# giữ bản đang chạy để quay lại + sao lưu cấu hình
mkdir -p "$PREV"
cp -f "$APP/vm-monitor-admin.jar" "$APP/vm-monitor-khach.jar" "$PREV/" 2>/dev/null || true
[ -f "$APP/VERSION" ] && cp -f "$APP/VERSION" "$PREV/VERSION" || echo "trước-cập-nhật-$(date +%F)" > "$PREV/VERSION"
mkdir -p /var/lib/vm-monitor/backups
[ -f /var/lib/vm-monitor/config.json ] && cp -p /var/lib/vm-monitor/config.json "/var/lib/vm-monitor/backups/config-before-update-$(date +%Y%m%d-%H%M%S).json"
chown -R vmmonitor:vmmonitor /var/lib/vm-monitor/backups 2>/dev/null || true

echo "Cài $NEWVER (giữ nguyên dữ liệu, cổng, cấu hình)..."
if bash "$NEW/install.sh" "$NEW"; then
  echo "Cập nhật xong: $NEWVER.  Quay lại bản cũ nếu cần: sudo $APP/update.sh --rollback"
else
  echo "Bản mới KHÔNG chạy được."
  rollback
  exit 1
fi
