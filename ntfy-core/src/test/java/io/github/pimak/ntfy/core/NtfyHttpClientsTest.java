package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit contract for the single {@link HttpClient} construction site.
 *
 * <p>Everything here asserts on the {@link ProxySelector} / {@link javax.net.ssl.SSLContext} that
 * would be handed to the builder, never on the built client's accessors. That is deliberate:
 * {@link HttpClient#proxy()} returns an EMPTY optional even when the client is actively proxying
 * through the JDK's default selector, because that selector is not exposed. Asserting on it would
 * produce a test that passes for the wrong reason. The end-to-end proof that a trust store really
 * changes the handshake lives in {@code AlertEngineTruststoreIT}.
 */
class NtfyHttpClientsTest {

  private static final Path FIXTURES = Path.of("src/test/resources/tls");
  private static final String FIXTURE_PASSWORD = "changeit";

  // --- proxy resolution ---

  @Test
  void proxySelector_unsetOrSystem_keepsTheJdkDefault() {
    // null means "do not call builder.proxy(..) at all", which leaves the JDK default selector in
    // place. That default already honors -Dhttps.proxyHost/-Dhttp.nonProxyHosts, so `system` is a
    // genuine no-op rather than a synonym for "no proxy".
    assertThat(NtfyHttpClients.proxySelector(null)).isNull();
    assertThat(NtfyHttpClients.proxySelector("")).isNull();
    assertThat(NtfyHttpClients.proxySelector("   ")).isNull();
    assertThat(NtfyHttpClients.proxySelector("system")).isNull();
    assertThat(NtfyHttpClients.proxySelector("  SySTeM  ")).isNull();
  }

  @Test
  void proxySelector_none_forcesADirectConnection() {
    // The escape from a JVM-wide proxy that cannot reach an internal ntfy server.
    assertThat(NtfyHttpClients.proxySelector("none")).isSameAs(HttpClient.Builder.NO_PROXY);
    assertThat(NtfyHttpClients.proxySelector("NONE")).isSameAs(HttpClient.Builder.NO_PROXY);
  }

  @Test
  void proxySelector_hostPort_routesThroughThatProxy() {
    ProxySelector selector = NtfyHttpClients.proxySelector("proxy.corp.example.com:3128");

    List<Proxy> proxies = selector.select(URI.create("https://ntfy.example.com/alerts"));
    assertThat(proxies).hasSize(1);
    assertThat(proxies.get(0).type()).isEqualTo(Proxy.Type.HTTP);
    InetSocketAddress address = (InetSocketAddress) proxies.get(0).address();
    assertThat(address.getHostString()).isEqualTo("proxy.corp.example.com");
    assertThat(address.getPort()).isEqualTo(3128);
    // Unresolved on purpose: no DNS lookup at construction time, so logging initialization does not
    // depend on the proxy resolving yet and a native image cannot bake in a build-time address.
    assertThat(address.isUnresolved()).isTrue();
  }

  @Test
  void proxySelector_ipv6Literal_splitsOnTheRightColon() {
    // A naive indexOf(':') would split inside the address and produce host "[" / port garbage.
    ProxySelector selector = NtfyHttpClients.proxySelector("[2001:db8::1]:8080");

    InetSocketAddress address =
        (InetSocketAddress) selector.select(URI.create("https://ntfy.example.com/")).get(0).address();
    assertThat(address.getHostString()).isEqualTo("2001:db8::1");
    assertThat(address.getPort()).isEqualTo(8080);
  }

  @ParameterizedTest
  @ValueSource(strings = {"proxy.example.com", "proxy.example.com:", ":3128", "host:notaport",
      "host:0", "host:65536", "host:-1"})
  void proxySelector_malformed_throws(String raw) {
    // Throwing is what lets AlertEngine.start() emit statusInvalidProxy(); the engine then keeps
    // running on the JVM default selector rather than refusing to alert over a routing typo.
    assertThatThrownBy(() -> NtfyHttpClients.proxySelector(raw))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void create_malformedProxy_degradesToTheDefaultSelectorInsteadOfThrowing() {
    // The counterpart to the warn-and-continue policy: start() has already warned, so building the
    // client must not then blow up on the value it just said it would ignore.
    NtfyConfig config = NtfyConfig.builder().proxy("nonsense").build();

    assertThatCode(() -> NtfyHttpClients.create(config, Duration.ofSeconds(5), null))
        .doesNotThrowAnyException();
  }

  // --- trust store ---

  @Test
  void sslContext_noTruststoreConfigured_keepsTheJdkDefault() {
    // null means "do not call builder.sslContext(..)", so -Djavax.net.ssl.trustStore still applies.
    assertThat(NtfyHttpClients.sslContext(NtfyConfig.builder().build())).isNull();
    assertThat(NtfyHttpClients.sslContext(NtfyConfig.builder().truststorePath("  ").build()))
        .isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"ca.p12", "ca.jks", "ca.crt"})
  void sslContext_everySupportedFormat_yieldsAContextTrustingOnlyTheFixtureCa(String fixture) {
    String type = switch (fixture) {
      case "ca.jks" -> "JKS";
      case "ca.crt" -> "PEM";
      default -> "PKCS12";
    };
    NtfyConfig config = NtfyConfig.builder()
        .truststorePath(FIXTURES.resolve(fixture).toString())
        .truststoreType(type)
        .truststorePassword("PEM".equals(type) ? null : FIXTURE_PASSWORD)
        .build();

    assertThat(NtfyHttpClients.sslContext(config)).isNotNull();
  }

  @Test
  void sslContext_typeIsCaseInsensitiveAndDefaultsToPkcs12() {
    NtfyConfig lowercase = NtfyConfig.builder()
        .truststorePath(FIXTURES.resolve("ca.jks").toString())
        .truststoreType("jks")
        .truststorePassword(FIXTURE_PASSWORD)
        .build();
    assertThat(NtfyHttpClients.sslContext(lowercase)).isNotNull();

    // No truststore-type at all: PKCS12 is assumed, so a .p12 needs no extra configuration.
    NtfyConfig defaulted = NtfyConfig.builder()
        .truststorePath(FIXTURES.resolve("ca.p12").toString())
        .truststorePassword(FIXTURE_PASSWORD)
        .build();
    assertThat(NtfyHttpClients.sslContext(defaulted)).isNotNull();
  }

  @Test
  void sslContext_missingPath_failsRatherThanFallingBackToDefaultTrust() {
    NtfyConfig config =
        NtfyConfig.builder().truststorePath(FIXTURES.resolve("nope.p12").toString()).build();

    assertThatThrownBy(() -> NtfyHttpClients.sslContext(config))
        .isInstanceOf(NtfyHttpClients.NtfyTlsException.class);
  }

  @Test
  void sslContext_wrongPassword_fails() {
    NtfyConfig config = NtfyConfig.builder()
        .truststorePath(FIXTURES.resolve("ca.p12").toString())
        .truststorePassword("not-the-password")
        .build();

    assertThatThrownBy(() -> NtfyHttpClients.sslContext(config))
        .isInstanceOf(NtfyHttpClients.NtfyTlsException.class);
  }

  @Test
  void sslContext_wrongTypeForTheFile_fails() {
    NtfyConfig config = NtfyConfig.builder()
        .truststorePath(FIXTURES.resolve("ca.crt").toString())
        .truststoreType("PKCS12")
        .build();

    assertThatThrownBy(() -> NtfyHttpClients.sslContext(config))
        .isInstanceOf(NtfyHttpClients.NtfyTlsException.class);
  }

  @Test
  void sslContext_emptyPemFile_failsInsteadOfTrustingNothingSilently() throws Exception {
    // An empty ca.crt is a realistic Kubernetes accident (ConfigMap key present but unpopulated).
    // Trusting an empty anchor set would fail every handshake with no explanation.
    Path empty = Files.createTempFile("ntfy-empty", ".crt");
    try {
      NtfyConfig config =
          NtfyConfig.builder().truststorePath(empty.toString()).truststoreType("PEM").build();

      assertThatThrownBy(() -> NtfyHttpClients.sslContext(config))
          .isInstanceOf(NtfyHttpClients.NtfyTlsException.class);
    } finally {
      Files.deleteIfExists(empty);
    }
  }

  @Test
  void sslContext_failureMessageCarriesNoPathAndNoPassword(@TempDir Path dir) {
    // The whole catalog is credential-safe by construction; this exception must not be the hole in
    // it. An operator pasting the stack trace into a public issue must not leak the password.
    NtfyConfig config = NtfyConfig.builder()
        .truststorePath(dir.resolve("secret-location.p12").toString())
        .truststorePassword("hunter2")
        .build();

    assertThatThrownBy(() -> NtfyHttpClients.sslContext(config))
        .isInstanceOf(NtfyHttpClients.NtfyTlsException.class)
        .satisfies(e -> {
          assertThat(e.getMessage()).doesNotContain("hunter2").doesNotContain("secret-location");
        });
  }

  // --- customizer ---

  @Test
  void create_customizerRunsLastAndCanOverrideADeclarativeSetting() {
    // The escape hatch is only useful if it wins. Here the config asks for an explicit proxy and
    // the customizer overrides it with NO_PROXY; the customizer's choice must be the one in effect.
    boolean[] invoked = {false};
    NtfyConfig config = NtfyConfig.builder()
        .proxy("proxy.corp.example.com:3128")
        .httpClientCustomizer(b -> {
          invoked[0] = true;
          return b.proxy(HttpClient.Builder.NO_PROXY);
        })
        .build();

    try (HttpClient client = NtfyHttpClients.create(config, Duration.ofSeconds(5), null)) {
      assertThat(invoked[0]).isTrue();
      // Here proxy() IS meaningful: NO_PROXY was set explicitly, so it is exposed. (An absent
      // optional would still be ambiguous — which is why no other test reads this accessor.)
      assertThat(client.proxy()).contains(HttpClient.Builder.NO_PROXY);
    }
  }

  @Test
  void create_customizerReturningNull_isTreatedAsNoChange() {
    // A customizer written as a statement lambda that forgets to return would otherwise NPE deep
    // inside build() with nothing pointing back at the caller's code.
    NtfyConfig config = NtfyConfig.builder().httpClientCustomizer(b -> null).build();

    assertThatCode(() -> NtfyHttpClients.create(config, Duration.ofSeconds(5), null).close())
        .doesNotThrowAnyException();
  }

  @Test
  void create_noTransportSettings_buildsTheSameClientAsBefore() {
    // The non-regression guarantee: an untouched config must not opt into any of this.
    NtfyConfig config = NtfyConfig.builder().build();

    try (HttpClient client = NtfyHttpClients.create(config, Duration.ofSeconds(5), null)) {
      assertThat(client.connectTimeout()).contains(Duration.ofSeconds(5));
      assertThat(client.proxy()).isEmpty();
    }
  }
}
