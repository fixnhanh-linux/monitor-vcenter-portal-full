VM MONITOR - CÀI WEB ADMIN + WEB KHÁCH CHẠY SERVICE TRÊN LINUX (systemd)
=========================================================================

Yêu cầu: Linux có systemd (Ubuntu 22.04+/Debian 12/RHEL 9...) và Java 21
  Ubuntu/Debian : sudo apt install -y openjdk-21-jre-headless unzip
  RHEL/Rocky    : sudo dnf install -y java-21-openjdk-headless unzip

Cài:
  unzip vm-monitor-linux.zip
  cd vm-monitor-linux
  sudo bash install.sh

Cổng mặc định 3000 / 8080; cổng bận (vd Grafana chiếm 3000) thì tự lấy cổng trống kế tiếp.
Tự chọn cổng: sudo ADMIN_PORT=3001 KHACH_PORT=8081 bash install.sh

Sau khi cài:
  Web admin : http://<ip-server>:3000   (lần đầu vào sẽ tạo tài khoản admin)
  Web khách : http://<ip-server>:8080

Thư mục:
  /opt/vm-monitor/           2 file jar
  /var/lib/vm-monitor/       dữ liệu: config.json, data/ (portal.log), backups/
  /etc/vm-monitor/admin.env  tham số thêm cho web admin (ADMIN_ARGS=...)
  /etc/vm-monitor/khach.env  ADMIN_URL, PORT, BRAND_NAME, TRUST_PROXY

Quản lý:
  sudo systemctl status  vm-monitor-admin vm-monitor-khach
  sudo systemctl restart vm-monitor-admin vm-monitor-khach
  sudo journalctl -u vm-monitor-admin -f
  tail -f /var/lib/vm-monitor/data/portal.log

Cập nhật bản mới (đang chạy vẫn cập nhật được, giữ nguyên dữ liệu, lỗi thì tự quay lại bản cũ):
  sudo /opt/vm-monitor/update.sh
  sudo /opt/vm-monitor/update.sh --rollback      # quay lại bản trước
Gỡ:       sudo bash uninstall.sh (giữ dữ liệu trong /var/lib/vm-monitor).

Tường lửa (nếu bật):
  sudo ufw allow 3000/tcp; sudo ufw allow 8080/tcp
  hoặc: sudo firewall-cmd --permanent --add-port={3000,8080}/tcp && sudo firewall-cmd --reload

Agent thu thập dữ liệu ở các site chạy bằng Docker: xem gói vm-monitor-agent-docker.zip.
