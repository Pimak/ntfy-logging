package io.github.pimak.ntfy.core;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

/**
 * Proves the {@code proxy} setting reaches the wire, not just the {@link java.net.ProxySelector}.
 *
 * <p>The trick that makes this testable with no new dependency and no CONNECT tunnel: for a plain
 * {@code http://} target the JDK does not tunnel, it sends the request straight to the proxy with an
 * ABSOLUTE request URI ({@code POST http://ntfy.internal/alerts}) instead of the usual origin form
 * ({@code POST /alerts}). So an ordinary WireMock instance can stand in for the proxy, and the
 * absolute URI in the log is the proof that the request was proxied rather than sent direct.
 *
 * <p>A hostname that does not resolve is used as the ntfy endpoint on purpose: if the proxy setting
 * were ignored, the publish could not accidentally succeed by reaching a real server.
 */
@WireMockTest
class AlertEngineProxyIT {

  private static final String UNRESOLVABLE_ENDPOINT = "http://ntfy.internal.invalid";

  private static final Diagnostics NO_OP =
      new Diagnostics() {
        @Override
        public void info(String msg) {}

        @Override
        public void warn(String msg) {}

        @Override
        public void error(String msg, Throwable t) {}
      };

  private static AlertEngine engine(String url, String proxy) {
    NtfyConfig config = NtfyConfig.builder()
        .url(url)
        .topic("alerts")
        .maxAlertsPerWindow(10)
        .suppressionWindow(Duration.ofMillis(60_000L))
        .asyncEnabled(false)
        .proxy(proxy)
        .build();
    return new AlertEngine(config, NO_OP);
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

  @Test
  void explicitProxy_routesThePublishThroughIt(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    AlertEngine engine = engine(UNRESOLVABLE_ENDPOINT, "localhost:" + wm.getHttpPort());

    engine.start();
    try {
      assertThat(engine.isStarted()).isTrue();
      engine.submit(event());
    } finally {
      engine.stop();
    }

    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    assertThat(requests).hasSize(1);
    // Absolute-form request target: only a proxied request looks like this.
    assertThat(requests.get(0).getAbsoluteUrl())
        .isEqualTo(UNRESOLVABLE_ENDPOINT + "/alerts");
    assertThat(engine.counters().published()).isEqualTo(1);
  }

  @Test
  void proxyNone_bypassesTheProxyAndSoNeverReachesTheServer(WireMockRuntimeInfo wm) {
    // The control case for the test above, and the reason `none` exists: it must be able to escape
    // a proxy even when one is configured JVM-wide. Direct to an unresolvable host => no request.
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    AlertEngine engine = engine(UNRESOLVABLE_ENDPOINT, "none");

    engine.start();
    try {
      engine.submit(event());
    } finally {
      engine.stop();
    }

    assertThat(findAll(postRequestedFor(urlEqualTo("/alerts")))).isEmpty();
    assertThat(engine.counters().published()).isZero();
  }

  @Test
  void proxyNone_stillPublishesToADirectlyReachableServer(WireMockRuntimeInfo wm) {
    // Guards against the previous test passing for the wrong reason (e.g. `none` breaking delivery
    // outright rather than merely declining to proxy).
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    AlertEngine engine = engine("http://localhost:" + wm.getHttpPort(), "none");

    engine.start();
    try {
      engine.submit(event());
    } finally {
      engine.stop();
    }

    assertThat(engine.counters().published()).isEqualTo(1);
    assertThat(findAll(postRequestedFor(urlEqualTo("/alerts")))).hasSize(1);
  }
}
