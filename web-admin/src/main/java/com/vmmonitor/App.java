package com.vmmonitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.vmmonitor.config.Cfg;
import com.vmmonitor.core.Portal;
import com.vmmonitor.security.Auth;
import com.vmmonitor.util.Json;
import com.vmmonitor.util.Log;
import com.vmmonitor.web.Router;
import org.apache.catalina.connector.Connector;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * VM Monitor Portal — bản Java / Spring Boot. Dùng chung config.json, data/ và giao diện public/ với bản Node.
 *
 *   java -jar vm-monitor-admin.jar [--demo] [--root <thư mục dữ liệu>] [--config config.json] [--port 3000] [--log-file data/portal.log] [--ui <thư mục giao diện>]
 *   java -jar vm-monitor-admin.jar adduser <tên> [--role admin|viewer]
 *   java -jar vm-monitor-admin.jar apikey  <tên> [--role read|ingest|collector|admin]
 */
@SpringBootApplication
public class App {
  static Portal portal;

  public static void main(String[] argv) throws Exception {
    Map<String, String> args = new HashMap<>();
    for (int i = 0; i < argv.length; i++) if (argv[i].startsWith("--")) args.put(argv[i].substring(2), i + 1 < argv.length && !argv[i + 1].startsWith("--") ? argv[i + 1] : "true");
    Path root = Path.of(args.getOrDefault("root", System.getenv().getOrDefault("MONITOR_ROOT", "."))).toAbsolutePath().normalize();
    String cfgArg = args.getOrDefault("config", System.getenv("MONITOR_CONFIG"));
    Path configFile = cfgArg != null ? Path.of(cfgArg).toAbsolutePath() : root.resolve("config.json");

    if (argv.length > 0 && "adduser".equals(argv[0])) { Auth.addUserCli(new Cfg(configFile), argv.length > 1 ? argv[1] : null, args.get("role")); return; }
    if (argv.length > 0 && "apikey".equals(argv[0])) { Auth.genKeyCli(new Cfg(configFile), argv.length > 1 ? argv[1] : null, args.get("role")); return; }
    if (args.containsKey("log-file")) Log.toFile(Path.of(args.get("log-file")).toAbsolutePath(), args.containsKey("log-echo"));

    String ui = args.getOrDefault("ui", System.getenv("MONITOR_UI"));
    portal = new Portal(root, configFile, ui == null ? null : Path.of(ui).toAbsolutePath().normalize());
    JsonNode cfg = portal.cfg.get();
    String port = args.getOrDefault("port", System.getenv().getOrDefault("PORT", Json.num(cfg.path("port")) > 0 ? Json.str(cfg.path("port")) : "3000"));

    SpringApplication app = new SpringApplication(App.class);
    Map<String, Object> props = new HashMap<>();
    props.put("server.port", port);
    if (Json.truthy(cfg.path("host"))) props.put("server.address", Json.str(cfg.path("host")));
    props.put("spring.main.banner-mode", "off");
    props.put("logging.level.root", "WARN");
    props.put("server.error.whitelabel.enabled", "false");
    props.put("server.tomcat.encoded-solidus-handling", "passthrough"); // tên VM có "/" (mã hoá %2F) vẫn tới được router
    props.put("server.tomcat.max-swallow-size", "-1");
    props.put("server.tomcat.relaxed-query-chars", "|,{,},[,]");
    props.put("server.tomcat.relaxed-path-chars", "|,{,},[,]");
    app.setDefaultProperties(props);
    app.run(argv);
    portal.start();
    if (args.containsKey("demo") || "1".equals(System.getenv("DEMO")) || "true".equalsIgnoreCase(System.getenv("DEMO"))) new com.vmmonitor.core.Demo(portal).start();
    Log.info("VM Monitor Portal (Java): http://localhost:" + port + "   API: http://localhost:" + port + "/docs");
  }

  /** Mọi đường dẫn đi qua Router (thay cho DispatcherServlet của Spring MVC). */
  @Bean
  public ServletRegistrationBean<Router> router() {
    ServletRegistrationBean<Router> b = new ServletRegistrationBean<>(new Router(portal), "/*");
    b.setAsyncSupported(true);
    b.setLoadOnStartup(1);
    return b;
  }

  /** HTTPS trên cổng riêng (Cài đặt → Người dùng & bảo mật): file .pfx hoặc cặp cert + key PEM. */
  @Bean
  public WebServerFactoryCustomizer<TomcatServletWebServerFactory> https() {
    return (factory) -> {
      JsonNode h = portal.cfg.get().path("https");
      if (!Json.truthy(h.path("enabled"))) return;
      try {
        int port = Json.num(h.path("port")) > 0 ? (int) Json.num(h.path("port")) : 3443;
        Path base = portal.cfg.file.toAbsolutePath().getParent();
        Connector con = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        con.setPort(port); con.setScheme("https"); con.setSecure(true);
        con.setProperty("SSLEnabled", "true");
        SSLHostConfig ssl = new SSLHostConfig();
        SSLHostConfigCertificate cert = new SSLHostConfigCertificate(ssl, SSLHostConfigCertificate.Type.UNDEFINED);
        if (Json.truthy(h.path("pfx"))) {
          cert.setCertificateKeystoreFile(base.resolve(Json.str(h.path("pfx"))).toString());
          cert.setCertificateKeystoreType("PKCS12");
          String pass = portal.secrets.get("https:passphrase");
          if (!pass.isEmpty()) cert.setCertificateKeystorePassword(pass);
        } else {
          cert.setCertificateFile(base.resolve(Json.str(h.path("cert"))).toString());
          cert.setCertificateKeyFile(base.resolve(Json.str(h.path("key"))).toString());
        }
        ssl.addCertificate(cert);
        con.addSslHostConfig(ssl);
        factory.addAdditionalTomcatConnectors(con);
        portal.httpsState.put("running", true).put("port", port).put("error", "");
        Log.info("HTTPS: https://localhost:" + port);
      } catch (Exception e) {
        portal.httpsState.put("running", false).put("error", String.valueOf(e.getMessage()));
        Log.error("Không bật được HTTPS: " + e.getMessage());
      }
    };
  }
}
