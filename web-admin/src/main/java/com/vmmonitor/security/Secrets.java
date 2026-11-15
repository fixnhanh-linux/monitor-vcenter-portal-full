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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;

/**
 * Kho bí mật mã hoá AES-256-GCM (mật khẩu vCenter, khoá 2FA…), cùng định dạng với bản Node:
 * data/secret.key (32 byte), data/secrets.json { tên: "v1:iv:tag:dữ liệu" } (base64).
 */
public class Secrets {
  private final Path keyFile, file;
  private final byte[] key;
  private ObjectNode data;
  private static final SecureRandom RND = new SecureRandom();

  public Secrets(Path dir) {
    this.keyFile = dir.resolve("secret.key");
    this.file = dir.resolve("secrets.json");
    try { Files.createDirectories(dir); } catch (Exception ignored) {}
    this.key = loadKey();
    this.data = loadData();
  }

  private byte[] loadKey() {
    try { byte[] k = Files.readAllBytes(keyFile); if (k.length == 32) return k; } catch (Exception ignored) {}
    byte[] k = new byte[32]; RND.nextBytes(k);
    try { Files.write(keyFile, k); } catch (Exception e) { Log.error("Không ghi được " + keyFile + ": " + e.getMessage()); }
    return k;
  }

  private ObjectNode loadData() {
    try { JsonNode n = Json.M.readTree(Files.readString(file)); return n.isObject() ? (ObjectNode) n : Json.obj(); } catch (Exception e) { return Json.obj(); }
  }

  private synchronized void persist() {
    try {
      Path tmp = file.resolveSibling("secrets.json.tmp");
      Files.writeString(tmp, Json.pretty(data), StandardCharsets.UTF_8);
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (Exception e) { Log.error("Không ghi được " + file + ": " + e.getMessage()); }
  }

  public synchronized void set(String name, String value) {
    if (value == null || value.isEmpty()) { del(name); return; }
    try {
      byte[] iv = new byte[12]; RND.nextBytes(iv);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      byte[] out = c.doFinal(value.getBytes(StandardCharsets.UTF_8)); // Java: dữ liệu || tag(16)
      byte[] enc = java.util.Arrays.copyOfRange(out, 0, out.length - 16), tag = java.util.Arrays.copyOfRange(out, out.length - 16, out.length);
      Base64.Encoder b = Base64.getEncoder();
      data.put(name, "v1:" + b.encodeToString(iv) + ":" + b.encodeToString(tag) + ":" + b.encodeToString(enc));
      persist();
    } catch (Exception e) { throw new RuntimeException(e); }
  }

  public synchronized String get(String name) {
    String v = data.path(name).asText("");
    if (v.isEmpty()) return "";
    try {
      String[] p = v.split(":");
      Base64.Decoder d = Base64.getDecoder();
      byte[] iv = d.decode(p[1]), tag = d.decode(p[2]), enc = d.decode(p[3]);
      byte[] all = new byte[enc.length + tag.length];
      System.arraycopy(enc, 0, all, 0, enc.length); System.arraycopy(tag, 0, all, enc.length, tag.length);
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
      return new String(c.doFinal(all), StandardCharsets.UTF_8);
    } catch (Exception e) { return ""; }
  }

  public synchronized boolean has(String name) { return Json.truthy(data.path(name)); }

  public synchronized void del(String name) { if (data.has(name)) { data.remove(name); persist(); } }

  public synchronized void delPrefix(String prefix) {
    boolean changed = false;
    for (Iterator<String> it = new ArrayList<>(iterable(data.fieldNames())).iterator(); it.hasNext(); ) {
      String k = it.next();
      if (k.startsWith(prefix)) { data.remove(k); changed = true; }
    }
    if (changed) persist();
  }

  private static <T> java.util.List<T> iterable(Iterator<T> it) { java.util.List<T> l = new ArrayList<>(); it.forEachRemaining(l::add); return l; }
}
