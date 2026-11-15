package com.vmmonitor.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vmmonitor.admin.Admin;
import com.vmmonitor.alert.Alerts;
import com.vmmonitor.alert.Notify;
import com.vmmonitor.config.Cfg;
import com.vmmonitor.customer.Customers;
import com.vmmonitor.infra.Influx;
import com.vmmonitor.infra.Infra;
import com.vmmonitor.ops.Audit;
import com.vmmonitor.ops.Backup;
import com.vmmonitor.ops.Ops;
import com.vmmonitor.security.Account;
import com.vmmonitor.security.Auth;
import com.vmmonitor.security.Secrets;
import com.vmmonitor.store.Store;
import com.vmmonitor.store.Usage;
import com.vmmonitor.util.Json;
import com.vmmonitor.util.Log;
import com.vmmonitor.util.NaturalOrder;
import jakarta.servlet.AsyncContext;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

/** Lõi portal: nhận số liệu, mô hình VM, phạm vi dữ liệu, realtime (SSE), Prometheus, CSV, việc định kỳ. */
public class Portal {
  /** Khoá chung: mọi request và việc định kỳ chạy tuần tự như bản Node (một luồng sự kiện). */
  public final Object lock = new Object();

  public final Path root, dataDir;
  /** Thư mục giao diện khi chạy với --ui (sửa giao diện không cần build lại); null = giao diện đóng gói trong file jar. */
  public final Path publicDir;
  public final Cfg cfg;
  public final ScheduledExecutorService timers = Executors.newScheduledThreadPool(2, (r) -> { Thread t = new Thread(r, "vmmon-timer"); t.setDaemon(true); return t; });
  public final Store store;
  public final Usage usage;
  public final Secrets secrets;
  public final Auth auth;
  public final Audit audit;
  public final Influx influx;
  public final Infra infra;
  public final Customers customers;
  public final Alerts alerts, hostAlerts, dsAlerts;
  public final Admin admin;
  public final Backup backup;
  public final Ops ops;
  public final Account account;
  public final ObjectNode httpsState = Json.obj().put("running", false).put("port", 0).put("error", "");
  public volatile ArrayNode lastNotify = Json.arr();
  public volatile String uiVersion;
  private final Path alertStateFile;
  private Map<String, Integer> alertCountCache;
  private final Set<String> pendingUpdates = new LinkedHashSet<>();
  public final long startedAt = System.currentTimeMillis();

  /** Một trình duyệt đang nghe /api/v1/stream. */
  public static final class Client {
    final AsyncContext ac; final OutputStream out; final Scope scope;
    public Client(AsyncContext ac, OutputStream out, Scope scope) { this.ac = ac; this.out = out; this.scope = scope; }
  }
  public final List<Client> clients = new CopyOnWriteArrayList<>();

  public Portal(Path root, Path configFile, Path uiDir) {
    this.root = root;
    this.publicDir = uiDir;
    this.cfg = new Cfg(configFile);
    cfg.load();
    ObjectNode c = cfg.get();
    Path dataFile = root.resolve(Json.truthy(c.path("dataFile")) ? Json.str(c.path("dataFile")) : "data/state.json").toAbsolutePath().normalize();
    this.dataDir = dataFile.getParent();
    double ret = Json.num(c.path("retentionDays")) > 0 ? Json.num(c.path("retentionDays")) : 7;
    store = new Store(dataFile, ret, Json.num(c.path("rollupMinutes")) > 0 ? Json.num(c.path("rollupMinutes")) : 5, Json.num(c.path("rawPoints")) > 0 ? (int) Json.num(c.path("rawPoints")) : 360);
    int restored = store.load();
    if (restored > 0) Log.info("Khôi phục " + restored + " VM từ " + dataFile);
    usage = new Usage(dataDir.resolve("usage"));
    try { long t = System.currentTimeMillis(); int d = usage.backfill(store); if (d > 0) Log.info("Báo cáo tháng: bù " + d + " ngày từ lịch sử (" + (System.currentTimeMillis() - t) + " ms)"); }
    catch (Exception e) { Log.error("Bù số liệu báo cáo lỗi: " + e.getMessage()); }
    alertStateFile = dataDir.resolve("alert-states.json");
    secrets = new Secrets(dataDir);
    auth = new Auth(cfg::get, dataDir.resolve("session.secret"), () -> secrets, (fn) -> cfg.mutate(fn));
    audit = new Audit(dataDir.resolve("audit.log"));
    influx = new Influx(cfg::get, timers);
    infra = new Infra(dataDir, cfg::get, influx);
    customers = new Customers(cfg::get);
    if (influx.enabled()) Log.info("InfluxDB: ghi tới " + Json.str(influx.status().path("url")) + " (" + Json.str(influx.status().path("target")) + ")");

    java.util.function.Consumer<ObjectNode> notify = (msg) -> {
      lastNotify = Notify.notifyAll(cfg.get().path("notify"), msg);
      broadcast("notify", Json.obj().put("ts", System.currentTimeMillis()).set("results", lastNotify));
    };
    java.util.function.Consumer<ObjectNode> onChange = (ev) -> {
      alertCountCache = null;
      if ("rules-changed".equals(Json.str(ev.path("type")))) { broadcast("alert", Json.obj().set("active", Json.arr(allActive()))); return; }
      if (!"repeat".equals(Json.str(ev.path("type")))) influx.addAlert(ev);
      broadcast("alert", Json.obj().<ObjectNode>set("event", ev).set("active", Json.arr(allActive())));
    };
    alerts = new Alerts(cfg::get, store, store, "vm", notify, onChange, timers);
    hostAlerts = new Alerts(cfg::get, infra.hosts, store, "host", notify, onChange, timers);
    dsAlerts = new Alerts(cfg::get, infra.ds, store, "datastore", notify, onChange, timers);
    try {
      JsonNode saved = Json.M.readTree(Files.readString(alertStateFile));
      int n = alerts.importStates(saved.path("vm")) + hostAlerts.importStates(saved.path("host")) + dsAlerts.importStates(saved.path("datastore"));
      if (n > 0) Log.info("Khôi phục " + n + " cảnh báo đang hoạt động");
    } catch (java.nio.file.NoSuchFileException ignored) {
    } catch (Exception e) { Log.error("Không đọc được " + alertStateFile + ": " + e.getMessage()); }
    infra.onHost = (id, h) -> hostAlerts.evaluate(id, h, System.currentTimeMillis() - h.lastSeen < infra.offlineMs());
    infra.onDatastore = (id, d) -> dsAlerts.evaluate(id, d, true);

    admin = new Admin(cfg, secrets, auth, influx, this::broadcast, () -> httpsState);
    backup = new Backup(root, dataDir, configFile, cfg::get, this::saveAll);
    ops = new Ops(cfg, store, infra, new Ops.Engines(alerts, hostAlerts, dsAlerts), usage, backup, customers, this::broadcast, dataDir,
        () -> { alertCountCache = null; broadcast("alert", Json.obj().set("active", Json.arr(allActive()))); });
    account = new Account(auth, cfg, secrets);
    uiVersion = computeUiVersion();
  }

  /** Việc định kỳ (giống các setInterval của bản Node). */
  public void start() {
    long save = (long) (Json.num(cfg.get().path("saveSeconds")) > 0 ? Json.num(cfg.get().path("saveSeconds")) : 120);
    every(save, TimeUnit.SECONDS, this::saveAll);
    every(1, TimeUnit.HOURS, () -> { store.prune(); infra.prune(); });
    every(1, TimeUnit.SECONDS, this::flushUpdates);
    every(5, TimeUnit.SECONDS, this::checkOffline);
    every(2, TimeUnit.SECONDS, () -> { cfg.checkChanged(); checkUi(); });
    timers.schedule(() -> locked(() -> { ops.purge("tự động"); }), 2, TimeUnit.MINUTES);
    timers.schedule(() -> { backup.tick(); ops.tickReports(); }, 2, TimeUnit.MINUTES);
    every(1, TimeUnit.HOURS, () -> ops.purge("tự động"));
    timers.scheduleAtFixedRate(() -> { try { backup.tick(); ops.tickReports(); } catch (Exception e) { Log.error("tick: " + e.getMessage()); } }, 10, 10, TimeUnit.MINUTES);
    Runtime.getRuntime().addShutdownHook(new Thread(() -> { saveAll(); influx.flush(); }));
  }
  private void every(long n, TimeUnit u, Runnable r) {
    timers.scheduleAtFixedRate(() -> { try { locked(r); } catch (Exception e) { Log.error("Việc định kỳ lỗi: " + e); } }, n, n, u);
  }
  private void locked(Runnable r) { synchronized (lock) { r.run(); } }

  public void saveAll() {
    store.save(); infra.save(); usage.save();
    try {
      ObjectNode data = Json.obj().put("savedAt", System.currentTimeMillis());
      data.set("vm", alerts.exportStates()); data.set("host", hostAlerts.exportStates()); data.set("datastore", dsAlerts.exportStates());
      Path tmp = alertStateFile.resolveSibling("alert-states.json.tmp");
      Files.writeString(tmp, Json.stringify(data), StandardCharsets.UTF_8);
      Files.move(tmp, alertStateFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception e) { Log.error("Không lưu được trạng thái cảnh báo: " + e.getMessage()); }
  }

  // ---------- giao diện thay đổi -> trình duyệt tự tải lại ----------
  String computeUiVersion() {
    if (publicDir == null) return Long.toString(startedAt, 36); // giao diện trong jar: đổi khi khởi động lại
    try (Stream<Path> s = Files.list(publicDir)) {
      StringBuilder sb = new StringBuilder();
      s.sorted().forEach((p) -> { try { sb.append(p.getFileName()).append(':').append(Files.getLastModifiedTime(p).toMillis()).append('|'); } catch (Exception ignored) {} });
      return Integer.toString(sb.toString().hashCode() & 0x7fffffff, 36);
    } catch (Exception e) { return "x"; }
  }
  void checkUi() {
    String v = computeUiVersion();
    if (!v.equals(uiVersion)) { uiVersion = v; Log.info("Giao diện thay đổi — báo trình duyệt tải lại"); broadcast("reload", Json.obj().put("version", v)); }
  }

  // ---------- realtime ----------
  public void broadcast(String event, Object data) {
    if (clients.isEmpty()) return;
    String msg = "event: " + event + "\ndata: " + Json.stringify(data) + "\n\n";
    for (Client c : clients) {
      String m = msg;
      if (c.scope != null) {
        Object d = scopeEvent(event, data, c.scope);
        if (d == null) continue;
        m = "event: " + event + "\ndata: " + Json.stringify(d) + "\n\n";
      }
      write(c, m);
    }
  }
  public void write(Client c, String m) {
    try { synchronized (c) { c.out.write(m.getBytes(StandardCharsets.UTF_8)); c.out.flush(); } }
    catch (Exception e) { clients.remove(c); try { c.ac.complete(); } catch (Exception ignored) {} }
  }

  Object scopeEvent(String event, Object data, Scope s) {
    JsonNode d = Json.M.valueToTree(data);
    switch (event) {
      case "updates" -> {
        ArrayNode out = Json.arr();
        for (JsonNode v : d) if (Scope.in(Json.str(v.path("customer")), Json.str(v.path("site")), s)) out.add(strip((ObjectNode) v, s));
        return out.size() > 0 ? out : null;
      }
      case "status" -> { return visible(store.vms.get(Json.str(d.path("id"))), s) ? d : null; }
      case "alert" -> {
        ObjectNode o = Json.obj();
        JsonNode ev = d.path("event");
        if (ev.isObject() && alertVisible(ev, s)) o.set("event", stripAlert((ObjectNode) ev, s));
        ArrayNode act = o.putArray("active");
        for (JsonNode a : d.path("active")) if (alertVisible(a, s)) act.add(stripAlert((ObjectNode) a, s));
        return o;
      }
      case "hello", "reload" -> { return d; }
      default -> { return s.customers() != null ? null : d; } // removed, collector, notify: chỉ cho nhân viên
    }
  }

  public void addPending(String id) { synchronized (pendingUpdates) { pendingUpdates.add(id); } }
  void flushUpdates() {
    List<String> ids;
    synchronized (pendingUpdates) { if (pendingUpdates.isEmpty()) return; ids = new ArrayList<>(pendingUpdates); pendingUpdates.clear(); }
    ArrayNode list = Json.arr();
    for (String id : ids) { Store.Rec r = store.vms.get(id); if (r != null) list.add(view(r, false, false)); }
    broadcast("updates", list);
  }
  void checkOffline() {
    for (Map.Entry<String, Store.Rec> e : store.vms.entrySet()) {
      String st = statusOf(e.getValue());
      if (!st.equals(e.getValue().wasOnline)) { e.getValue().wasOnline = st; broadcast("status", Json.obj().put("id", e.getKey()).put("status", st)); }
      if ("offline".equals(st)) alerts.evaluate(e.getKey(), e.getValue(), false);
    }
    for (Map.Entry<String, Store.Rec> e : infra.hosts.vms.entrySet())
      if (System.currentTimeMillis() - e.getValue().lastSeen >= infra.offlineMs()) hostAlerts.evaluate(e.getKey(), e.getValue(), false);
    for (Client c : clients) write(c, ": ping\n\n");
  }

  // ---------- cảnh báo ----------
  public List<ObjectNode> allActive() { List<ObjectNode> l = new ArrayList<>(alerts.active()); l.addAll(hostAlerts.active()); l.addAll(dsAlerts.active()); return l; }
  public void invalidateAlertCounts() { alertCountCache = null; }
  Map<String, Integer> alertCounts() {
    if (alertCountCache == null) { Map<String, Integer> m = new HashMap<>(); for (ObjectNode a : alerts.active()) m.merge(Json.str(a.path("vmId")), 1, Integer::sum); alertCountCache = m; }
    return alertCountCache;
  }

  // ---------- mô hình VM ----------
  long offlineAfter() { double s = Json.num(cfg.get().path("offlineAfterSeconds")); return (long) ((s > 0 ? s : 60) * 1000); }
  public String statusOf(Store.Rec vm) {
    String power = Json.str(vm.info.path("power"));
    if (!power.isEmpty() && !"on".equals(power)) return "off";
    return System.currentTimeMillis() - vm.lastSeen < offlineAfter() ? "online" : "offline";
  }

  public boolean ingest(JsonNode m) {
    if (m == null || !m.isObject()) return false;
    ObjectNode c = cfg.get();
    String site = Json.clean(Json.truthy(m.path("site")) ? m.path("site") : Json.truthy(c.path("defaultSite")) ? c.path("defaultSite") : Json.F.textNode("default"), 64).replace("/", "-");
    if (site.isEmpty()) site = "default";
    String name = Json.clean(Json.truthy(m.path("name")) ? m.path("name") : Json.truthy(m.path("hostname")) ? m.path("hostname") : Json.F.textNode("unknown"), 128);
    if (name.isEmpty()) name = "unknown";
    String id = site + "/" + name;
    String power = Json.truthy(m.path("power")) ? Json.clean(m.path("power"), 16) : "on";
    boolean vcenter = "vcenter".equals(Json.str(m.path("source")));
    ObjectNode info = Json.obj().put("id", id).put("site", site).put("name", name).put("power", power)
        .put("hostname", Json.clean(m.path("hostname"), 128)).put("ip", Json.clean(m.path("ip"), 64)).put("os", Json.clean(m.path("os"), 200)).put("cores", Json.num(m.path("cores")))
        .put("uptime", Json.num(m.path("uptime")));
    ArrayNode load = info.putArray("load"); int k = 0; for (JsonNode x : m.path("load")) { if (k++ >= 3) break; load.add(x); }
    info.put("source", Json.clean(Json.truthy(m.path("source")) ? m.path("source") : Json.F.textNode("agent"), 16))
        .put("cluster", Json.clean(m.path("cluster"), 128)).put("host", Json.clean(Json.truthy(m.path("esxiHost")) ? m.path("esxiHost") : m.path("host"), 128))
        .put("datacenter", Json.clean(m.path("datacenter"), 128))
        .put("diskSource", Json.clean(Json.truthy(m.path("diskSource")) ? m.path("diskSource") : Json.F.textNode(vcenter ? "tools" : "os"), 16))
        .put("vcenter", Json.clean(m.path("vcenter"), 128)).put("folder", Json.clean(m.path("folder"), 300))
        .put("notes", Json.clean(m.path("notes"), 500).replaceAll("\\s+", " ")).put("resourcePool", Json.clean(m.path("resourcePool"), 128));
    ObjectNode attrs = info.putObject("attributes"); k = 0;
    for (var it = m.path("attributes").fields(); it.hasNext() && k++ < 30; ) { var e = it.next(); attrs.put(Json.clean(e.getKey(), 60), Json.clean(e.getValue(), 200)); }
    info.put("memSource", Json.clean(Json.truthy(m.path("memSource")) ? m.path("memSource") : Json.F.textNode(vcenter ? "active" : "os"), 16));
    if (m.path("tags").isArray()) { ArrayNode t = info.putArray("tags"); k = 0; for (JsonNode x : m.path("tags")) { if (k++ >= 20) break; t.add(Json.clean(x, 40)); } }
    boolean on = "on".equals(power);
    Store.Point p = new Store.Point(System.currentTimeMillis(), on ? Json.num(m.path("cpu").path("percent")) : 0, on ? Json.num(m.path("mem").path("percent")) : 0,
        Json.num(m.path("disk").path("percent")), on ? Json.num(m.path("net").path("rxRate")) : 0, on ? Json.num(m.path("net").path("txRate")) : 0,
        on ? Json.num(m.path("disk").path("readRate")) : 0, on ? Json.num(m.path("disk").path("writeRate")) : 0);
    ObjectNode latest = Json.obj();
    if (!Json.missing(m.path("mem"))) latest.set("mem", m.path("mem"));
    if (!Json.missing(m.path("disk"))) latest.set("disk", m.path("disk"));
    latest.put("cpuMhz", Json.num(m.path("cpuMhz")));
    Store.Rec prev = store.vms.get(id);
    String prevStatus = prev == null ? null : statusOf(prev);
    Store.Rec vm = store.add(id, info, latest, p);
    usage.add(id, info, latest, p);
    if (influx.enabled()) influx.addVm(view(vm, false, false), latest.path("disk").path("mounts"), p.ts);
    String st = statusOf(vm);
    if (prevStatus != null && !prevStatus.equals(st)) broadcast("status", Json.obj().put("id", id).put("status", st));
    vm.wasOnline = st;
    alerts.evaluate(id, vm, true);
    if (!clients.isEmpty()) addPending(id);
    return true;
  }

  public static double diskMax(Store.Rec vm) {
    JsonNode mounts = vm.latest.path("disk").path("mounts");
    if (mounts.size() > 0) { double m = 0; for (JsonNode x : mounts) m = Math.max(m, Json.num(x.path("percent"))); return m; }
    Store.Point p = vm.last(); return p == null ? 0 : p.disk;
  }

  /** Dạng phẳng của VM cho API và dashboard. */
  public ObjectNode view(Store.Rec vm, boolean detail, boolean spark) {
    JsonNode i = vm.info; Store.Point p = vm.last();
    Customers.Result cust = customers.resolve(i);
    ObjectNode o = Json.obj();
    o.set("id", i.path("id")); o.set("site", i.path("site")); o.set("name", i.path("name"));
    o.put("status", statusOf(vm)).put("power", Json.truthy(i.path("power")) ? Json.str(i.path("power")) : "on");
    for (String f : new String[]{"ip", "os", "hostname", "cores", "uptime", "source"}) if (i.has(f)) o.set(f, i.get(f));
    for (String f : new String[]{"cluster", "host", "datacenter"}) if (Json.truthy(i.path(f))) o.set(f, i.get(f));
    if (i.has("tags")) o.set("tags", i.get("tags"));
    if (i.has("diskSource")) o.set("diskSource", i.get("diskSource"));
    if (i.has("memSource")) o.set("memSource", i.get("memSource"));
    if (Json.truthy(i.path("vcenter"))) o.set("vcenter", i.get("vcenter"));
    o.put("cpuMhz", Json.num(vm.latest.path("cpuMhz")));
    o.put("customer", cust == null ? "" : cust.customer()).put("customerSource", cust == null ? "" : cust.source());
    if (Json.truthy(i.path("folder"))) o.set("folder", i.get("folder"));
    if (Json.truthy(i.path("resourcePool"))) o.set("resourcePool", i.get("resourcePool"));
    o.put("lastSeen", vm.lastSeen)
        .put("cpu", p == null ? 0 : p.cpu).put("mem", p == null ? 0 : p.mem).put("disk", p == null ? 0 : p.disk).put("diskMax", diskMax(vm))
        .put("memTotal", Json.num(vm.latest.path("mem").path("total"))).put("memUsed", Json.num(vm.latest.path("mem").path("used")))
        .put("diskTotal", Json.num(vm.latest.path("disk").path("total"))).put("diskUsed", Json.num(vm.latest.path("disk").path("used")))
        .put("netRx", p == null ? 0 : p.rx).put("netTx", p == null ? 0 : p.tx).put("diskRead", p == null ? 0 : p.rd).put("diskWrite", p == null ? 0 : p.wr)
        .put("alerts", alertCounts().getOrDefault(Json.str(i.path("id")), 0));
    if (detail) {
      o.set("load", i.path("load"));
      o.set("mounts", vm.latest.path("disk").path("mounts").isArray() ? vm.latest.path("disk").path("mounts") : Json.arr());
      o.put("notes", Json.str(i.path("notes")));
      o.set("tags", i.path("tags").isArray() ? i.get("tags") : Json.arr());
      o.set("attributes", i.path("attributes").isObject() ? i.get("attributes") : Json.obj());
      o.put("customerSourceLabel", cust == null ? "" : Customers.LABEL.getOrDefault(cust.source(), ""));
      ArrayNode act = o.putArray("activeAlerts");
      for (ObjectNode a : alerts.active()) if (Json.str(a.path("vmId")).equals(Json.str(i.path("id")))) act.add(a);
    }
    if (spark) {
      ArrayNode s = o.putArray("spark");
      for (int k = Math.max(0, vm.history.size() - 30); k < vm.history.size(); k++) { Store.Point x = vm.history.get(k); s.addArray().add(x.ts).add(x.cpu).add(x.mem); }
    }
    return o;
  }

  // ---------- phạm vi ----------
  public String custOf(Store.Rec vm) { return vm == null ? "" : customers.of(vm.info); }
  public boolean visible(Store.Rec vm, Scope s) { return s == null || (vm != null && Scope.in(custOf(vm), Json.str(vm.info.path("site")), s)); }
  public boolean alertVisible(JsonNode a, Scope s) {
    if (s == null) return true;
    if ("vm".equals(Json.str(a.path("kind")))) return visible(store.vms.get(Json.str(a.path("vmId"))), s);
    return s.customers() == null && Scope.in("", Json.str(a.path("site")), s);
  }
  static final String[] INTERNAL = {"site", "cluster", "host", "datacenter", "vcenter", "folder", "resourcePool", "customerSource", "customerSourceLabel", "notes", "attributes", "tags", "diskSource", "memSource", "cpuMhz", "source"};
  public ObjectNode strip(ObjectNode v, Scope s) {
    if (s == null || !s.hide() || v == null) return v;
    ObjectNode o = v.deepCopy();
    for (String k : INTERNAL) o.remove(k);
    if (o.path("activeAlerts").isArray()) { ArrayNode a = Json.arr(); for (JsonNode x : o.get("activeAlerts")) a.add(stripAlert((ObjectNode) x, s)); o.set("activeAlerts", a); }
    return o;
  }
  public ObjectNode stripAlert(ObjectNode a, Scope s) {
    if (s == null || !s.hide() || a == null) return a;
    ObjectNode o = a.deepCopy(); o.remove("site");
    o.put("text", Json.str(a.path("text")).replaceFirst("^\\[[^\\]]*\\]\\s*", ""));
    return o;
  }

  public List<Store.Rec> filterVms(Function<String, String> q, Scope scope) {
    List<String> sites = new ArrayList<>(), custs = new ArrayList<>();
    for (String x : q.apply("site").split(",")) if (!x.trim().isEmpty()) sites.add(x.trim());
    for (String x : q.apply("customer").split(",")) if (!x.trim().isEmpty()) custs.add(x.trim());
    String status = q.apply("status"), qs = q.apply("q").trim().toLowerCase();
    Map<String, Integer> counts = alertCounts();
    List<Store.Rec> list = new ArrayList<>();
    for (Store.Rec vm : store.vms.values()) {
      JsonNode i = vm.info;
      if (scope != null && !visible(vm, scope)) continue;
      if (!sites.isEmpty() && !sites.contains(Json.str(i.path("site")))) continue;
      if (!custs.isEmpty()) { String c = customers.of(i); if (!custs.contains(c.isEmpty() ? "(none)" : c)) continue; }
      if (!qs.isEmpty() && !(Json.str(i.path("name")) + " " + Json.str(i.path("ip")) + " " + Json.str(i.path("host")) + " " + Json.str(i.path("cluster")) + " " + Json.str(i.path("hostname"))
          + " " + Json.str(i.path("os")) + " " + customers.of(i) + " " + Json.str(i.path("folder"))).toLowerCase().contains(qs)) continue;
      if (!status.isEmpty()) {
        if ("alert".equals(status)) { if (counts.getOrDefault(Json.str(i.path("id")), 0) == 0) continue; }
        else if (!statusOf(vm).equals(status)) continue;
      }
      list.add(vm);
    }
    return list;
  }

  public List<Store.Rec> sortVms(List<Store.Rec> list, String sort) {
    boolean desc = sort.startsWith("-"); String key = sort.replaceFirst("^-", "");
    boolean text = List.of("name", "site", "customer").contains(key);
    Function<Store.Rec, Object> fn = switch (key) {
      case "site" -> (v) -> Json.str(v.info.path("site")) + "/" + Json.str(v.info.path("name"));
      case "customer" -> (v) -> (customers.of(v.info).isEmpty() ? "~" : customers.of(v.info)) + "/" + Json.str(v.info.path("name"));
      case "cpu" -> (v) -> v.last() == null ? 0.0 : v.last().cpu;
      case "mem" -> (v) -> v.last() == null ? 0.0 : v.last().mem;
      case "disk" -> (v) -> diskMax(v);
      case "net" -> (v) -> v.last() == null ? 0.0 : v.last().rx + v.last().tx;
      default -> (v) -> Json.str(v.info.path("name"));
    };
    Comparator<Store.Rec> cmp = text ? (a, b) -> NaturalOrder.compare((String) fn.apply(a), (String) fn.apply(b))
        : (a, b) -> Double.compare((Double) fn.apply(b), (Double) fn.apply(a)); // số: giảm dần
    list.sort(desc ? cmp.reversed() : cmp);
    return list;
  }

  public ArrayNode siteStats(List<ObjectNode> list) {
    Map<String, double[]> m = new TreeMap<>(NaturalOrder::compare); // vms online offline off cpu mem disk rx tx alerts critical memTotal cores
    Map<String, Set<String>> src = new HashMap<>();
    for (ObjectNode v : list) {
      String site = Json.str(v.path("site"));
      double[] s = m.computeIfAbsent(site, (k) -> new double[13]);
      src.computeIfAbsent(site, (k) -> new LinkedHashSet<>()).add(Json.str(v.path("source")));
      s[0]++;
      switch (Json.str(v.path("status"))) { case "online" -> s[1]++; case "offline" -> s[2]++; default -> s[3]++; }
      if ("online".equals(Json.str(v.path("status")))) {
        s[4] += Json.num(v.path("cpu")); s[5] += Json.num(v.path("mem")); s[6] += Json.num(v.path("diskMax")); s[7] += Json.num(v.path("netRx")); s[8] += Json.num(v.path("netTx"));
        s[11] += Json.num(v.path("memTotal")); s[12] += Json.num(v.path("cores"));
      }
    }
    for (ObjectNode a : allActive()) { double[] s = m.get(Json.str(a.path("site"))); if (s != null) { s[9]++; if ("critical".equals(Json.str(a.path("severity")))) s[10]++; } }
    ArrayNode out = Json.arr();
    for (Map.Entry<String, double[]> e : m.entrySet()) {
      double[] s = e.getValue(); double on = s[1];
      ObjectNode o = out.addObject().put("site", e.getKey()).put("vms", (int) s[0]).put("online", (int) s[1]).put("offline", (int) s[2]).put("off", (int) s[3])
          .put("netRx", s[7]).put("netTx", s[8]).put("alerts", (int) s[9]).put("critical", (int) s[10]).put("memTotal", s[11]).put("cores", s[12]);
      o.set("sources", Json.strArr(src.get(e.getKey())));
      o.put("cpuAvg", on > 0 ? Json.r1(s[4] / on) : 0).put("memAvg", on > 0 ? Json.r1(s[5] / on) : 0).put("diskAvg", on > 0 ? Json.r1(s[6] / on) : 0);
    }
    return out;
  }

  // ---------- Prometheus ----------
  static String esc(String s) { return s.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\""); }
  public String prometheus() {
    Object[][] metrics = {
        {"vm_up", "VM đang gửi số liệu (1) hay mất kết nối (0)", (Function<ObjectNode, Double>) (v) -> "online".equals(v.path("status").asText()) ? 1.0 : 0.0},
        {"vm_power_on", "VM đang bật nguồn", (Function<ObjectNode, Double>) (v) -> "on".equals(v.path("power").asText()) ? 1.0 : 0.0},
        {"vm_cpu_usage_percent", "CPU %", (Function<ObjectNode, Double>) (v) -> Json.num(v.path("cpu"))},
        {"vm_memory_usage_percent", "RAM %", (Function<ObjectNode, Double>) (v) -> Json.num(v.path("mem"))},
        {"vm_memory_total_bytes", "Tổng RAM", (Function<ObjectNode, Double>) (v) -> Json.num(v.path("memTotal"))},
        {"vm_disk_usage_percent", "Disk % (tổng các phân vùng)", (Function<ObjectNode, Double>) (v) -> Json.num(v.path("disk"))},
        {"vm_disk_max_usage_percent", "Disk % phân vùng đầy nhất", (Function<ObjectNode, Double>) (v) -> Json.num(v.path("diskMax"))},
        {"vm_disk_total_bytes", "Tổng dung lượng ổ", (Function<ObjectNode, Double>) (v) -> Json.num(v.path("diskTotal"))},
        {"vm_network_receive_bytes_per_second", "Network in", (Function<ObjectNode, Double>) (v) -> Json.num(v.path("netRx"))},
        {"vm_network_transmit_bytes_per_second", "Network out", (Function<ObjectNode, Double>) (v) -> Json.num(v.path("netTx"))},
        {"vm_disk_read_bytes_per_second", "Disk read", (Function<ObjectNode, Double>) (v) -> Json.num(v.path("diskRead"))},
        {"vm_disk_write_bytes_per_second", "Disk write", (Function<ObjectNode, Double>) (v) -> Json.num(v.path("diskWrite"))},
        {"vm_alerts_active", "Số cảnh báo đang hoạt động", (Function<ObjectNode, Double>) (v) -> Json.num(v.path("alerts"))},
    };
    List<ObjectNode> vms = new ArrayList<>();
    for (Store.Rec r : store.vms.values()) vms.add(view(r, false, false));
    StringBuilder out = new StringBuilder();
    for (Object[] m : metrics) {
      out.append("# HELP ").append(m[0]).append(' ').append(m[1]).append("\n# TYPE ").append(m[0]).append(" gauge\n");
      @SuppressWarnings("unchecked") Function<ObjectNode, Double> fn = (Function<ObjectNode, Double>) m[2];
      for (ObjectNode v : vms) out.append(m[0]).append("{site=\"").append(esc(Json.str(v.path("site")))).append("\",vm=\"").append(esc(Json.str(v.path("name"))))
          .append("\",customer=\"").append(esc(Json.str(v.path("customer")))).append("\",ip=\"").append(esc(Json.str(v.path("ip")))).append("\",cluster=\"").append(esc(Json.str(v.path("cluster"))))
          .append("\",host=\"").append(esc(Json.str(v.path("host")))).append("\"} ").append(Json.numStr(fn.apply(v))).append('\n');
    }
    out.append("# HELP vmportal_site_vms Số VM theo site và trạng thái\n# TYPE vmportal_site_vms gauge\n");
    for (JsonNode s : siteStats(vms)) for (String st : new String[]{"online", "offline", "off"})
      out.append("vmportal_site_vms{site=\"").append(esc(Json.str(s.path("site")))).append("\",status=\"").append(st).append("\"} ").append(s.path(st).asInt()).append('\n');
    return out + infra.prometheus(Portal::esc);
  }

  // ---------- CSV ----------
  public static String toCsv(List<ObjectNode> rows) {
    String[] cols = {"site", "customer", "name", "status", "ip", "os", "cluster", "host", "cores", "cpu", "mem", "disk", "diskMax", "memTotal", "diskTotal", "netRx", "netTx", "diskRead", "diskWrite", "alerts", "lastSeen"};
    StringBuilder sb = new StringBuilder("\uFEFF").append(String.join(",", cols));
    for (ObjectNode r : rows) {
      List<String> l = new ArrayList<>();
      for (String c : cols) {
        String s = "lastSeen".equals(c) ? Instant.ofEpochMilli(r.path(c).asLong()).toString() : Json.str(r.path(c));
        l.add(s.matches("(?s).*[\",\\n;].*") ? "\"" + s.replace("\"", "\"\"") + "\"" : s);
      }
      sb.append("\r\n").append(String.join(",", l));
    }
    return sb.toString();
  }
}
