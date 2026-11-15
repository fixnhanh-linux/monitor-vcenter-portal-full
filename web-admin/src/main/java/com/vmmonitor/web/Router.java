package com.vmmonitor.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vmmonitor.alert.Notify;
import com.vmmonitor.core.Portal;
import com.vmmonitor.core.Scope;
import com.vmmonitor.security.Auth;
import com.vmmonitor.store.Store;
import com.vmmonitor.util.HttpError;
import com.vmmonitor.util.Json;
import com.vmmonitor.util.Log;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;

/** Định tuyến mọi request (tương đương handle() của server.js, giữ nguyên thứ tự xử lý). */
public class Router extends HttpServlet {
  private final Portal p;
  private volatile long lastTest = 0;
  static final Map<String, String> MIME = Map.of(".html", "text/html; charset=utf-8", ".js", "text/javascript; charset=utf-8", ".css", "text/css; charset=utf-8",
      ".svg", "image/svg+xml", ".ico", "image/x-icon", ".json", "application/json", ".png", "image/png");
  static final Map<String, String> PAGES = Map.of("/login", "login.html", "/khach-hang", "login.html", "/docs", "docs.html", "/settings", "settings.html", "/hosts", "hosts.html",
      "/report", "report.html", "/account", "account.html", "/my", "my.html", "/home", "home.html");
  private final String openapi;

  public Router(Portal p) {
    this.p = p;
    String s;
    try (InputStream in = Router.class.getResourceAsStream("/openapi.json")) { s = new String(in.readAllBytes(), StandardCharsets.UTF_8); } catch (Exception e) { s = "{}"; }
    this.openapi = s;
  }

  @Override
  protected void service(HttpServletRequest req, HttpServletResponse res) {
    Ctx c = new Ctx(req, res);
    try {
      if ("GET".equals(c.method) && "/api/v1/stream".equals(c.path)) { stream(c); return; }
      synchronized (p.lock) { handle(c); }
    } catch (HttpError e) {
      c.error(e.status, e.getMessage());
    } catch (Exception e) {
      Log.error("Lỗi xử lý " + c.method + " " + c.path + ": " + e);
      c.error(400, String.valueOf(e.getMessage()));
    } finally {
      audit(c);
    }
  }

  /** nhật ký thao tác: mọi request thay đổi dữ liệu (trừ số liệu từ collector / agent) */
  void audit(Ctx c) {
    if (!List.of("POST", "PUT", "DELETE").contains(c.method) || !c.path.startsWith("/api/v1/") || c.path.matches("^/api/v1/(report|collector/).*")) return;
    Auth.Who who = null;
    try { who = p.auth.identify(c); } catch (Exception ignored) {}
    if (c.auditUser != null) { Auth.Who w = new Auth.Who(c.auditUser, who == null ? null : who.role, "login"); w.imp = who == null ? null : who.imp; who = w; }
    if (who != null && who.imp != null) { Auth.Who w = new Auth.Who(who.imp + " (xem như " + who.name + ")", who.role, who.via); w.imp = who.imp; who = w; }
    p.audit.request(c.method, c.path.substring(8), c.res.getStatus(), who, c.ip, c.body);
  }

  Auth.Who need(Ctx c, String role) {
    Auth.Check r = p.auth.check(c, role);
    if (!r.ok()) { ObjectNode e = Json.obj().put("error", r.error()); if (r.enroll()) e.put("enroll", true); c.json(r.status(), e); return null; }
    return r.who();
  }

  void cors(Ctx c) {
    JsonNode allowed = p.cfg.get().path("cors");
    String origin = c.header("origin");
    if (Json.missing(allowed) || origin.isEmpty()) return;
    List<String> list = Json.strings(allowed);
    if (list.contains("*") || list.contains(origin)) {
      c.res.setHeader("access-control-allow-origin", list.contains("*") ? "*" : origin);
      c.res.setHeader("access-control-allow-headers", "content-type, x-api-key, authorization");
      c.res.setHeader("access-control-allow-methods", "GET, POST, DELETE, OPTIONS");
      c.res.setHeader("vary", "origin");
    }
  }

  void serveFile(Ctx c, String rel) {
    if (rel.contains("..") || rel.contains("\\")) { c.error(403, "forbidden"); return; }
    try {
      byte[] data;
      if (p.publicDir == null) {
        try (InputStream in = Router.class.getResourceAsStream("/public/" + rel)) { if (in == null) throw new java.io.FileNotFoundException(rel); data = in.readAllBytes(); }
      } else {
        Path f = p.publicDir.resolve(rel).normalize();
        if (!f.startsWith(p.publicDir.normalize())) { c.error(403, "forbidden"); return; }
        data = Files.readAllBytes(f);
      }
      String name = rel.contains("/") ? rel.substring(rel.lastIndexOf('/') + 1) : rel, ext = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
      c.send(200, data, MIME.getOrDefault(ext, "application/octet-stream"), Map.of("cache-control", "no-cache"));
    } catch (Exception e) { c.error(404, "not found"); }
  }

  // ---------- realtime ----------
  void stream(Ctx c) throws Exception {
    Auth.Who who = need(c, "read");
    if (who == null) return;
    c.done = true;
    HttpServletResponse res = c.res;
    res.setStatus(200);
    res.setHeader("content-type", "text/event-stream");
    res.setHeader("cache-control", "no-cache");
    res.setHeader("connection", "keep-alive");
    Ctx.SEC_HEADERS.forEach(res::setHeader);
    AsyncContext ac = c.req.startAsync();
    ac.setTimeout(0);
    OutputStream out = res.getOutputStream();
    Portal.Client client = new Portal.Client(ac, out, Scope.of(who));
    p.write(client, "retry: 3000\n\n");
    p.write(client, "event: hello\ndata: " + Json.stringify(Json.obj().put("version", p.uiVersion)) + "\n\n");
    p.clients.add(client);
    ac.addListener(new jakarta.servlet.AsyncListener() {
      public void onComplete(jakarta.servlet.AsyncEvent e) { p.clients.remove(client); }
      public void onTimeout(jakarta.servlet.AsyncEvent e) { p.clients.remove(client); }
      public void onError(jakarta.servlet.AsyncEvent e) { p.clients.remove(client); }
      public void onStartAsync(jakarta.servlet.AsyncEvent e) {}
    });
  }

  // ---------- định tuyến ----------
  void handle(Ctx c) throws Exception {
    String path = c.path, m = c.method;
    Function<String, Auth.Who> need = (role) -> need(c, role);
    ObjectNode cfg = p.cfg.get();

    // HTTP -> HTTPS cho trình duyệt từ máy khác (API / collector / Prometheus vẫn dùng HTTP)
    if (p.httpsState.path("running").asBoolean() && Json.truthy(cfg.path("https").path("redirectHttp")) && !c.encrypted() && "GET".equals(m) && !c.loopback()
        && !path.startsWith("/api/") && !"/metrics".equals(path) && !"/healthz".equals(path)) {
      String host = c.header("host").replaceAll(":\\d+$", ""); int port = p.httpsState.path("port").asInt();
      String qs = c.req.getQueryString();
      c.res.setStatus(301); c.res.setHeader("location", "https://" + (host.isEmpty() ? "localhost" : host) + (port == 443 ? "" : ":" + port) + c.req.getRequestURI() + (qs == null ? "" : "?" + qs));
      c.done = true; return;
    }
    if (c.encrypted()) c.res.setHeader("strict-transport-security", "max-age=15552000");
    if (path.startsWith("/api/") || "/metrics".equals(path)) {
      cors(c);
      if ("OPTIONS".equals(m)) { c.res.setStatus(204); c.done = true; return; }
    }

    // --- không cần đăng nhập ---
    if ("/healthz".equals(path)) {
      c.json(200, Json.obj().put("ok", true).put("vms", p.store.vms.size()).put("uptime", (System.currentTimeMillis() - p.startedAt) / 1000.0).put("runtime", "java").set("influxdb", p.influx.status()));
      return;
    }
    if (PAGES.containsKey(path)) { serveFile(c, PAGES.get(path)); return; }
    if ("/api/v1/openapi.json".equals(path)) {
      String base = Json.str(cfg.path("publicUrl"));
      c.send(200, openapi.replace("__BASE_URL__", base.isEmpty() ? "/" : base.replace("\"", "")), "application/json; charset=utf-8", null);
      return;
    }
    if ("POST".equals(m) && "/api/v1/login".equals(path)) {
      JsonNode b = c.jsonBody(10000);
      String u = Json.str(b.path("username"));
      c.auditUser = u.length() > 64 ? u.substring(0, 64) : u;
      Auth.LoginResult r = p.auth.login(u, Json.str(b.path("password")), c.ip);
      if (r.error() != null) { c.error(401, r.error()); return; }
      if (r.need2fa()) { c.json(200, Json.obj().put("need2fa", true).put("ticket", r.ticket())); return; }
      c.json(200, Json.obj().put("ok", true).<ObjectNode>set("user", r.user()).put("enroll", r.enroll()), Map.of("set-cookie", p.auth.cookieHeader(r.cookie(), c)));
      return;
    }
    if ("POST".equals(m) && "/api/v1/login/2fa".equals(path)) {
      JsonNode b = c.jsonBody(10000);
      JsonNode t = p.auth.unsign(Json.str(b.path("ticket")));
      c.auditUser = t == null ? "(?)" : Json.str(t.path("u"));
      Auth.LoginResult r = p.auth.login2fa(Json.str(b.path("ticket")), Json.str(b.path("code")), c.ip);
      if (r.error() != null) { c.json(401, Json.obj().put("error", r.error()).put("restart", r.restart())); return; }
      c.json(200, Json.obj().put("ok", true).<ObjectNode>set("user", r.user()).put("recovery", r.recovery()).put("recoveryLeft", r.recoveryLeft()),
          Map.of("set-cookie", p.auth.cookieHeader(r.cookie(), c)));
      return;
    }
    if ("POST".equals(m) && "/api/v1/setup".equals(path)) {
      if (p.auth.loginRequired()) { c.error(409, "Portal đã có tài khoản, hãy đăng nhập"); return; }
      JsonNode b = c.jsonBody(10000);
      String username = Json.str(b.path("username")).trim(), password = Json.str(b.path("password"));
      if (!username.matches("[\\w.@-]{2,64}")) { c.error(400, "Tên đăng nhập 2–64 ký tự: chữ, số, . _ @ -"); return; }
      if (password.length() < 8) { c.error(400, "Mật khẩu cần ít nhất 8 ký tự"); return; }
      boolean[] created = {false};
      String hash = Auth.hashPassword(password);
      p.cfg.mutate((raw) -> {
        if (raw.path("users").size() > 0) return;
        raw.putArray("users").addObject().put("username", username).put("role", "admin").put("passwordHash", hash);
        created[0] = true;
      });
      if (!created[0]) { c.error(409, "Portal đã có tài khoản, hãy đăng nhập"); return; }
      Log.info("[auth] đã tạo tài khoản admin đầu tiên \"" + username + "\" từ " + c.ip);
      c.auditUser = username;
      Auth.LoginResult r = p.auth.login(username, password, c.ip);
      c.json(200, Json.obj().put("ok", true).set("user", r.user()), Map.of("set-cookie", p.auth.cookieHeader(r.cookie(), c)));
      return;
    }
    if ("POST".equals(m) && "/api/v1/logout".equals(path)) { c.json(200, Json.obj().put("ok", true), Map.of("set-cookie", p.auth.cookieHeader("", c))); return; }
    if ("/api/v1/me".equals(path)) {
      Auth.Check r = p.auth.check(c, "read");
      if (!r.ok() && r.enroll()) { Auth.Who w = p.auth.identify(c); c.json(200, Json.obj().put("user", w.name).put("role", w.role).put("via", w.via).put("enroll", true).put("loginRequired", true)); return; }
      if (!r.ok()) { c.json(401, Json.obj().put("error", r.error()).put("loginRequired", p.auth.loginRequired())); return; }
      Auth.Who w = r.who();
      ObjectNode o = Json.obj().put("user", w.name).put("role", w.role).put("via", w.via);
      if (w.customers != null) o.set("customers", Json.strArr(w.customers));
      if (w.sites != null) o.set("sites", Json.strArr(w.sites));
      if (w.imp != null) o.put("imp", w.imp);
      o.put("loginRequired", p.auth.loginRequired()).put("grafanaUrl", Json.str(cfg.path("grafanaUrl")));
      c.json(200, o); return;
    }
    if ("POST".equals(m) && "/api/v1/impersonate/stop".equals(path)) {
      String back = p.auth.savedAdmin(c);
      Auth.Who w = p.auth.identify(c);
      c.auditUser = w == null ? "(?)" : w.imp != null ? w.imp : w.name;
      c.json(200, Json.obj().put("ok", true).put("back", !back.isEmpty()), Map.of("set-cookie", List.of(p.auth.cookieHeader(back, c), p.auth.adminCookieHeader("", c))));
      return;
    }
    if (path.startsWith("/api/v1/account")) { if (p.account.handle(c, need)) return; }
    if ("GET".equals(m) && "/api/v1/admin/audit".equals(path)) {
      if (need.apply("admin") == null) return;
      int limit = (int) Math.min(Json.num(Json.F.textNode(c.q("limit"))) > 0 ? Json.num(Json.F.textNode(c.q("limit"))) : 300, 2000);
      c.json(200, Json.obj().set("data", Json.arr(p.audit.query(limit, c.q("q"), c.q("user"), "1".equals(c.q("failed"))))));
      return;
    }

    // --- gửi số liệu (agent / collector) ---
    if ("POST".equals(m) && ("/api/report".equals(path) || "/api/v1/report".equals(path))) {
      if (need.apply("ingest") == null) return;
      JsonNode body = Json.parse(c.readBody());
      List<JsonNode> items = new ArrayList<>();
      if (body.isArray()) { int k = 0; for (JsonNode x : body) { if (k++ >= 2000) break; items.add(x); } } else items.add(body);
      int accepted = 0;
      for (JsonNode x : items) if (p.ingest(x)) accepted++;
      c.json(200, Json.obj().put("ok", true).put("accepted", accepted)); return;
    }

    // --- đọc ---
    if ("/metrics".equals(path)) {
      if (!Json.truthy(cfg.path("metricsPublic"))) {
        Auth.Who w = need.apply("staff"); if (w == null) return;
        if (w.sites != null) { c.error(403, "Tài khoản giới hạn site không đọc được /metrics"); return; }
      }
      String text = p.prometheus();
      if (c.header("accept-encoding").matches(".*\\bgzip\\b.*")) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(); try (GZIPOutputStream gz = new GZIPOutputStream(bo)) { gz.write(text.getBytes(StandardCharsets.UTF_8)); }
        c.send(200, bo.toByteArray(), "text/plain; version=0.0.4; charset=utf-8", Map.of("content-encoding", "gzip", "vary", "accept-encoding"));
      } else c.send(200, text, "text/plain; version=0.0.4; charset=utf-8", null);
      return;
    }
    if (path.startsWith("/api/v1/hosts") || path.startsWith("/api/v1/datastores")) {
      if (p.infra.handle(c, (role) -> need(c, "read".equals(role) ? "staff" : role))) return;
    }
    if (path.startsWith("/api/v1/usage") || path.startsWith("/api/v1/admin/")) { if (p.ops.handle(c, need)) return; }
    if (path.startsWith("/api/v1/admin/") || path.startsWith("/api/v1/collector/")) { if (p.admin.handle(c, need)) return; }

    if (path.startsWith("/api/v1/") || "/api/v1".equals(path)) {
      List<String> r = c.seg.subList(2, c.seg.size());
      String r0 = r.isEmpty() ? "" : r.get(0);
      if ("GET".equals(m) && "summary".equals(r0)) {
        Auth.Who who = need.apply("read"); if (who == null) return;
        Scope scope = Scope.of(who);
        List<ObjectNode> list = new ArrayList<>(); for (Store.Rec v : p.filterVms(c::q, scope)) list.add(p.view(v, false, false));
        List<ObjectNode> on = list.stream().filter((v) -> "online".equals(v.path("status").asText())).toList();
        Function<String, Double> avg = (k) -> on.isEmpty() ? 0 : Json.r1(on.stream().mapToDouble((v) -> Json.num(v.path(k))).sum() / on.size());
        String site = c.q("site");
        List<ObjectNode> act = p.allActive().stream().filter((a) -> p.alertVisible(a, scope) && (site.isEmpty() || List.of(site.split(",")).contains(Json.str(a.path("site"))))).toList();
        ObjectNode o = Json.obj().put("vms", list.size()).put("online", on.size()).put("offline", list.stream().filter((v) -> "offline".equals(v.path("status").asText())).count())
            .put("off", list.stream().filter((v) -> "off".equals(v.path("status").asText())).count()).put("sites", list.stream().map((v) -> v.path("site").asText()).distinct().count())
            .put("cpuAvg", avg.apply("cpu")).put("memAvg", avg.apply("mem")).put("diskAvg", avg.apply("diskMax"))
            .put("netRx", on.stream().mapToDouble((v) -> Json.num(v.path("netRx"))).sum()).put("netTx", on.stream().mapToDouble((v) -> Json.num(v.path("netTx"))).sum());
        o.putObject("alerts").put("total", act.size()).put("critical", act.stream().filter((a) -> "critical".equals(Json.str(a.path("severity")))).count())
            .put("warning", act.stream().filter((a) -> "warning".equals(Json.str(a.path("severity")))).count()).put("vms", act.stream().map((a) -> Json.str(a.path("vmId"))).distinct().count());
        c.json(200, o); return;
      }
      if ("GET".equals(m) && "customers".equals(r0)) {
        Auth.Who who = need.apply("read"); if (who == null) return;
        Scope scope = Scope.of(who);
        Map<String, ObjectNode> map = new java.util.LinkedHashMap<>();
        Map<String, java.util.Set<String>> sites = new java.util.HashMap<>();
        for (Store.Rec vm : p.store.vms.values()) {
          if (!p.visible(vm, scope)) continue;
          var cr = p.customers.resolve(vm.info);
          String key = cr == null ? "" : cr.customer();
          ObjectNode x = map.computeIfAbsent(key, (k) -> { ObjectNode n = Json.obj().put("customer", k).put("vms", 0).put("online", 0).put("off", 0).put("offline", 0); n.putObject("sources"); return n; });
          x.put("vms", x.get("vms").asInt() + 1);
          String st = p.statusOf(vm); x.put(st, x.path(st).asInt() + 1);
          sites.computeIfAbsent(key, (k) -> new java.util.LinkedHashSet<>()).add(Json.str(vm.info.path("site")));
          if (cr != null) { ObjectNode s = (ObjectNode) x.get("sources"); s.put(cr.source(), s.path(cr.source()).asInt() + 1); }
        }
        List<ObjectNode> data = new ArrayList<>();
        for (var e : map.entrySet()) {
          ObjectNode x = e.getValue();
          if (scope != null && scope.hide()) x.remove("sources"); else x.set("sites", Json.strArr(sites.get(e.getKey())));
          data.add(x);
        }
        data.sort((a, b) -> {
          boolean ea = a.get("customer").asText().isEmpty(), eb = b.get("customer").asText().isEmpty();
          if (ea != eb) return ea ? 1 : -1;
          return b.get("vms").asInt() - a.get("vms").asInt();
        });
        long total = data.stream().filter((x) -> !x.get("customer").asText().isEmpty()).count();
        ObjectNode none = map.get("");
        c.json(200, Json.obj().put("total", total).put("unassigned", none == null ? 0 : none.get("vms").asInt()).set("data", Json.arr(data))); return;
      }
      if ("GET".equals(m) && "sites".equals(r0)) {
        Auth.Who who = need.apply("read"); if (who == null) return;
        Scope scope = Scope.of(who);
        if (scope != null && scope.hide()) { c.json(200, Json.obj().set("data", Json.arr())); return; }
        List<ObjectNode> list = new ArrayList<>(); for (Store.Rec v : p.store.vms.values()) if (p.visible(v, scope)) list.add(p.view(v, false, false));
        c.json(200, Json.obj().set("data", p.siteStats(list))); return;
      }
      if ("vms".equals(r0) && r.size() == 1 && "GET".equals(m)) {
        Auth.Who who = need.apply("read"); if (who == null) return;
        Scope scope = Scope.of(who);
        List<Store.Rec> list = p.sortVms(p.filterVms(c::q, scope), c.q("sort").isEmpty() ? "name" : c.q("sort"));
        if ("csv".equals(c.q("format"))) {
          List<ObjectNode> rows = new ArrayList<>(); for (Store.Rec v : list) rows.add(p.strip(p.view(v, false, false), scope));
          c.send(200, Portal.toCsv(rows), "text/csv; charset=utf-8", Map.of("content-disposition", "attachment; filename=\"vms-" + java.time.LocalDate.now() + ".csv\""));
          return;
        }
        int limit = (int) Math.min(Math.max(1, Json.num(Json.F.textNode(c.q("limit"))) > 0 ? Json.num(Json.F.textNode(c.q("limit"))) : 100), 5000);
        int offset = (int) Math.max(0, Json.num(Json.F.textNode(c.q("offset"))));
        boolean spark = "1".equals(c.q("spark")), all = "1".equals(c.q("all"));
        List<Store.Rec> page = all ? list : list.subList(Math.min(offset, list.size()), Math.min(list.size(), offset + limit));
        ArrayNode data = Json.arr(); for (Store.Rec v : page) data.add(p.strip(p.view(v, false, spark), scope));
        c.json(200, Json.obj().put("total", list.size()).put("limit", all ? list.size() : limit).put("offset", all ? 0 : offset).set("data", data)); return;
      }
      if ("vms".equals(r0) && r.size() >= 3) {
        boolean metrics = "metrics".equals(r.get(r.size() - 1)) && r.size() > 3;
        String id = r.get(1) + "/" + String.join("/", r.subList(2, metrics ? r.size() - 1 : r.size()));
        Store.Rec vm = p.store.vms.get(id);
        if ("DELETE".equals(m)) {
          if (need.apply("admin") == null) return;
          if (vm == null) { c.error(404, "Không tìm thấy VM"); return; }
          p.store.vms.remove(id); p.store.dirty = true; p.alerts.forget(id); p.invalidateAlertCounts();
          p.broadcast("removed", Json.obj().put("id", id)); p.broadcast("alert", Json.obj().set("active", Json.arr(p.allActive())));
          c.json(200, Json.obj().put("ok", true)); return;
        }
        Auth.Who who = need.apply("read"); if (who == null) return;
        Scope scope = Scope.of(who);
        if (vm == null || !p.visible(vm, scope)) { c.error(404, "Không tìm thấy VM"); return; }
        if (metrics) {
          double ret = Json.num(cfg.path("retentionDays")) > 0 ? Json.num(cfg.path("retentionDays")) : 7;
          long[] w = Store.window(c.q("from"), c.q("to"), c.q("range"), ret);
          ObjectNode s = p.store.series(vm, w[0], w[1]); s.put("id", id);
          c.json(200, s); return;
        }
        c.json(200, p.strip(p.view(vm, true, false), scope)); return;
      }
      if ("GET".equals(m) && "alerts".equals(r0)) {
        Auth.Who who = need.apply("read"); if (who == null) return;
        Scope scope = Scope.of(who);
        String site = c.q("site"), sev = c.q("severity");
        java.util.function.Predicate<JsonNode> match = (a) -> p.alertVisible(a, scope) && (site.isEmpty() || List.of(site.split(",")).contains(Json.str(a.path("site")))) && (sev.isEmpty() || sev.equals(Json.str(a.path("severity"))));
        if ("history".equals(c.q("state"))) {
          int limit = (int) Math.min(Json.num(Json.F.textNode(c.q("limit"))) > 0 ? Json.num(Json.F.textNode(c.q("limit"))) : 100, 500);
          List<ObjectNode> l = new ArrayList<>(); synchronized (p.store) { for (int k = p.store.alertHistory.size() - 1; k >= 0; k--) { ObjectNode a = p.store.alertHistory.get(k); if (match.test(a)) l.add(a); } }
          ArrayNode data = Json.arr(); for (int k = 0; k < Math.min(limit, l.size()); k++) data.add(p.stripAlert(l.get(k), scope));
          c.json(200, Json.obj().put("total", l.size()).set("data", data)); return;
        }
        List<ObjectNode> list = p.allActive().stream().filter(match).toList();
        if (scope != null && scope.customers() != null) {
          ArrayNode data = Json.arr(); for (ObjectNode a : list) data.add(p.stripAlert(a, scope));
          c.json(200, Json.obj().put("total", list.size()).<ObjectNode>set("data", data).<ObjectNode>set("channels", Json.arr()).set("rules", Json.arr())); return;
        }
        ObjectNode o = Json.obj().put("total", list.size());
        o.set("data", Json.arr(list));
        o.set("channels", Json.strArr(Notify.enabledChannels(cfg.path("notify"))));
        JsonNode al = cfg.path("alerts");
        o.set("rules", al.path("enabled").isBoolean() && !al.path("enabled").booleanValue() ? Json.arr() : al.path("rules"));
        o.set("lastNotify", p.lastNotify);
        c.json(200, o); return;
      }
      if ("POST".equals(m) && "alerts".equals(r0) && r.size() > 1 && "test".equals(r.get(1))) {
        if (need.apply("admin") == null) return;
        if (System.currentTimeMillis() - lastTest < 10000) { c.error(429, "Vui lòng đợi 10 giây giữa các lần gửi thử"); return; }
        lastTest = System.currentTimeMillis();
        if (Notify.enabledChannels(cfg.path("notify")).isEmpty()) { c.error(400, "Chưa bật kênh nào trong config.json (notify.telegram / email / webhook)"); return; }
        ObjectNode msg = Json.obj().put("subject", "[VM Monitor] Thông báo thử")
            .put("text", "🔔 Đây là thông báo thử từ VM Monitor Portal.\nNếu bạn nhận được tin này, kênh đã được cấu hình đúng.\n\n" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss d/M/yyyy")));
        msg.putArray("events");
        c.json(200, Json.obj().set("results", Notify.notifyAll(cfg.path("notify"), msg))); return;
      }
      c.error(404, "Không có endpoint này. Xem /docs"); return;
    }

    // --- giao diện ---
    if ("GET".equals(m)) { serveFile(c, "/".equals(path) || "/index.html".equals(path) ? "index.html" : path.substring(1)); return; }
    c.error(405, "method not allowed");
  }
}
