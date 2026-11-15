package com.vmmonitor.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vmmonitor.config.Cfg;
import com.vmmonitor.customer.Customers;
import com.vmmonitor.infra.Influx;
import com.vmmonitor.security.Auth;
import com.vmmonitor.security.Secrets;
import com.vmmonitor.util.Json;
import com.vmmonitor.web.Ctx;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * API quản trị (trang Cài đặt) + API cho collector.
 *   GET /api/v1/admin/settings · PUT|DELETE /admin/vcenters/:id · PUT /admin/{general|influxdb|notify|alerts|customers|security}
 *   POST|DELETE /admin/users · /admin/apikeys · DELETE /admin/collectors/:name · POST /admin/users/:n/{reset2fa|impersonate}
 *   GET /api/v1/collector/config · POST /api/v1/collector/status   (API key collector hoặc chính máy portal)
 */
public class Admin {
  static final Map<String, List<String>> METRICS = Map.of(
      "vm", List.of("cpu", "mem", "disk", "rx", "tx", "rd", "wr", "offline"),
      "host", List.of("cpu", "mem", "rx", "tx", "disconnected", "alarm"),
      "datastore", List.of("disk", "free_gb", "provisioned", "inaccessible"));
  static final List<String> NO_THRESHOLD = List.of("offline", "disconnected", "alarm", "inaccessible");
  static final List<String> SEVERITIES = List.of("critical", "warning", "info");

  /** Collector đang báo về. */
  static final class Collector { String name, host, ip, version; long lastSeen; boolean local; Map<String, ObjectNode> statuses = new LinkedHashMap<>(); }

  private final Cfg cfg;
  private final Secrets secrets;
  private final Auth auth;
  private final Influx influx;
  private final BiConsumer<String, Object> broadcast;
  private final Supplier<ObjectNode> httpsStatus;
  final Map<String, Collector> collectors = new LinkedHashMap<>();

  public Admin(Cfg cfg, Secrets secrets, Auth auth, Influx influx, BiConsumer<String, Object> broadcast, Supplier<ObjectNode> httpsStatus) {
    this.cfg = cfg; this.secrets = secrets; this.auth = auth; this.influx = influx; this.broadcast = broadcast; this.httpsStatus = httpsStatus;
  }

  public static String vcId(JsonNode vc) {
    if (Json.truthy(vc.path("id"))) return Json.str(vc.path("id"));
    String h = Json.truthy(vc.path("host")) ? Json.str(vc.path("host")) : "x";
    return "vc-" + h.toLowerCase().replaceAll("[^a-z0-9]+", "-");
  }
  static List<JsonNode> vcenters(JsonNode raw) {
    List<JsonNode> l = new ArrayList<>();
    if (raw.path("vcenters").isArray()) raw.path("vcenters").forEach(l::add);
    else if (raw.path("vcenter").isObject()) l.add(raw.path("vcenter"));
    return l;
  }

  ObjectNode statusOf(String id, String assigned) {
    Function<Collector, ObjectNode> pick = (c) -> {
      if (c == null || !c.statuses.containsKey(id)) return null;
      ObjectNode o = c.statuses.get(id).deepCopy();
      o.put("collector", c.name).put("collectorAlive", System.currentTimeMillis() - c.lastSeen < 60000);
      return o;
    };
    if (assigned != null && !assigned.isEmpty()) return pick.apply(collectors.get(assigned));
    for (Collector c : collectors.values()) if (c.local && c.statuses.containsKey(id)) return pick.apply(c);
    for (Collector c : collectors.values()) if (c.statuses.containsKey(id)) return pick.apply(c);
    return null;
  }

  ArrayNode collectorList() {
    ObjectNode raw = cfg.readRaw();
    List<JsonNode> vcs = vcenters(raw);
    Set<String> names = new LinkedHashSet<>(collectors.keySet()), keyNames = new LinkedHashSet<>();
    for (JsonNode k : raw.path("apiKeys")) if ("collector".equals(Json.str(k.path("role")))) { names.add(Json.str(k.path("name"))); keyNames.add(Json.str(k.path("name"))); }
    List<ObjectNode> out = new ArrayList<>();
    for (String name : names) {
      Collector c = collectors.get(name);
      long own = vcs.stream().filter((v) -> name.equals(Json.str(v.path("collector")))).count();
      long free = vcs.stream().filter((v) -> !Json.truthy(v.path("collector"))).count();
      out.add(Json.obj().put("name", name).put("host", c == null || c.host == null ? "" : c.host).put("ip", c == null || c.ip == null ? "" : c.ip).put("lastSeen", c == null ? 0 : c.lastSeen)
          .put("local", c != null && c.local).put("hasKey", keyNames.contains(name)).put("alive", c != null && System.currentTimeMillis() - c.lastSeen < 60000)
          .put("vcenters", c != null && c.local ? free + own : own));
    }
    out.sort((a, b) -> { int x = Boolean.compare(b.get("local").asBoolean(), a.get("local").asBoolean()); return x != 0 ? x : a.get("name").asText().compareTo(b.get("name").asText()); });
    return Json.arr(out);
  }

  void broadcastCollectors() {
    ArrayNode vcs = Json.arr();
    for (JsonNode v : vcenters(cfg.readRaw())) { ObjectNode s = statusOf(vcId(v), Json.str(v.path("collector"))); vcs.addObject().put("id", vcId(v)).set("status", s == null ? Json.F.nullNode() : s); }
    broadcast.accept("collector", Json.obj().<ObjectNode>set("collectors", collectorList()).set("vcenters", vcs));
  }

  ObjectNode vcView(JsonNode vc) {
    String id = vcId(vc);
    JsonNode g = vc.path("guestOps");
    ObjectNode o = Json.obj().put("id", id).put("site", Json.truthy(vc.path("site")) ? Json.str(vc.path("site")) : "auto").put("host", Json.str(vc.path("host")))
        .put("port", Json.num(vc.path("port")) > 0 ? (int) Json.num(vc.path("port")) : 443).put("user", Json.str(vc.path("user"))).put("insecure", Json.notFalse(vc.path("insecure")))
        .put("interval", Json.num(vc.path("interval")) > 0 ? (int) Json.num(vc.path("interval")) : 20);
    o.set("include", vc.path("include").isArray() ? vc.path("include") : Json.arr().add("*"));
    o.set("exclude", vc.path("exclude").isArray() ? vc.path("exclude") : Json.arr().add("vCLS-*"));
    o.put("prefix", Json.str(vc.path("prefix"))).put("enabled", Json.notFalse(vc.path("enabled"))).put("sendPoweredOff", Json.notFalse(vc.path("sendPoweredOff")))
        .put("collector", Json.str(vc.path("collector"))).put("hasPassword", secrets.has("vc:" + id + ":password"));
    ObjectNode go = o.putObject("guestOps").put("enabled", Json.truthy(g.path("enabled"))).put("user", Json.truthy(g.path("user")) ? Json.str(g.path("user")) : "root");
    go.set("include", g.path("include").isArray() ? g.path("include") : Json.arr());
    go.set("exclude", g.path("exclude").isArray() ? g.path("exclude") : Json.arr());
    go.put("intervalSeconds", Json.num(g.path("intervalSeconds")) > 0 ? Json.num(g.path("intervalSeconds")) : (Json.num(g.path("intervalMinutes")) > 0 ? Json.num(g.path("intervalMinutes")) : 15) * 60)
        .put("hasPassword", secrets.has("vc:" + id + ":guest"));
    ObjectNode st = statusOf(id, Json.str(vc.path("collector")));
    o.set("status", st == null ? Json.F.nullNode() : st);
    return o;
  }

  ObjectNode settings() {
    ObjectNode raw = cfg.readRaw();
    JsonNode n = raw.path("notify"), i = raw.path("influxdb");
    ObjectNode s = Json.obj();
    ObjectNode g = s.putObject("general").put("publicUrl", Json.str(raw.path("publicUrl"))).put("grafanaUrl", Json.str(raw.path("grafanaUrl")))
        .put("retentionDays", Json.num(raw.path("retentionDays")) > 0 ? Json.num(raw.path("retentionDays")) : 7)
        .put("offlineAfterSeconds", Json.num(raw.path("offlineAfterSeconds")) > 0 ? Json.num(raw.path("offlineAfterSeconds")) : 60)
        .put("defaultSite", Json.str(raw.path("defaultSite"))).put("metricsPublic", Json.truthy(raw.path("metricsPublic")));
    g.set("cors", raw.path("cors").isArray() ? raw.path("cors") : Json.arr());
    ArrayNode vcs = s.putArray("vcenters"); for (JsonNode v : vcenters(raw)) vcs.add(vcView(v));
    s.set("collectors", collectorList());
    String schema = Json.str(i.path("schema"));
    ObjectNode inf = s.putObject("influxdb").put("enabled", Json.truthy(i.path("enabled"))).put("version", Json.num(i.path("version")) == 1 ? 1 : 2).put("url", Json.str(i.path("url")))
        .put("org", Json.str(i.path("org"))).put("bucket", Json.truthy(i.path("bucket")) ? Json.str(i.path("bucket")) : "vm-monitor").put("hasToken", Json.truthy(i.path("token")))
        .put("database", Json.truthy(i.path("database")) ? Json.str(i.path("database")) : "vm_monitor").put("username", Json.str(i.path("username"))).put("hasPassword", Json.truthy(i.path("password")))
        .put("measurement", Json.truthy(i.path("measurement")) ? Json.str(i.path("measurement")) : "vm").put("batchSeconds", Json.num(i.path("batchSeconds")) > 0 ? Json.num(i.path("batchSeconds")) : 10)
        .put("mounts", Json.notFalse(i.path("mounts"))).put("alerts", Json.notFalse(i.path("alerts"))).put("schema", "telegraf".equals(schema) || "both".equals(schema) ? schema : "portal");
    inf.set("status", influx.status());
    ObjectNode no = s.putObject("notify");
    no.putObject("telegram").put("enabled", Json.truthy(n.path("telegram").path("enabled"))).put("chatId", String.join(", ", Json.strings(n.path("telegram").path("chatId"))))
        .put("hasBotToken", Json.truthy(n.path("telegram").path("botToken")));
    JsonNode e = n.path("email");
    no.putObject("email").put("enabled", Json.truthy(e.path("enabled"))).put("host", Json.str(e.path("host"))).put("port", Json.num(e.path("port")) > 0 ? Json.num(e.path("port")) : 465)
        .put("secure", Json.notFalse(e.path("secure"))).put("user", Json.str(e.path("user"))).put("hasPass", Json.truthy(e.path("pass"))).put("from", Json.str(e.path("from")))
        .put("fromName", Json.truthy(e.path("fromName")) ? Json.str(e.path("fromName")) : "VM Monitor").put("to", String.join(", ", Json.strings(e.path("to"))));
    JsonNode w = n.path("webhook");
    no.putObject("webhook").put("enabled", Json.truthy(w.path("enabled"))).put("format", Json.truthy(w.path("format")) ? Json.str(w.path("format")) : "slack").put("hasUrl", Json.truthy(w.path("url")))
        .put("urlHint", Json.truthy(w.path("url")) ? Json.str(w.path("url")).replaceAll("^(https?://[^/]+).*$", "$1/…") : "");
    ObjectNode cu = s.putObject("customers");
    cu.set("sources", raw.path("customers").path("sources").isArray() ? raw.path("customers").path("sources") : Customers.defaultSources());
    cu.set("rules", raw.path("customers").path("rules").isArray() ? raw.path("customers").path("rules") : Json.arr());
    cu.put("manualCount", raw.path("customers").path("manual").size());
    JsonNode al = raw.path("alerts");
    ObjectNode ao = s.putObject("alerts").put("enabled", Json.notFalse(al.path("enabled"))).put("repeatMinutes", al.has("repeatMinutes") ? Json.num(al.path("repeatMinutes")) : 60)
        .put("sendResolved", Json.notFalse(al.path("sendResolved")));
    ao.set("rules", cfg.get().path("alerts").path("rules"));
    ArrayNode us = s.putArray("users");
    for (JsonNode u : raw.path("users")) {
      String role = Json.truthy(u.path("role")) ? Json.str(u.path("role")) : "admin";
      ObjectNode x = us.addObject().put("username", Json.str(u.path("username"))).put("role", role);
      if (List.of("customer", "manager").contains(role)) x.set("customers", Json.strArr(Json.strings(u.path("customers"))));
      if ("viewer".equals(role) && !Json.strings(u.path("sites")).isEmpty()) x.set("sites", Json.strArr(Json.strings(u.path("sites"))));
      x.put("totp", Json.truthy(u.path("totp")));
      if (Json.truthy(u.path("totpSince"))) x.put("totpSince", Json.str(u.path("totpSince")));
      x.put("recoveryLeft", u.path("recovery").size());
    }
    JsonNode h = raw.path("https");
    String r2 = Json.str(raw.path("security").path("require2fa"));
    ObjectNode sec = s.putObject("security").put("require2fa", "admin".equals(r2) || "all".equals(r2) ? r2 : "none");
    ObjectNode hs = sec.putObject("https").put("enabled", Json.truthy(h.path("enabled"))).put("port", Json.num(h.path("port")) > 0 ? (int) Json.num(h.path("port")) : 3443)
        .put("cert", Json.str(h.path("cert"))).put("key", Json.str(h.path("key"))).put("pfx", Json.str(h.path("pfx"))).put("hasPassphrase", secrets.has("https:passphrase"))
        .put("redirectHttp", Json.truthy(h.path("redirectHttp")));
    hs.set("status", httpsStatus.get());
    ArrayNode keys = s.putArray("apiKeys");
    for (JsonNode k : raw.path("apiKeys")) {
      String key = Json.str(k.path("key"));
      keys.addObject().put("name", Json.str(k.path("name"))).put("role", Json.str(k.path("role"))).put("created", Json.str(k.path("created"))).put("prefix", key.substring(0, Math.min(8, key.length())) + "…");
    }
    return s;
  }

  static String str(JsonNode v, int max) { return Json.trim(v, max); }
  static String str(JsonNode v) { return Json.trim(v, 200); }
  static List<String> list(JsonNode v) { return Json.list(v); }

  /** @return true nếu đã xử lý. */
  public boolean handle(Ctx c, Function<String, Auth.Who> need) {
    List<String> r = c.seg.subList(2, c.seg.size());
    if (r.isEmpty()) return false;
    String a1 = r.size() > 1 ? r.get(1) : null, a2 = r.size() > 2 ? r.get(2) : null, a3 = r.size() > 3 ? r.get(3) : null;

    // ----- collector -----
    if ("collector".equals(r.get(0))) {
      boolean allowLocal = Json.notFalse(cfg.get().path("collector").path("allowLocal"));
      Auth.Who who = auth.identify(c);
      boolean sentKey = !c.header("x-api-key").isEmpty() || c.header("authorization").matches("(?i)^Bearer\\s.*") || c.hasQ("api_key");
      if (sentKey && !(who != null && "apikey".equals(who.via))) { c.error(401, "API key không hợp lệ hoặc đã bị thu hồi"); return true; }
      if (who != null && !("apikey".equals(who.via) && List.of("collector", "admin").contains(who.role))) who = null;
      boolean local = who == null && allowLocal && c.loopback() && c.header("x-forwarded-for").isEmpty();
      if (who == null && !local) { who = need.apply("collector"); if (who == null) return true; }
      JsonNode b = "POST".equals(c.method) ? c.jsonBody(200000) : Json.obj();
      String nm = who != null && "apikey".equals(who.via) && "collector".equals(who.role) ? who.name
          : (!str(Json.F.textNode(c.q("name")), 100).isEmpty() ? str(Json.F.textNode(c.q("name")), 100) : !str(b.path("collector"), 100).isEmpty() ? str(b.path("collector"), 100) : "local");
      boolean isLocal = local;
      java.util.function.Predicate<JsonNode> owns = (vc) -> Json.truthy(vc.path("collector")) ? nm.equals(Json.str(vc.path("collector"))) : isLocal;
      if ("GET".equals(c.method) && "config".equals(a1)) {
        ArrayNode vcs = Json.arr();
        for (JsonNode vc : vcenters(cfg.readRaw())) {
          if (!owns.test(vc)) continue;
          String id = vcId(vc);
          ObjectNode o = Json.copy(vc); o.put("id", id).put("password", secrets.get("vc:" + id + ":password"));
          ObjectNode g = Json.copy(vc.path("guestOps"));
          if (Json.truthy(g.path("enabled"))) g.put("password", secrets.get("vc:" + id + ":guest")); else g.put("enabled", false);
          o.set("guestOps", g);
          vcs.add(o);
        }
        c.json(200, Json.obj().put("collector", nm).put("local", local).<ObjectNode>set("vcenters", vcs).put("time", System.currentTimeMillis()));
        return true;
      }
      if ("POST".equals(c.method) && "status".equals(a1)) {
        Set<String> allowed = new LinkedHashSet<>();
        for (JsonNode vc : vcenters(cfg.readRaw())) if (owns.test(vc)) allowed.add(vcId(vc));
        Collector col = new Collector();
        col.name = nm; col.host = str(Json.truthy(b.path("host")) ? b.path("host") : b.path("hostname"), 100); col.ip = c.ip; col.lastSeen = System.currentTimeMillis();
        col.local = local; col.version = str(b.path("version"), 40);
        for (JsonNode st : b.path("vcenters")) if (Json.truthy(st.path("id")) && allowed.contains(Json.str(st.path("id")))) col.statuses.put(Json.str(st.path("id")), Json.copy(st).put("receivedAt", System.currentTimeMillis()));
        synchronized (collectors) { collectors.put(nm, col); }
        broadcastCollectors();
        c.json(200, Json.obj().put("ok", true).put("collector", nm));
        return true;
      }
      c.error(404, "not found");
      return true;
    }

    synchronized (collectors) { collectors.values().removeIf((x) -> System.currentTimeMillis() - x.lastSeen > 86400000L); }
    if (!"admin".equals(r.get(0))) return false;
    Auth.Who who = need.apply("admin");
    if (who == null) return true;

    if ("GET".equals(c.method) && "settings".equals(a1)) { c.json(200, settings()); return true; }

    // ----- vCenter -----
    if ("vcenters".equals(a1) && a2 != null) {
      String id = a2;
      if ("DELETE".equals(c.method)) {
        boolean[] found = {false};
        cfg.mutate((raw) -> {
          ArrayNode keep = Json.arr();
          for (JsonNode v : vcenters(raw)) { if (vcId(v).equals(id)) found[0] = true; else keep.add(v); }
          raw.set("vcenters", keep); raw.remove("vcenter");
        });
        if (!found[0]) { c.error(404, "Không tìm thấy vCenter"); return true; }
        secrets.delPrefix("vc:" + id + ":");
        synchronized (collectors) { for (Collector x : collectors.values()) x.statuses.remove(id); }
        c.json(200, Json.obj().put("ok", true)); return true;
      }
      if ("PUT".equals(c.method)) {
        JsonNode b = c.jsonBody(200000);
        String host = str(b.path("host"), 255);
        if (host.isEmpty() || !host.matches("[a-zA-Z0-9.\\-:\\[\\]]+")) { c.error(400, "Host không hợp lệ"); return true; }
        if (str(b.path("user")).isEmpty()) { c.error(400, "Thiếu tài khoản vCenter"); return true; }
        String[] newId = {id}; boolean[] notFound = {false};
        cfg.mutate((raw) -> {
          List<JsonNode> vcs = vcenters(raw);
          raw.remove("vcenter");
          ObjectNode vc = null;
          if (!"new".equals(id)) for (JsonNode v : vcs) if (vcId(v).equals(id)) vc = (ObjectNode) v;
          if (!"new".equals(id) && vc == null) { notFound[0] = true; return; }
          if (vc == null) {
            for (JsonNode v : vcs) if (Json.str(v.path("host")).equalsIgnoreCase(host)) { newId[0] = null; return; }
            byte[] rb = new byte[4]; new SecureRandom().nextBytes(rb);
            vc = Json.obj().put("id", "vc-" + HexFormat.of().formatHex(rb));
            vcs.add(vc);
          }
          vc.put("id", vcId(vc)); newId[0] = vc.get("id").asText();
          vc.put("site", str(b.path("site"), 64).isEmpty() ? "auto" : str(b.path("site"), 64)).put("host", host)
              .put("port", (int) Math.min(65535, Math.max(1, Json.num(b.path("port")) > 0 ? Json.num(b.path("port")) : 443))).put("user", str(b.path("user")))
              .put("insecure", Json.notFalse(b.path("insecure"))).put("interval", (int) Math.min(3600, Math.max(20, Json.num(b.path("interval")) > 0 ? Json.num(b.path("interval")) : 20)));
          vc.set("include", list(b.path("include")).isEmpty() ? Json.arr().add("*") : Json.strArr(list(b.path("include"))));
          vc.set("exclude", Json.strArr(list(b.path("exclude"))));
          vc.put("prefix", str(b.path("prefix"), 32)).put("enabled", Json.notFalse(b.path("enabled"))).put("sendPoweredOff", Json.notFalse(b.path("sendPoweredOff")));
          String col = str(b.path("collector"), 64);
          if (!col.isEmpty()) vc.put("collector", col); else vc.remove("collector");
          JsonNode g = b.path("guestOps");
          ObjectNode go = vc.putObject("guestOps").put("enabled", Json.truthy(g.path("enabled"))).put("user", str(g.path("user"), 64).isEmpty() ? "root" : str(g.path("user"), 64));
          go.set("include", Json.strArr(list(g.path("include")))); go.set("exclude", Json.strArr(list(g.path("exclude"))));
          go.put("intervalSeconds", (int) Math.min(86400, Math.max(30, Json.num(g.path("intervalSeconds")) > 0 ? Json.num(g.path("intervalSeconds")) : 900))).put("concurrency", 8);
          ArrayNode arr = Json.arr(); vcs.forEach(arr::add); raw.set("vcenters", arr);
        });
        if (notFound[0]) { c.error(404, "Không tìm thấy vCenter"); return true; }
        if (newId[0] == null) { c.error(409, "vCenter này đã có trong danh sách"); return true; }
        if (b.path("password").isTextual() && !b.path("password").asText().isEmpty()) secrets.set("vc:" + newId[0] + ":password", b.path("password").asText());
        if (b.path("guestOps").path("password").isTextual() && !b.path("guestOps").path("password").asText().isEmpty()) secrets.set("vc:" + newId[0] + ":guest", b.path("guestOps").path("password").asText());
        if (Json.truthy(b.path("clearGuestPassword"))) secrets.del("vc:" + newId[0] + ":guest");
        JsonNode saved = null;
        for (JsonNode v : vcenters(cfg.readRaw())) if (vcId(v).equals(newId[0])) saved = v;
        c.json(200, Json.obj().put("ok", true).set("vcenter", saved == null ? Json.F.nullNode() : vcView(saved)));
        return true;
      }
    }

    // ----- collector: xoá -----
    if ("DELETE".equals(c.method) && "collectors".equals(a1) && a2 != null) {
      long used = vcenters(cfg.readRaw()).stream().filter((v) -> a2.equals(Json.str(v.path("collector")))).count();
      if (used > 0) { c.error(400, "Còn " + used + " vCenter đang gán cho collector này — chuyển sang collector khác trước"); return true; }
      synchronized (collectors) { collectors.remove(a2); }
      cfg.mutate((raw) -> { ArrayNode keep = Json.arr(); raw.path("apiKeys").forEach((k) -> { if (!("collector".equals(Json.str(k.path("role"))) && a2.equals(Json.str(k.path("name"))))) keep.add(k); }); raw.set("apiKeys", keep); });
      c.json(200, Json.obj().put("ok", true)); return true;
    }

    // ----- khách hàng -----
    if ("PUT".equals(c.method) && "customers".equals(a1) && "assign".equals(a2)) {
      JsonNode b = c.jsonBody(200000);
      List<String> ids = new ArrayList<>();
      for (String x : Json.strings(b.has("ids") ? b.path("ids") : b.path("id"))) { x = x.trim(); if (x.length() > 300) x = x.substring(0, 300); if (!x.isEmpty()) ids.add(x); }
      if (ids.isEmpty()) { c.error(400, "Thiếu VM"); return true; }
      String customer = str(b.path("customer"), 100);
      cfg.mutate((raw) -> {
        ObjectNode cu = raw.path("customers").isObject() ? (ObjectNode) raw.get("customers") : raw.putObject("customers");
        ObjectNode man = cu.path("manual").isObject() ? (ObjectNode) cu.get("manual") : cu.putObject("manual");
        for (String id : ids) { if (!customer.isEmpty()) man.put(id, customer); else man.remove(id); }
      });
      c.json(200, Json.obj().put("ok", true).put("count", ids.size())); return true;
    }
    if ("PUT".equals(c.method) && "customers".equals(a1)) {
      JsonNode b = c.jsonBody(200000);
      List<String> types = List.of("manual", "rules", "tag", "attribute", "folder", "name", "notes");
      ArrayNode sources = Json.arr();
      for (JsonNode x : b.path("sources")) {
        String t = Json.str(x.path("type"));
        if (!types.contains(t)) continue;
        ObjectNode src = sources.addObject().put("type", t).put("enabled", Json.notFalse(x.path("enabled")));
        if ("tag".equals(t)) src.set("category", Json.strArr(list(x.path("category")).isEmpty() ? List.of("Customer") : list(x.path("category"))));
        if ("attribute".equals(t)) src.set("name", Json.strArr(list(x.path("name")).isEmpty() ? List.of("Customer") : list(x.path("name"))));
        if ("folder".equals(t)) { src.put("level", (int) Math.min(10, Math.max(1, Json.num(x.path("level")) > 0 ? Json.num(x.path("level")) : 1))); if (!str(x.path("prefix")).isEmpty()) src.put("prefix", str(x.path("prefix"))); }
        if ("name".equals(t) || "notes".equals(t)) {
          try { Pattern.compile(Json.str(x.path("regex"))); } catch (Exception ex) { c.error(400, "Regex không hợp lệ (" + t + "): " + Json.str(x.path("regex"))); return true; }
          String re = Json.str(x.path("regex")); src.put("regex", re.length() > 300 ? re.substring(0, 300) : re);
        }
      }
      ArrayNode rules = Json.arr();
      for (JsonNode x : b.path("rules")) {
        String cust = str(x.path("customer"), 100); List<String> vms = list(x.path("vms"));
        if (cust.isEmpty() || vms.isEmpty()) continue;
        ObjectNode o = rules.addObject().put("customer", cust); o.set("vms", Json.strArr(vms));
        if (!list(x.path("sites")).isEmpty()) o.set("sites", Json.strArr(list(x.path("sites"))));
      }
      cfg.mutate((raw) -> { ObjectNode cu = raw.path("customers").isObject() ? (ObjectNode) raw.get("customers") : raw.putObject("customers"); cu.set("sources", sources); cu.set("rules", rules); });
      c.json(200, Json.obj().put("ok", true)); return true;
    }

    // ----- chung -----
    if ("PUT".equals(c.method) && "general".equals(a1)) {
      JsonNode b = c.jsonBody(200000);
      cfg.mutate((raw) -> {
        raw.put("publicUrl", str(b.path("publicUrl"), 300)).put("grafanaUrl", str(b.path("grafanaUrl"), 300).replaceAll("/+$", ""))
            .put("retentionDays", (int) Math.min(366, Math.max(1, Json.num(b.path("retentionDays")) > 0 ? Json.num(b.path("retentionDays")) : 7)))
            .put("offlineAfterSeconds", (int) Math.min(3600, Math.max(20, Json.num(b.path("offlineAfterSeconds")) > 0 ? Json.num(b.path("offlineAfterSeconds")) : 60)))
            .put("defaultSite", str(b.path("defaultSite"), 64)).put("metricsPublic", Json.truthy(b.path("metricsPublic")));
        raw.set("cors", Json.strArr(list(b.path("cors"))));
      });
      c.json(200, Json.obj().put("ok", true).put("note", "retentionDays có hiệu lực sau khi khởi động lại portal")); return true;
    }

    // ----- InfluxDB -----
    if ("influxdb".equals(a1)) {
      JsonNode b = c.jsonBody(200000);
      Function<JsonNode, ObjectNode> merged = (old) -> {
        ObjectNode o = Json.copy(old);
        String sc = Json.str(b.path("schema"));
        o.put("enabled", Json.truthy(b.path("enabled"))).put("version", Json.num(b.path("version")) == 1 ? 1 : 2).put("url", str(b.path("url"), 300)).put("org", str(b.path("org")))
            .put("bucket", str(b.path("bucket")).isEmpty() ? "vm-monitor" : str(b.path("bucket"))).put("token", Json.truthy(b.path("token")) ? Json.str(b.path("token")) : Json.str(old.path("token")))
            .put("database", str(b.path("database")).isEmpty() ? "vm_monitor" : str(b.path("database"))).put("username", str(b.path("username")))
            .put("password", Json.truthy(b.path("password")) ? Json.str(b.path("password")) : Json.str(old.path("password")))
            .put("measurement", str(b.path("measurement"), 64).isEmpty() ? "vm" : str(b.path("measurement"), 64))
            .put("batchSeconds", (int) Math.min(300, Math.max(2, Json.num(b.path("batchSeconds")) > 0 ? Json.num(b.path("batchSeconds")) : 10)))
            .put("mounts", Json.notFalse(b.path("mounts"))).put("alerts", Json.notFalse(b.path("alerts"))).put("schema", "telegraf".equals(sc) || "both".equals(sc) ? sc : "portal");
        return o;
      };
      if ("POST".equals(c.method) && "test".equals(a2)) { c.json(200, influx.testWrite(merged.apply(cfg.readRaw().path("influxdb")))); return true; }
      if ("PUT".equals(c.method)) { cfg.mutate((raw) -> raw.set("influxdb", merged.apply(raw.path("influxdb")))); c.json(200, Json.obj().put("ok", true)); return true; }
    }

    // ----- kênh gửi -----
    if ("PUT".equals(c.method) && "notify".equals(a1)) {
      JsonNode b = c.jsonBody(200000);
      cfg.mutate((raw) -> {
        ObjectNode n = raw.path("notify").isObject() ? (ObjectNode) raw.get("notify") : raw.putObject("notify");
        JsonNode t = b.path("telegram"), e = b.path("email"), w = b.path("webhook");
        ObjectNode tg = Json.copy(n.path("telegram")).put("enabled", Json.truthy(t.path("enabled")));
        List<String> ids = list(t.path("chatId"));
        if (ids.size() > 1) tg.set("chatId", Json.strArr(ids)); else tg.put("chatId", ids.isEmpty() ? "" : ids.get(0));
        tg.put("botToken", Json.truthy(t.path("botToken")) ? Json.str(t.path("botToken")) : Json.str(n.path("telegram").path("botToken")));
        n.set("telegram", tg);
        ObjectNode em = Json.copy(n.path("email")).put("enabled", Json.truthy(e.path("enabled"))).put("host", str(e.path("host"))).put("port", Json.num(e.path("port")) > 0 ? (int) Json.num(e.path("port")) : 465)
            .put("secure", Json.notFalse(e.path("secure"))).put("user", str(e.path("user"))).put("pass", Json.truthy(e.path("pass")) ? Json.str(e.path("pass")) : Json.str(n.path("email").path("pass")))
            .put("from", str(e.path("from"))).put("fromName", str(e.path("fromName")).isEmpty() ? "VM Monitor" : str(e.path("fromName")));
        em.set("to", Json.strArr(list(e.path("to"))));
        n.set("email", em);
        String fmt = Json.str(w.path("format"));
        n.set("webhook", Json.copy(n.path("webhook")).put("enabled", Json.truthy(w.path("enabled"))).put("format", List.of("slack", "discord", "teams", "json").contains(fmt) ? fmt : "json")
            .put("url", Json.truthy(w.path("url")) ? Json.str(w.path("url")) : Json.str(n.path("webhook").path("url"))));
      });
      c.json(200, Json.obj().put("ok", true)); return true;
    }

    // ----- rule cảnh báo -----
    if ("PUT".equals(c.method) && "alerts".equals(a1)) {
      JsonNode b = c.jsonBody(200000);
      ArrayNode rules = Json.arr();
      for (JsonNode x : b.path("rules")) {
        String target = List.of("host", "datastore").contains(Json.str(x.path("target"))) ? Json.str(x.path("target")) : "vm";
        String metric = Json.str(x.path("metric"));
        if (!METRICS.get(target).contains(metric)) { c.error(400, "Chỉ số \"" + metric + "\" không dùng được cho " + target); return true; }
        ObjectNode rule = rules.addObject().put("metric", metric).put("severity", SEVERITIES.contains(Json.str(x.path("severity"))) ? Json.str(x.path("severity")) : "warning")
            .put("for", Math.max(0, Json.num(x.path("for"))));
        if (!"vm".equals(target)) rule.put("target", target);
        if (!NO_THRESHOLD.contains(metric)) {
          rule.put("threshold", Json.num(x.path("threshold")));
          if ("<".equals(Json.str(x.path("op"))) || ("free_gb".equals(metric) && !">".equals(Json.str(x.path("op"))))) rule.put("op", "<");
        }
        if (!list(x.path("sites")).isEmpty()) rule.set("sites", Json.strArr(list(x.path("sites"))));
        if (!list(x.path("vms")).isEmpty()) rule.set("vms", Json.strArr(list(x.path("vms"))));
        if (!list(x.path("excludeVms")).isEmpty()) rule.set("excludeVms", Json.strArr(list(x.path("excludeVms"))));
      }
      cfg.mutate((raw) -> {
        ObjectNode a = Json.copy(raw.path("alerts")).put("enabled", Json.notFalse(b.path("enabled"))).put("repeatMinutes", Math.max(0, Json.num(b.path("repeatMinutes"))))
            .put("sendResolved", Json.notFalse(b.path("sendResolved")));
        a.set("rules", rules);
        raw.set("alerts", a);
      });
      c.json(200, Json.obj().put("ok", true)); return true;
    }

    // ----- người dùng -----
    if ("users".equals(a1)) {
      if ("POST".equals(c.method) && a2 == null) {
        JsonNode b = c.jsonBody(200000);
        String username = str(b.path("username"), 64);
        if (!username.matches("[\\w.@-]{2,64}")) { c.error(400, "Tên đăng nhập 2–64 ký tự: chữ, số, . _ @ -"); return true; }
        if (Json.str(b.path("password")).length() < 8) { c.error(400, "Mật khẩu cần ít nhất 8 ký tự"); return true; }
        String role = Auth.USER_ROLES.contains(Json.str(b.path("role"))) ? Json.str(b.path("role")) : "admin";
        List<String> custs = new ArrayList<>(); for (String x : list(b.path("customers"))) { x = x.length() > 100 ? x.substring(0, 100) : x; if (!x.isEmpty()) custs.add(x); }
        if (List.of("customer", "manager").contains(role) && custs.isEmpty()) { c.error(400, "customer".equals(role) ? "Tài khoản khách hàng cần ít nhất một khách hàng" : "Nhân viên phụ trách cần ít nhất một khách hàng"); return true; }
        List<String> sites = new ArrayList<>(); if ("viewer".equals(role)) for (String x : list(b.path("sites"))) if (!x.isEmpty()) sites.add(x.length() > 64 ? x.substring(0, 64) : x);
        boolean first = cfg.readRaw().path("users").size() == 0;
        String hash = Auth.hashPassword(Json.str(b.path("password")));
        cfg.mutate((raw) -> {
          JsonNode old = null; ArrayNode keep = Json.arr();
          for (JsonNode u : raw.path("users")) { if (username.equals(Json.str(u.path("username")))) old = u; else keep.add(u); }
          ObjectNode nu = keep.addObject().put("username", username).put("role", role);
          if (List.of("customer", "manager").contains(role)) nu.set("customers", Json.strArr(custs));
          if (!sites.isEmpty()) nu.set("sites", Json.strArr(sites));
          nu.put("passwordHash", hash);
          if (old != null && Json.truthy(old.path("totp"))) { nu.put("totp", true); nu.set("recovery", old.path("recovery").isArray() ? old.path("recovery") : Json.arr()); if (old.has("totpSince")) nu.set("totpSince", old.get("totpSince")); }
          raw.set("users", keep);
        });
        c.json(200, Json.obj().put("ok", true).put("firstUser", first)); return true;
      }
      if ("POST".equals(c.method) && a2 != null && "impersonate".equals(a3)) {
        Auth.Who me = auth.identify(c);
        if (me == null || !"session".equals(me.via) || me.imp != null) { c.error(400, "Chỉ dùng khi đăng nhập bằng tài khoản admin"); return true; }
        JsonNode target = auth.user(a2);
        if (target == null) { c.error(404, "Không có người dùng này"); return true; }
        if ("admin".equals(Json.str(target.path("role")))) { c.error(400, "Không \"xem như\" tài khoản admin khác"); return true; }
        String mine = c.cookie("vmsess");
        c.json(200, Json.obj().put("ok", true).put("user", a2).put("role", Json.str(target.path("role"))).put("go", "customer".equals(Json.str(target.path("role"))) ? "my" : "./"),
            Map.of("set-cookie", List.of(auth.adminCookieHeader(mine, c), auth.cookieHeader(auth.session(target, false, me.name), c))));
        return true;
      }
      if ("POST".equals(c.method) && a2 != null && "reset2fa".equals(a3)) {
        if (auth.user(a2) == null) { c.error(404, "Không có người dùng này"); return true; }
        secrets.del("totp:" + a2);
        cfg.mutate((raw) -> { for (JsonNode u : raw.path("users")) if (a2.equals(Json.str(u.path("username")))) { ((ObjectNode) u).remove("totp"); ((ObjectNode) u).remove("recovery"); ((ObjectNode) u).remove("totpSince"); } });
        c.json(200, Json.obj().put("ok", true)); return true;
      }
      if ("DELETE".equals(c.method) && a2 != null) {
        List<JsonNode> users = new ArrayList<>(); cfg.readRaw().path("users").forEach(users::add);
        JsonNode target = users.stream().filter((u) -> a2.equals(Json.str(u.path("username")))).findFirst().orElse(null);
        if (target == null) { c.error(404, "Không có người dùng này"); return true; }
        long admins = users.stream().filter((u) -> !"viewer".equals(Json.str(u.path("role")))).count();
        if (!"viewer".equals(Json.str(target.path("role"))) && admins == 1 && users.size() > 1) { c.error(400, "Không xoá được admin cuối cùng khi vẫn còn tài khoản khác"); return true; }
        cfg.mutate((raw) -> { ArrayNode keep = Json.arr(); raw.path("users").forEach((u) -> { if (!a2.equals(Json.str(u.path("username")))) keep.add(u); }); raw.set("users", keep); });
        secrets.del("totp:" + a2);
        c.json(200, Json.obj().put("ok", true)); return true;
      }
    }

    // ----- bảo mật -----
    if ("PUT".equals(c.method) && "security".equals(a1)) {
      JsonNode b = c.jsonBody(200000);
      JsonNode h = b.path("https");
      if (Json.truthy(h.path("enabled"))) {
        Path base = cfg.file.toAbsolutePath().getParent();
        List<String> need2 = Json.truthy(h.path("pfx")) ? List.of(Json.str(h.path("pfx"))) : List.of(Json.str(h.path("cert")), Json.str(h.path("key")));
        if (need2.stream().anyMatch(String::isEmpty)) { c.error(400, "Bật HTTPS cần file chứng chỉ: .pfx, hoặc cặp cert + key (.pem)"); return true; }
        List<String> missing = need2.stream().filter((f) -> !Files.exists(base.resolve(f))).toList();
        if (!missing.isEmpty()) { c.error(400, "Không thấy file: " + String.join(", ", missing)); return true; }
      }
      if (h.path("passphrase").isTextual() && !h.path("passphrase").asText().isEmpty()) secrets.set("https:passphrase", h.path("passphrase").asText());
      if (Json.truthy(h.path("clearPassphrase"))) secrets.del("https:passphrase");
      String r2 = Json.str(b.path("require2fa"));
      cfg.mutate((raw) -> {
        ObjectNode sec = Json.copy(raw.path("security")).put("require2fa", "admin".equals(r2) || "all".equals(r2) ? r2 : "none");
        raw.set("security", sec);
        raw.putObject("https").put("enabled", Json.truthy(h.path("enabled"))).put("port", (int) Math.min(65535, Math.max(1, Json.num(h.path("port")) > 0 ? Json.num(h.path("port")) : 3443)))
            .put("cert", str(h.path("cert"), 300)).put("key", str(h.path("key"), 300)).put("pfx", str(h.path("pfx"), 300)).put("redirectHttp", Json.truthy(h.path("redirectHttp")));
      });
      c.json(200, Json.obj().put("ok", true).put("note", "Thay đổi HTTPS có hiệu lực sau khi khởi động lại portal")); return true;
    }

    // ----- API key -----
    if ("apikeys".equals(a1)) {
      if ("POST".equals(c.method)) {
        JsonNode b = c.jsonBody(200000);
        String name = str(b.path("name"), 64);
        if (name.isEmpty()) { c.error(400, "Thiếu tên"); return true; }
        String role = Auth.KEY_ROLES.contains(Json.str(b.path("role"))) ? Json.str(b.path("role")) : "read";
        String key = Auth.newApiKey();
        cfg.mutate((raw) -> {
          ArrayNode keep = Json.arr(); raw.path("apiKeys").forEach((k) -> { if (!name.equals(Json.str(k.path("name")))) keep.add(k); });
          keep.addObject().put("name", name).put("role", role).put("key", key).put("created", java.time.Instant.now().toString());
          raw.set("apiKeys", keep);
        });
        c.json(200, Json.obj().put("ok", true).put("name", name).put("role", role).put("key", key)); return true;
      }
      if ("DELETE".equals(c.method) && a2 != null) {
        cfg.mutate((raw) -> { ArrayNode keep = Json.arr(); raw.path("apiKeys").forEach((k) -> { if (!a2.equals(Json.str(k.path("name")))) keep.add(k); }); raw.set("apiKeys", keep); });
        c.json(200, Json.obj().put("ok", true)); return true;
      }
    }

    c.error(404, "Không có endpoint quản trị này");
    return true;
  }
}
