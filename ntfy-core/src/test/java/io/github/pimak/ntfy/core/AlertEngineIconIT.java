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
 * The {@code icon} key on the wire, and what happens to a value that cannot work.
 *
 * <p>An icon URL fails invisibly if it is wrong: the ntfy SERVER never fetches it — the subscriber's
 * client does, on display — so a bad URL produces neither a non-2xx nor any other signal. The only
 * place it can be caught is at startup, which is what these tests pin.
 */
@WireMockTest
class AlertEngineIconIT {

  private static final String VALID = "https://cdn.example.com/logo.png";

  private static final class RecordingDiagnostics implements Diagnostics {
    // Copy-on-write like the startup-ping harnesses in this package. These tests publish
    // inline, so nothing crosses a thread yet — but the day one of them switches on a
    // self-test or async delivery, a plain list would start racing in silence.
    final List<String> warns = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Override
    public void info(String msg) {}

    @Override
    public void warn(String msg) {
      warns.add(msg);
    }

    @Override
    public void error(String msg, Throwable t) {}
  }

  private record Published(LoggedRequest request, List<String> warns) {}

  private static Published publishOnce(WireMockRuntimeInfo wm, String icon) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    NtfyConfig config =
        NtfyConfig.builder()
            .url("http://localhost:" + wm.getHttpPort())
            .topic("alerts")
            .icon(icon)
            .asyncEnabled(false)
            .build();

    RecordingDiagnostics diagnostics = new RecordingDiagnostics();
    AlertEngine engine = new AlertEngine(config, diagnostics);
    engine.start();
    engine.submit(AlertEvent.of("com.example.app.Service", "boom", 0L));
    engine.stop();

    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    assertThat(requests).as("alerting must not be silenced by an icon problem").isNotEmpty();
    return new Published(requests.get(0), diagnostics.warns);
  }

  @Test
  void configuredIconIsSentAsTheIconHeader(WireMockRuntimeInfo wm) {
    Published published = publishOnce(wm, VALID);

    assertThat(published.request().getHeader("Icon")).isEqualTo(VALID);
    assertThat(published.warns()).noneSatisfy(w -> assertThat(w).contains("icon"));
  }

  @Test
  void unsetIconSendsNoHeader(WireMockRuntimeInfo wm) {
    Published published = publishOnce(wm, null);

    assertThat(published.request().containsHeader("Icon")).isFalse();
  }

  @Test
  void anUnrenderableExtensionIsWarnedAboutButStillSent(WireMockRuntimeInfo wm) {
    // ntfy renders JPEG and PNG only, so an .svg almost certainly shows nothing — worth saying.
    // Dropping it would not be: the format lives in the bytes, and the URL is only a hint.
    String url = "https://cdn.example.com/logo.svg";
    Published published = publishOnce(wm, url);

    assertThat(published.request().getHeader("Icon")).isEqualTo(url);
    assertThat(published.warns()).isNotEmpty();
  }

  @Test
  void anExtensionlessUrlIsAccepted(WireMockRuntimeInfo wm) {
    // Content-negotiated and avatar URLs routinely serve a PNG from a path with no extension.
    // An earlier version of this guard rejected them on the strength of the spelling alone.
    String url = "https://avatars.example.com/u/9919?v=4";
    Published published = publishOnce(wm, url);

    assertThat(published.request().getHeader("Icon")).isEqualTo(url);
    assertThat(published.warns()).isEmpty();
  }

  @Test
  void anAuthorityWithNoHostIsRefused(WireMockRuntimeInfo wm) {
    // "http://:80/logo.png" has an authority and no host. Nothing could fetch it.
    Published published = publishOnce(wm, "http://:80/logo.png");

    assertThat(published.request().containsHeader("Icon")).isFalse();
    assertThat(published.warns()).isNotEmpty();
  }

  @Test
  void anIpv6LiteralIsAccepted(WireMockRuntimeInfo wm) {
    // The port cannot be found by looking for the last colon: in [2001:db8::1] that lands inside
    // the address and leaves "[2001:db8:" behind as the supposed host. The bracket delimits it.
    String url = "http://[2001:db8::1]:8080/logo.png";
    Published published = publishOnce(wm, url);

    assertThat(published.request().getHeader("Icon")).isEqualTo(url);
    assertThat(published.warns()).isEmpty();
  }

  @Test
  void anUnclosedBracketIsRefused(WireMockRuntimeInfo wm) {
    Published published = publishOnce(wm, "http://[2001:db8/logo.png");

    assertThat(published.request().containsHeader("Icon")).isFalse();
  }

  @Test
  void anUnderscoreHostnameIsAccepted(WireMockRuntimeInfo wm) {
    // URI.getHost() returns null here, which is why the check reads the host PART of the authority
    // instead. Testing getHost() would reject this working internal-network form.
    String url = "http://my_host/logo.png";
    Published published = publishOnce(wm, url);

    assertThat(published.request().getHeader("Icon")).isEqualTo(url);
    assertThat(published.warns()).isEmpty();
  }

  @Test
  void aCredentialInFrontOfAnUnderscoreHostIsAccepted(WireMockRuntimeInfo wm) {
    // The case that actually justifies reading the host out of the authority by hand. Userinfo
    // alone does not defeat URI.getHost() — it resolves user:pass@host to "host" quite happily —
    // and an underscore alone is handled. Together they were the untested combination.
    String url = "https://user:pass@my_host/logo.png";
    Published published = publishOnce(wm, url);

    assertThat(published.request().getHeader("Icon")).isEqualTo(url);
    assertThat(published.warns()).isEmpty();
  }

  @Test
  void aUserinfoUrlIsAccepted(WireMockRuntimeInfo wm) {
    // getHost() resolves this one correctly, so it is not the case the authority approach exists
    // for — but stripping the userinfo naively could leave nothing behind, and it must not.
    String url = "https://user:pass@cdn.example.com/logo.png";
    Published published = publishOnce(wm, url);

    assertThat(published.request().getHeader("Icon")).isEqualTo(url);
  }

  @Test
  void aSchemeNoClientCouldFetchIsRefusedAtStartupAndDropped(WireMockRuntimeInfo wm) {
    Published published = publishOnce(wm, "file:///etc/passwd.png");

    assertThat(published.request().containsHeader("Icon")).isFalse();
    assertThat(published.warns())
        .anySatisfy(
            w -> {
              assertThat(w).contains("icon");
              // and not the per-logger table message, which names a different setting
              assertThat(w).doesNotContain("icons-by-logger");
            });
  }

  @Test
  void anInvalidIconNeverSilencesAlerting(WireMockRuntimeInfo wm) {
    // The guard drops the icon loudly; it must not refuse activation the way an invalid url or
    // topic does. A decorative value cannot be allowed to stop the paging. publishOnce already
    // asserts a request was made at all — what matters here is that the alert still carries its
    // real content and only the icon was dropped.
    Published published = publishOnce(wm, "not a url at all");

    assertThat(published.request().getBodyAsString()).contains("com.example.app.Service");
    assertThat(published.request().containsHeader("Icon")).isFalse();
    assertThat(published.warns())
        .anySatisfy(
            w -> {
              assertThat(w).contains("icon");
              // and not the per-logger table message, which names a different setting
              assertThat(w).doesNotContain("icons-by-logger");
            });
  }

  @Test
  void jpegIsAccepted(WireMockRuntimeInfo wm) {
    assertThat(publishOnce(wm, "https://cdn.example.com/a.jpeg").request().getHeader("Icon"))
        .isEqualTo("https://cdn.example.com/a.jpeg");
  }

  @Test
  void theExtensionCheckIsCaseInsensitive(WireMockRuntimeInfo wm) {
    assertThat(publishOnce(wm, "http://cdn.example.com/a.JPG").request().getHeader("Icon"))
        .isEqualTo("http://cdn.example.com/a.JPG");
  }
}
