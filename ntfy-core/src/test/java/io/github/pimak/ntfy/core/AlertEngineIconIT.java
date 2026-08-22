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
import java.util.ArrayList;
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
    final List<String> warns = new ArrayList<>();

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
  void nonImageExtensionIsRefusedAtStartupAndDropped(WireMockRuntimeInfo wm) {
    // ntfy supports JPEG and PNG only. An .svg would download and silently never render.
    Published published = publishOnce(wm, "https://cdn.example.com/logo.svg");

    assertThat(published.request().containsHeader("Icon")).isFalse();
    assertThat(published.warns()).anySatisfy(w -> assertThat(w).contains("icon"));
  }

  @Test
  void nonHttpSchemeIsRefusedAtStartupAndDropped(WireMockRuntimeInfo wm) {
    Published published = publishOnce(wm, "file:///etc/passwd.png");

    assertThat(published.request().containsHeader("Icon")).isFalse();
    assertThat(published.warns()).anySatisfy(w -> assertThat(w).contains("icon"));
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
    assertThat(published.warns()).anySatisfy(w -> assertThat(w).contains("icon"));
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
