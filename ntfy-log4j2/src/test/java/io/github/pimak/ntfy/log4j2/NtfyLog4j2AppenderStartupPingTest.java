package io.github.pimak.ntfy.log4j2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the {@code startupPing} / {@code startupPingFailFast} plugin attributes reach the built
 * {@link io.github.pimak.ntfy.core.NtfyConfig} and take effect, and that leaving them untouched
 * keeps the appender's behaviour and status output exactly as before the feature existed.
 */
@WireMockTest
class NtfyLog4j2AppenderStartupPingTest {

  private RecordingStatusListener status;

  @BeforeEach
  void attachStatusListener() {
    status = RecordingStatusListener.attach();
  }

  @AfterEach
  void detachStatusListener() {
    status.detach();
  }

  private static NtfyLog4j2Appender.Builder builder(WireMockRuntimeInfo wm) {
    return NtfyLog4j2Appender.newBuilder()
        .setName("ntfy")
        .setUrl("http://localhost:" + wm.getHttpPort())
        .setTopic("alerts");
  }

  private void awaitSelfTestStatus() throws InterruptedException {
    long deadline = System.currentTimeMillis() + 3000;
    while (status.messages().stream().noneMatch(m -> m.contains("startup self-test"))
        && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
  }

  @Test
  void untouched_noSelfTestRunsAndNoStatusIsAdded(WireMockRuntimeInfo wm) throws Exception {
    NtfyLog4j2Appender appender = builder(wm).build();

    appender.start();
    Thread.sleep(200);
    appender.stop();

    verify(0, anyRequestedFor(anyUrl()));
    assertThat(status.messages()).noneMatch(m -> m.contains("startup self-test"));
  }

  @Test
  void startupPingProbe_reachesTheEngineAndReportsOnTheStatusLogger(WireMockRuntimeInfo wm)
      throws Exception {
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(200)));
    NtfyLog4j2Appender appender = builder(wm).setStartupPing("probe").build();

    appender.start();
    awaitSelfTestStatus();
    appender.stop();

    verify(1, getRequestedFor(urlPathEqualTo("/alerts/json")));
    assertThat(status.messages()).anyMatch(m -> m.contains("startup self-test PASSED (probe)"));
  }

  @Test
  void aFailedSelfTestIsDiagnosedButStillLeavesTheAppenderStarted(WireMockRuntimeInfo wm)
      throws Exception {
    stubFor(get(urlPathEqualTo("/alerts/json")).willReturn(aResponse().withStatus(403)));
    NtfyLog4j2Appender appender = builder(wm).setStartupPing("probe").build();

    appender.start();
    awaitSelfTestStatus();
    boolean started = appender.isStarted();
    appender.stop();

    assertThat(started).isTrue();
    assertThat(status.messages())
        .anyMatch(m -> m.contains("403") && m.contains("revoked or expired"));
  }

  @Test
  void publishMode_postsTheTestNotification(WireMockRuntimeInfo wm) throws Exception {
    stubFor(
        com.github.tomakehurst.wiremock.client.WireMock.post(urlPathEqualTo("/alerts"))
            .willReturn(aResponse().withStatus(200)));
    NtfyLog4j2Appender appender = builder(wm).setStartupPing("publish").build();

    appender.start();
    awaitSelfTestStatus();
    appender.stop();

    verify(
        1,
        com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlPathEqualTo("/alerts"))
            .withHeader(
                "Priority", com.github.tomakehurst.wiremock.client.WireMock.equalTo("low")));
    assertThat(status.messages()).anyMatch(m -> m.contains("startup self-test PASSED (publish)"));
  }

  @Test
  void anUnrecognizedModeIsWarnedAboutRatherThanSilentlyTreatedAsOff(WireMockRuntimeInfo wm)
      throws Exception {
    NtfyLog4j2Appender appender = builder(wm).setStartupPing("prob").build();

    appender.start();
    Thread.sleep(150);
    appender.stop();

    verify(0, anyRequestedFor(anyUrl()));
    assertThat(status.messages()).anyMatch(m -> m.contains("not a recognized mode"));
  }
}
