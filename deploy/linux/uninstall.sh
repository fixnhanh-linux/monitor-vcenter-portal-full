#!/usr/bin/env bash
# Gỡ service web-admin + web-khach (GIỮ dữ liệu ở /var/lib/vm-monitor và cấu hình ở /etc/vm-monitor).
set -euo pipefail
[ "$(id -u)" -eq 0 ] || { echo "Chạy bằng sudo / root"; exit 1; }
systemctl disable --now vm-monitor-khach.service vm-monitor-admin.service 2>/dev/null || true
rm -f /etc/systemd/system/vm-monitor-admin.service /etc/systemd/system/vm-monitor-khach.service
systemctl daemon-reload
rm -rf /opt/vm-monitor
echo "Đã gỡ service. Dữ liệu vẫn ở /var/lib/vm-monitor, cấu hình ở /etc/vm-monitor (xoá tay nếu không cần)."
