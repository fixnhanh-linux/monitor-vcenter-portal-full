package com.vmmonitor.alert;

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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Engine cảnh báo cho một loại đối tượng (vm / host / datastore): rule có ngưỡng, thời gian "for", nhắc lại, báo khi hết.
 * Gom các sự kiện trong 3 giây thành một tin nhắn.
 */
public class Alerts {
  static final Map<String, String> LABEL = Map.ofEntries(Map.entry("cpu", "CPU"), Map.entry("mem", "RAM"), Map.entry("disk", "Disk"), Map.entry("rx", "Network in"),
      Map.entry("tx", "Network out"), Map.entry("rd", "Disk read"), Map.entry("wr", "Disk write"), Map.entry("offline", "Mất kết nối"), Map.entry("disconnected", "Mất kết nối"),
      Map.entry("alarm", "Alarm đỏ trên vCenter"), Map.entry("free_gb", "Còn trống"), Map.entry("inaccessible", "Không truy cập được"), Map.entry("provisioned", "Đã cấp phát"));
  static final Set<String> PERCENT = Set.of("cpu", "mem", "disk", "provisioned");
  static final Set<String> STATE_METRICS = Set.of("offline", "disconnected", "alarm", "inaccessible");
  static final Map<String, String> KIND_LABEL = Map.of("vm", "", "host", "Host ", "datastore", "Datastore ");
  static final Map<String, String> ICON = Map.of("critical", "🔴", "warning", "🟠", "info", "🔵", "resolved", "✅");
  static final DateTimeFormatter VN = DateTimeFormatter.ofPattern("HH:mm:ss d/M/yyyy");

  public static String fmtValue(String metric, double v) {
    if (PERCENT.contains(metric)) return String.format("%.1f%%", v);
    if ("free_gb".equals(metric)) return String.format("%.0f GB", v);
    String[] u = {"B/s", "KB/s", "MB/s", "GB/s"}; int i = 0;
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
    return String.format("%.1f %s", v, u[i]);
  }
  public static String fmtDuration(double sec) {
    long s = Math.round(sec);
    if (s < 60) return s + " giây";
    if (s < 3600) return Math.round(s / 60.0) + " phút";
    if (s < 86400) return String.format("%.1f giờ", s / 3600.0);
    return String.format("%.1f ngày", s / 86400.0);
  }

  /** Rule đã biên dịch. */
  static final class Rule {
    String id, metric, op, severity; double threshold, forSec;
    List<Pattern> sites, match, exclude;
  }
  /** Trạng thái một cặp (đối tượng, rule). */
  static final class State {
    String id; Rule rule; long since, firedAt, lastNotified; boolean firing; double value;
  }

  private final Supplier<ObjectNode> config;
  private final Store store, historyStore;
  public final String kind;
  private final Consumer<ObjectNode> notify;   // nhận {subject, text, events}
  private final Consumer<ObjectNode> onChange; // sự kiện firing / repeat / resolved / rules-changed
  private final ScheduledExecutorService timers;
  final Map<String, State> states = new LinkedHashMap<>();
  private final List<ObjectNode> queue = new ArrayList<>();
  private ScheduledFuture<?> flushTimer;
  private JsonNode cachedFrom;
  private List<Rule> cachedRules = List.of();

  public Alerts(Supplier<ObjectNode> config, Store store, Store historyStore, String kind, Consumer<ObjectNode> notify, Consumer<ObjectNode> onChange, ScheduledExecutorService timers) {
    this.config = config; this.store = store; this.historyStore = historyStore == null ? store : historyStore;
    this.kind = kind; this.notify = notify; this.onChange = onChange; this.timers = timers;
  }

  private static List<Pattern> toList(JsonNode v) {
    if (Json.missing(v)) return null;
    List<Pattern> out = new ArrayList<>();
    for (String s : Json.strings(v.isArray() ? v : Json.arr().add(Json.str(v)))) out.add(Customers.glob(s));
    return out;
  }

  static String ruleId(JsonNode r) {
    if (Json.truthy(r.path("id"))) return Json.str(r.path("id"));
    return String.join("|", Json.str(r.path("target")).isEmpty() ? "vm" : Json.str(r.path("target")), Json.str(r.path("metric")), Json.str(r.path("op")),
        Json.missing(r.path("threshold")) ? "" : Json.str(r.path("threshold")), Json.missing(r.path("for")) ? "0" : Json.str(r.path("for")), Json.str(r.path("severity")),
        String.join(",", Json.strings(r.path("sites"))), String.join(",", Json.strings(r.path("vms"))), String.join(",", Json.strings(r.path("excludeVms"))));
  }

  synchronized List<Rule> rules() {
    JsonNode cfg = config.get().path("alerts");
    if (cfg.path("enabled").isBoolean() && !cfg.path("enabled").booleanValue()) return List.of();
    JsonNode rulesNode = cfg.path("rules");
    if (cachedFrom == rulesNode) return cachedRules;
    cachedFrom = rulesNode;
    List<Rule> out = new ArrayList<>();
    for (JsonNode r : rulesNode) {
      String target = Json.str(r.path("target")).isEmpty() ? "vm" : Json.str(r.path("target"));
      if (!target.equals(kind)) continue;
      Rule x = new Rule();
      x.id = ruleId(r); x.metric = Json.str(r.path("metric"));
      x.op = Json.truthy(r.path("op")) ? Json.str(r.path("op")) : ("free_gb".equals(x.metric) ? "<" : ">");
      x.threshold = Json.num(r.path("threshold")); x.forSec = Json.num(r.path("for"));
      x.severity = Json.truthy(r.path("severity")) ? Json.str(r.path("severity")) : "warning";
      x.sites = toList(r.path("sites")); x.match = toList(r.path("vms")); x.exclude = toList(r.path("excludeVms"));
      out.add(x);
    }
    cachedRules = out;
    // rule đã xoá / sửa: bỏ cảnh báo đang treo của nó (không gửi tin)
    Set<String> live = new HashSet<>(); out.forEach((x) -> live.add(x.id));
    int dropped = 0;
    for (var it = states.entrySet().iterator(); it.hasNext(); ) {
      State st = it.next().getValue();
      if (!live.contains(st.rule.id)) { it.remove(); if (st.firing) dropped++; }
    }
    if (dropped > 0) timers.execute(() -> onChange.accept(Json.obj().put("type", "rules-changed").put("kind", kind)));
    return out;
  }

  double valueOf(Store.Rec o, String metric) {
    Store.Point last = o.last();
    if ("vm".equals(kind) && "disk".equals(metric)) {
      JsonNode mounts = o.latest.path("disk").path("mounts");
      if (mounts.size() > 0) { double m = 0; for (JsonNode x : mounts) m = Math.max(m, Json.num(x.path("percent"))); return m; }
    }
    if ("datastore".equals(kind)) {
      if ("free_gb".equals(metric)) return Json.num(o.latest.path("free")) / 1073741824.0;
      if ("provisioned".equals(metric)) { double cap = Json.num(o.latest.path("capacity")); return cap > 0 ? Json.num(o.latest.path("provisioned")) / cap * 100 : 0; }
    }
    return last == null ? 0 : last.get(metric);
  }

  JsonNode worstMount(Store.Rec vm) {
    JsonNode best = null;
    for (JsonNode m : vm.latest.path("disk").path("mounts")) if (best == null || Json.num(m.path("percent")) > Json.num(best.path("percent"))) best = m;
    return best;
  }

  public synchronized void evaluate(String id, Store.Rec o, boolean online) {
    long now = System.currentTimeMillis();
    JsonNode cfg = config.get().path("alerts");
    long repeatMs = (long) ((Json.missing(cfg.path("repeatMinutes")) ? 60 : Json.num(cfg.path("repeatMinutes"))) * 60000);
    JsonNode i = o.info;
    String site = Json.str(i.path("site")).isEmpty() ? "default" : Json.str(i.path("site"));
    String name = Json.str(i.path("name")).isEmpty() ? id : Json.str(i.path("name"));
    boolean quiet = "vm".equals(kind) ? (Json.truthy(i.path("power")) && !"on".equals(Json.str(i.path("power"))))
        : "host".equals(kind) && Json.truthy(i.path("maintenance"));
    String conn = Json.str(i.path("connection"));
    for (Rule rule : rules()) {
      if (rule.sites != null && rule.sites.stream().noneMatch((re) -> re.matcher(site).matches())) continue;
      if (rule.match != null && rule.match.stream().noneMatch((re) -> re.matcher(name).matches())) continue;
      if (rule.exclude != null && rule.exclude.stream().anyMatch((re) -> re.matcher(name).matches())) continue;
      boolean breached; double value;
      if (quiet) { breached = false; value = 0; }
      else if ("offline".equals(rule.metric) || "disconnected".equals(rule.metric)) {
        breached = !online || ("host".equals(kind) && !conn.isEmpty() && !"connected".equals(conn));
        value = (now - o.lastSeen) / 1000.0;
      } else if ("alarm".equals(rule.metric)) {
        if (!online) continue;
        breached = "red".equals(Json.str(i.path("health"))); value = breached ? 1 : 0;
      } else if ("inaccessible".equals(rule.metric)) {
        if (!online) continue;
        breached = i.path("accessible").isBoolean() && !i.path("accessible").booleanValue(); value = breached ? 1 : 0;
      } else {
        if (!online) continue;
        if ("host".equals(kind) && !conn.isEmpty() && !"connected".equals(conn)) continue;
        value = valueOf(o, rule.metric);
        breached = "<".equals(rule.op) ? value < rule.threshold : value > rule.threshold;
      }
      String key = id + "|" + rule.id;
      State st = states.get(key);
      if (breached) {
        if (st == null) { st = new State(); st.id = id; st.rule = rule; st.since = now; st.value = value; states.put(key, st); }
        st.value = value; st.rule = rule;
        if (!st.firing && (now - st.since) / 1000.0 >= rule.forSec) {
          st.firing = true; st.firedAt = now; st.lastNotified = now;
          emit("firing", st);
        } else if (st.firing && repeatMs > 0 && now - st.lastNotified >= repeatMs) {
          st.lastNotified = now;
          emit("repeat", st);
        }
      } else if (st != null) {
        states.remove(key);
        if (st.firing) { st.value = value; emit("resolved", st); }
      }
    }
  }

  public synchronized void forget(String id) { states.values().removeIf((st) -> st.id.equals(id)); }

  public synchronized List<ObjectNode> active() {
    List<ObjectNode> out = new ArrayList<>();
    for (State s : states.values()) if (s.firing) out.add(describe(s, "firing"));
    return out;
  }

  public synchronized ArrayNode exportStates() {
    ArrayNode a = Json.arr();
    for (State s : states.values()) {
      ObjectNode o = a.addObject();
      o.put("id", s.id).put("rule", s.rule.id).put("since", s.since).put("firing", s.firing).put("value", s.value);
      if (s.firedAt > 0) o.put("firedAt", s.firedAt);
      if (s.lastNotified > 0) o.put("lastNotified", s.lastNotified);
    }
    return a;
  }

  public synchronized int importStates(JsonNode list) {
    Map<String, Rule> byId = new LinkedHashMap<>();
    for (Rule r : rules()) byId.put(r.id, r);
    int n = 0;
    if (list == null) return 0;
    for (JsonNode s : list) {
      Rule rule = byId.get(Json.str(s.path("rule")));
      String id = Json.str(s.path("id"));
      if (rule == null || !store.vms.containsKey(id)) continue;
      State st = new State();
      st.id = id; st.rule = rule; st.since = (long) Json.num(s.path("since")); st.firing = Json.truthy(s.path("firing"));
      st.firedAt = (long) Json.num(s.path("firedAt")); st.lastNotified = (long) Json.num(s.path("lastNotified")); st.value = Json.num(s.path("value"));
      states.put(id + "|" + rule.id, st);
      if (st.firing) n++;
    }
    return n;
  }

  ObjectNode describe(State st, String type) {
    Rule r = st.rule;
    Store.Rec o = store.vms.get(st.id);
    String site = o != null && !Json.str(o.info.path("site")).isEmpty() ? Json.str(o.info.path("site")) : st.id.split("/")[0];
    String name = o != null && !Json.str(o.info.path("name")).isEmpty() ? Json.str(o.info.path("name")) : st.id.substring(Math.min(st.id.length(), site.length() + 1));
    String label = "[" + site + "] " + KIND_LABEL.get(kind) + name;
    String text;
    if ("offline".equals(r.metric) || "disconnected".equals(r.metric)) {
      String conn = o == null ? "" : Json.str(o.info.path("connection"));
      String why = "host".equals(kind) && !conn.isEmpty() && !"connected".equals(conn) ? " (vCenter: " + conn + ")" : "";
      text = "resolved".equals(type) ? label + " đã kết nối lại" : label + " mất kết nối " + fmtDuration(st.value) + why;
    } else if (STATE_METRICS.contains(r.metric)) {
      text = label + ": " + LABEL.get(r.metric) + ("resolved".equals(type) ? " — đã hết" : "");
    } else {
      JsonNode mount = "vm".equals(kind) && "disk".equals(r.metric) && o != null ? worstMount(o) : null;
      text = label + ": " + LABEL.getOrDefault(r.metric, r.metric) + (mount != null ? " (" + Json.str(mount.path("mount")) + ")" : "") + " " + fmtValue(r.metric, st.value)
          + ("resolved".equals(type) ? " — đã trở lại bình thường"
          : " " + r.op + " " + fmtValue(r.metric, r.threshold) + (r.forSec > 0 ? " trong " + fmtDuration(r.forSec) : ""));
    }
    ObjectNode ev = Json.obj();
    ev.put("id", ("vm".equals(kind) ? "" : kind + ":") + st.id + "|" + r.id).put("kind", kind)
        .put("vmId", "vm".equals(kind) ? st.id : kind + ":" + st.id).put("site", site).put("vm", name).put("metric", r.metric).put("rule", r.id)
        .put("severity", r.severity).put("value", st.value).put("threshold", r.threshold).put("since", st.firedAt > 0 ? st.firedAt : st.since)
        .put("type", type).put("ts", System.currentTimeMillis()).put("text", text);
    if (o != null && Json.truthy(o.info.path("ip"))) ev.put("ip", Json.str(o.info.path("ip")));
    return ev;
  }

  private void emit(String type, State st) {
    ObjectNode ev = describe(st, type);
    if ("resolved".equals(type)) ev.put("duration", (System.currentTimeMillis() - (st.firedAt > 0 ? st.firedAt : st.since)) / 1000.0);
    if (!"repeat".equals(type)) historyStore.addAlertEvent(ev);
    onChange.accept(ev);
    JsonNode cfg = config.get().path("alerts");
    if ("resolved".equals(type) && cfg.path("sendResolved").isBoolean() && !cfg.path("sendResolved").booleanValue()) return;
    queue.add(ev);
    if (flushTimer == null) flushTimer = timers.schedule(this::flush, 3, TimeUnit.SECONDS);
  }

  private void flush() {
    List<ObjectNode> events;
    synchronized (this) { flushTimer = null; events = new ArrayList<>(queue); queue.clear(); }
    if (events.isEmpty()) return;
    List<String> lines = new ArrayList<>();
    int firing = 0;
    Set<String> sites = new java.util.LinkedHashSet<>();
    for (ObjectNode e : events) {
      String type = Json.str(e.path("type")), sev = Json.str(e.path("severity"));
      String icon = "resolved".equals(type) ? ICON.get("resolved") : ICON.getOrDefault(sev, "⚠️");
      String tag = "resolved".equals(type) ? "RESOLVED" : "repeat".equals(type) ? sev.toUpperCase() + " (nhắc lại)" : sev.toUpperCase();
      lines.add(icon + " [" + tag + "] " + Json.str(e.path("text")) + (Json.truthy(e.path("ip")) ? " · " + Json.str(e.path("ip")) : "")
          + (Json.truthy(e.path("duration")) ? " (kéo dài " + fmtDuration(Json.num(e.path("duration"))) + ")" : ""));
      if (!"resolved".equals(type)) firing++;
      sites.add(Json.str(e.path("site")));
    }
    String subject = events.size() == 1 ? "[VM Monitor] " + lines.get(0).replaceFirst("^\\S+\\s", "")
        : "[VM Monitor] " + firing + " cảnh báo, " + (events.size() - firing) + " đã hết" + (sites.size() <= 3 ? " — " + String.join(", ", sites) : " — " + sites.size() + " site");
    String portal = Json.str(config.get().path("publicUrl"));
    String text = String.join("\n", lines) + "\n\n" + LocalDateTime.now().format(VN) + (portal.isEmpty() ? "" : "\n" + portal);
    ObjectNode msg = Json.obj().put("subject", subject).put("text", text);
    ArrayNode evs = msg.putArray("events"); events.forEach(evs::add);
    notify.accept(msg);
  }
}
