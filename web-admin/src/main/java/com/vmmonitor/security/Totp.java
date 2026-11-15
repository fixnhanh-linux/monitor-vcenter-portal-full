package com.vmmonitor.security;

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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Mã xác thực 2 lớp TOTP (RFC 6238): HMAC-SHA1, 6 số, 30 giây, chấp nhận lệch ±1 bước. */
public final class Totp {
  private Totp() {}
  private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private static final SecureRandom RND = new SecureRandom();

  public static String base32Encode(byte[] buf) {
    StringBuilder out = new StringBuilder();
    int bits = 0, value = 0;
    for (byte b : buf) {
      value = (value << 8) | (b & 0xff); bits += 8;
      while (bits >= 5) { out.append(B32.charAt((value >>> (bits - 5)) & 31)); bits -= 5; }
    }
    if (bits > 0) out.append(B32.charAt((value << (5 - bits)) & 31));
    return out.toString();
  }

  public static byte[] base32Decode(String s) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int bits = 0, value = 0;
    for (char ch : s.toUpperCase().replaceAll("[^A-Z2-7]", "").toCharArray()) {
      value = (value << 5) | B32.indexOf(ch); bits += 5;
      if (bits >= 8) { out.write((value >>> (bits - 8)) & 255); bits -= 8; }
    }
    return out.toByteArray();
  }

  public static String newSecret() { byte[] b = new byte[20]; RND.nextBytes(b); return base32Encode(b); }

  static String hotp(byte[] key, long counter) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(key, "HmacSHA1"));
      byte[] msg = java.nio.ByteBuffer.allocate(8).putLong(counter).array();
      byte[] h = mac.doFinal(msg);
      int o = h[h.length - 1] & 15;
      int code = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
      return String.format("%06d", code % 1_000_000);
    } catch (Exception e) { throw new RuntimeException(e); }
  }

  public static String code(String secret, long timeMs) { return hotp(base32Decode(secret), Math.floorDiv(timeMs, 30000)); }

  /** Trả về bước thời gian khớp (chặn dùng lại mã), hoặc -1. */
  public static long verify(String secret, String code, long lastStep) {
    String c = code == null ? "" : code.replaceAll("\\s+", "");
    if (!c.matches("\\d{6}") || secret == null || secret.isEmpty()) return -1;
    byte[] key = base32Decode(secret);
    long now = Math.floorDiv(System.currentTimeMillis(), 30000);
    for (int d = -1; d <= 1; d++) {
      long step = now + d;
      if (step <= lastStep) continue;
      if (MessageDigest.isEqual(hotp(key, step).getBytes(), c.getBytes())) return step;
    }
    return -1;
  }

  public static String uri(String secret, String account) {
    String issuer = "VM Monitor";
    String label = URLEncoder.encode(issuer + ":" + account, StandardCharsets.UTF_8).replace("+", "%20");
    return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + URLEncoder.encode(issuer, StandardCharsets.UTF_8).replace("+", "%20")
        + "&algorithm=SHA1&digits=6&period=30";
  }

  public static List<String> recoveryCodes(int n) {
    String alphabet = "abcdefghjkmnpqrstuvwxyz23456789";
    List<String> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      byte[] b = new byte[8]; RND.nextBytes(b);
      StringBuilder s = new StringBuilder();
      for (byte x : b) s.append(alphabet.charAt((x & 0xff) % alphabet.length()));
      out.add(s.substring(0, 4) + "-" + s.substring(4));
    }
    return out;
  }

  public static String hashRecovery(String code) {
    try {
      String c = code.trim().toLowerCase().replaceAll("\\s+", "");
      return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(c.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) { throw new RuntimeException(e); }
  }
}
