package com.vmmonitor.ops;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vmmonitor.util.Json;
import com.vmmonitor.util.Log;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/** Sao lưu hằng ngày: nén gzip config.json + data/*.json|key|secret|xml vào backups/YYYY-MM-DD_HHmm/, giữ keepDays ngày. */
public class Backup {
  private final Path root, dataDir, configFile;
  private final Supplier<ObjectNode> config;
  private final Runnable beforeBackup;
  private volatile ObjectNode last;
  private volatile boolean running;
  static final DateTimeFormatter DIR = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm");

  public Backup(Path root, Path dataDir, Path configFile, Supplier<ObjectNode> config, Runnable beforeBackup) {
    this.root = root; this.dataDir = dataDir; this.configFile = configFile; this.config = config; this.beforeBackup = beforeBackup;
  }

  record C(boolean enabled, int hour, int keepDays, Path dir) {}
  C cfg() {
    JsonNode b = config.get().path("backup");
    int hour = b.has("hour") ? (int) Json.num(b.path("hour")) : 2;
    int keep = Json.num(b.path("keepDays")) > 0 ? (int) Json.num(b.path("keepDays")) : 14;
    return new C(Json.notFalse(b.path("enabled")), hour, keep, root.resolve(Json.truthy(b.path("dir")) ? Json.str(b.path("dir")) : "backups").normalize());
  }

  List<Path> sources() {
    List<Path> out = new ArrayList<>();
    if (Files.exists(configFile)) out.add(configFile);
    try (Stream<Path> s = Files.walk(dataDir, 2)) {
      s.filter(Files::isRegularFile).filter((p) -> !p.toString().contains("backups"))
          .filter((p) -> { String n = p.getFileName().toString(); return n.matches(".*\\.(json|key|secret|xml)$") && !n.endsWith(".tmp"); }).forEach(out::add);
    } catch (Exception ignored) {}
    return out;
  }

  public synchronized ObjectNode run(String reason) {
    if (running) throw new RuntimeException("Đang sao lưu");
    running = true;
    C c = cfg();
    LocalDateTime now = LocalDateTime.now();
    Path dir = c.dir().resolve(now.format(DIR));
    try {
      if (beforeBackup != null) beforeBackup.run();
      Files.createDirectories(dir);
      long bytes = 0; List<String> files = new ArrayList<>();
      for (Path src : sources()) {
        String rel = root.relativize(src).toString();
        Path dst = dir.resolve(rel + ".gz");
        Files.createDirectories(dst.getParent());
        try (OutputStream os = Files.newOutputStream(dst); GZIPOutputStream gz = new GZIPOutputStream(os)) { Files.copy(src, gz); }
        bytes += Files.size(dst); files.add(rel);
      }
      Files.writeString(dir.resolve("README.txt"), "Sao lưu VM Monitor Portal lúc " + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " (" + reason + ").\r\n"
          + "Khôi phục: dừng portal, giải nén từng file .gz về đúng đường dẫn (vd: data\\state.json.gz -> data\\state.json), rồi chạy lại portal.\r\n"
          + "Có chứa mật khẩu đã mã hoá (secrets.json) cùng khoá giải mã (secret.key): cất giữ cẩn thận.\r\n\r\n" + String.join("\r\n", files) + "\r\n", StandardCharsets.UTF_8);
      prune(c);
      last = Json.obj().put("ok", true).put("at", System.currentTimeMillis()).put("dir", dir.toString()).put("files", files.size()).put("bytes", bytes).put("reason", reason);
      Log.info("Sao lưu (" + reason + "): " + files.size() + " file, " + String.format("%.1f", bytes / 1048576.0) + " MB -> " + dir);
      return last;
    } catch (Exception e) {
      last = Json.obj().put("ok", false).put("at", System.currentTimeMillis()).put("dir", dir.toString()).put("error", String.valueOf(e.getMessage())).put("reason", reason);
      Log.error("Sao lưu lỗi: " + e.getMessage());
      throw new RuntimeException(e.getMessage());
    } finally { running = false; }
  }

  void prune(C c) {
    long cutoff = System.currentTimeMillis() - c.keepDays() * 86400000L;
    for (JsonNode b : list(c)) if (b.path("at").asLong() < cutoff) {
      try (Stream<Path> s = Files.walk(c.dir().resolve(b.path("name").asText()))) { s.sorted(Comparator.reverseOrder()).forEach((p) -> { try { Files.delete(p); } catch (Exception ignored) {} }); }
      catch (Exception ignored) {}
    }
  }

  ArrayNode list(C c) {
    List<String> names = new ArrayList<>();
    try (Stream<Path> s = Files.list(c.dir())) { s.map((p) -> p.getFileName().toString()).filter((n) -> n.matches("\\d{4}-\\d{2}-\\d{2}_\\d{4}")).forEach(names::add); } catch (Exception ignored) {}
    names.sort(Comparator.reverseOrder());
    ArrayNode out = Json.arr();
    for (String n : names) {
      long bytes = 0;
      try (Stream<Path> s = Files.walk(c.dir().resolve(n))) { bytes = s.filter(Files::isRegularFile).mapToLong((p) -> { try { return Files.size(p); } catch (Exception e) { return 0; } }).sum(); } catch (Exception ignored) {}
      long at = LocalDateTime.parse(n, DIR).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
      out.addObject().put("name", n).put("at", at).put("bytes", bytes);
    }
    return out;
  }

  /** Gọi định kỳ: sao lưu một lần mỗi ngày sau giờ đã đặt. */
  public void tick() {
    C c = cfg();
    if (!c.enabled() || running) return;
    LocalDateTime now = LocalDateTime.now();
    if (now.getHour() < c.hour()) return;
    ArrayNode l = list(c);
    if (l.size() > 0 && l.get(0).path("name").asText().startsWith(now.toLocalDate().toString())) return;
    try { run("tự động"); } catch (Exception ignored) {}
  }

  public ObjectNode status() {
    C c = cfg();
    ArrayNode l = list(c);
    ObjectNode o = Json.obj().put("enabled", c.enabled()).put("hour", c.hour()).put("keepDays", c.keepDays()).put("dir", c.dir().toString())
        .put("dirRaw", Json.str(config.get().path("backup").path("dir"))).put("count", l.size());
    o.set("last", last == null ? Json.F.nullNode() : last);
    ArrayNode top = o.putArray("backups"); for (int i = 0; i < Math.min(30, l.size()); i++) top.add(l.get(i));
    return o;
  }
}
