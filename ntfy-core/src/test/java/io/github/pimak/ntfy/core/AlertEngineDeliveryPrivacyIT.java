package io.github.pimak.ntfy.core;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Drives a real {@link AlertEngine} against a loopback server and asserts what {@code cache} and
 * {@code firebase} put on the wire.
 *
 * <p>The default cases matter as much as the opt-outs: ntfy caches and forwards to Firebase unless
 * told otherwise, so an affirmative header would be a no-op that merely widens every request. The
 * absence of a header IS the default, and asserting that absence is what keeps a future refactor
 * from "helpfully" always sending one.
 */
@WireMockTest
class AlertEngineDeliveryPrivacyIT {

  private static final Diagnostics NO_OP =
      new Diagnostics() {
        @Override
        public void info(String msg) {}

        @Override
        public void warn(String msg) {}

        @Override
        public void error(String msg, Throwable t) {}
      };

  private static LoggedRequest publishOnce(WireMockRuntimeInfo wm, Consumer<NtfyConfig.Builder> tune) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    NtfyConfig.Builder builder =
        NtfyConfig.builder()
            .url("http://localhost:" + wm.getHttpPort())
            .topic("alerts")
            .maxAlertsPerWindow(10)
            .suppressionWindow(java.time.Duration.ofMillis(60_000L))
            // Inline delivery: an async individual alert would still be queued at stop() and fold
            // into the digest instead of reaching the wire as itself.
            .asyncEnabled(false);
    tune.accept(builder);

    AlertEngine engine = new AlertEngine(builder.build(), NO_OP);
    engine.start();
    engine.submit(AlertEvent.of("com.example.app.Service", "boom", 0L));
    engine.stop();

    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    assertThat(requests).isNotEmpty();
    return requests.get(0);
  }

  @Test
  void byDefaultNeitherHeaderIsSent(WireMockRuntimeInfo wm) {
    LoggedRequest request = publishOnce(wm, b -> {});

    assertThat(request.containsHeader("Cache")).isFalse();
    assertThat(request.containsHeader("Firebase")).isFalse();
  }

  @Test
  void cacheDisabledSendsCacheNo(WireMockRuntimeInfo wm) {
    LoggedRequest request = publishOnce(wm, b -> b.cache(false));

    assertThat(request.getHeader("Cache")).isEqualTo("no");
    assertThat(request.containsHeader("Firebase")).isFalse();
  }

  @Test
  void firebaseDisabledSendsFirebaseNo(WireMockRuntimeInfo wm) {
    LoggedRequest request = publishOnce(wm, b -> b.firebase(false));

    assertThat(request.getHeader("Firebase")).isEqualTo("no");
    assertThat(request.containsHeader("Cache")).isFalse();
  }

  @Test
  void theTwoAreIndependentAndCanBothBeDisabled(WireMockRuntimeInfo wm) {
    // The combination that must stay reachable is the MIXED one below; this only proves the pair
    // does not interfere.
    LoggedRequest request = publishOnce(wm, b -> b.cache(false).firebase(false));

    assertThat(request.getHeader("Cache")).isEqualTo("no");
    assertThat(request.getHeader("Firebase")).isEqualTo("no");
  }

  @Test
  void firebaseOffWithCachingOnIsReachable(WireMockRuntimeInfo wm) {
    // The reason these are two keys rather than one "private delivery" switch: a self-hosted server
    // with no FCM configured legitimately wants no Firebase forwarding AND server-side caching, so
    // offline subscribers still get their alerts. Coupling the two would make this unreachable.
    LoggedRequest request = publishOnce(wm, b -> b.firebase(false).cache(true));

    assertThat(request.getHeader("Firebase")).isEqualTo("no");
    assertThat(request.containsHeader("Cache")).isFalse();
  }
}
