package com.vmmonitor.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vmmonitor.config.Cfg;
import com.vmmonitor.util.Json;
import com.vmmonitor.web.Ctx;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * "Tài khoản của tôi": đổi mật khẩu, bật / tắt 2FA, mã khôi phục.
 *   GET /api/v1/account · POST /account/password · POST /account/2fa/{setup|enable|disable|recovery}
 */
public class Account {
  private final Auth auth;
  private final Cfg cfg;
  private final Secrets secrets;
  private final Map<String, Object[]> pending = new ConcurrentHashMap<>(); // user -> {secret, exp}

  public Account(Auth auth, Cfg cfg, Secrets secrets) { this.auth = auth; this.cfg = cfg; this.secrets = secrets; }

  ObjectNode info(JsonNode u, Auth.Who who) {
    String role = Json.truthy(u.path("role")) ? Json.str(u.path("role")) : "admin";
    ObjectNode o = Json.obj().put("user", Json.str(u.path("username"))).put("role", role);
    if (List.of("customer", "manager").contains(role)) o.set("customers", Json.strArr(Json.strings(u.path("customers"))));
    if ("viewer".equals(role) && !Json.strings(u.path("sites")).isEmpty()) o.set("sites", Json.strArr(Json.strings(u.path("sites"))));
    if (who.imp != null) o.put("imp", who.imp);
    o.put("totp", Json.truthy(u.path("totp")));
    if (Json.truthy(u.path("totpSince"))) o.put("totpSince", Json.str(u.path("totpSince")));
    o.put("recoveryLeft", u.path("recovery").size()).put("required", auth.mustHave2fa(u)).put("enroll", who.enroll);
    return o;
  }

  void renew(Ctx c, String username, ObjectNode data) {
    JsonNode u = auth.user(username);
    c.json(200, data, Map.of("set-cookie", auth.cookieHeader(auth.session(u, false, null), c)));
  }

  void save(String username, Consumer<ObjectNode> fn) {
    cfg.mutate((raw) -> { for (JsonNode x : raw.path("users")) if (username.equals(Json.str(x.path("username")))) fn.accept((ObjectNode) x); });
  }

  public boolean handle(Ctx c, Function<String, Auth.Who> need) {
    List<String> r = c.seg.subList(2, c.seg.size());
    if (r.isEmpty() || !"account".equals(r.get(0))) return false;
    Auth.Who who = need.apply("self");
    if (who == null) return true;
    if (!"session".equals(who.via)) { c.error(400, "Chỉ dùng được khi đăng nhập bằng tài khoản (không phải API key)"); return true; }
    c.auditUser = who.name; // cookie đổi trong lúc xử lý: ghi tên trước
    if (who.imp != null && !"GET".equals(c.method)) { c.error(403, "Đang xem như " + who.name + ": không đổi được mật khẩu / 2FA của người này"); return true; }
    JsonNode u = auth.user(who.name);
    if (u == null) { c.error(404, "Không tìm thấy tài khoản"); return true; }
    String uname = who.name, action = String.join("/", r.subList(1, r.size()));
    if ("GET".equals(c.method) && action.isEmpty()) { c.json(200, info(u, who)); return true; }
    if (!"POST".equals(c.method)) return false;
    switch (action) {
      case "password" -> {
        JsonNode b = c.jsonBody(10000);
        if (!Auth.verifyPassword(Json.str(b.path("current")), Json.str(u.path("passwordHash")))) { c.error(400, "Mật khẩu hiện tại không đúng"); return true; }
        if (Json.str(b.path("next")).length() < 8) { c.error(400, "Mật khẩu mới cần ít nhất 8 ký tự"); return true; }
        String hash = Auth.hashPassword(Json.str(b.path("next")));
        save(uname, (x) -> x.put("passwordHash", hash));
        renew(c, uname, Json.obj().put("ok", true));
      }
      case "2fa/setup" -> {
        if (Json.truthy(u.path("totp"))) { c.error(400, "Đã bật 2FA. Tắt trước nếu muốn đổi điện thoại."); return true; }
        String secret = Totp.newSecret();
        pending.put(uname, new Object[]{secret, System.currentTimeMillis() + 15 * 60000});
        c.json(200, Json.obj().put("secret", secret).put("uri", Totp.uri(secret, uname)));
      }
      case "2fa/enable" -> {
        JsonNode b = c.jsonBody(10000);
        Object[] p = pending.get(uname);
        if (p == null || (long) p[1] < System.currentTimeMillis()) { c.error(400, "Mã QR đã hết hạn, bấm \"Bật 2FA\" để tạo lại"); return true; }
        long step = Totp.verify((String) p[0], Json.str(b.path("code")), -1);
        if (step < 0) { c.error(400, "Mã không đúng. Kiểm tra giờ trên điện thoại có chính xác không."); return true; }
        pending.remove(uname);
        auth.lastStep.put(uname, step);
        secrets.set("totp:" + uname, (String) p[0]);
        List<String> codes = Totp.recoveryCodes(10);
        save(uname, (x) -> { x.put("totp", true); ArrayNode rec = x.putArray("recovery"); codes.forEach((k) -> rec.add(Totp.hashRecovery(k))); x.put("totpSince", java.time.Instant.now().toString()); });
        ObjectNode out = Json.obj().put("ok", true); out.set("recovery", Json.strArr(codes));
        renew(c, uname, out);
      }
      case "2fa/disable" -> {
        JsonNode b = c.jsonBody(10000);
        if (!Json.truthy(u.path("totp"))) { c.error(400, "2FA đang tắt"); return true; }
        if (auth.mustHave2fa(u)) { c.error(400, "Quản trị viên bắt buộc 2FA cho tài khoản này, không tắt được"); return true; }
        if (!Auth.verifyPassword(Json.str(b.path("password")), Json.str(u.path("passwordHash")))) { c.error(400, "Mật khẩu không đúng"); return true; }
        if (Totp.verify(auth.totpSecret(u), Json.str(b.path("code")), -1) < 0) { c.error(400, "Mã 2FA không đúng"); return true; }
        secrets.del("totp:" + uname);
        save(uname, (x) -> { x.remove("totp"); x.remove("recovery"); x.remove("totpSince"); });
        renew(c, uname, Json.obj().put("ok", true));
      }
      case "2fa/recovery" -> {
        JsonNode b = c.jsonBody(10000);
        if (!Json.truthy(u.path("totp"))) { c.error(400, "2FA đang tắt"); return true; }
        if (Totp.verify(auth.totpSecret(u), Json.str(b.path("code")), -1) < 0) { c.error(400, "Mã 2FA không đúng"); return true; }
        List<String> codes = Totp.recoveryCodes(10);
        save(uname, (x) -> { ArrayNode rec = x.putArray("recovery"); codes.forEach((k) -> rec.add(Totp.hashRecovery(k))); });
        ObjectNode out = Json.obj().put("ok", true); out.set("recovery", Json.strArr(codes));
        c.json(200, out);
      }
      default -> { return false; }
    }
    return true;
  }
}
