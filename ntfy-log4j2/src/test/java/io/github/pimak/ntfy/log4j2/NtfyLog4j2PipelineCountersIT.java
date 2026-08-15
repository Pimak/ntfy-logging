package io.github.pimak.ntfy.log4j2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import io.github.pimak.ntfy.core.PipelineCounters;

/**
 * Proves {@link NtfyLog4j2Appender#getCounters()} exposes correct pipeline tallies and that the
 * counters are owned by the appender: the same {@link PipelineCounters} instance survives a {@code
 * stop()}/{@code start()} cycle (a new engine, same holder) and keeps accumulating monotonically.
 */
@WireMockTest
class NtfyLog4j2PipelineCountersIT {

  private static LogEvent errorEvent(String message) {
    return Log4jLogEvent.newBuilder()
        .setLoggerName("com.example.testapp.SimulatedConsumer")
        .setLevel(Level.ERROR)
        .setMessage(new SimpleMessage(message))
        .build();
  }

  private static NtfyLog4j2Appender appender(WireMockRuntimeInfo wm, int maxAlertsPerWindow) {
    return NtfyLog4j2Appender.newBuilder()
        .setName("ntfy")
        .setUrl("http://localhost:" + wm.getHttpPort())
        .setTopic("alerts")
        // Pinned synchronous so the assertions below observe publish/suppress semantics
        // deterministically; the async default is covered by NtfyLog4j2AppenderAsyncIT.
        .setAsync("false")
        .setMaxAlertsPerWindow(String.valueOf(maxAlertsPerWindow))
        .setSuppressionWindow("60000") // never fires naturally during the test
        .build();
  }

  @Test
  void counters_reflectMixOfPublishedAndSuppressed(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    NtfyLog4j2Appender appender = appender(wm, 2);
    appender.start();
    assertThat(appender.isStarted()).isTrue();

    // 2 within allowance publish, 2 over-allowance suppressed.
    for (int i = 0; i < 4; i++) {
      appender.append(errorEvent("boom " + i));
    }

    assertThat(appender.getCounters().published()).isEqualTo(2);
    assertThat(appender.getCounters().suppressed()).isEqualTo(2);
    assertThat(appender.getCounters().failed()).isZero();

    appender.stop();
  }

  @Test
  void counters_surviveStopStartCycle_monotonic(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    NtfyLog4j2Appender appender = appender(wm, 10);
    appender.start();

    PipelineCounters countersBefore = appender.getCounters();
    for (int i = 0; i < 3; i++) {
      appender.append(errorEvent("first-cycle " + i));
    }
    assertThat(appender.getCounters().published()).isEqualTo(3);

    // A stop/start cycle builds a brand-new engine but must reuse the appender-owned counters.
    appender.stop();
    appender.start();
    assertThat(appender.isStarted()).isTrue();
    assertThat(appender.getCounters())
        .as("same PipelineCounters instance across the stop/start cycle")
        .isSameAs(countersBefore);

    for (int i = 0; i < 2; i++) {
      appender.append(errorEvent("second-cycle " + i));
    }

    // 3 from the first engine + 2 from the second — accumulated, not reset.
    assertThat(appender.getCounters().published()).isEqualTo(5);
    assertThat(appender.getCounters().suppressed()).isZero();
    assertThat(appender.getCounters().failed()).isZero();

    appender.stop();
  }

  @Test
  void counters_countFailedPublishes(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(500)));

    NtfyLog4j2Appender appender = appender(wm, 10);
    appender.start();

    appender.append(errorEvent("boom"));

    assertThat(appender.getCounters().failed()).isEqualTo(1);
    assertThat(appender.getCounters().published()).isZero();

    appender.stop();
  }
}
