package com.vmmonitor.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vmmonitor.security.Auth;
import com.vmmonitor.store.Store;
import com.vmmonitor.util.Json;
import com.vmmonitor.util.NaturalOrder;
import com.vmmonitor.web.Ctx;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Host ESXi + datastore: lưu số liệu, API, Prometheus.
 *   POST /api/v1/hosts/report      collector gửi {hosts, datastores}   ingest
 *   GET  /api/v1/hosts[/{site}/{name}[/metrics]]                       staff
 *   GET  /api/v1/datastores                                              staff
 */
public class Infra {
  private final Supplier<ObjectNode> config;
  private final Influx influx;
  public final Store hosts, ds;
  public BiConsumer<String, Store.Rec> onHost, onDatastore;

  public Infra(Path dataDir, Supplier<ObjectNode> config, Influx influx) {
    this.config = config; this.influx = influx;
    double ret = Json.num(config.get().path("retentionDays")); if (ret <= 0) ret = 7;
    hosts = new Store(dataDir.resolve("hosts.json"), ret, 5, 360);
    ds = new Store(dataDir.resolve("datastores.json"), ret, 15, 120);
    hosts.load(); ds.load();
  }

  public void save() { hosts.save(); ds.save(); }
  public void prune() { hosts.prune(); ds.prune(); }
  public long offlineMs() { double s = Json.num(config.get().path("offlineAfterSeconds")); return (long) ((s > 0 ? s : 60) * 1000 * 3); }

  public ObjectNode ingest(JsonNode body) {
    long now = System.currentTimeMillis();
    int nh = 0, nd = 0, k = 0;
    for (JsonNode h : body.path("hosts")) {
      if (k++ >= 5000) break;
      String site = Json.clean(h.path("site"), 64).replace("/", "-"); if (site.isEmpty()) site = "default";
      String name = Json.clean(h.path("name"), 200);
      if (name.isEmpty()) continue;
      String id = site + "/" + name;
      ObjectNode info = Json.obj().put("id", id).put("site", site).put("name", name).put("cluster", Json.clean(h.path("cluster"), 200))
          .put("datacenter", Json.clean(h.path("datacenter"), 200)).put("vcenter", Json.clean(h.path("vcenter"), 200)).put("connection", Json.clean(h.path("connection"), 32))
          .put("power", Json.clean(h.path("power"), 32)).put("maintenance", Json.truthy(h.path("maintenance"))).put("health", Json.clean(h.path("health"), 16))
          .put("cores", Json.num(h.path("cores"))).put("threads", Json.num(h.path("threads"))).put("cpuTotalMhz", Json.num(h.path("cpuTotalMhz"))).put("memTotal", Json.num(h.path("memTotal")))
          .put("uptime", Json.num(h.path("uptime"))).put("version", Json.clean(h.path("version"), 200)).put("vendor", Json.clean(h.path("vendor"), 100)).put("model", Json.clean(h.path("model"), 100))
          .put("vms", Json.num(h.path("vms"))).put("vmsRunning", Json.num(h.path("vmsRunning")));
      Store.Point p = new Store.Point(now, Json.num(h.path("cpu")), Json.num(h.path("mem")), 0, Json.num(h.path("rx")), Json.num(h.path("tx")), Json.num(h.path("rd")), Json.num(h.path("wr")));
      Store.Rec vm = hosts.add(id, info, Json.obj().put("cpuUsedMhz", Json.num(h.path("cpuUsedMhz"))).put("memUsed", Json.num(h.path("memUsed"))), p);
      if (influx.enabled()) influx.addHost(hostView(vm));
      if (onHost != null) onHost.accept(id, vm);
      nh++;
    }
    k = 0;
    for (JsonNode d : body.path("datastores")) {
      if (k++ >= 5000) break;
      String site = Json.clean(d.path("site"), 64).replace("/", "-"); if (site.isEmpty()) site = "default";
      String name = Json.clean(d.path("name"), 200);
      if (name.isEmpty()) continue;
      String id = site + "/" + name;
      ArrayNode hn = Json.arr(); int c = 0;
      for (JsonNode x : d.path("hostNames")) { if (c++ >= 50) break; hn.add(Json.clean(x, 200)); }
      ObjectNode info = Json.obj().put("id", id).put("site", site).put("name", name).put("datacenter", Json.clean(d.path("datacenter"), 200)).put("vcenter", Json.clean(d.path("vcenter"), 200))
          .put("type", Json.clean(d.path("type"), 32)).put("accessible", Json.truthy(d.path("accessible"))).put("maintenance", Json.clean(d.path("maintenance"), 32))
          .put("hosts", Json.num(d.path("hosts"))).put("vms", Json.num(d.path("vms")));
      info.set("hostNames", hn);
      ObjectNode latest = Json.obj().put("capacity", Json.num(d.path("capacity"))).put("free", Json.num(d.path("free"))).put("used", Json.num(d.path("used"))).put("provisioned", Json.num(d.path("provisioned")));
      Store.Rec st = ds.add(id, info, latest, new Store.Point(now, 0, 0, Json.num(d.path("percent")), 0, 0, 0, 0));
      if (influx.enabled()) influx.addDatastore(dsView(st));
      if (onDatastore != null) onDatastore.accept(id, st);
      nd++;
    }
    return Json.obj().put("hosts", nh).put("datastores", nd);
  }

  public ObjectNode hostView(Store.Rec h) {
    JsonNode i = h.info; Store.Point p = h.last();
    boolean stale = System.currentTimeMillis() - h.lastSeen > offlineMs();
    String conn = Json.str(i.path("connection"));
    String status = stale ? "stale" : !"connected".equals(conn) ? (conn.isEmpty() ? "disconnected" : conn) : Json.truthy(i.path("maintenance")) ? "maintenance" : "ok";
    ObjectNode o = Json.copy(i);
    o.put("status", status).put("lastSeen", h.lastSeen)
        .put("cpu", p == null ? 0 : p.cpu).put("mem", p == null ? 0 : p.mem).put("netRx", p == null ? 0 : p.rx).put("netTx", p == null ? 0 : p.tx)
        .put("diskRead", p == null ? 0 : p.rd).put("diskWrite", p == null ? 0 : p.wr)
        .put("cpuUsedMhz", Json.num(h.latest.path("cpuUsedMhz"))).put("memUsed", Json.num(h.latest.path("memUsed")));
    return o;
  }

  public ObjectNode dsView(Store.Rec d) {
    JsonNode l = d.latest; Store.Point p = d.last();
    double cap = Json.num(l.path("capacity"));
    ObjectNode o = Json.copy(d.info);
    o.put("lastSeen", d.lastSeen).put("stale", System.currentTimeMillis() - d.lastSeen > offlineMs())
        .put("capacity", cap).put("free", Json.num(l.path("free"))).put("used", Json.num(l.path("used"))).put("provisioned", Json.num(l.path("provisioned")))
        .put("percent", p == null ? 0 : p.disk).put("overProvisioned", cap > 0 ? Math.round(Json.num(l.path("provisioned")) / cap * 1000) / 10.0 : 0);
    return o;
  }

  List<ObjectNode> filter(Store store, Ctx c, UnaryOperator<ObjectNode> unused, Function<Store.Rec, ObjectNode> view) {
    List<String> sites = new ArrayList<>();
    for (String s : c.q("site").split(",")) if (!s.trim().isEmpty()) sites.add(s.trim());
    String q = c.q("q").toLowerCase(), status = c.q("status");
    List<ObjectNode> list = new ArrayList<>();
    synchronized (store) { for (Store.Rec r : store.vms.values()) list.add(view.apply(r)); }
    list.removeIf((x) -> !sites.isEmpty() && !sites.contains(Json.str(x.path("site"))));
    if (!q.isEmpty()) list.removeIf((x) -> !(Json.str(x.path("name")) + " " + Json.str(x.path("cluster")) + " " + Json.str(x.path("datacenter")) + " " + Json.str(x.path("type")) + " " + Json.str(x.path("model"))).toLowerCase().contains(q));
    if (!status.isEmpty()) list.removeIf((x) -> "problem".equals(status) ? "ok".equals(Json.str(x.path("status"))) && !Json.truthy(x.path("stale")) : !status.equals(Json.str(x.path("status"))));
    String sort = c.q("sort").isEmpty() ? "name" : c.q("sort");
    boolean desc = sort.startsWith("-"); String key = sort.replaceFirst("^-", "");
    list.sort((a, b) -> {
      JsonNode x = a.path(key), y = b.path(key);
      int r = x.isNumber() ? Double.compare(Json.num(y), Json.num(x))
          : NaturalOrder.compare(Json.missing(x) ? Json.str(a.path("name")) : Json.str(x), Json.missing(y) ? Json.str(b.path("name")) : Json.str(y));
      return desc ? -r : r;
    });
    return list;
  }

  /** @return true nếu đã xử lý. need("read") đã được bọc thành quyền staff ở router. */
  public boolean handle(Ctx c, Function<String, Auth.Who> need) {
    List<String> r = c.seg.subList(2, c.seg.size());
    if (r.size() >= 2 && "hosts".equals(r.get(0)) && "report".equals(r.get(1)) && "POST".equals(c.method)) {
      if (need.apply("ingest") == null) return true;
      c.json(200, ingest(c.jsonBody(20 * 1024 * 1024)).put("ok", true));
      return true;
    }
    if (!"GET".equals(c.method) || r.isEmpty()) return false;
    if (!"hosts".equals(r.get(0)) && !"datastores".equals(r.get(0))) return false;
    Auth.Who who = need.apply("read");
    if (who == null) return true;
    Function<String, Boolean> siteOk = (s) -> who.sites == null || who.sites.contains(s);
    if ("hosts".equals(r.get(0))) {
      if (r.size() == 1) {
        List<ObjectNode> list = filter(hosts, c, null, this::hostView);
        list.removeIf((x) -> !siteOk.apply(Json.str(x.path("site"))));
        c.json(200, Json.obj().put("total", list.size()).set("data", Json.arr(list)));
        return true;
      }
      boolean isMetrics = "metrics".equals(r.get(r.size() - 1)) && r.size() > 3;
      String id = r.get(1) + "/" + String.join("/", r.subList(2, isMetrics ? r.size() - 1 : r.size()));
      Store.Rec h = hosts.vms.get(id);
      if (h == null || !siteOk.apply(Json.str(h.info.path("site")))) { c.error(404, "Không tìm thấy host"); return true; }
      if (isMetrics) {
        double ret = Json.num(config.get().path("retentionDays")); if (ret <= 0) ret = 7;
        long[] w = Store.window(c.q("from"), c.q("to"), c.q("range"), ret);
        ObjectNode s = hosts.series(h, w[0], w[1]); s.put("id", id);
        c.json(200, s); return true;
      }
      ObjectNode view = hostView(h);
      ArrayNode dss = Json.arr();
      synchronized (ds) {
        for (Store.Rec d : ds.vms.values()) {
          ObjectNode dv = dsView(d);
          if (Json.str(dv.path("site")).equals(Json.str(view.path("site")))) for (JsonNode n : dv.path("hostNames")) if (n.asText().equals(Json.str(view.path("name")))) { dss.add(dv); break; }
        }
      }
      view.set("datastores", dss);
      c.json(200, view); return true;
    }
    List<ObjectNode> list = filter(ds, c, null, this::dsView);
    list.removeIf((x) -> !siteOk.apply(Json.str(x.path("site"))));
    c.json(200, Json.obj().put("total", list.size()).set("data", Json.arr(list)));
    return true;
  }

  public String prometheus(UnaryOperator<String> esc) {
    StringBuilder out = new StringBuilder();
    List<ObjectNode> hs = new ArrayList<>();
    synchronized (hosts) { for (Store.Rec h : hosts.vms.values()) hs.add(hostView(h)); }
    Object[][] hm = {
        {"vmhost_up", "Host ESXi kết nối và gửi số liệu", (Function<ObjectNode, Double>) (h) -> "ok".equals(h.path("status").asText()) || "maintenance".equals(h.path("status").asText()) ? 1.0 : 0.0},
        {"vmhost_maintenance", "Host đang ở chế độ bảo trì", (Function<ObjectNode, Double>) (h) -> Json.truthy(h.path("maintenance")) ? 1.0 : 0.0},
        {"vmhost_cpu_usage_percent", "CPU host %", (Function<ObjectNode, Double>) (h) -> Json.num(h.path("cpu"))},
        {"vmhost_memory_usage_percent", "RAM host %", (Function<ObjectNode, Double>) (h) -> Json.num(h.path("mem"))},
        {"vmhost_memory_total_bytes", "Tổng RAM host", (Function<ObjectNode, Double>) (h) -> Json.num(h.path("memTotal"))},
        {"vmhost_network_receive_bytes_per_second", "Network in", (Function<ObjectNode, Double>) (h) -> Json.num(h.path("netRx"))},
        {"vmhost_network_transmit_bytes_per_second", "Network out", (Function<ObjectNode, Double>) (h) -> Json.num(h.path("netTx"))},
        {"vmhost_vms", "Số VM trên host", (Function<ObjectNode, Double>) (h) -> Json.num(h.path("vms"))},
        {"vmhost_vms_running", "Số VM đang chạy trên host", (Function<ObjectNode, Double>) (h) -> Json.num(h.path("vmsRunning"))},
    };
    for (Object[] m : hm) {
      out.append("# HELP ").append(m[0]).append(' ').append(m[1]).append("\n# TYPE ").append(m[0]).append(" gauge\n");
      @SuppressWarnings("unchecked") Function<ObjectNode, Double> fn = (Function<ObjectNode, Double>) m[2];
      for (ObjectNode h : hs) out.append(m[0]).append("{site=\"").append(esc.apply(Json.str(h.path("site")))).append("\",host=\"").append(esc.apply(Json.str(h.path("name"))))
          .append("\",cluster=\"").append(esc.apply(Json.str(h.path("cluster")))).append("\"} ").append(Json.numStr(fn.apply(h))).append('\n');
    }
    List<ObjectNode> dl = new ArrayList<>();
    synchronized (ds) { for (Store.Rec d : ds.vms.values()) dl.add(dsView(d)); }
    Object[][] dm = {
        {"vmdatastore_capacity_bytes", "Dung lượng datastore", (Function<ObjectNode, Double>) (d) -> Json.num(d.path("capacity"))},
        {"vmdatastore_free_bytes", "Còn trống", (Function<ObjectNode, Double>) (d) -> Json.num(d.path("free"))},
        {"vmdatastore_usage_percent", "Đã dùng %", (Function<ObjectNode, Double>) (d) -> Json.num(d.path("percent"))},
        {"vmdatastore_provisioned_percent", "Đã cấp phát / dung lượng %", (Function<ObjectNode, Double>) (d) -> Json.num(d.path("overProvisioned"))},
        {"vmdatastore_accessible", "Datastore truy cập được", (Function<ObjectNode, Double>) (d) -> Json.truthy(d.path("accessible")) ? 1.0 : 0.0},
    };
    for (Object[] m : dm) {
      out.append("# HELP ").append(m[0]).append(' ').append(m[1]).append("\n# TYPE ").append(m[0]).append(" gauge\n");
      @SuppressWarnings("unchecked") Function<ObjectNode, Double> fn = (Function<ObjectNode, Double>) m[2];
      for (ObjectNode d : dl) out.append(m[0]).append("{site=\"").append(esc.apply(Json.str(d.path("site")))).append("\",datastore=\"").append(esc.apply(Json.str(d.path("name"))))
          .append("\",type=\"").append(esc.apply(Json.str(d.path("type")))).append("\"} ").append(Json.numStr(fn.apply(d))).append('\n');
    }
    return out.toString();
  }
}
