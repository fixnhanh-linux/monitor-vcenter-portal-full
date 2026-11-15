package com.vmmonitor.security;

import com.vmmonitor.util.*;
import com.vmmonitor.config.*;
import com.vmmonitor.store.*;
import com.vmmonitor.customer.*;
import com.vmmonitor.security.*;
import com.vmmonitor.web.*;
import com.vmmonitor.alert.*;
import com.vmmonitor.infra.*;
import com.vmmonitor.ops.*;
import com.vmmonitor.admin.*;
import com.vmmonitor.core.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.bouncycastle.crypto.generators.SCrypt;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Xác thực: người dùng (cookie phiên ký HMAC), API key, token agent. Định dạng tương thích bản Node
 * (scrypt$salt$hash, cookie "vmsess" = base64url(JSON).HMAC) nên tài khoản và phiên đang có vẫn dùng được.
 *
 * Vai trò: admin · viewer (nhân viên, user.sites) · manager (phụ trách khách) · customer (khách hàng) · API key: read / ingest / collector / admin.
 * Quyền "staff": dữ liệu hạ tầng (không cho khách / nhân viên phụ trách). "self": trang Tài khoản (kể cả phiên đang bắt buộc bật 2FA).
 */
public class Auth {
  public static final Map<String, Set<String>> ALLOWED = Map.of(
      "ingest", Set.of("ingest", "collector", "admin"), "collector", Set.of("collector", "admin"),
      "read", Set.of("read", "viewer", "admin", "manager", "customer"), "staff", Set.of("read", "viewer", "admin"),
      "admin", Set.of("admin"), "self", Set.of("read", "viewer", "admin", "manager", "customer"));
  public static final List<String> KEY_ROLES = List.of("read", "ingest", "collector", "admin");
  public static final List<String> USER_ROLES = List.of("admin", "viewer", "manager", "customer");
  static final String SESSION_COOKIE = "vmsess", SESSION_ADMIN_COOKIE = "vmsess_admin";
  static final int SESSION_HOURS = 12;
  private static final SecureRandom RND = new SecureRandom();

  /** Người đang gọi. */
  public static class Who {
    public String name, role, via, imp;
    public List<String> customers, sites;
    public boolean enroll;
    public Who(String name, String role, String via) { this.name = name; this.role = role; this.via = via; }
  }
  public record Check(boolean ok, int status, String error, boolean enroll, Who who) {}
  public record LoginResult(String error, boolean need2fa, String ticket, String cookie, ObjectNode user, boolean enroll, boolean restart, boolean recovery, int recoveryLeft) {}

  private final Supplier<ObjectNode> config;
  private final Supplier<Secrets> secrets;
  private final Consumer<Consumer<ObjectNode>> mutateConfig;
  private final byte[] secret;
  private final Map<String, long[]> failures = new ConcurrentHashMap<>(); // ip -> {n, until}
  public final Map<String, Long> lastStep = new ConcurrentHashMap<>();   // user -> bước TOTP đã dùng

  public Auth(Supplier<ObjectNode> config, Path secretFile, Supplier<Secrets> secrets, Consumer<Consumer<ObjectNode>> mutateConfig) {
    this.config = config; this.secrets = secrets; this.mutateConfig = mutateConfig;
    this.secret = loadSecret(secretFile);
  }

  private byte[] loadSecret(Path file) {
    String fromCfg = Json.str(config.get().path("sessionSecret"));
    if (!fromCfg.isEmpty()) return fromCfg.getBytes(StandardCharsets.UTF_8);
    try { return Files.readAllBytes(file); } catch (Exception ignored) {}
    byte[] s = new byte[32]; RND.nextBytes(s);
    try { Files.createDirectories(file.getParent()); Files.write(file, s); } catch (Exception ignored) {}
    return s;
  }

  // ---------- mật khẩu (scrypt, tham số mặc định của Node: N=16384, r=8, p=1) ----------
  public static String hashPassword(String password) {
    byte[] salt = new byte[16]; RND.nextBytes(salt);
    byte[] hash = SCrypt.generate(password.getBytes(StandardCharsets.UTF_8), salt, 16384, 8, 1, 32);
    return "scrypt$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(hash);
  }
  public static boolean verifyPassword(String password, String stored) {
    String[] p = String.valueOf(stored).split("\\$");
    if (p.length != 3 || !"scrypt".equals(p[0])) return false;
    byte[] expected = Base64.getDecoder().decode(p[2]);
    byte[] actual = SCrypt.generate(password.getBytes(StandardCharsets.UTF_8), Base64.getDecoder().decode(p[1]), 16384, 8, 1, expected.length);
    return MessageDigest.isEqual(expected, actual);
  }
  public static boolean safeEqual(String a, String b) { return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8)); }
  public static String newApiKey() { byte[] b = new byte[24]; RND.nextBytes(b); return "vmk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
  public static String sessionRole(JsonNode u) { String r = Json.str(u.path("role")); return List.of("admin", "customer", "manager").contains(r) ? r : "read"; }
  /** dấu vân tay mật khẩu: đổi mật khẩu / tắt 2FA là mọi phiên cũ hết hiệu lực */
  public static String pwTag(JsonNode u) {
    try {
      byte[] d = MessageDigest.getInstance("SHA-256").digest((Json.str(u.path("passwordHash")) + "|" + (Json.truthy(u.path("totp")) ? "t" : "")).getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(d).substring(0, 10);
    } catch (Exception e) { throw new RuntimeException(e); }
  }

  public List<JsonNode> users() { List<JsonNode> l = new ArrayList<>(); config.get().path("users").forEach(l::add); return l; }
  public boolean loginRequired() { return config.get().path("users").size() > 0; }
  public JsonNode user(String name) { for (JsonNode u : config.get().path("users")) if (name != null && name.equals(Json.str(u.path("username")))) return u; return null; }
  public boolean mustHave2fa(JsonNode u) {
    String r = Json.str(config.get().path("security").path("require2fa"));
    return "all".equals(r) || ("admin".equals(r) && "admin".equals(Json.str(u.path("role"))));
  }
  public String totpSecret(JsonNode u) { Secrets s = secrets.get(); return s == null ? "" : s.get("totp:" + Json.str(u.path("username"))); }

  // ---------- phiên ----------
  public String sign(ObjectNode payload) {
    String body = Base64.getUrlEncoder().withoutPadding().encodeToString(Json.stringify(payload).getBytes(StandardCharsets.UTF_8));
    return body + "." + hmac(body);
  }
  private String hmac(String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) { throw new RuntimeException(e); }
  }
  public JsonNode unsign(String token) {
    if (token == null) return null;
    String[] p = token.split("\\.");
    if (p.length != 2 || p[0].isEmpty()) return null;
    if (!safeEqual(p[1], hmac(p[0]))) return null;
    try {
      JsonNode n = Json.M.readTree(new String(Base64.getUrlDecoder().decode(p[0]), StandardCharsets.UTF_8));
      return Json.num(n.path("exp")) > System.currentTimeMillis() ? n : null;
    } catch (Exception e) { return null; }
  }
  public String session(JsonNode u, boolean enroll, String imp) {
    ObjectNode o = Json.obj().put("u", Json.str(u.path("username"))).put("h", pwTag(u));
    if (enroll) o.put("e", 1);
    if (imp != null && !imp.isEmpty()) o.put("imp", imp);
    o.put("exp", System.currentTimeMillis() + (imp != null && !imp.isEmpty() ? 2 : SESSION_HOURS) * 3600000L);
    return sign(o);
  }
  public String cookieHeader(String value, Ctx c) {
    String secure = "https".equals(c.header("x-forwarded-proto")) || c.encrypted() ? "; Secure" : "";
    return SESSION_COOKIE + "=" + value + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + (value.isEmpty() ? 0 : SESSION_HOURS * 3600) + secure;
  }
  public String adminCookieHeader(String value, Ctx c) {
    String secure = "https".equals(c.header("x-forwarded-proto")) || c.encrypted() ? "; Secure" : "";
    return SESSION_ADMIN_COOKIE + "=" + value + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + (value.isEmpty() ? 0 : 2 * 3600) + secure;
  }
  /** Phiên admin hợp lệ đã cất khi bấm "Xem như" (để quay lại). */
  public String savedAdmin(Ctx c) {
    String tok = c.cookie(SESSION_ADMIN_COOKIE);
    JsonNode s = tok.isEmpty() ? null : unsign(tok);
    JsonNode u = s != null && !s.has("t") && !s.has("imp") ? user(Json.str(s.path("u"))) : null;
    return u != null && "admin".equals(Json.str(u.path("role"))) && pwTag(u).equals(Json.str(s.path("h"))) ? tok : "";
  }

  private void fail(String ip) {
    long[] f = failures.get(ip);
    long n = (f == null ? 0 : f[0]) + 1;
    failures.put(ip, new long[]{n, n >= 5 ? System.currentTimeMillis() + 60000 * Math.min(15, n - 4) : 0});
  }
  private String blocked(String ip) {
    long[] f = failures.get(ip);
    return f != null && f[1] > System.currentTimeMillis() ? "Sai quá nhiều lần, thử lại sau " + (long) Math.ceil((f[1] - System.currentTimeMillis()) / 1000.0) + " giây" : null;
  }
  private static ObjectNode userObj(JsonNode u) { return Json.obj().put("username", Json.str(u.path("username"))).put("role", sessionRole(u)); }

  /** Bước 1: tên + mật khẩu; người bật 2FA nhận ticket (5 phút) để gửi kèm mã ở bước 2. */
  public LoginResult login(String username, String password, String ip) {
    String b = blocked(ip); if (b != null) return new LoginResult(b, false, null, null, null, false, false, false, 0);
    JsonNode u = user(username);
    if (u == null || !verifyPassword(password, Json.str(u.path("passwordHash")))) { fail(ip); return new LoginResult("Sai tên đăng nhập hoặc mật khẩu", false, null, null, null, false, false, false, 0); }
    if (Json.truthy(u.path("totp")) && !totpSecret(u).isEmpty()) {
      String t = sign(Json.obj().put("t", "2fa").put("u", username).put("h", pwTag(u)).put("exp", System.currentTimeMillis() + 5 * 60000));
      return new LoginResult(null, true, t, null, null, false, false, false, 0);
    }
    failures.remove(ip);
    boolean enroll = mustHave2fa(u);
    return new LoginResult(null, false, null, session(u, enroll, null), userObj(u), enroll, false, false, 0);
  }

  /** Bước 2: mã 6 số trong app, hoặc một mã khôi phục. */
  public LoginResult login2fa(String ticket, String code, String ip) {
    String b = blocked(ip); if (b != null) return new LoginResult(b, false, null, null, null, false, false, false, 0);
    JsonNode t = unsign(ticket);
    JsonNode u = t != null && "2fa".equals(Json.str(t.path("t"))) ? user(Json.str(t.path("u"))) : null;
    if (u == null || !pwTag(u).equals(Json.str(t.path("h")))) return new LoginResult("Phiên đăng nhập đã hết hạn, nhập lại mật khẩu", false, null, null, null, false, true, false, 0);
    String c = code == null ? "" : code.trim();
    String uname = Json.str(u.path("username"));
    boolean ok = false, recovery = false;
    if (c.replaceAll("\\s", "").matches("\\d{6}")) {
      long step = Totp.verify(totpSecret(u), c, lastStep.getOrDefault(uname, -1L));
      if (step >= 0) { ok = true; lastStep.put(uname, step); }
    } else if (c.length() >= 8) {
      String h = Totp.hashRecovery(c);
      for (JsonNode x : u.path("recovery")) if (h.equals(x.asText())) { ok = true; recovery = true; break; }
      if (recovery) mutateConfig.accept((raw) -> {
        for (JsonNode x : raw.path("users")) if (uname.equals(Json.str(x.path("username"))) && x.path("recovery").isArray()) {
          com.fasterxml.jackson.databind.node.ArrayNode a = Json.arr();
          x.path("recovery").forEach((r) -> { if (!h.equals(r.asText())) a.add(r); });
          ((ObjectNode) x).set("recovery", a);
        }
      });
    }
    if (!ok) { fail(ip); return new LoginResult("Mã xác thực không đúng", false, null, null, null, false, false, false, 0); }
    failures.remove(ip);
    return new LoginResult(null, false, null, session(u, false, null), userObj(u), false, false, recovery, u.path("recovery").size() - (recovery ? 1 : 0));
  }

  // ---------- nhận diện người gọi ----------
  public Who identify(Ctx c) {
    ObjectNode cfg = config.get();
    String authz = c.header("authorization");
    String bearer = authz.startsWith("Bearer ") ? authz.substring(7).trim() : "";
    String key = !c.header("x-api-key").isEmpty() ? c.header("x-api-key") : !bearer.isEmpty() ? bearer : c.q("api_key");
    if (!key.isEmpty()) {
      for (JsonNode k : cfg.path("apiKeys")) {
        String kk = Json.str(k.path("key"));
        if (!kk.isEmpty() && safeEqual(key, kk)) return new Who(Json.str(k.path("name")).isEmpty() ? "api-key" : Json.str(k.path("name")), Json.str(k.path("role")).isEmpty() ? "read" : Json.str(k.path("role")), "apikey");
      }
      String token = Json.str(cfg.path("token"));
      if (!token.isEmpty() && safeEqual(key, token)) return new Who("agent", "ingest", "token");
    }
    String ck = c.cookie(SESSION_COOKIE);
    if (!ck.isEmpty()) {
      JsonNode s = unsign(ck);
      JsonNode u = s != null && !s.has("t") ? user(Json.str(s.path("u"))) : null;
      if (u != null && pwTag(u).equals(Json.str(s.path("h")))) {
        String role = Json.str(u.path("role"));
        Who w = new Who(Json.str(u.path("username")), sessionRole(u), "session");
        w.imp = s.has("imp") ? Json.str(s.path("imp")) : null;
        w.enroll = w.imp == null && Json.truthy(s.path("e")) && mustHave2fa(u) && !Json.truthy(u.path("totp"));
        if (List.of("customer", "manager").contains(role)) w.customers = Json.strings(u.path("customers"));
        if ("viewer".equals(role) && !Json.strings(u.path("sites")).isEmpty()) w.sites = Json.strings(u.path("sites"));
        return w;
      }
    }
    return null;
  }

  /** Kiểm tra quyền. Khi chưa có tài khoản người dùng nào thì portal mở (cài đặt ban đầu). */
  public Check check(Ctx c, String need) {
    ObjectNode cfg = config.get();
    Who who = identify(c);
    if (who != null) {
      if (who.enroll && !"self".equals(need)) return new Check(false, 403, "Cần bật xác thực 2 lớp trước khi dùng portal", true, null);
      if (ALLOWED.get(need).contains(who.role)) return new Check(true, 200, null, false, who);
      return new Check(false, 403, "Cần quyền " + need, false, null);
    }
    boolean loopback = c.loopback() && c.header("x-forwarded-for").isEmpty();
    boolean open;
    if ("ingest".equals(need)) {
      boolean hasIngestKey = false;
      for (JsonNode k : cfg.path("apiKeys")) if ("ingest".equals(Json.str(k.path("role")))) hasIngestKey = true;
      open = (loopback && Json.notFalse(cfg.path("collector").path("allowLocal"))) || (!Json.truthy(cfg.path("token")) && !hasIngestKey);
    } else if ("self".equals(need) || "collector".equals(need)) {
      open = false;
    } else {
      open = !loginRequired();
    }
    if (open) return new Check(true, 200, null, false, new Who("anonymous", "admin", "open"));
    return new Check(false, 401, "Chưa đăng nhập hoặc thiếu API key", false, null);
  }

  // ---------- lệnh: adduser / apikey ----------
  public static void addUserCli(Cfg cfg, String username, String role) throws Exception {
    if (username == null || !username.matches("[\\w.@-]{2,64}")) { System.err.println("Cách dùng: java -jar vm-monitor-admin.jar adduser <tên-đăng-nhập> [--role admin|viewer]"); System.exit(1); }
    String pw = System.getenv("NEW_USER_PASSWORD");
    if (pw == null) {
      java.io.Console con = System.console();
      if (con == null) { System.err.println("Không có console: đặt biến NEW_USER_PASSWORD"); System.exit(1); }
      pw = new String(con.readPassword("Mật khẩu cho %s: ", username));
      String again = new String(con.readPassword("Nhập lại mật khẩu: "));
      if (!again.equals(pw)) { System.err.println("Hai lần nhập không khớp"); System.exit(1); }
    }
    if (pw.length() < 8) { System.err.println("Mật khẩu cần ít nhất 8 ký tự"); System.exit(1); }
    String p = pw, r = "viewer".equals(role) ? "viewer" : "admin";
    ObjectNode raw = cfg.readRaw();
    com.fasterxml.jackson.databind.node.ArrayNode users = Json.arr();
    raw.path("users").forEach((u) -> { if (!username.equals(Json.str(u.path("username")))) users.add(u); });
    users.addObject().put("username", username).put("role", r).put("passwordHash", hashPassword(p));
    raw.set("users", users);
    Files.writeString(cfg.file, Json.pretty(raw), StandardCharsets.UTF_8);
    System.out.println("Đã lưu người dùng \"" + username + "\" (" + r + ") vào " + cfg.file);
  }

  public static void genKeyCli(Cfg cfg, String name, String role) throws Exception {
    if (name == null) { System.err.println("Cách dùng: java -jar vm-monitor-admin.jar apikey <tên-hệ-thống> [--role read|ingest|admin]"); System.exit(1); }
    String key = newApiKey(), r = KEY_ROLES.contains(role) ? role : "read";
    ObjectNode raw = cfg.readRaw();
    com.fasterxml.jackson.databind.node.ArrayNode keys = Json.arr();
    raw.path("apiKeys").forEach((k) -> { if (!name.equals(Json.str(k.path("name")))) keys.add(k); });
    keys.addObject().put("name", name).put("role", r).put("key", key).put("created", java.time.Instant.now().toString());
    raw.set("apiKeys", keys);
    Files.writeString(cfg.file, Json.pretty(raw), StandardCharsets.UTF_8);
    System.out.println("API key cho \"" + name + "\" (" + r + "):\n\n  " + key + "\n\nĐã lưu vào " + cfg.file + ". Gửi kèm header:  X-API-Key: " + key);
  }
}
