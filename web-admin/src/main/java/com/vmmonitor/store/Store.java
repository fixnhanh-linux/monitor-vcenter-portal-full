package com.vmmonitor.store;

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

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trạng thái VM (hoặc host / datastore) + lịch sử, lưu xuống đĩa cùng định dạng với bản Node (state.json version 2):
 *  - history: điểm thô, giữ rawPoints điểm gần nhất
 *  - rollup : trung bình theo rollupMinutes phút, mảng gọn [ts, cpu, mem, disk, rx, tx, rd, wr], giữ retentionDays ngày.
 */
public class Store {
  public static final String[] FIELDS = {"cpu", "mem", "disk", "rx", "tx", "rd", "wr"};

  /** Một điểm số liệu. */
  public static final class Point {
    public long ts; public double cpu, mem, disk, rx, tx, rd, wr;
    public Point() {}
    public Point(long ts, double cpu, double mem, double disk, double rx, double tx, double rd, double wr) {
      this.ts = ts; this.cpu = cpu; this.mem = mem; this.disk = disk; this.rx = rx; this.tx = tx; this.rd = rd; this.wr = wr;
    }
    public double get(int i) { return switch (i) { case 0 -> cpu; case 1 -> mem; case 2 -> disk; case 3 -> rx; case 4 -> tx; case 5 -> rd; default -> wr; }; }
    public double get(String f) {
      return switch (f) { case "cpu" -> cpu; case "mem" -> mem; case "disk" -> disk; case "rx" -> rx; case "tx" -> tx; case "rd" -> rd; case "wr" -> wr; default -> 0; };
    }
    public ObjectNode toJson() {
      ObjectNode o = Json.obj(); o.put("ts", ts);
      for (int i = 0; i < FIELDS.length; i++) putNum(o, FIELDS[i], get(i));
      return o;
    }
    static Point of(JsonNode o) {
      return new Point((long) Json.num(o.path("ts")), Json.num(o.path("cpu")), Json.num(o.path("mem")), Json.num(o.path("disk")),
          Json.num(o.path("rx")), Json.num(o.path("tx")), Json.num(o.path("rd")), Json.num(o.path("wr")));
    }
  }

  /** Một đối tượng được theo dõi (VM / host / datastore). */
  public static final class Rec {
    public ObjectNode info = Json.obj();
    public ObjectNode latest = Json.obj();
    public long lastSeen;
    public final ArrayList<Point> history = new ArrayList<>();
    public final ArrayList<double[]> rollup = new ArrayList<>();
    double[] bucketSums; long bucketTs; int bucketN;
    public String wasOnline; // không lưu xuống đĩa
    public Point last() { return history.isEmpty() ? null : history.get(history.size() - 1); }
  }

  public final Path file;
  private final long retentionMs, bucketMs;
  private final int rawPoints;
  public final Map<String, Rec> vms = new LinkedHashMap<>();
  public final List<ObjectNode> alertHistory = new ArrayList<>();
  public volatile boolean dirty = false;

  public Store(Path file, double retentionDays, double rollupMinutes, int rawPoints) {
    this.file = file;
    this.retentionMs = (long) (retentionDays * 86400000L);
    this.bucketMs = (long) (Math.max(1, rollupMinutes) * 60000L);
    this.rawPoints = rawPoints;
  }

  static void putNum(ObjectNode o, String k, double v) {
    if (v == Math.rint(v) && Math.abs(v) < 1e15) o.put(k, (long) v); else o.put(k, v);
  }

  public synchronized int load() {
    try (JsonParser p = new JsonFactory().setCodec(Json.M).createParser(file.toFile())) {
      if (p.nextToken() != JsonToken.START_OBJECT) return 0;
      int version = 0;
      while (p.nextToken() == JsonToken.FIELD_NAME) {
        String k = p.currentName();
        p.nextToken();
        switch (k) {
          case "version" -> version = p.getIntValue();
          case "alertHistory" -> { JsonNode a = p.readValueAsTree(); a.forEach((e) -> { if (e.isObject()) alertHistory.add((ObjectNode) e); }); }
          case "vms" -> {
            if (version != 2) { p.skipChildren(); Log.info(file + ": định dạng cũ (chưa có site) — bỏ danh sách VM cũ, giữ lịch sử cảnh báo"); dirty = true; break; }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
              String id = p.currentName(); p.nextToken();
              JsonNode v = p.readValueAsTree();
              Rec r = new Rec();
              if (v.path("info").isObject()) r.info = (ObjectNode) v.get("info");
              if (v.path("latest").isObject()) r.latest = (ObjectNode) v.get("latest");
              r.lastSeen = (long) Json.num(v.path("lastSeen"));
              for (JsonNode h : v.path("history")) r.history.add(Point.of(h));
              while (r.history.size() > rawPoints) r.history.remove(0);
              for (JsonNode a : v.path("rollup")) {
                double[] x = new double[8];
                if (a.isArray()) for (int i = 0; i < 8; i++) x[i] = Json.num(a.path(i));
                else { x[0] = Json.num(a.path("ts")); for (int i = 0; i < 7; i++) x[i + 1] = Json.num(a.path(FIELDS[i])); }
                r.rollup.add(x);
              }
              vms.put(id, r);
            }
          }
          default -> p.skipChildren();
        }
      }
      prune();
      return vms.size();
    } catch (NoSuchFileException | java.io.FileNotFoundException e) {
      return 0;
    } catch (Exception e) {
      Log.error("Không đọc được " + file + ": " + e.getMessage());
      return 0;
    }
  }

  public synchronized void save() {
    if (!dirty) return;
    long started = System.currentTimeMillis();
    Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
    try {
      Files.createDirectories(file.getParent());
      try (OutputStream os = Files.newOutputStream(tmp); JsonGenerator g = new JsonFactory().setCodec(Json.M).createGenerator(os)) {
        g.writeStartObject();
        g.writeNumberField("version", 2);
        g.writeNumberField("savedAt", System.currentTimeMillis());
        g.writeFieldName("alertHistory"); g.writeStartArray(); for (ObjectNode e : alertHistory) g.writeTree(e); g.writeEndArray();
        g.writeFieldName("vms"); g.writeStartObject();
        for (Map.Entry<String, Rec> en : vms.entrySet()) {
          Rec r = en.getValue();
          g.writeFieldName(en.getKey()); g.writeStartObject();
          g.writeFieldName("history"); g.writeStartArray();
          for (Point pt : r.history) {
            g.writeStartObject(); g.writeNumberField("ts", pt.ts);
            for (int i = 0; i < FIELDS.length; i++) writeNum(g, FIELDS[i], pt.get(i));
            g.writeEndObject();
          }
          g.writeEndArray();
          g.writeFieldName("rollup"); g.writeStartArray();
          for (double[] a : r.rollup) { g.writeStartArray(); g.writeNumber((long) a[0]); for (int i = 1; i < 8; i++) writeNumRaw(g, a[i]); g.writeEndArray(); }
          g.writeEndArray();
          g.writeFieldName("info"); g.writeTree(r.info);
          g.writeFieldName("latest"); g.writeTree(r.latest);
          g.writeNumberField("lastSeen", r.lastSeen);
          g.writeEndObject();
        }
        g.writeEndObject();
        g.writeEndObject();
      }
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      dirty = false;
      long ms = System.currentTimeMillis() - started;
      if (ms > 1000) Log.info("Đã lưu " + vms.size() + " VM trong " + ms + " ms");
    } catch (Exception e) {
      Log.error("Không ghi được " + file + ": " + e.getMessage());
    }
  }

  private static void writeNum(JsonGenerator g, String k, double v) throws java.io.IOException { g.writeFieldName(k); writeNumRaw(g, v); }
  private static void writeNumRaw(JsonGenerator g, double v) throws java.io.IOException {
    if (v == Math.rint(v) && Math.abs(v) < 1e15) g.writeNumber((long) v); else g.writeNumber(v);
  }

  /** Thêm một điểm cho đối tượng, trả về bản ghi. */
  public synchronized Rec add(String id, ObjectNode info, ObjectNode latest, Point point) {
    Rec r = vms.computeIfAbsent(id, (k) -> new Rec());
    r.info = info; r.latest = latest; r.lastSeen = point.ts;
    r.history.add(point);
    while (r.history.size() > rawPoints) r.history.remove(0);
    long slot = Math.floorDiv(point.ts, bucketMs) * bucketMs;
    if (r.bucketSums != null && r.bucketTs != slot) flushBucket(r);
    if (r.bucketSums == null) { r.bucketSums = new double[FIELDS.length]; r.bucketTs = slot; r.bucketN = 0; }
    r.bucketN++;
    for (int i = 0; i < FIELDS.length; i++) r.bucketSums[i] += point.get(i);
    dirty = true;
    return r;
  }

  private void flushBucket(Rec r) {
    if (r.bucketSums == null || r.bucketN == 0) return;
    double[] x = new double[8];
    x[0] = r.bucketTs;
    for (int i = 0; i < FIELDS.length; i++) x[i + 1] = Json.r1(r.bucketSums[i] / r.bucketN);
    r.rollup.add(x);
    r.bucketSums = null;
  }

  public synchronized void prune() {
    long cutoff = System.currentTimeMillis() - retentionMs;
    for (Rec r : vms.values()) {
      if (!r.rollup.isEmpty() && r.rollup.get(0)[0] < cutoff) {
        r.rollup.removeIf((a) -> a[0] < cutoff);
        dirty = true;
      }
    }
  }

  /** Chuỗi số liệu cho khoảng thời gian (giây): điểm thô + rollup cho phần trước đó; khoảng dài nén còn ~1000 điểm. */
  public synchronized ObjectNode series(Rec r, long rangeSec) {
    long now = System.currentTimeMillis();
    return series(r, now - rangeSec * 1000, now);
  }

  /** Chuỗi số liệu trong khoảng [fromMs, toMs] (khoảng tuỳ chọn / phóng to biểu đồ). */
  public synchronized ObjectNode series(Rec r, long fromMs, long toMs) {
    long cutoff = fromMs;
    long rangeSec = Math.max(1, (toMs - fromMs) / 1000);
    List<Point> raw = new ArrayList<>();
    for (Point p : r.history) if (p.ts >= cutoff && p.ts <= toMs) raw.add(p);
    long rawStart = raw.isEmpty() ? Long.MAX_VALUE : raw.get(0).ts;
    // khoảng ngắn dùng điểm thô (20 giây); khoảng dài, hoặc phần cũ không còn điểm thô, dùng rollup
    boolean useRaw = rangeSec <= 3600 * 3 && !raw.isEmpty();
    List<Point> rolled = new ArrayList<>();
    for (double[] a : r.rollup) {
      if (a[0] >= cutoff - bucketMs && a[0] <= toMs && (!useRaw || a[0] + bucketMs <= rawStart)) rolled.add(new Point((long) a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7]));
    }
    ObjectNode out = Json.obj();
    out.put("from", fromMs).put("to", toMs);
    ArrayNode pts = out.putArray("points");
    if (useRaw) {
      out.put("resolution", "raw");
      rolled.forEach((p) -> pts.add(p.toJson())); raw.forEach((p) -> pts.add(p.toJson()));
      return out;
    }
    // nối thêm điểm thô mới hơn bucket rollup cuối (phần vài phút gần nhất chưa được gộp)
    long tailFrom = rolled.isEmpty() ? fromMs : rolled.get(rolled.size() - 1).ts + bucketMs;
    for (Point p : raw) if (p.ts >= tailFrom) rolled.add(p);
    int step = Math.max(1, (int) Math.ceil(rolled.size() / 1000.0));
    if (step == 1) { out.put("resolution", (bucketMs / 60000) + "m"); rolled.forEach((p) -> pts.add(p.toJson())); return out; }
    for (int i = 0; i < rolled.size(); i += step) {
      List<Point> chunk = rolled.subList(i, Math.min(rolled.size(), i + step));
      Point p = new Point(); p.ts = chunk.get(0).ts;
      double[] s = new double[7];
      for (Point c : chunk) for (int k = 0; k < 7; k++) s[k] += c.get(k);
      p.cpu = Json.r1(s[0] / chunk.size()); p.mem = Json.r1(s[1] / chunk.size()); p.disk = Json.r1(s[2] / chunk.size());
      p.rx = Json.r1(s[3] / chunk.size()); p.tx = Json.r1(s[4] / chunk.size()); p.rd = Json.r1(s[5] / chunk.size()); p.wr = Json.r1(s[6] / chunk.size());
      pts.add(p.toJson());
    }
    out.put("resolution", (step * bucketMs / 60000) + "m");
    return out;
  }

  public synchronized void addAlertEvent(ObjectNode ev) {
    alertHistory.add(ev);
    while (alertHistory.size() > 500) alertHistory.remove(0);
    dirty = true;
  }

  /** Khoảng thời gian từ query: from/to (ms) nếu có, không thì range (giây, mặc định 1 giờ); giới hạn trong số ngày lưu. */
  public static long[] window(String fromQ, String toQ, String rangeQ, double retentionDays) {
    long now = System.currentTimeMillis(), oldest = now - (long) (retentionDays * 86400000L);
    if (fromQ != null && !fromQ.isEmpty()) {
      long from, to;
      try { from = (long) Double.parseDouble(fromQ); to = toQ == null || toQ.isEmpty() ? now : (long) Double.parseDouble(toQ); }
      catch (NumberFormatException e) { from = now - 3600000; to = now; }
      to = Math.min(Math.max(to, oldest + 60000), now + 60000);
      from = Math.max(Math.min(from, to - 60000), oldest);
      return new long[] { from, to };
    }
    double rq = 0; try { rq = Double.parseDouble(rangeQ); } catch (Exception e) {}
    long range = (long) Math.min(Math.max(60, rq > 0 ? rq : 3600), retentionDays * 86400);
    return new long[] { now - range * 1000, now };
  }
}
