package com.vmmonitor.khach;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Cổng khách hàng:
 *  - phục vụ giao diện khách (đăng nhập, Máy chủ của tôi, Báo cáo tháng, Tài khoản) từ trong file jar;
 *  - chuyển tiếp sang web admin CHỈ các API khách được phép (danh sách trắng), mọi API khác trả 404;
 *  - chỉ tài khoản vai trò "khách hàng" đăng nhập được; cookie phiên riêng ("kh_sess") nên cookie của web admin không dùng được ở đây.
 */
public class Gateway extends HttpServlet {
  private static final Logger log = LoggerFactory.getLogger(Gateway.class);
  private static final ObjectMapper M = new ObjectMapper();
  static final String ADMIN = System.getenv().getOrDefault("ADMIN_URL", "http://localhost:3000").replaceAll("/+$", "");
  static final boolean TRUST_PROXY = "1".equals(System.getenv("TRUST_PROXY")) || "true".equalsIgnoreCase(System.getenv("TRUST_PROXY"));
  static final String BRAND = System.getenv().getOrDefault("BRAND_NAME", "Cổng khách hàng");
  static final String COOKIE = "kh_sess", ADMIN_COOKIE = "vmsess";
  private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();

  /** API khách được phép: phương thức + đường dẫn. */
  record Rule(String method, Pattern path) {}
  static final List<Rule> ALLOW = List.of(
      new Rule("POST", Pattern.compile("^/api/v1/login(/2fa)?$")),
      new Rule("POST", Pattern.compile("^/api/v1/logout$")),
      new Rule("GET", Pattern.compile("^/api/v1/me$")),
      new Rule("GET", Pattern.compile("^/api/v1/vms(/.+)?$")),
      new Rule("GET", Pattern.compile("^/api/v1/alerts$")),
      new Rule("GET", Pattern.compile("^/api/v1/stream$")),
      new Rule("GET", Pattern.compile("^/api/v1/summary$")),
      new Rule("GET", Pattern.compile("^/api/v1/customers$")),
      new Rule("GET", Pattern.compile("^/api/v1/usage(/months)?$")),
      new Rule("GET", Pattern.compile("^/api/v1/account$")),
      new Rule("POST", Pattern.compile("^/api/v1/account/(password|2fa/(setup|enable|disable|recovery))$")));

  static final Map<String, String> PAGES = Map.of("/", "my.html", "/my", "my.html", "/login", "login.html", "/report", "report.html", "/account", "account.html");
  static final Map<String, String> MIME = Map.of(".html", "text/html; charset=utf-8", ".js", "text/javascript; charset=utf-8", ".css", "text/css; charset=utf-8",
      ".svg", "image/svg+xml", ".ico", "image/x-icon", ".png", "image/png");
  static final Map<String, String> SEC = Map.of("x-content-type-options", "nosniff", "referrer-policy", "same-origin", "x-frame-options", "DENY");

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse res) throws IOException {
    String path = req.getRequestURI().replaceAll("/+$", "");
    if (path.isEmpty()) path = "/";
    SEC.forEach(res::setHeader);
    if (req.isSecure() || (TRUST_PROXY && "https".equals(req.getHeader("x-forwarded-proto")))) res.setHeader("strict-transport-security", "max-age=15552000");
    if ("/healthz".equals(path)) { healthz(res); return; }
    if (path.startsWith("/api/")) {
      String m = req.getMethod(), p = path;
      if (ALLOW.stream().noneMatch((r) -> r.method().equals(m) && r.path().matcher(p).matches())) { json(res, 404, "{\"error\":\"Không có API này\"}"); return; }
      proxy(req, res, path);
      return;
    }
    if (!"GET".equals(req.getMethod())) { json(res, 405, "{\"error\":\"method not allowed\"}"); return; }
    String file = PAGES.getOrDefault(path, path.substring(1));
    if (file.contains("/") || file.contains("..") || !file.matches("[\\w.-]+\\.(html|js|css|svg|ico|png)")) { json(res, 404, "{\"error\":\"not found\"}"); return; }
    serve(res, file);
  }

  void serve(HttpServletResponse res, String file) throws IOException {
    try (InputStream in = Gateway.class.getResourceAsStream("/public/" + file)) {
      if (in == null) { json(res, 404, "{\"error\":\"not found\"}"); return; }
      byte[] data = in.readAllBytes();
      String ext = file.substring(file.lastIndexOf('.'));
      if (".html".equals(ext)) data = new String(data, StandardCharsets.UTF_8).replace("{{BRAND}}", esc(BRAND)).getBytes(StandardCharsets.UTF_8);
      res.setStatus(200);
      res.setHeader("content-type", MIME.getOrDefault(ext, "application/octet-stream"));
      res.setHeader("cache-control", "no-cache");
      res.getOutputStream().write(data);
    }
  }

  static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }

  void healthz(HttpServletResponse res) throws IOException {
    boolean up;
    try { up = http.send(HttpRequest.newBuilder(URI.create(ADMIN + "/healthz")).timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200; }
    catch (Exception e) { up = false; }
    json(res, up ? 200 : 503, "{\"ok\":" + up + ",\"admin\":" + (up ? "\"ok\"" : "\"không kết nối được\"") + "}");
  }

  static void json(HttpServletResponse res, int code, String body) throws IOException {
    res.setStatus(code);
    res.setHeader("content-type", "application/json; charset=utf-8");
    res.setHeader("cache-control", "no-store");
    res.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
  }

  /** Lấy giá trị cookie phiên của cổng khách. */
  static String sessionCookie(HttpServletRequest req) {
    String h = req.getHeader("cookie");
    if (h == null) return "";
    for (String c : h.split(";\\s*")) if (c.startsWith(COOKIE + "=")) return c.substring(COOKIE.length() + 1);
    return "";
  }

  void proxy(HttpServletRequest req, HttpServletResponse res, String path) throws IOException {
    String qs = req.getQueryString();
    boolean stream = "/api/v1/stream".equals(path);
    boolean login = path.startsWith("/api/v1/login");
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(ADMIN + req.getRequestURI() + (qs == null ? "" : "?" + qs)));
    if (!stream) b.timeout(Duration.ofSeconds(60));
    String ct = req.getContentType();
    if (ct != null) b.header("content-type", ct);
    b.header("accept", req.getHeader("accept") == null ? "*/*" : req.getHeader("accept"));
    // chỉ chuyển cookie phiên của cổng khách (đổi tên sang cookie của web admin); cookie khác không gửi đi
    String sess = sessionCookie(req);
    if (!sess.isEmpty()) b.header("cookie", ADMIN_COOKIE + "=" + sess);
    String fwd = req.getHeader("x-forwarded-for");
    String ip = TRUST_PROXY && fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : req.getRemoteAddr();
    b.header("x-forwarded-for", ip);
    String proto = TRUST_PROXY && req.getHeader("x-forwarded-proto") != null ? req.getHeader("x-forwarded-proto") : (req.isSecure() ? "https" : "http");
    b.header("x-forwarded-proto", proto);
    if ("POST".equals(req.getMethod())) {
      byte[] body = req.getInputStream().readNBytes(1_000_000);
      b.POST(HttpRequest.BodyPublishers.ofByteArray(body));
    } else b.GET();

    HttpResponse<InputStream> up;
    try { up = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream()); }
    catch (Exception e) { log.warn("Không gọi được web admin {}: {}", ADMIN, e.toString()); json(res, 502, "{\"error\":\"Hệ thống đang bảo trì, vui lòng thử lại sau\"}"); return; }

    try (InputStream in = up.body()) {
      if (login) { // chỉ cho tài khoản khách hàng đăng nhập ở cổng này
        byte[] data = in.readAllBytes();
        JsonNode d; try { d = M.readTree(data); } catch (Exception e) { d = M.createObjectNode(); }
        String role = d.path("user").path("role").asText("");
        if (up.statusCode() == 200 && !role.isEmpty() && !"customer".equals(role)) {
          log.info("Chặn tài khoản không phải khách hàng đăng nhập cổng khách: {} ({}) từ {}", d.path("user").path("username").asText(), role, ip);
          json(res, 403, "{\"error\":\"Tài khoản này không phải tài khoản khách hàng. Nhân viên vui lòng đăng nhập tại web nội bộ.\"}");
          return;
        }
        copyHeaders(up, res);
        res.getOutputStream().write(data);
        return;
      }
      copyHeaders(up, res);
      OutputStream out = res.getOutputStream();
      byte[] buf = new byte[stream ? 8192 : 65536];
      int n;
      while ((n = in.read(buf)) > 0) {
        out.write(buf, 0, n);
        if (stream) out.flush(); // realtime: đẩy ngay từng sự kiện
      }
    } catch (IOException e) {
      // trình duyệt đóng kết nối (rời trang) — bỏ qua
    }
  }

  void copyHeaders(HttpResponse<InputStream> up, HttpServletResponse res) {
    res.setStatus(up.statusCode());
    for (String h : List.of("content-type", "cache-control", "content-disposition", "connection")) up.headers().firstValue(h).ifPresent((v) -> res.setHeader(h, v));
    for (String sc : up.headers().allValues("set-cookie")) {
      if (sc.startsWith(ADMIN_COOKIE + "=")) res.addHeader("set-cookie", COOKIE + sc.substring(ADMIN_COOKIE.length()));
    }
  }
}
