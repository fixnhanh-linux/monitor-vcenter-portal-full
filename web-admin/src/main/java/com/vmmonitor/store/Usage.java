package com.vmmonitor.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vmmonitor.util.Json;
import com.vmmonitor.util.Log;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Thống kê mức dùng theo ngày cho báo cáo tháng (giữ 13 tháng), cùng định dạng file với bản Node:
 * data/usage/YYYY-MM.json { meta: {vmId: {site, name, cores, memTotal, diskTotal}}, days: {"DD": {vmId: [n, cpuSum, cpuMax, memSum, memMax, diskMax, rxBytes, txBytes, onSec, lastTs]}} }
 */
public class Usage {
  static final int N = 0, CPU = 1, CPUMAX = 2, MEM = 3, MEMMAX = 4, DISK = 5, RX = 6, TX = 7, ON = 8, LAST = 9;
  static final double MAX_GAP = 600;
  private final Path dir;
  private final int keepMonths;
  private final Map<String, Month> months = new LinkedHashMap<>();

  static final class Month { ObjectNode meta = Json.obj(); ObjectNode days = Json.obj(); boolean dirty; }

  public Usage(Path dir) { this.dir = dir; this.keepMonths = 13; }

  public static String monthKey(LocalDate d) { return String.format("%04d-%02d", d.getYear(), d.getMonthValue()); }
  static LocalDateTime local(long ts) { return LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ts), ZoneId.systemDefault()); }
  static String pad(int n) { return String.format("%02d", n); }

  private Path file(String m) { return dir.resolve(m + ".json"); }

  private synchronized Month month(String m, boolean create) {
    Month mo = months.get(m);
    if (mo == null) {
      JsonNode n = null;
      try { n = Json.M.readTree(Files.readString(file(m), StandardCharsets.UTF_8)); } catch (Exception ignored) {}
      if (n == null && !create) return null;
      mo = new Month();
      if (n != null) { if (n.path("meta").isObject()) mo.meta = (ObjectNode) n.get("meta"); if (n.path("days").isObject()) mo.days = (ObjectNode) n.get("days"); }
      months.put(m, mo);
    }
    return mo;
  }

  private static ArrayNode slot(ObjectNode day, String id) {
    JsonNode a = day.get(id);
    if (a instanceof ArrayNode an && an.size() >= 10) return an;
    ArrayNode n = day.putArray(id);
    for (int i = 0; i < 10; i++) n.add(0);
    return n;
  }
  private static double g(ArrayNode a, int i) { return a.get(i).asDouble(); }
  private static void s(ArrayNode a, int i, double v) { if (v == Math.rint(v) && Math.abs(v) < 1e15) a.set(i, (long) v); else a.set(i, v); }

  private static ObjectNode meta(ObjectNode info, ObjectNode latest) {
    return Json.obj().put("site", Json.str(info.path("site"))).put("name", Json.str(info.path("name"))).put("cores", Json.num(info.path("cores")))
        .put("memTotal", Json.num(latest.path("mem").path("total"))).put("diskTotal", Json.num(latest.path("disk").path("total")));
  }

  /** Ghi nhận một lần gửi số liệu của VM. */
  public synchronized void add(String id, ObjectNode info, ObjectNode latest, Store.Point p) {
    LocalDateTime d = local(p.ts);
    Month mo = month(monthKey(d.toLocalDate()), true);
    String dd = pad(d.getDayOfMonth());
    ObjectNode day = mo.days.path(dd).isObject() ? (ObjectNode) mo.days.get(dd) : mo.days.putObject(dd).put("__live", 1);
    ArrayNode a = slot(day, id);
    boolean on = !Json.truthy(info.path("power")) || "on".equals(Json.str(info.path("power")));
    double dt = g(a, LAST) > 0 ? Math.min((p.ts - g(a, LAST)) / 1000.0, MAX_GAP) : 0;
    if (on) {
      s(a, N, g(a, N) + 1); s(a, CPU, g(a, CPU) + p.cpu); s(a, MEM, g(a, MEM) + p.mem);
      s(a, CPUMAX, Math.max(g(a, CPUMAX), p.cpu)); s(a, MEMMAX, Math.max(g(a, MEMMAX), p.mem));
      if (dt > 0) { s(a, ON, g(a, ON) + dt); s(a, RX, g(a, RX) + p.rx * dt); s(a, TX, g(a, TX) + p.tx * dt); }
    }
    JsonNode mounts = latest.path("disk").path("mounts");
    double dmax = p.disk;
    if (mounts.size() > 0) { dmax = 0; for (JsonNode m : mounts) dmax = Math.max(dmax, Json.num(m.path("percent"))); }
    s(a, DISK, Math.max(g(a, DISK), dmax));
    s(a, LAST, p.ts);
    mo.meta.set(id, meta(info, latest));
    mo.dirty = true;
  }

  /** Bù các ngày chưa có số liệu từ rollup 5 phút (một lần khi khởi động). */
  public synchronized int backfill(Store store) {
    int days = 0;
    for (Map.Entry<String, Store.Rec> en : store.vms.entrySet()) {
      String id = en.getKey(); Store.Rec vm = en.getValue();
      for (double[] p : vm.rollup) {
        LocalDateTime d = local((long) p[0]);
        Month mo = month(monthKey(d.toLocalDate()), true);
        String dd = pad(d.getDayOfMonth());
        if (mo.days.path(dd).path("__live").asInt() == 1) continue;
        ObjectNode day = mo.days.path(dd).isObject() ? (ObjectNode) mo.days.get(dd) : mo.days.putObject(dd);
        if (!day.has("__backfill")) { day.put("__backfill", 1); days++; }
        ArrayNode a = slot(day, id);
        if (g(a, LAST) >= p[0]) continue;
        double cpu = p[1], mem = p[2], disk = p[3], rx = p[4], tx = p[5], dt = 300;
        if (cpu > 0 || mem > 0) {
          s(a, N, g(a, N) + 1); s(a, CPU, g(a, CPU) + cpu); s(a, MEM, g(a, MEM) + mem);
          s(a, CPUMAX, Math.max(g(a, CPUMAX), cpu)); s(a, MEMMAX, Math.max(g(a, MEMMAX), mem));
          s(a, ON, g(a, ON) + dt); s(a, RX, g(a, RX) + rx * dt); s(a, TX, g(a, TX) + tx * dt);
        }
        s(a, DISK, Math.max(g(a, DISK), disk));
        s(a, LAST, p[0]);
        if (!mo.meta.has(id)) mo.meta.set(id, meta(vm.info, vm.latest));
        mo.dirty = true;
      }
    }
    LocalDate now = LocalDate.now();
    Month cur = month(monthKey(now), true);
    String dd = pad(now.getDayOfMonth());
    (cur.days.path(dd).isObject() ? (ObjectNode) cur.days.get(dd) : cur.days.putObject(dd)).put("__live", 1);
    return days;
  }

  public synchronized void save() {
    try {
      Files.createDirectories(dir);
      for (Map.Entry<String, Month> en : months.entrySet()) {
        Month mo = en.getValue();
        if (!mo.dirty) continue;
        Path tmp = file(en.getKey()).resolveSibling(en.getKey() + ".json.tmp");
        Files.writeString(tmp, Json.stringify(Json.obj().<ObjectNode>set("meta", mo.meta).set("days", mo.days)), StandardCharsets.UTF_8);
        Files.move(tmp, file(en.getKey()), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        mo.dirty = false;
      }
      String cur = monthKey(LocalDate.now());
      months.entrySet().removeIf((e) -> !e.getKey().equals(cur) && !e.getValue().dirty);
      List<String> files = list();
      for (int i = 0; i < files.size() - keepMonths; i++) Files.deleteIfExists(file(files.get(i)));
    } catch (Exception e) { Log.error("Lưu báo cáo lỗi: " + e.getMessage()); }
  }

  /** Các tháng có số liệu, cũ -> mới. */
  public synchronized List<String> list() {
    Set<String> out = new TreeSet<>(months.keySet());
    try (var st = Files.list(dir)) { st.map((p) -> p.getFileName().toString()).filter((f) -> f.matches("\\d{4}-\\d{2}\\.json")).forEach((f) -> out.add(f.substring(0, 7))); }
    catch (Exception ignored) {}
    return new ArrayList<>(out);
  }

  /** Bộ lọc báo cáo. */
  public record Filter(String site, String customer, Predicate<ObjectNode> allow) {}

  /** Báo cáo tháng: {month, days, from, to, customers[], vms[]} */
  public synchronized ObjectNode report(String m, Function<ObjectNode, String> customerOf, Filter f) {
    ObjectNode out = Json.obj().put("month", m);
    Month mo = month(m, false);
    if (mo == null) { out.put("days", 0); out.putArray("customers"); out.putArray("vms"); return out; }
    Map<String, double[]> per = new LinkedHashMap<>(); // n cpu cpuMax mem memMax disk rx tx on days
    List<String> dayKeys = new ArrayList<>(); mo.days.fieldNames().forEachRemaining(dayKeys::add); java.util.Collections.sort(dayKeys);
    List<String> withData = new ArrayList<>();
    for (String dd : dayKeys) {
      boolean any = false;
      for (Iterator<Map.Entry<String, JsonNode>> it = mo.days.get(dd).fields(); it.hasNext(); ) {
        Map.Entry<String, JsonNode> e = it.next();
        if (e.getKey().startsWith("__") || !e.getValue().isArray()) continue;
        any = true;
        ArrayNode a = (ArrayNode) e.getValue();
        double[] v = per.computeIfAbsent(e.getKey(), (k) -> new double[10]);
        v[0] += g(a, N); v[1] += g(a, CPU); v[3] += g(a, MEM); v[2] = Math.max(v[2], g(a, CPUMAX)); v[4] = Math.max(v[4], g(a, MEMMAX));
        v[5] = Math.max(v[5], g(a, DISK)); v[6] += g(a, RX); v[7] += g(a, TX); v[8] += g(a, ON); v[9]++;
      }
      if (any) withData.add(dd);
    }
    List<ObjectNode> vms = new ArrayList<>();
    for (Map.Entry<String, double[]> en : per.entrySet()) {
      String id = en.getKey(); double[] v = en.getValue();
      JsonNode meta = mo.meta.path(id);
      String site = Json.truthy(meta.path("site")) ? Json.str(meta.path("site")) : id.split("/")[0];
      String name = Json.truthy(meta.path("name")) ? Json.str(meta.path("name")) : id.substring(Math.min(id.length(), site.length() + 1));
      String customer = customerOf.apply(Json.obj().put("id", id).put("site", site).put("name", name));
      if (customer == null) customer = "";
      if (f != null) {
        if (!f.site().isEmpty() && !site.equals(f.site())) continue;
        if (!f.customer().isEmpty() && ("(none)".equals(f.customer()) ? !customer.isEmpty() : !customer.equals(f.customer()))) continue;
      }
      ObjectNode row = Json.obj().put("site", site).put("customer", customer).put("name", name)
          .put("cores", Json.num(meta.path("cores"))).put("memTotal", Json.num(meta.path("memTotal"))).put("diskTotal", Json.num(meta.path("diskTotal")))
          .put("cpuAvg", v[0] > 0 ? Json.r1(v[1] / v[0]) : 0).put("cpuMax", Json.r1(v[2])).put("memAvg", v[0] > 0 ? Json.r1(v[3] / v[0]) : 0).put("memMax", Json.r1(v[4]))
          .put("diskMax", Json.r1(v[5])).put("rxBytes", Math.round(v[6])).put("txBytes", Math.round(v[7])).put("onHours", Json.r1(v[8] / 3600)).put("days", (int) v[9]).put("samples", (long) v[0]);
      if (f != null && f.allow() != null && !f.allow().test(row)) continue;
      vms.add(row);
    }
    vms.sort((a, b) -> {
      String ca = a.get("customer").asText().isEmpty() ? "~" : a.get("customer").asText(), cb = b.get("customer").asText().isEmpty() ? "~" : b.get("customer").asText();
      int c = ca.compareTo(cb); if (c != 0) return c;
      c = a.get("site").asText().compareTo(b.get("site").asText()); if (c != 0) return c;
      return a.get("name").asText().compareTo(b.get("name").asText());
    });
    Map<String, double[]> cm = new LinkedHashMap<>(); // vms cores mem disk cpuW memW w cpuMax memMax diskMax rx tx on
    Map<String, Set<String>> cs = new LinkedHashMap<>();
    for (ObjectNode v : vms) {
      String k = v.get("customer").asText();
      double[] c = cm.computeIfAbsent(k, (x) -> new double[13]);
      cs.computeIfAbsent(k, (x) -> new LinkedHashSet<>()).add(v.get("site").asText());
      double smp = v.get("samples").asDouble();
      c[0]++; c[1] += v.get("cores").asDouble(); c[2] += v.get("memTotal").asDouble(); c[3] += v.get("diskTotal").asDouble();
      c[4] += v.get("cpuAvg").asDouble() * smp; c[5] += v.get("memAvg").asDouble() * smp; c[6] += smp;
      c[7] = Math.max(c[7], v.get("cpuMax").asDouble()); c[8] = Math.max(c[8], v.get("memMax").asDouble()); c[9] = Math.max(c[9], v.get("diskMax").asDouble());
      c[10] += v.get("rxBytes").asDouble(); c[11] += v.get("txBytes").asDouble(); c[12] += v.get("onHours").asDouble();
    }
    List<ObjectNode> customers = new ArrayList<>();
    for (Map.Entry<String, double[]> en : cm.entrySet()) {
      double[] c = en.getValue();
      customers.add(Json.obj().put("customer", en.getKey()).put("vms", (int) c[0]).put("cores", c[1]).put("memTotal", c[2]).put("diskTotal", c[3])
          .put("cpuMax", c[7]).put("memMax", c[8]).put("diskMax", c[9]).put("rxBytes", c[10]).put("txBytes", c[11]).put("onHours", Json.r1(c[12]))
          .put("sites", String.join(", ", new TreeSet<>(cs.get(en.getKey())))).put("cpuAvg", c[6] > 0 ? Json.r1(c[4] / c[6]) : 0).put("memAvg", c[6] > 0 ? Json.r1(c[5] / c[6]) : 0));
    }
    customers.sort((a, b) -> {
      boolean ea = a.get("customer").asText().isEmpty(), eb = b.get("customer").asText().isEmpty();
      if (ea != eb) return ea ? 1 : -1;
      return a.get("customer").asText().compareTo(b.get("customer").asText());
    });
    out.put("days", withData.size());
    if (!withData.isEmpty()) { out.put("from", m + "-" + withData.get(0)); out.put("to", m + "-" + withData.get(withData.size() - 1)); }
    else { out.putNull("from"); out.putNull("to"); }
    ArrayNode ca = out.putArray("customers"); customers.forEach(ca::add);
    ArrayNode va = out.putArray("vms"); vms.forEach(va::add);
    return out;
  }

  // ---------- xuất CSV / HTML ----------
  static final double GB = 1073741824.0;
  record Col(String key, String label, Function<JsonNode, String> fmt) {}
  static String gb(JsonNode v) { return Json.numStr(Math.round(v.asDouble() / GB * 10) / 10.0); }
  static String plain(JsonNode v) { return Json.str(v); }
  static String chua(JsonNode v) { String s = Json.str(v); return s.isEmpty() ? "(chưa gán)" : s; }
  static final List<Col> CUSTOMER_COLS = List.of(new Col("customer", "Khách hàng", Usage::chua), new Col("sites", "Site", Usage::plain), new Col("vms", "Số VM", Usage::plain),
      new Col("cores", "vCPU", Usage::plain), new Col("memTotal", "RAM (GB)", Usage::gb), new Col("diskTotal", "Disk cấp (GB)", Usage::gb), new Col("cpuAvg", "CPU TB (%)", Usage::plain),
      new Col("cpuMax", "CPU đỉnh (%)", Usage::plain), new Col("memAvg", "RAM TB (%)", Usage::plain), new Col("memMax", "RAM đỉnh (%)", Usage::plain),
      new Col("diskMax", "Disk đầy nhất (%)", Usage::plain), new Col("rxBytes", "Network in (GB)", Usage::gb), new Col("txBytes", "Network out (GB)", Usage::gb),
      new Col("onHours", "Giờ chạy (tổng VM)", Usage::plain));
  static final List<Col> VM_COLS = List.of(new Col("customer", "Khách hàng", Usage::chua), new Col("site", "Site", Usage::plain), new Col("name", "VM", Usage::plain),
      new Col("cores", "vCPU", Usage::plain), new Col("memTotal", "RAM (GB)", Usage::gb), new Col("diskTotal", "Disk cấp (GB)", Usage::gb), new Col("cpuAvg", "CPU TB (%)", Usage::plain),
      new Col("cpuMax", "CPU đỉnh (%)", Usage::plain), new Col("memAvg", "RAM TB (%)", Usage::plain), new Col("memMax", "RAM đỉnh (%)", Usage::plain),
      new Col("diskMax", "Disk đầy nhất (%)", Usage::plain), new Col("rxBytes", "Network in (GB)", Usage::gb), new Col("txBytes", "Network out (GB)", Usage::gb),
      new Col("onHours", "Giờ chạy", Usage::plain), new Col("days", "Số ngày có số liệu", Usage::plain));

  static String cell(String s) { return s.matches("(?s).*[\",\\n;].*") ? "\"" + s.replace("\"", "\"\"") + "\"" : s; }
  static String csv(List<Col> cols, JsonNode rows) {
    StringBuilder sb = new StringBuilder("﻿");
    List<String> h = new ArrayList<>(); for (Col c : cols) h.add(cell(c.label())); sb.append(String.join(",", h));
    for (JsonNode r : rows) {
      List<String> l = new ArrayList<>(); for (Col c : cols) l.add(cell(c.fmt().apply(r.path(c.key()))));
      sb.append("\r\n").append(String.join(",", l));
    }
    return sb.toString();
  }
  public static String customersCsv(ObjectNode rep) { return csv(CUSTOMER_COLS, rep.path("customers")); }
  public static String vmsCsv(ObjectNode rep) { return csv(VM_COLS, rep.path("vms")); }

  static String escH(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
  /** Bảng HTML gọn cho email. */
  public static String customersHtml(ObjectNode rep, String portal) {
    StringBuilder th = new StringBuilder();
    for (Col c : CUSTOMER_COLS) th.append("<th style=\"text-align:left;padding:4px 8px;border-bottom:2px solid #ccc;font-size:12px\">").append(escH(c.label())).append("</th>");
    StringBuilder rows = new StringBuilder(); int i = 0;
    for (JsonNode r : rep.path("customers")) {
      rows.append("<tr style=\"background:").append(i++ % 2 == 1 ? "#f6f7f9" : "#fff").append("\">");
      for (Col c : CUSTOMER_COLS) rows.append("<td style=\"padding:3px 8px;font-size:12px\">").append(escH(c.fmt().apply(r.path(c.key())))).append("</td>");
      rows.append("</tr>");
    }
    String m = Json.str(rep.path("month"));
    return "<div style=\"font-family:Segoe UI,Arial,sans-serif;color:#1f2937\"><h2 style=\"margin:0 0 4px\">Báo cáo mức dùng tháng " + escH(m) + "</h2>"
        + "<p style=\"margin:0 0 12px;color:#6b7280;font-size:13px\">" + rep.path("days").asInt() + " ngày có số liệu (" + escH(Json.str(rep.path("from"))) + " → " + escH(Json.str(rep.path("to"))) + ") · "
        + rep.path("customers").size() + " khách hàng · " + rep.path("vms").size() + " VM. Chi tiết từng VM trong file CSV đính kèm."
        + (portal.isEmpty() ? "" : " Xem trên portal: <a href=\"" + escH(portal) + "/report?month=" + escH(m) + "\">" + escH(portal) + "/report</a>") + "</p>"
        + "<table style=\"border-collapse:collapse\"><tr>" + th + "</tr>" + rows + "</table></div>";
  }
}
