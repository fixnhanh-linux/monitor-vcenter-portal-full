package com.vmmonitor.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vmmonitor.util.Json;
import com.vmmonitor.util.Log;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

/** Ghi số liệu sang InfluxDB (line protocol, gzip, gom lô, gửi lại khi lỗi). Định dạng "portal", "telegraf" (vsphere_*) hoặc "both". */
public class Influx {
  private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private Supplier<ObjectNode> config;
  private final ScheduledExecutorService timers;
  private final LinkedList<String> buffer = new LinkedList<>();
  private ScheduledFuture<?> timer;
  private volatile boolean sending;
  private long written, failed, dropped, lastOk, lastErrorAt, lastErrLog;
  private String lastError = "";

  public Influx(Supplier<ObjectNode> config, ScheduledExecutorService timers) { this.config = config; this.timers = timers; }

  JsonNode cfg() { return config.get().path("influxdb"); }
  public boolean enabled() { return Json.truthy(cfg().path("enabled")) && Json.truthy(cfg().path("url")); }
  String schema() { String s = Json.str(cfg().path("schema")); return "telegraf".equals(s) || "both".equals(s) ? s : "portal"; }

  // Line protocol: chỉ escape dấu phẩy, bằng, cách. Bỏ "\" cuối (vd ổ "C:\"), đổi "\" trước ký tự đặc biệt thành "/".
  static String escTag(String s) {
    String r = (s == null ? "" : s).replaceAll("[\\r\\n]+", " ").replaceAll("\\\\+$", "").replaceAll("\\\\(?=[,= ])", "/").replaceAll("([,= ])", "\\\\$1");
    return r.isEmpty() ? "-" : r;
  }
  static String escTag(JsonNode n) { return escTag(Json.str(n)); }
  static String escMeas(String s) { return s.replaceAll("([, \\\\])", "\\\\$1"); }
  static String f(double v) { return Json.numStr(Math.round(v * 1000) / 1000.0); }
  static String f(JsonNode v) { return f(Json.num(v)); }
  static String i(double v) { return Math.round(v) + "i"; }
  static String i(JsonNode v) { return i(Json.num(v)); }
  static String kb(double bytes) { return i(bytes / 1024); }
  static String kb(JsonNode v) { return kb(Json.num(v)); }
  /** tag set kiểu Telegraf: bỏ tag rỗng, sắp theo tên */
  static String tagSet(Map<String, String> o) {
    List<String> out = new ArrayList<>();
    new TreeMap<>(o).forEach((k, v) -> { if (v != null && !v.isEmpty()) out.add(k + "=" + escTag(v)); });
    return String.join(",", out);
  }
  static Map<String, String> with(Map<String, String> base, String k, String v) { Map<String, String> m = new LinkedHashMap<>(base); m.put(k, v); return m; }
  String meas(String suffix) { String m = Json.truthy(cfg().path("measurement")) ? Json.str(cfg().path("measurement")) : "vm"; return escMeas(m + suffix); }

  /** Một điểm của VM (dạng phẳng từ view()). */
  public void addVm(ObjectNode v, JsonNode mounts, long ts) {
    if (!enabled()) return;
    JsonNode c = cfg();
    long t = ts / 1000;
    if (!"portal".equals(schema())) telegrafVm(v, t);
    if ("telegraf".equals(schema())) return;
    String tags = "site=" + escTag(v.path("site")) + ",vm=" + escTag(v.path("name")) + ",cluster=" + escTag(v.path("cluster")) + ",host=" + escTag(v.path("host")) + ",source=" + escTag(v.path("source"))
        + (Json.notFalse(c.path("tagIp")) && Json.truthy(v.path("ip")) ? ",ip=" + escTag(v.path("ip")) : "");
    String fields = String.join(",", "cpu=" + f(v.path("cpu")), "mem=" + f(v.path("mem")), "disk=" + f(v.path("disk")), "disk_max=" + f(v.path("diskMax")),
        "net_rx=" + f(v.path("netRx")), "net_tx=" + f(v.path("netTx")), "disk_read=" + f(v.path("diskRead")), "disk_write=" + f(v.path("diskWrite")),
        "mem_total=" + i(v.path("memTotal")), "mem_used=" + i(v.path("memUsed")), "disk_total=" + i(v.path("diskTotal")), "disk_used=" + i(v.path("diskUsed")),
        "cores=" + i(v.path("cores")), "up=" + ("online".equals(Json.str(v.path("status"))) ? 1 : 0) + "i", "power_on=" + ("on".equals(Json.str(v.path("power"))) ? 1 : 0) + "i",
        "alerts=" + i(v.path("alerts")));
    push(meas("") + "," + tags + " " + fields + " " + t);
    if (Json.notFalse(c.path("mounts")) && mounts != null) {
      for (JsonNode m : mounts) {
        double avail = m.has("avail") ? Json.num(m.path("avail")) : Json.num(m.path("total")) - Json.num(m.path("used"));
        push(meas("_disk") + ",site=" + escTag(v.path("site")) + ",vm=" + escTag(v.path("name")) + ",mount=" + escTag(m.path("mount"))
            + " total=" + i(m.path("total")) + ",used=" + i(m.path("used")) + ",avail=" + i(avail) + ",percent=" + f(m.path("percent")) + " " + t);
      }
    }
  }

  public void addHost(ObjectNode h) {
    if (!enabled()) return;
    long t = System.currentTimeMillis() / 1000;
    if (!"portal".equals(schema())) telegrafHost(h, t);
    if ("telegraf".equals(schema())) return;
    push(meas("_host") + ",site=" + escTag(h.path("site")) + ",host=" + escTag(h.path("name")) + ",cluster=" + escTag(h.path("cluster")) + ",vcenter=" + escTag(h.path("vcenter")) + " "
        + "cpu=" + f(h.path("cpu")) + ",mem=" + f(h.path("mem")) + ",cpu_used_mhz=" + i(h.path("cpuUsedMhz")) + ",cpu_total_mhz=" + i(h.path("cpuTotalMhz")) + ","
        + "mem_used=" + i(h.path("memUsed")) + ",mem_total=" + i(h.path("memTotal")) + ",net_rx=" + f(h.path("netRx")) + ",net_tx=" + f(h.path("netTx")) + ","
        + "disk_read=" + f(h.path("diskRead")) + ",disk_write=" + f(h.path("diskWrite")) + ",vms=" + i(h.path("vms")) + ",vms_running=" + i(h.path("vmsRunning")) + ","
        + "connected=" + ("connected".equals(Json.str(h.path("connection"))) ? 1 : 0) + "i,maintenance=" + (Json.truthy(h.path("maintenance")) ? 1 : 0) + "i " + t);
  }

  public void addDatastore(ObjectNode d) {
    if (!enabled()) return;
    long t = System.currentTimeMillis() / 1000;
    if (!"portal".equals(schema())) telegrafDatastore(d, t);
    if ("telegraf".equals(schema())) return;
    push(meas("_datastore") + ",site=" + escTag(d.path("site")) + ",datastore=" + escTag(d.path("name")) + ",type=" + escTag(d.path("type")) + " "
        + "capacity=" + i(d.path("capacity")) + ",free=" + i(d.path("free")) + ",used=" + i(d.path("used")) + ",provisioned=" + i(d.path("provisioned")) + ","
        + "percent=" + f(d.path("percent")) + ",accessible=" + (Json.truthy(d.path("accessible")) ? 1 : 0) + "i,hosts=" + i(d.path("hosts")) + ",vms=" + i(d.path("vms")) + " " + t);
  }

  // ---------- định dạng Telegraf vSphere ----------
  void tpush(String line) {
    JsonNode inc = cfg().path("include");
    if (inc.isArray() && inc.size() > 0) {
      String m = line.substring(0, line.indexOf(','));
      boolean ok = false; for (JsonNode x : inc) if (m.equals(x.asText())) ok = true;
      if (!ok) return;
    }
    push(line);
  }
  void telegrafVm(ObjectNode v, long t) {
    if (Json.truthy(v.path("power")) && !"on".equals(Json.str(v.path("power")))) return;
    Map<String, String> base = new LinkedHashMap<>();
    base.put("vcenter", Json.str(v.path("vcenter"))); base.put("dcname", Json.str(v.path("datacenter"))); base.put("clustername", Json.str(v.path("cluster")));
    base.put("esxhostname", Json.str(v.path("host"))); base.put("vmname", Json.str(v.path("name"))); base.put("source", Json.str(v.path("name")));
    base.put("guest", Json.str(v.path("os"))); base.put("site", Json.str(v.path("site"))); base.put("customer", Json.str(v.path("customer")));
    String tags = tagSet(base);
    double memUsed = Json.num(v.path("memUsed")), memTotal = Json.num(v.path("memTotal")), cpuMhz = Json.num(v.path("cpuMhz"));
    tpush("vsphere_vm_cpu," + tagSet(with(base, "cpu", "instance-total")) + " usage_average=" + f(v.path("cpu")) + (cpuMhz > 0 ? ",usagemhz_average=" + i(cpuMhz) : "") + " " + t);
    tpush("vsphere_vm_mem," + tags + " usage_average=" + f(v.path("mem")) + (memUsed > 0 ? ",active_average=" + kb(memUsed) : "") + (memTotal > 0 ? ",granted_average=" + kb(memTotal) : "") + " " + t);
    tpush("vsphere_vm_net," + tagSet(with(base, "interface", "instance-total")) + " bytesRx_average=" + kb(v.path("netRx")) + ",bytesTx_average=" + kb(v.path("netTx"))
        + ",usage_average=" + kb(Json.num(v.path("netRx")) + Json.num(v.path("netTx"))) + " " + t);
    tpush("vsphere_vm_virtualDisk," + tagSet(with(base, "disk", "instance-total")) + " read_average=" + kb(v.path("diskRead")) + ",write_average=" + kb(v.path("diskWrite")) + " " + t);
    if (Json.truthy(v.path("uptime"))) tpush("vsphere_vm_sys," + tags + " uptime_latest=" + i(v.path("uptime")) + " " + t);
  }
  void telegrafHost(ObjectNode h, long t) {
    if (Json.truthy(h.path("connection")) && !"connected".equals(Json.str(h.path("connection")))) return;
    Map<String, String> base = new LinkedHashMap<>();
    base.put("vcenter", Json.str(h.path("vcenter"))); base.put("dcname", Json.str(h.path("datacenter"))); base.put("clustername", Json.str(h.path("cluster")));
    base.put("esxhostname", Json.str(h.path("name"))); base.put("source", Json.str(h.path("name"))); base.put("site", Json.str(h.path("site")));
    String tags = tagSet(base);
    tpush("vsphere_host_cpu," + tagSet(with(base, "cpu", "instance-total")) + " usage_average=" + f(h.path("cpu")) + ",usagemhz_average=" + i(h.path("cpuUsedMhz")) + " " + t);
    tpush("vsphere_host_mem," + tags + " usage_average=" + f(h.path("mem")) + ",consumed_average=" + kb(h.path("memUsed")) + ",totalCapacity_average=" + i(Json.num(h.path("memTotal")) / 1048576) + " " + t);
    tpush("vsphere_host_net," + tagSet(with(base, "interface", "instance-total")) + " bytesRx_average=" + kb(h.path("netRx")) + ",bytesTx_average=" + kb(h.path("netTx"))
        + ",usage_average=" + kb(Json.num(h.path("netRx")) + Json.num(h.path("netTx"))) + " " + t);
    tpush("vsphere_host_disk," + tagSet(with(base, "disk", "instance-total")) + " read_average=" + kb(h.path("diskRead")) + ",write_average=" + kb(h.path("diskWrite")) + " " + t);
    if (Json.truthy(h.path("uptime"))) tpush("vsphere_host_sys," + tags + " uptime_latest=" + i(h.path("uptime")) + " " + t);
  }
  void telegrafDatastore(ObjectNode d, long t) {
    Map<String, String> base = new LinkedHashMap<>();
    base.put("vcenter", Json.str(d.path("vcenter"))); base.put("dcname", Json.str(d.path("datacenter"))); base.put("dsname", Json.str(d.path("name")));
    base.put("source", Json.str(d.path("name"))); base.put("site", Json.str(d.path("site"))); base.put("disk", "instance-total");
    tpush("vsphere_datastore_disk," + tagSet(base) + " capacity_latest=" + kb(d.path("capacity")) + ",used_latest=" + kb(d.path("used")) + ",provisioned_latest=" + kb(d.path("provisioned")) + " " + t);
  }

  /** Sự kiện cảnh báo (firing / resolved). */
  public void addAlert(ObjectNode ev) {
    if (!enabled() || (cfg().path("alerts").isBoolean() && !cfg().path("alerts").booleanValue()) || ev == null) return;
    String text = Json.str(ev.path("text")).replace("\\", "\\\\").replace("\"", "\\\"");
    long ts = ev.has("ts") ? (long) Json.num(ev.path("ts")) : System.currentTimeMillis();
    push(meas("_alert") + ",site=" + escTag(ev.path("site")) + ",vm=" + escTag(ev.path("vm")) + ",metric=" + escTag(ev.path("metric")) + ",severity=" + escTag(ev.path("severity"))
        + ",type=" + escTag(ev.path("type")) + " value=" + f(ev.path("value")) + ",threshold=" + f(ev.path("threshold")) + ",text=\"" + text + "\" " + ts / 1000);
  }

  synchronized void push(String line) {
    double max = Json.num(cfg().path("maxBufferLines")); if (max <= 0) max = 200000;
    if (buffer.size() >= max) { buffer.removeFirst(); dropped++; }
    buffer.add(line);
    if (timer == null) {
      double sec = Json.num(cfg().path("batchSeconds")); if (sec <= 0) sec = 10;
      timer = timers.schedule(this::flush, (long) (sec * 1000), TimeUnit.MILLISECONDS);
    }
  }

  String writeUrl(JsonNode c) {
    String base = Json.str(c.path("url")).replaceAll("/+$", "");
    if ((int) Json.num(c.path("version")) == 1) {
      String q = "db=" + enc(Json.truthy(c.path("database")) ? Json.str(c.path("database")) : "vm_monitor") + "&precision=s";
      if (Json.truthy(c.path("retentionPolicy"))) q += "&rp=" + enc(Json.str(c.path("retentionPolicy")));
      return base + "/write?" + q;
    }
    return base + "/api/v2/write?org=" + enc(Json.str(c.path("org"))) + "&bucket=" + enc(Json.truthy(c.path("bucket")) ? Json.str(c.path("bucket")) : "vm-monitor") + "&precision=s";
  }
  static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

  HttpRequest.Builder request(JsonNode c, byte[] gz) {
    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(writeUrl(c))).timeout(Duration.ofSeconds(15))
        .header("content-type", "text/plain; charset=utf-8").header("content-encoding", "gzip").POST(HttpRequest.BodyPublishers.ofByteArray(gz));
    if ((int) Json.num(c.path("version")) == 1) {
      if (Json.truthy(c.path("username"))) b.header("authorization", "Basic " + Base64.getEncoder().encodeToString((Json.str(c.path("username")) + ":" + Json.str(c.path("password"))).getBytes(StandardCharsets.UTF_8)));
    } else if (Json.truthy(c.path("token"))) b.header("authorization", "Token " + Json.str(c.path("token")));
    return b;
  }
  static byte[] gzip(String s) {
    try { ByteArrayOutputStream bo = new ByteArrayOutputStream(); try (GZIPOutputStream gz = new GZIPOutputStream(bo)) { gz.write(s.getBytes(StandardCharsets.UTF_8)); } return bo.toByteArray(); }
    catch (Exception e) { throw new RuntimeException(e); }
  }

  public void flush() {
    synchronized (this) { timer = null; if (sending || buffer.isEmpty()) return; if (!enabled()) { buffer.clear(); return; } sending = true; }
    try {
      while (true) {
        List<String> lines;
        synchronized (this) { if (buffer.isEmpty()) break; lines = new ArrayList<>(buffer.subList(0, Math.min(5000, buffer.size()))); }
        HttpResponse<String> res = HTTP.send(request(cfg(), gzip(String.join("\n", lines))).build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 300) {
          synchronized (this) { for (int k = 0; k < lines.size() && !buffer.isEmpty(); k++) buffer.removeFirst(); written += lines.size(); lastOk = System.currentTimeMillis(); }
          continue;
        }
        String text = res.body() == null ? "" : res.body().substring(0, Math.min(300, res.body().length()));
        if (res.statusCode() == 400 || res.statusCode() == 422) synchronized (this) { for (int k = 0; k < lines.size() && !buffer.isEmpty(); k++) buffer.removeFirst(); dropped += lines.size(); }
        throw new Exception("HTTP " + res.statusCode() + " " + text);
      }
    } catch (Exception e) {
      synchronized (this) {
        failed++; lastError = String.valueOf(e.getMessage()); lastErrorAt = System.currentTimeMillis();
        if (System.currentTimeMillis() - lastErrLog > 60000) { lastErrLog = System.currentTimeMillis(); Log.info("[influxdb] ghi lỗi (" + buffer.size() + " dòng chờ gửi lại): " + e.getMessage()); }
        if (timer == null) timer = timers.schedule(this::flush, 30, TimeUnit.SECONDS);
      }
    } finally { sending = false; }
  }

  /** Ghi thử 1 điểm với cấu hình cho trước. */
  public ObjectNode testWrite(ObjectNode c) {
    if (!Json.truthy(c.path("url"))) return Json.obj().put("ok", false).put("message", "Chưa nhập URL");
    try {
      HttpResponse<String> res = HTTP.send(request(c, gzip("vmportal_test,source=portal value=1i " + System.currentTimeMillis() / 1000)).timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
      String target = (int) Json.num(c.path("version")) == 1 ? Json.str(c.path("database")) : Json.str(c.path("org")) + "/" + Json.str(c.path("bucket"));
      if (res.statusCode() < 300) return Json.obj().put("ok", true).put("message", "Ghi thử thành công (HTTP " + res.statusCode() + ") vào " + target);
      return Json.obj().put("ok", false).put("message", "HTTP " + res.statusCode() + ": " + res.body().substring(0, Math.min(300, res.body().length())));
    } catch (Exception e) { return Json.obj().put("ok", false).put("message", String.valueOf(e.getMessage())); }
  }

  public synchronized ObjectNode status() {
    JsonNode c = cfg();
    ObjectNode o = Json.obj().put("enabled", enabled());
    if (Json.truthy(c.path("url"))) o.put("url", Json.str(c.path("url")).replaceAll("//[^@/]*@", "//***@"));
    int ver = Json.num(c.path("version")) == 1 ? 1 : 2;
    o.put("version", ver).put("target", ver == 1 ? Json.str(c.path("database")) : Json.str(c.path("org")) + "/" + Json.str(c.path("bucket")))
        .put("pending", buffer.size()).put("written", written).put("failed", failed).put("dropped", dropped).put("lastOk", lastOk).put("lastError", lastError).put("lastErrorAt", lastErrorAt);
    return o;
  }
}
