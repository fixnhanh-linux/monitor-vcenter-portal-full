# VM Monitor — web admin (Spring Boot 3, Java 21)

Viết lại portal Node.js (`server.js` + `lib/`) bằng Java, **giữ nguyên API, giao diện và định dạng dữ liệu**: dùng chung `config.json`, thư mục `data/` và `public/` với bản Node. Tài khoản, mật khẩu (scrypt), phiên đăng nhập, mật khẩu vCenter (AES-GCM), 2FA đều dùng tiếp được — đổi qua lại giữa hai bản không mất gì.

## Cấu trúc mã nguồn

```
web-admin/
├─ pom.xml                         Spring Boot 3.3 + BouncyCastle (scrypt)
├─ Dockerfile                      build nhiều tầng: Maven -> JRE 21
└─ src/main/
   ├─ resources/openapi.json       mô tả API (xuất từ lib/openapi.js)
   └─ java/com/vmmonitor/
      ├─ App.java                  khởi động Spring Boot, lệnh adduser / apikey, cổng HTTPS phụ
      ├─ web/      Router          định tuyến mọi request (tương đương handle() của server.js)
      │            Ctx             request / response, đọc body, cookie, trả JSON
      ├─ core/     Portal          nhận số liệu, mô hình VM, realtime (SSE), Prometheus, CSV, việc định kỳ
      │            Scope           phạm vi dữ liệu theo vai trò (khách hàng / phụ trách / site)
      ├─ config/   Cfg             đọc / tự nạp lại / ghi nguyên tử config.json
      ├─ store/    Store           lịch sử VM / host / datastore (state.json, hosts.json, datastores.json)
      │            Usage           thống kê theo ngày cho báo cáo tháng (data/usage/)
      ├─ security/ Auth            đăng nhập, phiên, API key, vai trò, 2FA khi đăng nhập
      │            Account         trang Tài khoản: đổi mật khẩu, bật / tắt 2FA
      │            Secrets         kho bí mật AES-256-GCM (data/secrets.json)
      │            Totp            mã 2FA (RFC 6238)
      ├─ alert/    Alerts          engine cảnh báo VM / host / datastore
      │            Notify          Telegram, email SMTP (đính kèm), webhook
      ├─ infra/    Infra           host ESXi + datastore
      │            Influx          ghi InfluxDB (portal / Telegraf vSphere)
      ├─ customer/ Customers       nhận diện khách hàng của VM
      ├─ admin/    Admin           API trang Cài đặt + API cho collector
      ├─ ops/      Ops             dọn VM biến mất, báo cáo tháng + email, gợi ý gán khách
      │            Backup          sao lưu hằng ngày
      │            Audit           nhật ký thao tác
      └─ util/     Json, Log, HttpError, NaturalOrder
```

Mỗi package tương ứng một nhóm file trong `lib/` của bản Node. Mọi request và việc định kỳ chạy tuần tự qua một khoá chung (`Portal.lock`), giống mô hình một luồng của Node; gửi thông báo, ghi InfluxDB chạy ở luồng riêng.

## Build

Không cần cài JDK / Maven — build bằng Docker:

```bash
docker build -t vm-monitor-admin web-admin
```

hoặc chỉ ra file jar (`web-admin/target/vm-monitor-admin.jar`):

```bash
docker run --rm -v "%cd%\web-admin:/src" -v vmmon-m2:/root/.m2 -w /src maven:3.9.9-eclipse-temurin-21 mvn -q -B -DskipTests package
```

## Chạy

Thư mục portal (chứa `config.json`, `data/`, `public/`) gắn vào `/portal`:

```bash
docker run -d --name vm-monitor-admin -p 3000:3000 -p 3443:3443 -v "C:\path\Monitor VM Portal:/portal" --restart unless-stopped vm-monitor-admin
```

Có JDK 21 thì chạy thẳng: `java -jar vm-monitor-admin.jar --root "C:\path\Monitor VM Portal" --log-file data\portal.log`

Lệnh quản trị: `java -jar vm-monitor-admin.jar adduser admin` · `java -jar vm-monitor-admin.jar apikey grafana --role read`

> Chỉ chạy **một** bản (Node hoặc Java) trên cùng thư mục `data/` tại một thời điểm.

## Đã kiểm tra

Chạy song song với bản Node trên bản sao dữ liệu của một hệ thống thật (~1.600 VM, ~100 host, ~40 datastore): danh sách VM và mọi bộ lọc, khách hàng, site, tóm tắt, host, datastore, chi tiết + biểu đồ, báo cáo tháng, `/metrics`, cảnh báo, SSE, trang tĩnh, OpenAPI đều khớp; cookie đăng nhập do bản Node cấp dùng được trên bản Java; phạm vi khách hàng / phụ trách / nhân viên theo site đúng; nhận số liệu + cảnh báo chạy.

Đang chạy thật (web-admin + web-khach) cho ~1.600 VM / ~100 host trên 2 vCenter, collector chạy ngoài Docker gửi vào bằng API key: số liệu, cảnh báo, ghi InfluxDB (định dạng Telegraf) và Prometheus / Grafana hoạt động bình thường.## Chạy

Chạy như **service** (khuyên dùng): xem `deploy/linux/` (systemd, `sudo bash install.sh`) hoặc `deploy/windows/install.ps1` — hướng dẫn ở [README chính](../README.md#bước-1--web-admin--web-khách-chạy-service-trên-một-server-không-dùng-docker).

Chạy tay (Java 21): `java -jar vm-monitor-admin.jar --root /var/lib/vm-monitor --log-file /var/lib/vm-monitor/data/portal.log` — thư mục `--root` chứa `config.json`, `data/`, `backups/`. Thêm `--demo` để chạy với dữ liệu giả.

Lệnh quản trị: `java -jar vm-monitor-admin.jar adduser admin` · `java -jar vm-monitor-admin.jar apikey grafana --role read`

> Chỉ chạy **một** bản (Node hoặc Java) trên cùng thư mục `data/` tại một thời điểm.

## Đã kiểm tra

Chạy song song với bản Node trên bản sao dữ liệu của một hệ thống thật (~1.600 VM, ~100 host, ~40 datastore): danh sách VM và mọi bộ lọc, khách hàng, site, tóm tắt, host, datastore, chi tiết + biểu đồ, báo cáo tháng, `/metrics`, cảnh báo, SSE, trang tĩnh, OpenAPI đều khớp; cookie đăng nhập do bản Node cấp dùng được trên bản Java; phạm vi khách hàng / phụ trách / nhân viên theo site đúng; nhận số liệu + cảnh báo chạy.

Đang chạy thật (Docker, web-admin + web-khach) cho ~1.600 VM / ~100 host trên 2 vCenter, collector chạy ngoài Docker gửi vào bằng API key: số liệu, cảnh báo, ghi InfluxDB (định dạng Telegraf) và Prometheus / Grafana hoạt động bình thường.
