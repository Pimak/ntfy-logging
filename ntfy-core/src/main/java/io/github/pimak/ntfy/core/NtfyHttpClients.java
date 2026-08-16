package io.github.pimak.ntfy.core;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.Collection;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.UnaryOperator;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * The single place an {@link HttpClient} is built for this library — used by both {@link AlertEngine}
 * (alert pipeline) and {@link NtfyClient} (ad-hoc publishes), which previously each built their own
 * and had already drifted apart.
 *
 * <p><strong>What the JDK already does, and why most of this class is opt-in.</strong> A bare {@code
 * HttpClient.newBuilder().build()} already routes through {@link ProxySelector#getDefault()} — so
 * {@code -Dhttps.proxyHost}, {@code -Dhttps.proxyPort} and {@code -Dhttp.nonProxyHosts} are honored
 * with no code at all — and its SSL context already IS {@link SSLContext#getDefault()}, so
 * {@code -Djavax.net.ssl.trustStore} is honored too. Setting {@code proxy(ProxySelector.getDefault())}
 * would be a literal no-op. What the JVM-wide switches cannot do is *scope* either setting to ntfy:
 * {@code javax.net.ssl.trustStore} REPLACES the JDK's {@code cacerts} wholesale, so trusting a
 * corporate CA that way re-points every TLS connection the application makes. That scoping — plus
 * being configurable from an application's own config file rather than a JVM flag — is what the
 * settings here exist for. Every one of them is unset by default, and an untouched config produces
 * exactly the client the library built before they existed.
 *
 * <p>Beware one trap when reasoning about this: {@link HttpClient#proxy()} returns an empty optional
 * even while the client is actively proxying through the default selector, because that selector is
 * not exposed. It is not a usable probe, in production code or in a test assertion.
 */
final class NtfyHttpClients {

  /** Value of {@code proxy} meaning "inherit the JVM's default proxy selector" — the default. */
  private static final String PROXY_SYSTEM = "system";

  /** Value of {@code proxy} meaning "connect directly, ignoring any JVM-wide proxy". */
  private static final String PROXY_NONE = "none";

  /** {@code truststore-type} value selecting the PEM reader rather than a {@link KeyStore} format. */
  private static final String TRUSTSTORE_TYPE_PEM = "PEM";

  /** Keystore type assumed when {@code truststore-type} is unset. */
  private static final String DEFAULT_TRUSTSTORE_TYPE = "PKCS12";

  private NtfyHttpClients() {}

  /**
   * Builds the client for the supplied config.
   *
   * <p>Application order is deliberate: timeouts, then executor, then proxy, then SSL context, and
   * the caller-supplied {@link NtfyConfig#getHttpClientCustomizer() customizer} strictly last — so a
   * programmatic customizer can override anything the declarative settings decided, which is the
   * whole point of having an escape hatch.
   *
   * @param connectTimeout the ALREADY-VALIDATED connect timeout (callers apply their own fallback
   *     policy before calling; this method does not second-guess it)
   * @param executor executor for the client's internal machinery, or {@code null} to let the JDK
   *     supply its own
   * @throws NtfyTlsException if a {@code truststore-path} is configured but cannot be turned into a
   *     trust store — never silently falls back to the default trust store, which would be an
   *     invisible downgrade of who the process trusts. A malformed {@code proxy}, by contrast, is
   *     tolerated here and degrades to the JVM default selector.
   */
  static HttpClient create(NtfyConfig config, Duration connectTimeout, Executor executor) {
    HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(connectTimeout);
    if (executor != null) {
      builder.executor(executor);
    }

    // Lenient here, loud at the caller: AlertEngine.start() has already validated the value and
    // emitted statusInvalidProxy() for a malformed one. Swallowing it a second time is what makes
    // "warn and fall back to the JVM default selector" actually happen instead of the engine
    // blowing up on a value it just told the operator it would ignore.
    ProxySelector proxySelector;
    try {
      proxySelector = proxySelector(config.getProxy());
    } catch (IllegalArgumentException e) {
      proxySelector = null;
    }
    if (proxySelector != null) {
      builder.proxy(proxySelector);
    }

    SSLContext sslContext = sslContext(config);
    if (sslContext != null) {
      builder.sslContext(sslContext);
    }

    UnaryOperator<HttpClient.Builder> customizer = config.getHttpClientCustomizer();
    if (customizer != null) {
      HttpClient.Builder customized = customizer.apply(builder);
      // A customizer that returns null (e.g. a lambda written as a statement) would otherwise NPE
      // deep inside build() with no hint about where it came from. Treat it as "no change".
      if (customized != null) {
        builder = customized;
      }
    }
    return builder.build();
  }

  /**
   * Resolves the {@code proxy} setting to a selector, or {@code null} to leave the JDK default in
   * place (which already honors the JVM's proxy system properties).
   *
   * <p>Accepts {@code system} (or unset), {@code none}, and {@code host:port}. There is deliberately
   * no "non-proxy hosts" companion setting: this client talks to exactly one host, so the answer to
   * "don't proxy this one" is simply {@code none}.
   *
   * @throws IllegalArgumentException if the value is neither keyword nor a valid {@code host:port}
   */
  static ProxySelector proxySelector(String proxy) {
    if (proxy == null || proxy.isBlank()) {
      return null;
    }
    String trimmed = proxy.trim();
    if (trimmed.equalsIgnoreCase(PROXY_SYSTEM)) {
      return null;
    }
    if (trimmed.equalsIgnoreCase(PROXY_NONE)) {
      return HttpClient.Builder.NO_PROXY;
    }

    // host:port. rindex, not index, so a bracketed IPv6 literal ([::1]:3128) splits at the right
    // colon instead of the first one inside the address.
    int separator = trimmed.lastIndexOf(':');
    if (separator <= 0 || separator == trimmed.length() - 1) {
      throw new IllegalArgumentException("proxy must be 'system', 'none', or 'host:port'");
    }
    String host = trimmed.substring(0, separator).trim();
    if (host.startsWith("[") && host.endsWith("]")) {
      host = host.substring(1, host.length() - 1);
    }
    if (host.isEmpty()) {
      throw new IllegalArgumentException("proxy host is empty");
    }
    int port;
    try {
      port = Integer.parseInt(trimmed.substring(separator + 1).trim());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException("proxy port is not a number");
    }
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("proxy port is out of range");
    }
    // createUnresolved: no DNS lookup at construction time. The proxy may legitimately not resolve
    // yet when logging initializes, and resolving here would also bake a build-time address into a
    // GraalVM native image.
    return ProxySelector.of(InetSocketAddress.createUnresolved(host, port));
  }

  /**
   * Builds the SSL context for a configured {@code truststore-path}, or {@code null} when none is
   * set so the JDK default context (and therefore {@code -Djavax.net.ssl.trustStore}) applies
   * unchanged.
   *
   * <p>The configured store REPLACES the default trust anchors rather than adding to them — the same
   * semantics as {@code javax.net.ssl.trustStore} and as Log4j2's {@code SslConfiguration}. Scoped
   * to this client, that is the intent: trust exactly the CA that signed your ntfy server, and
   * nothing else, without touching what the rest of the application trusts.
   *
   * @throws NtfyTlsException if the store cannot be read or parsed
   */
  static SSLContext sslContext(NtfyConfig config) {
    String path = config.getTruststorePath();
    if (path == null || path.isBlank()) {
      return null;
    }
    try {
      KeyStore trustStore = loadTrustStore(path.trim(), config.getTruststoreType(),
          config.getTruststorePassword());
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(trustStore);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, tmf.getTrustManagers(), null);
      return context;
    } catch (NtfyTlsException e) {
      throw e;
    } catch (Exception e) {
      // Deliberately generic and detail-free: the cause can carry the store path and, for a wrong
      // password, provider text an operator may paste into a public issue. The message is fixed;
      // the cause is attached for whoever is holding the exception.
      throw new NtfyTlsException("truststore could not be loaded", e);
    }
  }

  /** Reads the trust store from disk in whichever of the three supported shapes it is in. */
  private static KeyStore loadTrustStore(String path, String type, String password)
      throws Exception {
    Path file = Path.of(path);
    if (!Files.isReadable(file)) {
      throw new NtfyTlsException("truststore path is not readable", null);
    }
    String effectiveType = (type == null || type.isBlank())
        ? DEFAULT_TRUSTSTORE_TYPE
        : type.trim().toUpperCase(Locale.ROOT);

    if (TRUSTSTORE_TYPE_PEM.equals(effectiveType)) {
      return pemTrustStore(file);
    }

    KeyStore store = KeyStore.getInstance(effectiveType);
    // A null password is valid for a PKCS12/JKS store whose integrity is not password-protected;
    // passing null (rather than an empty array) is what skips the integrity check.
    char[] chars = (password == null || password.isEmpty()) ? null : password.toCharArray();
    try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
      store.load(in, chars);
    }
    return store;
  }

  /**
   * Builds an in-memory trust store from a PEM file holding one or more X.509 certificates.
   *
   * <p>This is the shape a corporate CA actually turns up in on Kubernetes — a {@code ca.crt} key
   * mounted from a ConfigMap or projected by cert-manager — so supporting it directly saves every
   * operator a {@code keytool -import} step and a second artifact to ship in the image.
   */
  private static KeyStore pemTrustStore(Path file) throws Exception {
    KeyStore store = KeyStore.getInstance(DEFAULT_TRUSTSTORE_TYPE);
    store.load(null, null);
    CertificateFactory factory = CertificateFactory.getInstance("X.509");
    Collection<? extends Certificate> certificates;
    try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
      certificates = factory.generateCertificates(in);
    }
    if (certificates.isEmpty()) {
      throw new NtfyTlsException("truststore PEM file contains no certificate", null);
    }
    int index = 0;
    for (Certificate certificate : certificates) {
      store.setCertificateEntry("ntfy-pem-" + index++, certificate);
    }
    return store;
  }

  /**
   * Signals that a configured trust store could not be turned into an {@link SSLContext}. Carries no
   * detail in its message on purpose — see {@link #sslContext}.
   */
  static final class NtfyTlsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    NtfyTlsException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
