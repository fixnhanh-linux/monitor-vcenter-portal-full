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

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Kênh gửi thông báo: Telegram, Email (SMTP tự viết: SSL 465 / STARTTLS 587 / plain 25, AUTH LOGIN), Webhook (Slack / Discord / Teams / JSON). */
public final class Notify {
  private Notify() {}
  static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  static final List<String> CHANNELS = List.of("telegram", "email", "webhook");

  /** File đính kèm email. */
  public record Attachment(String filename, String type, byte[] content) {}

  public static List<String> enabledChannels(JsonNode notifyCfg) {
    List<String> out = new ArrayList<>();
    for (String ch : CHANNELS) if (Json.truthy(Json.at(notifyCfg, ch, "enabled"))) out.add(ch);
    return out;
  }

  /** Gửi tới mọi kênh đang bật. Trả về [{channel, ok, error}] */
  public static ArrayNode notifyAll(JsonNode notifyCfg, ObjectNode message) {
    ArrayNode results = Json.arr();
    List<Thread> threads = new ArrayList<>();
    for (String ch : enabledChannels(notifyCfg)) {
      ObjectNode r = Json.obj().put("channel", ch);
      synchronized (results) { results.add(r); }
      Thread t = Thread.ofVirtual().start(() -> {
        try {
          JsonNode c = notifyCfg.path(ch);
          switch (ch) {
            case "telegram" -> sendTelegram(c, Json.str(message.path("text")));
            case "email" -> sendEmail(c, Json.str(message.path("subject")), Json.str(message.path("text")), null, List.of(), null);
            default -> sendWebhook(c, message);
          }
          r.put("ok", true);
        } catch (Exception e) {
          Log.error("[notify:" + ch + "] " + e.getMessage());
          r.put("ok", false).put("error", String.valueOf(e.getMessage()));
        }
      });
      threads.add(t);
    }
    for (Thread t : threads) { try { t.join(30000); } catch (InterruptedException ignored) {} }
    return results;
  }

  // ---------- Telegram ----------
  public static void sendTelegram(JsonNode cfg, String text) throws Exception {
    String token = Json.str(cfg.path("botToken"));
    List<String> chatIds = Json.strings(cfg.path("chatId"));
    if (token.isEmpty() || chatIds.isEmpty()) throw new Exception("thiếu botToken hoặc chatId");
    // Telegram giới hạn 4096 ký tự/tin: chia theo dòng, tối đa 5 tin
    List<String> chunks = new ArrayList<>();
    String cur = "";
    String[] allLines = text.split("\n", -1);
    for (String line : allLines) {
      if ((cur + "\n" + line).length() > 3900) { chunks.add(cur); cur = line; } else cur = cur.isEmpty() ? line : cur + "\n" + line;
    }
    if (!cur.isEmpty()) chunks.add(cur);
    if (chunks.size() > 5) {
      int used = String.join("\n", chunks.subList(0, 5)).split("\n", -1).length;
      chunks = new ArrayList<>(chunks.subList(0, 5));
      chunks.set(4, chunks.get(4) + "\n… và " + (allLines.length - used) + " dòng nữa, xem trên portal");
    }
    for (String chatId : chatIds) for (String part : chunks) {
      String body = Json.stringify(Json.obj().put("chat_id", chatId).put("text", part).put("disable_web_page_preview", true));
      HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token + "/sendMessage"))
          .timeout(Duration.ofSeconds(10)).header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
      JsonNode b; try { b = Json.M.readTree(res.body()); } catch (Exception e) { b = Json.obj(); }
      if (res.statusCode() >= 300 || (b.path("ok").isBoolean() && !b.path("ok").booleanValue()))
        throw new Exception("Telegram: " + (Json.truthy(b.path("description")) ? Json.str(b.path("description")) : "HTTP " + res.statusCode()));
    }
  }

  // ---------- Webhook ----------
  public static void sendWebhook(JsonNode cfg, ObjectNode msg) throws Exception {
    String url = Json.str(cfg.path("url"));
    if (url.isEmpty()) throw new Exception("thiếu url");
    String format = Json.truthy(cfg.path("format")) ? Json.str(cfg.path("format")) : "json";
    String text = Json.str(msg.path("text")), subject = Json.str(msg.path("subject"));
    ObjectNode body = Json.obj();
    switch (format) {
      case "slack" -> body.put("text", text);
      case "discord" -> body.put("content", text.length() > 1990 ? text.substring(0, 1990) : text);
      case "teams" -> body.put("@type", "MessageCard").put("@context", "https://schema.org/extensions").put("summary", subject).put("title", subject).put("text", text.replace("\n", "<br>"));
      default -> { body.put("subject", subject).put("text", text); body.set("events", msg.path("events")); }
    }
    HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("content-type", "application/json");
    for (Iterator<Map.Entry<String, JsonNode>> it = cfg.path("headers").fields(); it.hasNext(); ) { var e = it.next(); rb.header(e.getKey(), Json.str(e.getValue())); }
    HttpResponse<Void> res = HTTP.send(rb.POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body))).build(), HttpResponse.BodyHandlers.discarding());
    if (res.statusCode() >= 300) throw new Exception("Webhook: HTTP " + res.statusCode());
  }

  // ---------- Email ----------
  static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
  static String encodeHeader(String s) { return s.matches("[\\x20-\\x7e]*") ? s : "=?UTF-8?B?" + b64(s) + "?="; }
  static String wrap76(byte[] buf) { return Base64.getEncoder().encodeToString(buf).replaceAll("(.{1,76})", "$1\r\n").trim(); }

  static List<String> part(String type, String body) { return List.of("Content-Type: " + type + "; charset=UTF-8", "Content-Transfer-Encoding: base64", "", wrap76(body.getBytes(StandardCharsets.UTF_8))); }
  static String boundary() { return "=_vmmon_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12); }

  /** Thân MIME: text thuần, hoặc text + html, có thể kèm file. */
  static List<String> mimeBody(String text, String html, List<Attachment> attachments) {
    List<String> lines = new ArrayList<>(part("text/plain", text == null ? "" : text));
    if (html != null && !html.isEmpty()) {
      String b = boundary();
      lines = new ArrayList<>(List.of("Content-Type: multipart/alternative; boundary=\"" + b + "\"", "", "--" + b));
      lines.addAll(part("text/plain", text == null ? "" : text)); lines.add("--" + b); lines.addAll(part("text/html", html)); lines.add("--" + b + "--");
    }
    if (attachments == null || attachments.isEmpty()) return lines;
    String b = boundary();
    List<String> out = new ArrayList<>(List.of("Content-Type: multipart/mixed; boundary=\"" + b + "\"", "", "--" + b));
    out.addAll(lines);
    for (Attachment a : attachments) {
      out.add("--" + b);
      out.add("Content-Type: " + (a.type() == null ? "application/octet-stream" : a.type()) + "; name=\"" + encodeHeader(a.filename()) + "\"");
      out.add("Content-Transfer-Encoding: base64");
      out.add("Content-Disposition: attachment; filename=\"" + encodeHeader(a.filename()) + "\"");
      out.add(""); out.add(wrap76(a.content()));
    }
    out.add("--" + b + "--");
    return out;
  }

  /** Phiên SMTP đơn giản: đọc phản hồi nhiều dòng, gửi lệnh, kiểm tra mã. */
  static final class Smtp {
    Socket sock; BufferedReader in; OutputStream out;
    Smtp(Socket s) throws IOException { attach(s); }
    void attach(Socket s) throws IOException {
      sock = s; sock.setSoTimeout(15000);
      in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)); out = s.getOutputStream();
    }
    String[] read() throws IOException {
      StringBuilder text = new StringBuilder(); String line; int code = 0;
      while ((line = in.readLine()) != null) {
        text.append(line).append('\n');
        if (line.length() >= 4 && line.charAt(3) == '-') continue;
        code = line.length() >= 3 ? Integer.parseInt(line.substring(0, 3)) : 0; break;
      }
      return new String[]{String.valueOf(code), text.toString().trim()};
    }
    String[] cmd(String c, int... ok) throws IOException {
      out.write((c + "\r\n").getBytes(StandardCharsets.UTF_8)); out.flush();
      String[] r = read();
      if (ok.length > 0) {
        int code = Integer.parseInt(r[0]); boolean good = false;
        for (int k : ok) if (k == code) good = true;
        if (!good) throw new IOException("SMTP " + (c.startsWith("AUTH") || c.length() > 60 ? c.split(" ")[0] : c.split(" ")[0]) + ": " + r[1]);
      }
      return r;
    }
  }

  public static void sendEmail(JsonNode cfg, String subject, String text, String html, List<Attachment> attachments, List<String> toOverride) throws Exception {
    String host = Json.str(cfg.path("host"));
    boolean secureSet = cfg.path("secure").isBoolean();
    int port = (int) (Json.num(cfg.path("port")) > 0 ? Json.num(cfg.path("port")) : (secureSet && cfg.path("secure").booleanValue() ? 465 : 587));
    boolean secure = secureSet ? cfg.path("secure").booleanValue() : port == 465;
    List<String> to = toOverride != null && !toOverride.isEmpty() ? toOverride : Json.list(cfg.path("to"));
    String from = Json.truthy(cfg.path("from")) ? Json.str(cfg.path("from")) : Json.str(cfg.path("user"));
    if (host.isEmpty() || from.isEmpty() || to.isEmpty()) throw new Exception("thiếu host / from / to");
    String fromAddr = from.replaceAll("^.*<|>.*$", "");
    Socket s = secure ? SSLSocketFactory.getDefault().createSocket() : new Socket();
    s.connect(new InetSocketAddress(host, port), 15000);
    if (s instanceof SSLSocket ssl) ssl.startHandshake();
    Smtp smtp = new Smtp(s);
    try {
      String[] greet = smtp.read();
      if (!"220".equals(greet[0])) throw new Exception("SMTP: " + greet[1]);
      String hn = java.net.InetAddress.getLocalHost().getHostName().replaceAll("[^a-zA-Z0-9.-]", "");
      String helo = "EHLO " + (hn.isEmpty() ? "localhost" : hn);
      String[] ehlo = smtp.cmd(helo, 250);
      if (!secure && ehlo[1].toUpperCase().contains("STARTTLS") && Json.notFalse(cfg.path("starttls"))) {
        smtp.cmd("STARTTLS", 220);
        SSLSocket up = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(s, host, port, true);
        up.startHandshake();
        smtp.attach(up);
        smtp.cmd(helo, 250);
      }
      if (Json.truthy(cfg.path("user"))) {
        smtp.cmd("AUTH LOGIN", 334);
        smtp.cmd(b64(Json.str(cfg.path("user"))), 334);
        smtp.cmd(b64(Json.str(cfg.path("pass"))), 235);
      }
      smtp.cmd("MAIL FROM:<" + fromAddr + ">", 250);
      for (String rcpt : to) smtp.cmd("RCPT TO:<" + rcpt.replaceAll("^.*<|>.*$", "") + ">", 250, 251);
      smtp.cmd("DATA", 354);
      List<String> msg = new ArrayList<>(List.of(
          "From: " + encodeHeader(Json.truthy(cfg.path("fromName")) ? Json.str(cfg.path("fromName")) : "VM Monitor") + " <" + fromAddr + ">",
          "To: " + String.join(", ", to),
          "Subject: " + encodeHeader(subject),
          "Date: " + ZonedDateTime.now().format(DateTimeFormatter.RFC_1123_DATE_TIME),
          "Message-ID: <" + System.currentTimeMillis() + "." + UUID.randomUUID().toString().substring(0, 8) + "@vm-monitor>",
          "MIME-Version: 1.0"));
      msg.addAll(mimeBody(text, html, attachments));
      smtp.cmd(String.join("\r\n", msg) + "\r\n.", 250);
      try { smtp.cmd("QUIT"); } catch (Exception ignored) {}
    } finally {
      try { smtp.sock.close(); } catch (Exception ignored) {}
    }
  }
}
