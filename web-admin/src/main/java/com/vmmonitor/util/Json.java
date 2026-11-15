package com.vmmonitor.util;

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

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/** Tiện ích JSON + chuyển đổi kiểu theo đúng ngữ nghĩa JavaScript của bản Node (Number(x) || 0, String(x ?? ''), …). */
public final class Json {
  private Json() {}

  public static final ObjectMapper M = new ObjectMapper()
      .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
      .configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true);
  public static final JsonNodeFactory F = JsonNodeFactory.instance;

  public static ObjectNode obj() { return F.objectNode(); }
  public static ArrayNode arr() { return F.arrayNode(); }

  public static ArrayNode arr(Collection<?> items) {
    ArrayNode a = arr();
    for (Object o : items) { JsonNode n = M.valueToTree(o); a.add(n); }
    return a;
  }

  public static JsonNode parse(String s) {
    try { return s == null || s.isBlank() ? obj() : M.readTree(s); }
    catch (Exception e) { throw new HttpError(400, "JSON không hợp lệ"); }
  }

  public static String stringify(Object o) {
    try { return M.writeValueAsString(o); } catch (Exception e) { throw new RuntimeException(e); }
  }

  public static String pretty(Object o) {
    try { return M.writerWithDefaultPrettyPrinter().writeValueAsString(o); } catch (Exception e) { throw new RuntimeException(e); }
  }

  /** n?.a?.b?.c — luôn trả về node (MissingNode nếu không có). */
  public static JsonNode at(JsonNode n, String... path) {
    JsonNode cur = n == null ? MissingNode.getInstance() : n;
    for (String p : path) { cur = cur.path(p); }
    return cur;
  }

  public static boolean missing(JsonNode n) { return n == null || n.isMissingNode() || n.isNull(); }

  /** Truthy theo JavaScript. */
  public static boolean truthy(JsonNode n) {
    if (missing(n)) return false;
    if (n.isBoolean()) return n.booleanValue();
    if (n.isNumber()) { double d = n.doubleValue(); return d != 0 && !Double.isNaN(d); }
    if (n.isTextual()) return !n.textValue().isEmpty();
    return true; // object / array
  }
  public static boolean truthy(JsonNode n, String... path) { return truthy(at(n, path)); }

  /** x !== false (mặc định bật). */
  public static boolean notFalse(JsonNode n) { return missing(n) || !(n.isBoolean() && !n.booleanValue()); }

  /** Number(x) || 0 */
  public static double num(JsonNode n) {
    if (missing(n)) return 0;
    if (n.isNumber()) { double d = n.doubleValue(); return Double.isFinite(d) ? d : 0; }
    if (n.isBoolean()) return n.booleanValue() ? 1 : 0;
    if (n.isTextual()) {
      String t = n.textValue().trim();
      if (t.isEmpty()) return 0;
      try { double d = Double.parseDouble(t); return Double.isFinite(d) ? d : 0; } catch (NumberFormatException e) { return 0; }
    }
    return 0;
  }
  public static double num(JsonNode n, String... path) { return num(at(n, path)); }

  /** String(x ?? '') */
  public static String str(JsonNode n) {
    if (missing(n)) return "";
    if (n.isTextual()) return n.textValue();
    if (n.isNumber()) return numStr(n.doubleValue());
    if (n.isBoolean()) return String.valueOf(n.booleanValue());
    if (n.isArray()) { List<String> l = new ArrayList<>(); n.forEach((x) -> l.add(str(x))); return String.join(",", l); }
    return n.toString();
  }
  public static String str(JsonNode n, String... path) { return str(at(n, path)); }

  /** String(x ?? '').trim().slice(0, max) — giống hàm str() trong admin.js */
  public static String trim(JsonNode n, int max) { String s = str(n).trim(); return s.length() > max ? s.substring(0, max) : s; }

  private static final Pattern CTRL = Pattern.compile("[\\u0000-\\u001f]");
  /** clean() của server.js: bỏ ký tự điều khiển, trim, cắt độ dài. */
  public static String clean(JsonNode n, int max) {
    String s = CTRL.matcher(str(n)).replaceAll("").trim();
    return s.length() > max ? s.substring(0, max) : s;
  }
  public static String clean(String s, int max) {
    s = CTRL.matcher(s == null ? "" : s).replaceAll("").trim();
    return s.length() > max ? s.substring(0, max) : s;
  }

  /** list() của admin.js: mảng, hoặc chuỗi tách bởi dấu phẩy / xuống dòng. */
  public static List<String> list(JsonNode v) {
    List<String> out = new ArrayList<>();
    if (v != null && v.isArray()) { v.forEach((x) -> { String s = str(x).trim(); if (!s.isEmpty()) out.add(s); }); return out; }
    for (String s : str(v).split("[,\\n]")) { s = s.trim(); if (!s.isEmpty()) out.add(s); }
    return out;
  }

  /** [].concat(x || []) thành danh sách chuỗi. */
  public static List<String> strings(JsonNode v) {
    List<String> out = new ArrayList<>();
    if (missing(v)) return out;
    if (v.isArray()) v.forEach((x) -> out.add(str(x)));
    else if (truthy(v)) out.add(str(v));
    return out;
  }

  /** Số dạng JavaScript: 5 thay vì 5.0. */
  public static String numStr(double d) {
    if (Double.isNaN(d) || Double.isInfinite(d)) return "0";
    if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
    String s = Double.toString(d);
    if (s.contains("E")) s = new java.math.BigDecimal(d).stripTrailingZeros().toPlainString();
    return s;
  }

  public static double r1(double n) { return Math.round(n * 10) / 10.0; }

  public static ObjectNode copy(JsonNode n) { return n != null && n.isObject() ? ((ObjectNode) n).deepCopy() : obj(); }

  public static ArrayNode strArr(Collection<String> items) { ArrayNode a = arr(); items.forEach(a::add); return a; }
}
