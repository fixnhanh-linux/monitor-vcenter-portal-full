# VM Monitor — web khách hàng (Spring Boot 3, Java 21)

Cổng riêng cho khách hàng, **đặt ra internet**; web admin để trong mạng nội bộ / VPN. Khách đăng nhập để xem máy chủ của mình, biểu đồ, cảnh báo, báo cáo tháng, đổi mật khẩu / bật 2FA.

```
 Khách (internet) ──HTTPS──▶ web-khach (8080) ──chỉ API khách──▶ web-admin (3000, mạng nội bộ)
```

## Bảo mật

- **Danh sách trắng API**: chỉ chuyển tiếp đăng nhập / đăng xuất, `/me`, VM (danh sách, chi tiết, biểu đồ, CSV), cảnh báo, realtime, báo cáo tháng, tài khoản / 2FA. Mọi API khác (Cài đặt, host, datastore, site, `/metrics`, collector, nhật ký, nhận số liệu, tạo admin…) trả **404**.
- **Chỉ tài khoản khách hàng** đăng nhập được; nhân viên / admin bị chặn (ghi log).
- **Cookie riêng** `kh_sess`: cookie của web admin không dùng được ở đây và ngược lại.
- Không có trang nội bộ nào (dashboard, cài đặt, host…) trong cổng này. Dữ liệu vẫn được web admin lọc theo khách (không lộ site, cluster, host, vCenter).
- IP thật của khách được chuyển sang web admin (nhật ký thao tác, khoá khi đoán sai mật khẩu).

## Mã nguồn

```
web-khach/
├─ pom.xml
├─ Dockerfile
└─ src/main/
   ├─ java/com/vmmonitor/khach/
   │  ├─ KhachApp.java      khởi động Spring Boot (virtual threads cho realtime)
   │  └─ Gateway.java       phục vụ giao diện + chuyển tiếp API theo danh sách trắng
   └─ resources/public/      giao diện khách
      ├─ login.html            đăng nhập (tên cổng lấy từ BRAND_NAME)
      ├─ my.html               Máy chủ của tôi
      ├─ report.html           Báo cáo tháng
      ├─ account.html, qr.js   Tài khoản, 2FA (mã QR)
      └─ app.css
```

## Build & chạy

```bash
docker build -t vm-monitor-khach web-khach
docker run -d --name vm-monitor-khach -p 8080:8080 -e ADMIN_URL=http://10.0.0.5:3000 -e BRAND_NAME="Cổng khách hàng ABC" --restart unless-stopped vm-monitor-khach
```

| Biến | Mặc định | Ý nghĩa |
|---|---|---|
| `ADMIN_URL` | `http://localhost:3000` | địa chỉ web admin trong mạng nội bộ |
| `PORT` | `8080` | cổng của cổng khách |
| `BRAND_NAME` | `Cổng khách hàng` | tên hiển thị trên trang đăng nhập |
| `TRUST_PROXY` | `0` | `1` khi đặt sau nginx / Caddy (dùng IP / https từ `X-Forwarded-*`) |

HTTPS: đặt sau reverse proxy (nginx, Caddy, Cloudflare…) với chứng chỉ thật, bật `TRUST_PROXY=1`. Kiểm tra: `GET /healthz` → `{"ok":true,"admin":"ok"}`.

Tạo tài khoản khách: web admin → *Cài đặt → Người dùng & bảo mật* → vai trò **Khách hàng**.
