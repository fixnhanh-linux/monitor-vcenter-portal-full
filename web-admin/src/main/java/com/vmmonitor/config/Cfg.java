package com.vmmonitor.config;

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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

/**
 * config.json: nạp, tự nạp lại khi file đổi (kiểm tra mỗi 2 giây), ghi nguyên tử (mutate).
 * Bộ cấu hình hiện hành (get()) đã gộp sẵn rule cảnh báo mặc định giống bản Node.
 */
public class Cfg {
  public final Path file;
  private volatile ObjectNode config = Json.obj();
  private long lastMtime = -1;
  private Runnable onReload = () -> {};

  public Cfg(Path file) { this.file = file; }

  public ObjectNode get() { return config; }
  public void onReload(Runnable r) { this.onReload = r; }

  static ObjectNode defaultAlerts() {
    ObjectNode a = Json.obj();
    a.put("enabled", true).put("repeatMinutes", 60).put("sendResolved", true);
    ArrayNode rules = a.putArray("rules");
    rules.addObject().put("metric", "cpu").put("threshold", 90).put("for", 120).put("severity", "critical");
    rules.addObject().put("metric", "mem").put("threshold", 90).put("for", 120).put("severity", "critical");
    rules.addObject().put("metric", "disk").put("threshold", 90).put("for", 0).put("severity", "warning");
    rules.addObject().put("metric", "offline").put("for", 60).put("severity", "critical");
    return a;
  }

  public synchronized void load() {
    ObjectNode c;
    try {
      lastMtime = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : -1;
      JsonNode n = Json.M.readTree(Files.readString(file, StandardCharsets.UTF_8));
      c = n != null && n.isObject() ? (ObjectNode) n : Json.obj();
    } catch (NoSuchFileException e) {
      c = Json.obj();
    } catch (Exception e) {
      Log.error("Lỗi đọc " + file + ": " + e.getMessage() + " — giữ cấu hình cũ");
      return;
    }
    ObjectNode alerts = defaultAlerts();
    if (c.path("alerts").isObject()) alerts.setAll((ObjectNode) c.get("alerts"));
    c.set("alerts", alerts);
    config = c;
    java.util.List<String> ch = Notify.enabledChannels(c.path("notify"));
    Log.info("Cấu hình: " + alerts.path("rules").size() + " rule, kênh gửi: " + (ch.isEmpty() ? "(chưa bật)" : String.join(", ", ch)) + ", "
        + c.path("users").size() + " người dùng, " + c.path("apiKeys").size() + " API key");
    if (c.path("users").size() == 0) Log.warn("⚠ Portal đang MỞ (chưa có tài khoản người dùng). Tạo trong trang Cài đặt hoặc: java -jar vm-monitor-admin.jar adduser admin");
    try { onReload.run(); } catch (Exception e) { Log.error("reload: " + e.getMessage()); }
  }

  /** Gọi định kỳ: file đổi thì nạp lại. */
  public void checkChanged() {
    try {
      long m = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() : -1;
      if (m != lastMtime) { Log.info("config.json thay đổi — nạp lại"); load(); }
    } catch (Exception ignored) {}
  }

  // ---------- đọc / ghi file thô (không gộp mặc định) ----------
  public ObjectNode readRaw() {
    try {
      JsonNode n = Json.M.readTree(Files.readString(file, StandardCharsets.UTF_8));
      return n != null && n.isObject() ? (ObjectNode) n : Json.obj();
    } catch (NoSuchFileException e) {
      return Json.obj();
    } catch (Exception e) {
      throw new RuntimeException("Không đọc được " + file + ": " + e.getMessage());
    }
  }

  public synchronized void writeRaw(ObjectNode raw) {
    try {
      Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
      Files.writeString(tmp, Json.pretty(raw), StandardCharsets.UTF_8);
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception e) {
      throw new RuntimeException("Không ghi được " + file + ": " + e.getMessage());
    }
    load();
  }

  /** Đọc file, sửa, ghi lại, nạp lại cấu hình. */
  public synchronized ObjectNode mutate(Consumer<ObjectNode> fn) {
    ObjectNode raw = readRaw();
    fn.accept(raw);
    writeRaw(raw);
    return raw;
  }
}
