package com.vmmonitor.web;

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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Một request HTTP (tương đương req/res + url + seg + ip trong handle() của bản Node). */
public class Ctx {
  public final HttpServletRequest req;
  public final HttpServletResponse res;
  public final String method, path;      // path đã bỏ dấu / cuối, "/" nếu rỗng
  public final List<String> seg;          // các đoạn đường dẫn đã giải mã
  public final String ip;
  private final Map<String, String> query = new LinkedHashMap<>();
  public String body;                     // nội dung request đã đọc (để ghi nhật ký)
  public String auditUser;                // tên người dùng ghi vào nhật ký (đăng nhập, đổi cookie…)
  public boolean done;                    // đã trả lời

  public static final Map<String, String> SEC_HEADERS = Map.of("x-content-type-options", "nosniff", "referrer-policy", "same-origin");

  public Ctx(HttpServletRequest req, HttpServletResponse res) {
    this.req = req; this.res = res;
    this.method = req.getMethod();
    String uri = req.getRequestURI();
    String p = uri.replaceAll("/+$", "");
    this.path = p.isEmpty() ? "/" : p;
    List<String> s = new ArrayList<>();
    String[] parts = path.split("/");
    for (int i = 1; i < parts.length; i++) {
      try { s.add(URLDecoder.decode(parts[i].replace("+", "%2B"), StandardCharsets.UTF_8)); } catch (Exception e) { s.add(parts[i]); }
    }
    this.seg = s;
    String fwd = req.getHeader("x-forwarded-for");
    this.ip = fwd != null && !fwd.isBlank() ? fwd.split(",")[0].trim() : req.getRemoteAddr();
    String qs = req.getQueryString();
    if (qs != null) {
      for (String kv : qs.split("&")) {
        if (kv.isEmpty()) continue;
        int i = kv.indexOf('=');
        String k = dec(i < 0 ? kv : kv.substring(0, i)), v = i < 0 ? "" : dec(kv.substring(i + 1));
        query.putIfAbsent(k, v); // URLSearchParams.get(): giá trị đầu tiên
      }
    }
  }

  private static String dec(String s) { try { return URLDecoder.decode(s, StandardCharsets.UTF_8); } catch (Exception e) { return s; } }

  /** url.searchParams.get(name) || '' */
  public String q(String name) { String v = query.get(name); return v == null ? "" : v; }
  public boolean hasQ(String name) { return query.containsKey(name); }
  public String header(String name) { String v = req.getHeader(name); return v == null ? "" : v; }

  public String cookie(String name) {
    for (String c : header("cookie").split(";\\s*")) if (c.startsWith(name + "=")) return c.substring(name.length() + 1);
    return "";
  }

  public boolean encrypted() { return req.isSecure(); }

  public boolean loopback() {
    String a = req.getRemoteAddr();
    return a.equals("127.0.0.1") || a.equals("::1") || a.equals("0:0:0:0:0:0:0:1") || a.equals("::ffff:127.0.0.1");
  }

  /** Đọc nội dung request (giới hạn kích thước). */
  public String readBody(int limit) {
    if (body != null) return body;
    try (InputStream in = req.getInputStream()) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[65536]; int n, size = 0;
      while ((n = in.read(buf)) > 0) {
        size += n;
        if (size > limit) throw new HttpError(413, "Body quá lớn");
        out.write(buf, 0, n);
      }
      body = out.toString(StandardCharsets.UTF_8);
      return body;
    } catch (IOException e) { throw new HttpError(400, e.getMessage()); }
  }
  public String readBody() { return readBody(20 * 1024 * 1024); }
  public JsonNode jsonBody(int limit) { return Json.parse(readBody(limit)); }

  // ---------- trả lời ----------
  public void send(int code, byte[] body, String type, Map<String, ?> extra) {
    if (done) return;
    done = true;
    res.setStatus(code);
    res.setHeader("content-type", type);
    res.setHeader("cache-control", "no-store");
    SEC_HEADERS.forEach(res::setHeader);
    if (extra != null) extra.forEach((k, v) -> {
      if (v instanceof List<?> l) { for (Object x : l) res.addHeader(k, String.valueOf(x)); } else res.setHeader(k, String.valueOf(v));
    });
    try { res.getOutputStream().write(body); res.getOutputStream().flush(); } catch (IOException ignored) {}
  }
  public void send(int code, String body, String type, Map<String, ?> extra) { send(code, body.getBytes(StandardCharsets.UTF_8), type, extra); }
  public void json(int code, Object data, Map<String, ?> extra) { send(code, Json.stringify(data), "application/json; charset=utf-8", extra); }
  public void json(int code, Object data) { json(code, data, null); }
  public void error(int code, String msg) { json(code, Json.obj().put("error", msg)); }
}
