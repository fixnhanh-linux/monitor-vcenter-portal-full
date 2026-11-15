package com.vmmonitor.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vmmonitor.security.Auth;
import com.vmmonitor.util.Json;
import com.vmmonitor.util.Log;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Nhật ký thao tác: data/audit.log, mỗi dòng một JSON, xoay vòng 5 MB × 5. Không ghi mật khẩu / token / mã. */
public class Audit {
  static final Pattern SECRET_KEY = Pattern.compile("pass|token|secret|key$|^key|apikey|code|ticket|current|next|passphrase|cookie|authorization", Pattern.CASE_INSENSITIVE);
  static final Object[][] ACTIONS = {
      {"^POST login$", "Đăng nhập"}, {"^POST login/2fa$", "Đăng nhập (mã 2FA)"}, {"^POST logout$", "Đăng xuất"}, {"^POST setup$", "Tạo tài khoản admin đầu tiên"},
      {"^PUT admin/vcenters/", "Sửa vCenter"}, {"^POST admin/vcenters$", "Thêm vCenter"}, {"^DELETE admin/vcenters/", "Xoá vCenter"}, {"^POST admin/vcenters/.+/test$", "Thử kết nối vCenter"},
      {"^PUT admin/customers/assign$", "Gán khách cho VM"}, {"^PUT admin/customers$", "Sửa nguồn / quy tắc khách hàng"},
      {"^PUT admin/alerts$", "Sửa rule cảnh báo"}, {"^PUT admin/notify$", "Sửa kênh gửi cảnh báo"}, {"^POST admin/notify/test", "Gửi thử kênh cảnh báo"},
      {"^PUT admin/influxdb$", "Sửa cấu hình InfluxDB"}, {"^POST admin/influxdb/test$", "Thử ghi InfluxDB"}, {"^PUT admin/general$", "Sửa cài đặt chung"},
      {"^POST admin/users$", "Tạo / đặt lại người dùng"}, {"^DELETE admin/users/", "Xoá người dùng"}, {"^POST admin/users/.+/reset2fa$", "Tắt 2FA của người dùng"},
      {"^POST admin/users/.+/impersonate$", "Xem như người dùng"}, {"^POST impersonate/stop$", "Thôi xem như"},
      {"^POST admin/apikeys$", "Tạo API key"}, {"^DELETE admin/apikeys/", "Thu hồi API key"}, {"^DELETE admin/collectors/", "Xoá collector"},
      {"^PUT admin/ops$", "Sửa cài đặt vận hành"}, {"^POST admin/ops/purge$", "Dọn VM biến mất"}, {"^POST admin/backup$", "Sao lưu"}, {"^POST admin/reports/send$", "Gửi báo cáo tháng"},
      {"^PUT admin/security$", "Sửa cài đặt bảo mật"}, {"^DELETE vms/", "Xoá VM"}, {"^POST alerts/test$", "Gửi thông báo thử"},
      {"^POST account/password$", "Đổi mật khẩu"}, {"^POST account/2fa/enable$", "Bật 2FA"}, {"^POST account/2fa/disable$", "Tắt 2FA"}, {"^POST account/2fa/recovery$", "Tạo lại mã khôi phục"},
      {"^POST account/2fa/setup$", null},
  };

  private final Path file;
  private final long maxBytes = 5L * 1024 * 1024;
  private final int keep = 5;

  public Audit(Path file) { this.file = file; try { Files.createDirectories(file.getParent()); } catch (Exception ignored) {} }

  public static JsonNode scrub(JsonNode v, int depth) {
    if (v == null || v.isNull() || v.isMissingNode()) return v;
    if (v.isTextual()) { String s = v.textValue(); return s.length() > 200 ? Json.F.textNode(s.substring(0, 200) + "…") : v; }
    if (!v.isContainerNode()) return v;
    if (depth > 3) return Json.F.textNode("…");
    if (v.isArray()) {
      ArrayNode a = Json.arr(); int i = 0;
      for (JsonNode x : v) { if (i++ >= 20) { a.add("… +" + (v.size() - 20)); break; } a.add(scrub(x, depth + 1)); }
      return a;
    }
    ObjectNode o = Json.obj();
    for (Iterator<Map.Entry<String, JsonNode>> it = v.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next(); JsonNode x = e.getValue();
      boolean secret = SECRET_KEY.matcher(e.getKey()).find() && !(x.isTextual() && x.textValue().isEmpty()) && !x.isNull() && !x.isBoolean();
      o.set(e.getKey(), secret ? Json.F.textNode("***") : scrub(x, depth + 1));
    }
    return o;
  }

  public static String actionOf(String method, String sub) {
    String k = method + " " + sub;
    for (Object[] a : ACTIONS) if (Pattern.compile((String) a[0]).matcher(k).find()) return (String) a[1];
    return method + " /api/v1/" + sub;
  }

  private Path nth(int i) { return i == 0 ? file : file.resolveSibling(file.getFileName() + "." + i); }

  public synchronized void write(ObjectNode entry) {
    entry.put("ts", System.currentTimeMillis());
    if (entry.has("detail")) entry.set("detail", scrub(entry.get("detail"), 0));
    String line = Json.stringify(entry) + "\n";
    try {
      long size = Files.exists(file) ? Files.size(file) : 0;
      if (size + line.length() > maxBytes) {
        Files.deleteIfExists(nth(keep));
        for (int i = keep - 1; i >= 0; i--) if (Files.exists(nth(i))) Files.move(nth(i), nth(i + 1), StandardCopyOption.REPLACE_EXISTING);
      }
      Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (Exception e) { Log.error("Không ghi được nhật ký thao tác: " + e.getMessage()); }
  }

  /** Ghi một request API làm thay đổi dữ liệu (sau khi đã trả lời). */
  public void request(String method, String sub, int status, Auth.Who who, String ip, String body) {
    String action = actionOf(method, sub);
    if (action == null) return;
    ObjectNode e = Json.obj();
    e.put("user", who == null ? "(chưa đăng nhập)" : who.name);
    if (who != null && who.role != null) e.put("role", who.role);
    if (who != null && who.via != null) e.put("via", who.via);
    e.put("ip", ip).put("action", action).put("path", method + " /api/v1/" + sub).put("status", status).put("ok", status < 400);
    if (body != null && !body.isBlank()) { try { e.set("detail", Json.M.readTree(body)); } catch (Exception ignored) {} }
    write(e);
  }

  /** Gần nhất trước, lọc theo chữ / người dùng / chỉ lỗi. */
  public synchronized List<JsonNode> query(int limit, String q, String user, boolean failed) {
    List<JsonNode> out = new ArrayList<>();
    String ql = q.toLowerCase();
    for (int i = 0; i <= keep && out.size() < limit; i++) {
      List<String> lines;
      try { lines = Files.readAllLines(nth(i), StandardCharsets.UTF_8); } catch (Exception e) { continue; }
      for (int k = lines.size() - 1; k >= 0 && out.size() < limit; k--) {
        String l = lines.get(k); if (l.isEmpty()) continue;
        JsonNode e; try { e = Json.M.readTree(l); } catch (Exception ex) { continue; }
        if (!user.isEmpty() && !user.equals(Json.str(e.path("user")))) continue;
        if (failed && !(e.path("ok").isBoolean() && !e.path("ok").booleanValue())) continue;
        if (!ql.isEmpty() && !l.toLowerCase().contains(ql)) continue;
        out.add(e);
      }
    }
    return out;
  }
}
