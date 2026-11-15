package com.vmmonitor.khach;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;

import java.util.HashMap;
import java.util.Map;

/**
 * Cổng khách hàng — ứng dụng riêng, đặt ra internet; web admin để trong mạng nội bộ.
 *
 * Biến môi trường:
 *   ADMIN_URL    địa chỉ web admin trong mạng nội bộ (mặc định http://localhost:3000)
 *   PORT         cổng của cổng khách hàng (mặc định 8080)
 *   TRUST_PROXY  1 = tin X-Forwarded-For / X-Forwarded-Proto từ reverse proxy (nginx, Caddy) đứng trước
 *   BRAND_NAME   tên hiển thị trên trang đăng nhập (mặc định "Cổng khách hàng")
 */
@SpringBootApplication
public class KhachApp {
  public static void main(String[] args) {
    SpringApplication app = new SpringApplication(KhachApp.class);
    Map<String, Object> props = new HashMap<>();
    props.put("server.port", System.getenv().getOrDefault("PORT", "8080"));
    props.put("spring.main.banner-mode", "off");
    props.put("logging.level.root", "WARN");
    props.put("logging.level.com.vmmonitor", "INFO");
    props.put("server.error.whitelabel.enabled", "false");
    props.put("spring.threads.virtual.enabled", "true"); // mỗi luồng realtime (SSE) một virtual thread
    props.put("server.tomcat.encoded-solidus-handling", "passthrough");
    app.setDefaultProperties(props);
    app.run(args);
  }

  @Bean
  public ServletRegistrationBean<Gateway> gateway() {
    ServletRegistrationBean<Gateway> b = new ServletRegistrationBean<>(new Gateway(), "/*");
    b.setAsyncSupported(true);
    b.setLoadOnStartup(1);
    return b;
  }
}
