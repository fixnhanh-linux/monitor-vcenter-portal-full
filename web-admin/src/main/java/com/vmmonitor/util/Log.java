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

import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Ghi log (giống console.log của bản Node), tuỳ chọn ra file có xoay vòng: file.log -> file.1.log -> … -> file.{keep}.log */
public final class Log {
  private Log() {}
  private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
  private static Path file;
  private static long maxBytes = 10L * 1024 * 1024;
  private static int keep = 5;
  private static boolean echo = true;
  private static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);

  public static synchronized void toFile(Path f, boolean alsoConsole) {
    file = f; echo = alsoConsole;
    try { Files.createDirectories(f.getParent()); } catch (IOException ignored) {}
    info("---- ghi log vào " + f + " (xoay vòng " + (maxBytes / 1048576) + " MB × " + keep + " file)");
  }

  public static void info(String msg) { write("log", msg); }
  public static void warn(String msg) { write("warn", msg); }
  public static void error(String msg) { write("error", msg); }
  public static void error(String msg, Throwable t) {
    StringWriter sw = new StringWriter(); t.printStackTrace(new PrintWriter(sw));
    write("error", msg + " " + sw);
  }

  private static synchronized void write(String level, String msg) {
    String line = "[" + LocalDateTime.now().format(TS) + "]" + ("log".equals(level) ? "" : " [" + level.toUpperCase() + "]") + " " + msg + "\n";
    if (file == null || echo) OUT.print(line);
    if (file == null) return;
    try {
      long size = Files.exists(file) ? Files.size(file) : 0;
      if (size + line.length() > maxBytes) rotate();
      Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException ignored) {}
  }

  private static void rotate() throws IOException {
    String name = file.getFileName().toString();
    int dot = name.lastIndexOf('.');
    String base = dot > 0 ? name.substring(0, dot) : name, ext = dot > 0 ? name.substring(dot) : "";
    Path dir = file.getParent();
    Files.deleteIfExists(dir.resolve(base + "." + keep + ext));
    for (int i = keep - 1; i >= 1; i--) {
      Path p = dir.resolve(base + "." + i + ext);
      if (Files.exists(p)) Files.move(p, dir.resolve(base + "." + (i + 1) + ext), StandardCopyOption.REPLACE_EXISTING);
    }
    if (Files.exists(file)) Files.move(file, dir.resolve(base + ".1" + ext), StandardCopyOption.REPLACE_EXISTING);
  }
}
