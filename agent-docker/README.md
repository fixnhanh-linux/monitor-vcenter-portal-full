# VM Monitor Agent (Docker) — collector cho site ở xa

Agent đặt **trong mạng của mỗi site**: đọc số liệu VM / host ESXi / datastore từ **vCenter** của site (API VMware, cổng 443 — không cài gì vào VM) rồi gửi về **web admin**. Agent chỉ **gọi ra**, không mở cổng nào vào site.

Danh sách vCenter, mật khẩu vCenter, Guest Operations… khai báo trên web admin (*Cài đặt → vCenter*); agent tự lấy về mỗi 30 giây bằng API key — thêm / đổi vCenter không phải sửa gì trên máy agent.

## Mã nguồn

```
agent-docker/
├─ collector/
│  ├─ vcenter_collector.py   lấy danh sách vCenter từ web admin, đọc số liệu (pyVmomi), gửi về theo lô
│  └─ guest_df.py            (tuỳ chọn) chạy df trong VM Linux qua VMware Tools để có dung lượng ổ chính xác
├─ requirements.txt          pyvmomi
├─ Dockerfile                python:3.12-slim, chạy bằng user không phải root
├─ docker-compose.yml
├─ .env.example              MONITOR_SERVER, MONITOR_TOKEN, MONITOR_INSECURE, TZ
└─ build-image.ps1           build + xuất vm-monitor-agent.tar mang sang site không có internet
```

## Số liệu lấy từ đâu

| Số liệu | Nguồn trong vCenter |
|---|---|
| CPU %, RAM %, Network, Disk đọc / ghi | bộ đếm hiệu năng realtime (mẫu 20 giây) |
| Bật / tắt, IP, hệ điều hành, vCPU, RAM | cấu hình VM + VMware Tools |
| Dung lượng từng phân vùng | VMware Tools (hoặc `df` qua Guest Operations nếu bật) |
| Cluster, host, folder, tag, attribute | inventory vCenter + vSphere Tag API |
| Host ESXi, datastore | số liệu host / datastore |

## Triển khai một site

1. Web admin → *Cài đặt → vCenter → ＋ Thêm collector cho site ở xa* → đặt tên (vd `site-hn`) → copy **API key**.
2. Trên máy có Docker trong mạng site (thấy được vCenter, gọi ra được web admin):
   ```bash
   cp .env.example .env      # điền MONITOR_SERVER, MONITOR_TOKEN
   docker compose up -d       # tự build image từ thư mục này
   docker compose logs -f     # thấy "Portal nhận diện collector này là 'site-hn'" là xong
   ```
   Máy không có internet: trên máy khác chạy `build-image.ps1`, mang `vm-monitor-agent.tar` sang rồi `docker load -i vm-monitor-agent.tar`.
3. Web admin → thêm / sửa vCenter của site → **Chạy bởi collector** = `site-hn` → nhập mật khẩu vCenter → Lưu.

| Biến | Ý nghĩa |
|---|---|
| `MONITOR_SERVER` | địa chỉ web admin mà agent gọi tới được (IP public / domain / VPN) |
| `MONITOR_TOKEN` | API key vai trò `collector` (tên collector = tên key) |
| `MONITOR_INSECURE` | `1` = không kiểm tra chứng chỉ HTTPS (web admin dùng chứng chỉ tự ký) |
| `TZ` | múi giờ trong log |

Cập nhật: build image mới → `docker compose up -d`. Thu hồi: xoá collector trên web admin, key mất hiệu lực ngay.
