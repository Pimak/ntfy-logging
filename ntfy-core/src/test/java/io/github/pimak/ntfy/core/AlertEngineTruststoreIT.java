package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * End-to-end proof that {@code truststore-path} actually changes the TLS handshake — the one claim a
 * unit test on {@link javax.net.ssl.SSLContext} construction cannot make.
 *
 * <p>Runs a real {@link HttpsServer} on loopback presenting a certificate signed by a private CA
 * that the JDK's default trust store has never heard of. That is exactly the shape of the problem
 * this feature exists for: a self-hosted ntfy behind a corporate CA, or behind a TLS-inspecting
 * proxy re-signing traffic. Trusting the fixture CA must make the publish succeed; not trusting it
 * must make the same publish fail.
 *
 * <p>The fixtures and how to regenerate them are documented in {@code src/test/resources/tls/README.md}.
 */
class AlertEngineTruststoreIT {

  private static final Path FIXTURES = Path.of("src/test/resources/tls");
  private static final String FIXTURE_PASSWORD = "changeit";

  private HttpsServer server;
  private final List<String> receivedPaths = new CopyOnWriteArrayList<>();

  /** Collects diagnostics so a refusal can be asserted on its exact message. */
  private static final class CapturingDiagnostics implements Diagnostics {
    private final List<String> warns = new ArrayList<>();

    @Override
    public void info(String msg) {}

    @Override
    public void warn(String msg) {
      warns.add(msg);
    }

    @Override
    public void error(String msg, Throwable t) {}
  }

  @BeforeEach
  void startServer() throws Exception {
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    try (InputStream in = Files.newInputStream(FIXTURES.resolve("server.p12"))) {
      keyStore.load(in, FIXTURE_PASSWORD.toCharArray());
    }
    KeyManagerFactory kmf =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(keyStore, FIXTURE_PASSWORD.toCharArray());
    SSLContext serverContext = SSLContext.getInstance("TLS");
    serverContext.init(kmf.getKeyManagers(), null, null);

    server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
    server.createContext(
        "/",
        exchange -> {
          receivedPaths.add(exchange.getRequestURI().getPath());
          byte[] body = "{\"id\":\"1\"}".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.setExecutor(Executors.newSingleThreadExecutor(r -> {
      Thread t = new Thread(r, "ntfy-test-https");
      t.setDaemon(true);
      return t;
    }));
    server.start();
  }

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  /**
   * The endpoint uses {@code localhost} rather than {@code 127.0.0.1} because hostname verification
   * is on (as it must be) — the fixture certificate carries both in its SAN, so either works, but
   * naming the host makes the intent obvious.
   */
  private String endpoint() {
    return "https://localhost:" + server.getAddress().getPort();
  }

  private NtfyConfig.Builder baseConfig() {
    return NtfyConfig.builder()
        .url(endpoint())
        .topic("alerts")
        .maxAlertsPerWindow(10)
        .suppressionWindow(Duration.ofMillis(60_000L))
        // Inline delivery: stop() folds queued-but-unsent events into the storm digest rather than
        // delivering them, so an async engine would not let us observe the individual publish.
        .asyncEnabled(false)
        // The publish must be tested against the CONFIGURED trust material, not routed through
        // whatever proxy the surrounding JVM happens to define (CI runners often define one).
        .proxy("none");
  }

  private static AlertEvent event() {
    return new AlertEvent(
        "com.example.app.Service",
        "boom",
        0L,
        List.of(new AlertEvent.Cause("java.lang.IllegalStateException", "bad")),
        List.of("com.example.app.Service.run(Service.java:1)"),
        Set.of());
  }

  @ParameterizedTest(name = "truststore-type={1}")
  @CsvSource({"ca.p12, PKCS12", "ca.jks, JKS", "ca.crt, PEM"})
  void privateCa_isTrusted_whenTheTruststoreNamesIt(String fixture, String type) {
    NtfyConfig config = baseConfig()
        .truststorePath(FIXTURES.resolve(fixture).toString())
        .truststoreType(type)
        .truststorePassword("PEM".equals(type) ? null : FIXTURE_PASSWORD)
        .build();
    AlertEngine engine = new AlertEngine(config, new CapturingDiagnostics());

    engine.start();
    try {
      assertThat(engine.isStarted()).isTrue();
      engine.submit(event());
    } finally {
      engine.stop();
    }

    assertThat(receivedPaths).containsExactly("/alerts");
    assertThat(engine.counters().published()).isEqualTo(1);
    assertThat(engine.counters().failed()).isZero();
  }

  @Test
  void privateCa_isRejected_onTheDefaultTrustStore() {
    // The control case. Without this assertion the test above could pass because the JDK trusted
    // the certificate all along, proving nothing about the truststore setting.
    NtfyConfig config = baseConfig().build();
    AlertEngine engine = new AlertEngine(config, new CapturingDiagnostics());

    engine.start();
    try {
      assertThat(engine.isStarted()).isTrue();
      engine.submit(event());
    } finally {
      engine.stop();
    }

    assertThat(receivedPaths).isEmpty();
    assertThat(engine.counters().published()).isZero();
    // Two failures, not one: the individual publish fails the handshake, that failure folds into
    // the storm-digest tally, and stop() then flushes the digest — which cannot connect either.
    assertThat(engine.counters().failed()).isEqualTo(2);
  }

  @Test
  void unloadableTruststore_refusesActivation_ratherThanFallBackToDefaultTrust() {
    // The security-critical policy: falling back would silently publish over a CA the operator
    // never pinned. Refusing is loud, and every publish would have failed the handshake anyway.
    CapturingDiagnostics diagnostics = new CapturingDiagnostics();
    NtfyConfig config =
        baseConfig().truststorePath(FIXTURES.resolve("does-not-exist.p12").toString()).build();
    AlertEngine engine = new AlertEngine(config, diagnostics);

    engine.start();

    assertThat(engine.isStarted()).isFalse();
    assertThat(diagnostics.warns)
        .contains(AlertMessages.forLocale(java.util.Locale.ENGLISH).statusInvalidTruststore());
    assertThat(receivedPaths).isEmpty();
  }

  @Test
  void customizerCanSupplyTheTrustMaterialWithoutAnyTruststoreKey() {
    // The escape hatch, exercised against a real handshake: whatever the declarative keys cannot
    // express, a caller can still reach — here by handing over a fully hand-built SSLContext.
    NtfyConfig probe = NtfyConfig.builder()
        .truststorePath(FIXTURES.resolve("ca.p12").toString())
        .truststorePassword(FIXTURE_PASSWORD)
        .build();
    SSLContext handBuilt = NtfyHttpClients.sslContext(probe);

    NtfyConfig config = baseConfig().httpClientCustomizer(b -> b.sslContext(handBuilt)).build();
    AlertEngine engine = new AlertEngine(config, new CapturingDiagnostics());

    engine.start();
    try {
      engine.submit(event());
    } finally {
      engine.stop();
    }

    assertThat(receivedPaths).containsExactly("/alerts");
    assertThat(engine.counters().published()).isEqualTo(1);
  }
}
