# Prometheus + Grafana cho VM Monitor Portal (Docker Compose)

Dựng sẵn: Prometheus lấy `/metrics` của portal mỗi 30 giây, Grafana có sẵn datasource và 5 dashboard trong thư mục *VM Monitor* (trang chủ là **1. Tổng quan**).

## Chạy

1. Cài Docker (Windows / macOS: Docker Desktop; Linux: Docker Engine + compose plugin).
2. Portal → **Cài đặt → Người dùng & API key** → tạo key vai trò **read** tên `prometheus`. Lưu key vào file:
   ```
   prometheus/portal_api_key.txt
   ```
3. Địa chỉ portal trong `prometheus/prometheus.yml` (mục `targets`):
   - Portal chạy trên chính máy này: giữ `host.docker.internal:3000`
   - Portal ở máy khác: `IP_PORTAL:3000` (hoặc `domain:443` và đổi `scheme: https`)
4. Copy `.env.example` thành `.env`, đặt `GRAFANA_ADMIN_PASSWORD`.
5. Trong thư mục này:
   ```
   docker compose up -d
   ```
6. Mở **http://localhost:3001** (hoặc IP server), đăng nhập `admin` / mật khẩu ở bước 4.

## Kiểm tra

- Prometheus: http://localhost:9090/targets → job `vm-monitor-portal` phải **UP**.
- Grafana: thư mục *VM Monitor* gồm **1. Tổng quan, 2. VM, 3. Khách hàng, 4. Host ESXi, 5. Datastore** (thanh liên kết ở trên cùng mỗi dashboard).

## Ghi chú

- Lịch sử Prometheus giữ 90 ngày (`PROMETHEUS_RETENTION` trong `.env`). Khoảng 1.600 VM ≈ 22.000 chuỗi số liệu, cần cỡ vài GB đĩa cho 90 ngày.
- Prometheus chỉ mở cổng 9090 cho chính máy này; Grafana mở cổng 3001.
- Cập nhật dashboard: chạy `python grafana/make_dashboards.py` ở thư mục gốc portal (Grafana tự nạp lại sau 60 giây; dashboard có file bị xoá cũng tự gỡ).
- Dừng: `docker compose down` (giữ dữ liệu) — `docker compose down -v` (xoá cả dữ liệu).
