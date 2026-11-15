package com.vmmonitor.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vmmonitor.alert.Alerts;
import com.vmmonitor.alert.Notify;
import com.vmmonitor.config.Cfg;
import com.vmmonitor.customer.Customers;
import com.vmmonitor.infra.Infra;
import com.vmmonitor.security.Auth;
import com.vmmonitor.store.Store;
import com.vmmonitor.store.Usage;
import com.vmmonitor.util.Json;
import com.vmmonitor.util.Log;
import com.vmmonitor.web.Ctx;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vận hành: dọn VM biến mất, báo cáo tháng theo khách (gửi email định kỳ), gợi ý gán khách, sao lưu.
 *   GET /api/v1/usage[/months]  · GET|PUT /api/v1/admin/ops · POST /admin/ops/purge · POST /admin/backup · POST /admin/reports/send · GET /admin/customers/suggest
 */
public class Ops {
  public record Engines(Alerts vm, Alerts host, Alerts datastore) {}

  private final Cfg cfg;
  private final Store store;
  private final Infra infra;
  private final Engines engines;
  private final Usage usage;
  public final Backup backup;
  private final Customers customers;
  private final BiConsumer<String, Object> broadcast;
  private final Runnable onPurged;
  private final Path stateFile;
  private ObjectNode reportState;
  private ObjectNode lastPurge;

  static final Set<String> GENERIC = Set.of("shadow", "test", "tmk", "cp", "db", "web", "vps", "server", "srv", "vm", "new", "backup", "proxy", "pfsense", "win", "windows", "ubuntu", "centos",
      "linux", "app", "api", "dev", "prod", "staging", "uat", "demo", "temp", "tmp", "clone", "template", "ec", "team", "node", "master", "worker", "mail", "dns", "ftp",
      "database", "redis", "mysql", "mongo", "nginx", "docker", "k8s", "rdp", "gw", "gateway", "monitor", "zabbix", "veeam", "kms", "vcenter", "esxi", "nas", "storage");
  static final Pattern ACCOUNT_RE = Pattern.compile("(?:^|[^0-9])(1\\d{7})(?![0-9])");

  public Ops(Cfg cfg, Store store, Infra infra, Engines engines, Usage usage, Backup backup, Customers customers, BiConsumer<String, Object> broadcast, Path dataDir, Runnable onPurged) {
    this.cfg = cfg; this.store = store; this.infra = infra; this.engines = engines; this.usage = usage; this.backup = backup; this.customers = customers;
    this.broadcast = broadcast; this.onPurged = onPurged;
    this.stateFile = dataDir.resolve("report-state.json");
    try { reportState = (ObjectNode) Json.M.readTree(Files.readString(stateFile)); } catch (Exception e) { reportState = Json.obj(); }
  }

  ObjectNode c() { return cfg.get(); }
  int purgeDays() { JsonNode v = c().path("purgeAfterDays"); if (!Json.missing(v) && Json.num(v) == 0 && (v.isNumber() || "0".equals(v.asText()))) return 0; double d = Json.num(v); return d > 0 ? (int) d : 7; }
  ObjectNode reportsCfg() {
    JsonNode r = c().path("reports");
    ObjectNode o = Json.obj().put("enabled", Json.truthy(r.path("enabled"))).put("day", (int) Math.min(28, Math.max(1, Json.num(r.path("day")) > 0 ? Json.num(r.path("day")) : 1)))
        .put("hour", (int) Math.min(23, Math.max(0, r.has("hour") ? Json.num(r.path("hour")) : 8))).put("vmDetail", Json.notFalse(r.path("vmDetail")));
    o.set("to", Json.strArr(Json.strings(r.path("to"))));
    return o;
  }
  static String prevMonth(LocalDate d) { return Usage.monthKey(d.withDayOfMonth(1).minusMonths(1)); }

  // ---------- dọn đối tượng không gửi số liệu ----------
  public synchronized ObjectNode purge(String reason) {
    int days = purgeDays();
    if (days == 0) return Json.obj().put("removed", 0).put("disabled", true);
    long cutoff = System.currentTimeMillis() - days * 86400000L;
    List<String> removed = new ArrayList<>();
    synchronized (store) {
      for (var it = store.vms.entrySet().iterator(); it.hasNext(); ) {
        var e = it.next();
        if (e.getValue().lastSeen >= cutoff) continue;
        it.remove(); store.dirty = true; engines.vm().forget(e.getKey());
        removed.add("VM " + e.getKey()); broadcast.accept("removed", Json.obj().put("id", e.getKey()));
      }
    }
    Object[][] others = {{"Host", infra.hosts, engines.host()}, {"Datastore", infra.ds, engines.datastore()}};
    for (Object[] o : others) {
      Store st = (Store) o[1]; Alerts eng = (Alerts) o[2];
      synchronized (st) {
        for (var it = st.vms.entrySet().iterator(); it.hasNext(); ) {
          var e = it.next();
          if (e.getValue().lastSeen >= cutoff) continue;
          it.remove(); st.dirty = true; eng.forget(e.getKey()); removed.add(o[0] + " " + e.getKey());
        }
      }
    }
    if (!removed.isEmpty()) {
      Log.info("Dọn " + removed.size() + " đối tượng không gửi số liệu > " + days + " ngày (" + reason + "): " + String.join(", ", removed.subList(0, Math.min(20, removed.size()))) + (removed.size() > 20 ? "…" : ""));
      if (onPurged != null) onPurged.run();
    }
    lastPurge = Json.obj().put("at", System.currentTimeMillis()).put("removed", removed.size()).put("reason", reason);
    lastPurge.set("items", Json.strArr(removed.subList(0, Math.min(50, removed.size()))));
    return lastPurge;
  }

  ArrayNode stale() {
    long now = System.currentTimeMillis();
    List<ObjectNode> l = new ArrayList<>();
    synchronized (store) {
      for (var e : store.vms.entrySet()) {
        double age = (now - e.getValue().lastSeen) / 86400000.0;
        if (age >= 1) l.add(Json.obj().put("id", e.getKey()).put("lastSeen", e.getValue().lastSeen).put("days", Math.round(age * 10) / 10.0));
      }
    }
    l.sort((a, b) -> Double.compare(b.get("days").asDouble(), a.get("days").asDouble()));
    return Json.arr(l.subList(0, Math.min(100, l.size())));
  }

  // ---------- báo cáo tháng ----------
  String customerOf(ObjectNode row) {
    Store.Rec vm = store.vms.get(row.path("id").asText());
    return customers.of(vm != null ? vm.info : row);
  }
  public ObjectNode report(String month, Usage.Filter f) { return usage.report(month, this::customerOf, f); }

  public ObjectNode sendReport(String month, List<String> toOverride, String reason) throws Exception {
    JsonNode email = c().path("notify").path("email");
    if (!Json.truthy(email.path("host"))) throw new Exception("Chưa cấu hình SMTP (Cài đặt → Kênh gửi → Email)");
    ObjectNode rc = reportsCfg();
    List<String> to = toOverride != null && !toOverride.isEmpty() ? toOverride : rc.path("to").size() > 0 ? Json.strings(rc.path("to")) : Json.list(email.path("to"));
    ObjectNode rep = report(month, new Usage.Filter("", "", null));
    if (rep.path("vms").size() == 0) throw new Exception("Tháng " + month + " chưa có số liệu");
    String portal = Json.str(c().path("publicUrl")).replaceAll("/+$", "");
    List<Notify.Attachment> att = new ArrayList<>();
    att.add(new Notify.Attachment("bao-cao-khach-hang-" + month + ".csv", "text/csv; charset=UTF-8", Usage.customersCsv(rep).getBytes(StandardCharsets.UTF_8)));
    if (rc.path("vmDetail").asBoolean()) att.add(new Notify.Attachment("bao-cao-vm-" + month + ".csv", "text/csv; charset=UTF-8", Usage.vmsCsv(rep).getBytes(StandardCharsets.UTF_8)));
    String text = "Báo cáo mức dùng tháng " + month + ": " + rep.path("customers").size() + " khách hàng, " + rep.path("vms").size() + " VM, " + rep.path("days").asInt() + " ngày có số liệu.\nChi tiết trong file CSV đính kèm."
        + (portal.isEmpty() ? "" : "\n" + portal + "/report?month=" + month);
    Notify.sendEmail(email, "[VM Monitor] Báo cáo mức dùng tháng " + month, text, Usage.customersHtml(rep, portal), att, to);
    ObjectNode result = Json.obj().put("ok", true).put("month", month).put("at", System.currentTimeMillis()).put("customers", rep.path("customers").size()).put("vms", rep.path("vms").size()).put("reason", reason);
    result.set("to", Json.strArr(to));
    synchronized (this) { if ("tự động".equals(reason)) reportState.put("lastSent", month); reportState.set("last", result); saveState(); }
    Log.info("Đã gửi báo cáo tháng " + month + " tới " + String.join(", ", to) + " (" + reason + ")");
    return result;
  }

  void saveState() { try { Files.writeString(stateFile, Json.pretty(reportState), StandardCharsets.UTF_8); } catch (Exception ignored) {} }

  /** Gọi mỗi 10 phút: tới ngày / giờ đã đặt thì gửi báo cáo tháng trước (mỗi tháng một lần). */
  public void tickReports() {
    ObjectNode rc = reportsCfg();
    if (!rc.path("enabled").asBoolean()) return;
    LocalDateTime now = LocalDateTime.now();
    String month = prevMonth(now.toLocalDate());
    int day = rc.path("day").asInt(), hour = rc.path("hour").asInt();
    if (month.equals(Json.str(reportState.path("lastSent"))) || now.getDayOfMonth() < day || (now.getDayOfMonth() == day && now.getHour() < hour)) return;
    try { sendReport(month, null, "tự động"); }
    catch (Exception e) {
      synchronized (this) { reportState.set("last", Json.obj().put("ok", false).put("month", month).put("at", System.currentTimeMillis()).put("error", String.valueOf(e.getMessage())).put("reason", "tự động")); saveState(); }
      Log.error("Gửi báo cáo tháng " + month + " lỗi: " + e.getMessage());
    }
  }

  // ---------- gợi ý khách hàng ----------
  static String norm(String s) {
    String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replace('đ', 'd').replace('Đ', 'd');
    return n.toLowerCase().replaceAll("[^a-z0-9]", "");
  }
  record U(String id, String site, String name, String acc, String prefix, String k) {}

  ObjectNode suggest() {
    Map<String, String> known = new LinkedHashMap<>();
    Map<String, Map<String, Integer>> byAccount = new LinkedHashMap<>();
    List<U> unassigned = new ArrayList<>();
    synchronized (store) {
      for (var e : store.vms.entrySet()) {
        JsonNode i = e.getValue().info;
        String name = Json.str(i.path("name")), c = customers.of(i);
        Matcher m = ACCOUNT_RE.matcher(name); String acc = m.find() ? m.group(1) : null;
        if (!c.isEmpty()) {
          if (!GENERIC.contains(norm(c))) known.put(norm(c), c);
          if (acc != null) byAccount.computeIfAbsent(acc, (x) -> new LinkedHashMap<>()).merge(c, 1, Integer::sum);
        } else {
          String prefix = name.split("[-_ .]")[0];
          unassigned.add(new U(e.getKey(), Json.str(i.path("site")), name, acc, prefix, norm(prefix)));
        }
      }
    }
    Map<String, ObjectNode> groups = new LinkedHashMap<>();
    BiConsumer<Object[], U> put = (g, vm) -> {
      String key = g[0] + "|" + g[1];
      ObjectNode o = groups.computeIfAbsent(key, (x) -> { ObjectNode n = Json.obj().put("customer", (String) g[0]).put("confidence", (String) g[1]).put("reason", (String) g[2]).put("isNew", (Boolean) g[3]); n.putArray("vms"); return n; });
      ((ArrayNode) o.get("vms")).addObject().put("id", vm.id()).put("site", vm.site()).put("name", vm.name());
    };
    List<U> rest = new ArrayList<>();
    for (U vm : unassigned) {
      Map<String, Integer> hit = vm.acc() == null ? null : byAccount.get(vm.acc());
      if (hit != null) {
        String best = hit.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
        put.accept(new Object[]{best, "cao", "cùng mã tài khoản " + vm.acc() + " với VM của khách này", false}, vm); continue;
      }
      if (vm.k().length() >= 4 && known.containsKey(vm.k()) && !GENERIC.contains(vm.k())) { put.accept(new Object[]{known.get(vm.k()), "cao", "tên VM bắt đầu bằng \"" + vm.prefix() + "\"", false}, vm); continue; }
      rest.add(vm);
    }
    Set<String> used = new HashSet<>();
    Map<String, List<U>> byAcc = new LinkedHashMap<>();
    for (U v : rest) if (v.acc() != null) byAcc.computeIfAbsent(v.acc(), (x) -> new ArrayList<>()).add(v);
    for (var e : byAcc.entrySet()) {
      List<U> list = e.getValue();
      if (list.size() < 2) continue;
      Map<String, Integer> counts = new LinkedHashMap<>();
      for (U v : list) if (!v.prefix().isEmpty() && !Character.isDigit(v.prefix().charAt(0)) && !GENERIC.contains(norm(v.prefix()))) counts.merge(v.prefix(), 1, Integer::sum);
      String name = counts.isEmpty() ? "KH-" + e.getKey() : counts.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
      for (U v : list) { put.accept(new Object[]{name, "trung bình", list.size() + " VM cùng mã tài khoản " + e.getKey(), true}, v); used.add(v.id()); }
    }
    Map<String, List<U>> byPrefix = new LinkedHashMap<>();
    for (U v : rest) if (!used.contains(v.id()) && v.k().length() >= 5 && v.k().matches("^[a-z].*") && !GENERIC.contains(v.k()) && v.k().matches(".*[a-z]{5,}.*"))
      byPrefix.computeIfAbsent(v.k(), (x) -> new ArrayList<>()).add(v);
    for (var e : byPrefix.entrySet()) {
      List<U> list = e.getValue();
      if (list.size() < 2) continue;
      for (U v : list) put.accept(new Object[]{list.get(0).prefix(), "thấp", list.size() + " VM cùng tiền tố tên \"" + list.get(0).prefix() + "\"", true}, v);
    }
    Map<String, Integer> order = Map.of("cao", 0, "trung bình", 1, "thấp", 2);
    List<ObjectNode> out = new ArrayList<>(groups.values());
    out.sort((a, b) -> { int c = order.get(a.get("confidence").asText()) - order.get(b.get("confidence").asText()); return c != 0 ? c : b.get("vms").size() - a.get("vms").size(); });
    int suggested = 0; for (ObjectNode g : out) suggested += g.get("vms").size();
    return Json.obj().put("unassigned", unassigned.size()).put("suggested", suggested).set("groups", Json.arr(out));
  }

  // ---------- trạng thái ----------
  ObjectNode status() {
    ObjectNode o = Json.obj().put("purgeAfterDays", purgeDays());
    o.set("lastPurge", lastPurge == null ? Json.F.nullNode() : lastPurge);
    o.set("stale", stale());
    o.set("backup", backup.status());
    ObjectNode r = reportsCfg();
    r.set("state", reportState);
    r.put("nextMonth", prevMonth(LocalDate.now())).put("smtp", Json.truthy(c().path("notify").path("email").path("host")));
    r.set("defaultTo", Json.strArr(Json.strings(c().path("notify").path("email").path("to"))));
    o.set("reports", r);
    o.set("usageMonths", Json.strArr(usage.list()));
    return o;
  }

  /** @return true nếu đã xử lý. */
  public boolean handle(Ctx ctx, Function<String, Auth.Who> need) throws Exception {
    List<String> r = ctx.seg.subList(2, ctx.seg.size());
    if (r.isEmpty()) return false;
    if ("usage".equals(r.get(0)) && "GET".equals(ctx.method)) {
      Auth.Who who = need.apply("read"); if (who == null) return true;
      if (r.size() > 1 && "months".equals(r.get(1))) { ctx.json(200, Json.obj().<ObjectNode>set("months", Json.strArr(usage.list())).put("current", Usage.monthKey(LocalDate.now()))); return true; }
      String month = ctx.q("month").matches("\\d{4}-\\d{2}") ? ctx.q("month") : Usage.monthKey(LocalDate.now());
      Set<String> cs = who.customers == null ? null : new HashSet<>(who.customers), ss = who.sites == null ? null : new HashSet<>(who.sites);
      ObjectNode rep = report(month, new Usage.Filter(ctx.q("site"), ctx.q("customer"),
          cs == null && ss == null ? null : (v) -> (cs == null || cs.contains(v.path("customer").asText())) && (ss == null || ss.contains(v.path("site").asText()))));
      if ("customer".equals(who.role)) { for (JsonNode v : rep.path("vms")) ((ObjectNode) v).remove("site"); for (JsonNode c : rep.path("customers")) ((ObjectNode) c).remove("sites"); }
      if ("csv".equals(ctx.q("format"))) {
        boolean vms = "vms".equals(ctx.q("detail"));
        ctx.send(200, vms ? Usage.vmsCsv(rep) : Usage.customersCsv(rep), "text/csv; charset=utf-8",
            Map.of("content-disposition", "attachment; filename=\"bao-cao-" + (vms ? "vm" : "khach-hang") + "-" + month + ".csv\""));
        return true;
      }
      ctx.json(200, rep); return true;
    }
    if (!"admin".equals(r.get(0)) || r.size() < 2) return false;
    String a1 = r.get(1), a2 = r.size() > 2 ? r.get(2) : null;
    if ("ops".equals(a1)) {
      if (need.apply("admin") == null) return true;
      if ("GET".equals(ctx.method) && a2 == null) { ctx.json(200, status()); return true; }
      if ("POST".equals(ctx.method) && "purge".equals(a2)) { ctx.json(200, purge("bấm tay")); return true; }
      if ("PUT".equals(ctx.method) && a2 == null) {
        JsonNode b = ctx.jsonBody(100000);
        cfg.mutate((raw) -> {
          if (b.has("purgeAfterDays")) raw.put("purgeAfterDays", (int) Math.min(365, Math.max(0, Math.round(Json.num(b.path("purgeAfterDays"))))));
          if (b.path("backup").isObject()) {
            JsonNode x = b.path("backup");
            ObjectNode o = raw.putObject("backup").put("enabled", Json.truthy(x.path("enabled"))).put("hour", (int) Math.min(23, Math.max(0, Json.num(x.path("hour")))))
                .put("keepDays", (int) Math.min(365, Math.max(1, Json.num(x.path("keepDays")) > 0 ? Json.num(x.path("keepDays")) : 14)));
            if (Json.truthy(x.path("dir"))) o.put("dir", Json.trim(x.path("dir"), 300));
          }
          if (b.path("reports").isObject()) {
            JsonNode x = b.path("reports");
            ObjectNode o = raw.putObject("reports").put("enabled", Json.truthy(x.path("enabled"))).put("day", (int) Math.min(28, Math.max(1, Json.num(x.path("day")) > 0 ? Json.num(x.path("day")) : 1)))
                .put("hour", (int) Math.min(23, Math.max(0, Json.num(x.path("hour"))))).put("vmDetail", Json.notFalse(x.path("vmDetail")));
            ArrayNode to = o.putArray("to");
            for (String s : Json.strings(x.path("to"))) { s = s.trim(); if (s.matches("[^\\s@]+@[^\\s@]+") && to.size() < 50) to.add(s); }
          }
        });
        ctx.json(200, Json.obj().put("ok", true).set("status", status())); return true;
      }
    }
    if ("backup".equals(a1) && "POST".equals(ctx.method)) {
      if (need.apply("admin") == null) return true;
      try { ObjectNode res = backup.run("bấm tay").deepCopy(); res.put("ok", true); ctx.json(200, res); } catch (Exception e) { ctx.error(500, e.getMessage()); }
      return true;
    }
    if ("reports".equals(a1) && "send".equals(a2) && "POST".equals(ctx.method)) {
      if (need.apply("admin") == null) return true;
      JsonNode b = ctx.jsonBody(100000);
      String month = Json.str(b.path("month")).matches("\\d{4}-\\d{2}") ? Json.str(b.path("month")) : prevMonth(LocalDate.now());
      try { ctx.json(200, sendReport(month, Json.strings(b.path("to")), "bấm tay")); } catch (Exception e) { ctx.error(400, e.getMessage()); }
      return true;
    }
    if ("customers".equals(a1) && "suggest".equals(a2) && "GET".equals(ctx.method)) {
      if (need.apply("admin") == null) return true;
      ctx.json(200, suggest()); return true;
    }
    return false;
  }
}
