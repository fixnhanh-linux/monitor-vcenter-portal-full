# VM Monitor Agent — collector vCenter chạy trong Docker, đặt tại site ở xa.
# Lấy danh sách vCenter + mật khẩu từ web admin bằng API key vai trò "collector", gửi số liệu VM / host / datastore về web admin.
#
# Build (trong thư mục agent-docker):  docker build -t vm-monitor-agent:latest .
FROM python:3.12-slim

LABEL org.opencontainers.image.title="vm-monitor-agent" \
      org.opencontainers.image.description="vCenter collector cho VM Monitor"

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    COLLECTOR_LOG=/app/data/collector.log \
    TZ=Asia/Ho_Chi_Minh

WORKDIR /app
COPY requirements.txt /app/
RUN pip install --no-cache-dir -r requirements.txt \
 && useradd --system --uid 10001 --home /app agent \
 && mkdir -p /app/data && chown agent /app/data

COPY --chown=agent collector/vcenter_collector.py collector/guest_df.py /app/

USER agent
VOLUME ["/app/data"]

# Không cần config.json: MONITOR_SERVER + MONITOR_TOKEN là đủ (vCenter khai báo trên trang Cài đặt của web admin)
ENTRYPOINT ["python", "/app/vcenter_collector.py"]
