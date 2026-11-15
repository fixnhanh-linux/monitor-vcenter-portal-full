package com.vmmonitor.customer;

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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Nhận diện khách hàng của VM theo thứ tự nguồn trong config.customers.sources (nguồn đầu tiên ra kết quả thắng):
 * manual (gán tay) · rules (mẫu tên VM) · tag (vSphere Tag theo category) · attribute (Custom Attribute) · folder · name (regex tên) · notes (regex).
 */
public class Customers {
  public record Result(String customer, String source) {}

  public static final Map<String, String> LABEL = Map.of("manual", "gán tay", "rules", "quy tắc tên", "tag", "vSphere Tag",
      "attribute", "Custom Attribute", "folder", "thư mục VM", "name", "tên VM", "notes", "Notes");

  public static ArrayNode defaultSources() {
    ArrayNode a = Json.arr();
    a.addObject().put("type", "manual");
    a.addObject().put("type", "rules");
    a.addObject().put("type", "tag").put("category", "Customer");
    a.addObject().put("type", "attribute").put("name", "Customer");
    return a;
  }

  private record Source(String type, Pattern re, List<String> cats, List<String> names, int level, String prefix) {}
  private record Rule(String customer, List<Pattern> vms, List<Pattern> sites) {}

  private final Supplier<ObjectNode> config;
  private JsonNode cacheFrom;
  private List<Source> sources;
  private List<Rule> rules;
  private JsonNode manual;

  public Customers(Supplier<ObjectNode> config) { this.config = config; }

  public static Pattern glob(String g) {
    StringBuilder sb = new StringBuilder("^");
    for (char ch : g.toCharArray()) {
      if (ch == '*') sb.append(".*");
      else if (ch == '?') sb.append('.');
      else if (".+^${}()|[]\\".indexOf(ch) >= 0) sb.append('\\').append(ch);
      else sb.append(ch);
    }
    return Pattern.compile(sb.append('$').toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
  }

  private synchronized void compile() {
    JsonNode c = config.get().path("customers");
    if (cacheFrom == c && sources != null) return;
    JsonNode src = c.path("sources").isArray() && c.path("sources").size() > 0 ? c.path("sources") : defaultSources();
    List<Source> out = new ArrayList<>();
    for (JsonNode s : src) {
      if (s.path("enabled").isBoolean() && !s.path("enabled").booleanValue()) continue;
      String type = Json.str(s.path("type"));
      Pattern re = null;
      if (("name".equals(type) || "notes".equals(type)) && Json.truthy(s.path("regex"))) {
        try { re = Pattern.compile(Json.str(s.path("regex")), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); } catch (Exception ignored) {}
      }
      List<String> cats = new ArrayList<>(), names = new ArrayList<>();
      if ("tag".equals(type)) { List<String> l = Json.strings(s.path("category")); if (l.isEmpty()) l.add("Customer"); l.forEach((x) -> cats.add(x.toLowerCase())); }
      if ("attribute".equals(type)) { List<String> l = Json.strings(s.path("name")); if (l.isEmpty()) l.add("Customer"); l.forEach((x) -> names.add(x.toLowerCase())); }
      out.add(new Source(type, re, cats, names, Math.max(1, (int) Json.num(s.path("level"))), Json.str(s.path("prefix"))));
    }
    List<Rule> rl = new ArrayList<>();
    for (JsonNode r : c.path("rules")) {
      if (!Json.truthy(r.path("customer"))) continue;
      List<Pattern> vms = new ArrayList<>();
      for (String v : Json.strings(r.path("vms"))) if (!v.isEmpty()) vms.add(glob(v));
      List<String> st = Json.strings(r.path("sites"));
      List<Pattern> sites = null;
      if (!st.isEmpty()) { sites = new ArrayList<>(); for (String x : st) sites.add(glob(x)); }
      rl.add(new Rule(Json.str(r.path("customer")), vms, sites));
    }
    sources = out; rules = rl; manual = c.path("manual"); cacheFrom = c;
  }

  /** info: thông tin VM (id, site, name, tags, attributes, folder, notes). */
  public Result resolve(JsonNode info) {
    if (Json.missing(info)) return null;
    compile();
    for (Source s : sources) {
      String v = "";
      switch (s.type) {
        case "manual" -> v = Json.str(manual.path(Json.str(info.path("id"))));
        case "rules" -> {
          for (Rule r : rules) {
            if (r.sites != null && r.sites.stream().noneMatch((re) -> re.matcher(Json.str(info.path("site"))).matches())) continue;
            if (r.vms.stream().anyMatch((re) -> re.matcher(Json.str(info.path("name"))).matches())) { v = r.customer; break; }
          }
        }
        case "tag" -> {
          for (JsonNode t : info.path("tags")) {
            String tt = Json.str(t); int i = tt.indexOf(':');
            if (i > 0 && s.cats.contains(tt.substring(0, i).toLowerCase())) { v = tt.substring(i + 1); break; }
          }
        }
        case "attribute" -> {
          for (Iterator<Map.Entry<String, JsonNode>> it = info.path("attributes").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (s.names.contains(e.getKey().toLowerCase()) && Json.truthy(e.getValue())) { v = Json.str(e.getValue()); break; }
          }
        }
        case "folder" -> {
          List<String> parts = new ArrayList<>();
          for (String x : Json.str(info.path("folder")).split("/")) if (!x.isEmpty()) parts.add(x);
          int lvl = s.level;
          if (!s.prefix.isEmpty()) v = !parts.isEmpty() && parts.get(0).equalsIgnoreCase(s.prefix) && parts.size() > lvl ? parts.get(lvl) : "";
          else v = parts.size() >= lvl ? parts.get(lvl - 1) : "";
        }
        case "name", "notes" -> {
          if (s.re != null) {
            Matcher m = s.re.matcher(Json.str(info.path("name".equals(s.type) ? "name" : "notes")));
            if (m.find()) v = m.groupCount() >= 1 && m.group(1) != null ? m.group(1) : m.group();
          }
        }
        default -> {}
      }
      v = v == null ? "" : v.trim();
      if (v.length() > 100) v = v.substring(0, 100);
      if (!v.isEmpty()) return new Result(v, s.type);
    }
    return null;
  }

  public String of(JsonNode info) { Result r = resolve(info); return r == null ? "" : r.customer(); }
}
