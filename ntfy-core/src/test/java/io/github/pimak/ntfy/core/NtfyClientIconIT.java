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
import org.junit.jupiter.api.Test;

/**
 * The icon on the NOTIFICATION path, which behaves differently from the alert path and until now
 * had no test at all.
 *
 * <p>Two differences, both deliberate and both worth pinning. A notification has no logger, so the
 * per-logger table never applies to it and the default icon is all it can carry. And this client
 * has no diagnostics channel, so an unusable value is dropped in silence — there is nowhere to
 * report it, which is precisely why the engine's startup check exists and why the documentation
 * says so.
 */
@WireMockTest
class NtfyClientIconIT {

  private static LoggedRequest notifyOnce(WireMockRuntimeInfo wm, String icon) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    NtfyConfig config =
        NtfyConfig.builder()
            .url("http://localhost:" + wm.getHttpPort())
            .topic("alerts")
            .icon(icon)
            .build();

    try (NtfyClient client = new NtfyClient(config)) {
      assertThat(client.notify("deploy finished", "all green").success()).isTrue();
    }

    List<LoggedRequest> published = findAll(postRequestedFor(urlEqualTo("/alerts")));
    assertThat(published).hasSize(1);
    return published.get(0);
  }

  @Test
  void aFetchableIconReachesTheWire(WireMockRuntimeInfo wm) {
    String url = "https://cdn.example.com/logo.png";

    assertThat(notifyOnce(wm, url).getHeader("Icon")).isEqualTo(url);
  }

  @Test
  void anExtensionlessIconReachesTheWireToo(WireMockRuntimeInfo wm) {
    // The same regression the alert path guards against: a URL that serves a PNG from a path with
    // no extension is ordinary, and an earlier version of the check rejected it.
    String url = "https://avatars.example.com/u/9919?v=4";

    assertThat(notifyOnce(wm, url).getHeader("Icon")).isEqualTo(url);
  }

  @Test
  void anUnrenderableExtensionStillReachesTheWire(WireMockRuntimeInfo wm) {
    // Warned about on the alert path, never dropped — and here there is no one to warn, so it
    // simply goes. Dropping it would be a decision this class has no standing to make.
    String url = "https://cdn.example.com/logo.svg";

    assertThat(notifyOnce(wm, url).getHeader("Icon")).isEqualTo(url);
  }

  @Test
  void anIconNoClientCouldFetchIsDropped(WireMockRuntimeInfo wm) {
    assertThat(notifyOnce(wm, "file:///etc/passwd.png").containsHeader("Icon")).isFalse();
  }

  @Test
  void anAuthorityWithNoHostIsDropped(WireMockRuntimeInfo wm) {
    assertThat(notifyOnce(wm, "http://:80/logo.png").containsHeader("Icon")).isFalse();
  }

  @Test
  void anUnsetIconSendsNoHeader(WireMockRuntimeInfo wm) {
    assertThat(notifyOnce(wm, null).containsHeader("Icon")).isFalse();
  }

  @Test
  void thePerLoggerTableNeverAppliesToANotification(WireMockRuntimeInfo wm) {
    // A notification has no logger to match on, so the table cannot reach it however tempting the
    // configuration looks. It carries the default icon or nothing.
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    NtfyConfig config =
        NtfyConfig.builder()
            .url("http://localhost:" + wm.getHttpPort())
            .topic("alerts")
            .icon("https://cdn.example.com/default.png")
            .iconsByLoggerPrefixCsv("com.acme.billing=https://cdn.example.com/billing.png")
            .build();

    try (NtfyClient client = new NtfyClient(config)) {
      client.notify("deploy finished", "all green");
    }

    assertThat(findAll(postRequestedFor(urlEqualTo("/alerts"))).get(0).getHeader("Icon"))
        .isEqualTo("https://cdn.example.com/default.png");
  }
}
