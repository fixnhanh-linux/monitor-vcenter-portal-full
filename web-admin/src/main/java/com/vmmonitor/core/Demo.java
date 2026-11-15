package com.vmmonitor.core;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vmmonitor.util.Json;
import com.vmmonitor.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

/**
 * Chế độ demo (--demo hoặc DEMO=1): sinh số liệu giả cho 2 site, ~120 VM của 6 khách hàng, host ESXi và datastore,
 * để chạy thử portal mà không cần vCenter. Tên VM dạng "KhachA_10001-web-01" nên nguồn "tên VM" tự nhận ra khách hàng.
 */
public class Demo {
  static final double GB = 1073741824.0, MB = 1048576.0;
  record Site(String name, String cluster, int hosts) {}
  static final Site[] SITES = {new Site("SITE-HN", "CL-HN-01", 6), new Site("SITE-HCM", "CL-HCM-01", 4)};
  static final String[] CUSTOMERS = {"KhachA", "KhachB", "KhachC", "KhachD", "KhachE", "KhachF"};
  static final String[] ROLES = {"web", "app", "db", "cache", "batch", "win"};

  static final class FakeVm {
    String site, name, ip, os, cluster, host, power; int cores; double ram, disk, base, cpu, mem, used, t; boolean flaky;
  }

  private final Portal p;
  private final Random rnd = new Random(42);
  private final List<FakeVm> vms = new ArrayList<>();

  public Demo(Portal p) {
    this.p = p;
    int id = 10001;
    for (Site s : SITES) {
      for (int i = 0; i < 60; i++) {
        FakeVm v = new FakeVm();
        String cust = CUSTOMERS[i % CUSTOMERS.length], role = ROLES[i % ROLES.length];
        boolean win = "win".equals(role);
        v.site = s.name(); v.name = cust + "_" + (id + (i / CUSTOMERS.length)) + "-" + role + "-" + String.format("%02d", i);
        v.ip = (s.name().equals("SITE-HN") ? "10.10." : "10.20.") + (i % 4) + "." + (10 + i);
        v.os = win ? "Microsoft Windows Server 2022 (64-bit)" : "Ubuntu Linux (64-bit)";
        v.cores = new int[]{2, 4, 8, 16}[i % 4]; v.ram = new int[]{4, 8, 16, 32}[i % 4] * GB; v.disk = new int[]{60, 100, 200, 500}[i % 4] * GB;
        v.base = switch (role) { case "web" -> 25; case "app" -> 40; case "db" -> 55; case "cache" -> 15; case "batch" -> 75; default -> 35; } + rnd.nextDouble() * 15;
        v.cpu = v.base; v.mem = Math.min(95, v.base + 20); v.used = 0.3 + rnd.nextDouble() * 0.62; v.t = rnd.nextDouble() * 100;
        v.cluster = s.cluster(); v.host = "esx" + String.format("%02d", (i % s.hosts()) + 1) + "." + s.name().toLowerCase() + ".local";
        v.power = i % 23 == 7 ? "off" : "on";
        v.flaky = i == 13;
        vms.add(v);
      }
      id += 100;
    }
  }

  public void start() {
    Log.info("CHẾ ĐỘ DEMO: sinh số liệu giả cho " + vms.size() + " VM trên " + SITES.length + " site (không cần vCenter)");
    p.timers.scheduleAtFixedRate(() -> { try { synchronized (p.lock) { tick(); } } catch (Exception e) { Log.error("demo: " + e); } }, 1, 10, TimeUnit.SECONDS);
  }

  static double walk(double v, double lo, double hi, double step, Random r) { return Math.max(lo, Math.min(hi, v + (r.nextDouble() - 0.5) * step)); }
  static double r1(double v) { return Math.round(v * 10) / 10.0; }

  private boolean seen = false;

  void tick() {
    for (FakeVm v : vms) {
      if (v.flaky && seen && (System.currentTimeMillis() / 120000) % 2 == 1) continue; // thỉnh thoảng mất kết nối (sau lần gửi đầu)
      v.t += 0.15;
      v.cpu = walk(v.cpu + (v.base - v.cpu) * 0.05, 1, 99, 14, rnd);
      v.mem = walk(v.mem, 10, 97, 3, rnd);
      v.used = Math.min(0.98, v.used + 0.00002);
      double wave = (Math.sin(v.t) + 1.2) / 2.2, dp = r1(v.used * 100);
      ObjectNode m = Json.obj().put("site", v.site).put("name", v.name).put("hostname", v.name).put("ip", v.ip).put("os", v.os).put("cores", v.cores)
          .put("uptime", 86400 * 12 + v.t * 50).put("source", "vcenter").put("power", v.power).put("cluster", v.cluster).put("esxiHost", v.host)
          .put("datacenter", "DC-" + v.site).put("vcenter", "vcenter." + v.site.toLowerCase() + ".local");
      m.putObject("cpu").put("percent", r1(v.cpu));
      m.putObject("mem").put("total", v.ram).put("used", v.ram * v.mem / 100).put("percent", r1(v.mem));
      ObjectNode disk = m.putObject("disk").put("total", v.disk).put("used", v.disk * v.used).put("percent", dp)
          .put("readRate", wave * 20 * MB * rnd.nextDouble()).put("writeRate", wave * 8 * MB * rnd.nextDouble());
      ArrayNode mounts = disk.putArray("mounts");
      mounts.addObject().put("mount", v.os.contains("Windows") ? "C:\\" : "/").put("total", v.disk).put("used", v.disk * v.used).put("percent", dp);
      m.putObject("net").put("rxRate", wave * 12 * MB * (0.5 + rnd.nextDouble())).put("txRate", wave * 4 * MB * (0.5 + rnd.nextDouble()));
      p.ingest(m);
    }
    // host ESXi + datastore
    ObjectNode body = Json.obj();
    ArrayNode hosts = body.putArray("hosts"), dss = body.putArray("datastores");
    for (Site s : SITES) {
      for (int h = 1; h <= s.hosts(); h++) {
        String name = "esx" + String.format("%02d", h) + "." + s.name().toLowerCase() + ".local";
        long running = vms.stream().filter((v) -> v.host.equals(name) && "on".equals(v.power)).count();
        hosts.addObject().put("site", s.name()).put("name", name).put("cluster", s.cluster()).put("datacenter", "DC-" + s.name()).put("vcenter", "vcenter." + s.name().toLowerCase() + ".local")
            .put("connection", "connected").put("power", "poweredOn").put("maintenance", false).put("health", "green").put("cores", 32).put("threads", 64)
            .put("cpuTotalMhz", 32 * 2600).put("memTotal", 512 * GB).put("uptime", 86400 * 40).put("version", "VMware ESXi 8.0.3").put("vendor", "Dell Inc.").put("model", "PowerEdge R750")
            .put("vms", running + 2).put("vmsRunning", running).put("cpu", r1(20 + rnd.nextDouble() * 40)).put("mem", r1(55 + rnd.nextDouble() * 25))
            .put("rx", 50 * MB * rnd.nextDouble()).put("tx", 30 * MB * rnd.nextDouble()).put("rd", 40 * MB * rnd.nextDouble()).put("wr", 20 * MB * rnd.nextDouble())
            .put("cpuUsedMhz", 32 * 2600 * 0.4).put("memUsed", 512 * GB * 0.65);
      }
      for (int d = 1; d <= 4; d++) {
        double cap = 20 * 1024 * GB, pct = d == 4 ? 88 : 45 + d * 10;
        dss.addObject().put("site", s.name()).put("name", "DS-" + s.name() + "-" + String.format("%02d", d)).put("datacenter", "DC-" + s.name()).put("vcenter", "vcenter." + s.name().toLowerCase() + ".local")
            .put("type", d == 1 ? "vsan" : "VMFS").put("accessible", true).put("capacity", cap).put("free", cap * (1 - pct / 100)).put("used", cap * pct / 100)
            .put("provisioned", cap * (pct / 100 + 0.3)).put("percent", pct).put("hosts", s.hosts()).put("vms", 30);
        ArrayNode hn = ((ObjectNode) dss.get(dss.size() - 1)).putArray("hostNames");
        for (int h = 1; h <= s.hosts(); h++) hn.add("esx" + String.format("%02d", h) + "." + s.name().toLowerCase() + ".local");
      }
    }
    p.infra.ingest(body);
    seen = true;
  }
}
