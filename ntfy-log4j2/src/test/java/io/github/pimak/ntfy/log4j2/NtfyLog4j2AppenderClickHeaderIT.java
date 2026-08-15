package io.github.pimak.ntfy.log4j2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

/**
 * Verifies the {@code clickUrl} plugin attribute maps onto ntfy's {@code Click} HTTP header:
 * present and verbatim when configured, entirely absent when left unset (an unset attribute must
 * send no {@code Click} header rather than an empty one).
 */
@WireMockTest
class NtfyLog4j2AppenderClickHeaderIT {

  private static NtfyLog4j2Appender.Builder builder(int wireMockPort) {
    return NtfyLog4j2Appender.newBuilder()
        .setName("ntfy")
        .setUrl("http://localhost:" + wireMockPort)
        .setTopic("alerts")
        // Pinned synchronous so the assertions below observe publish/suppress semantics
        // deterministically; the async default is covered by NtfyLog4j2AppenderAsyncIT.
        .setAsync("false");
  }

  private static LogEvent errorEvent() {
    return Log4jLogEvent.newBuilder()
        .setLoggerName("com.example.testapp.SimulatedConsumer")
        .setLevel(Level.ERROR)
        .setMessage(new SimpleMessage("boom"))
        .build();
  }

  @Test
  void append_withClickUrlConfigured_sendsClickHeaderVerbatim(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    NtfyLog4j2Appender appender =
        builder(wm.getHttpPort()).setClickUrl("https://grafana.example.com/d/abc123").build();
    appender.start();

    appender.append(errorEvent());
    appender.stop();

    verify(1, postRequestedFor(urlEqualTo("/alerts")));
    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    assertThat(requests.get(0).getHeader("Click"))
        .isEqualTo("https://grafana.example.com/d/abc123");
  }

  @Test
  void append_withoutClickUrl_sendsNoClickHeader(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    NtfyLog4j2Appender appender = builder(wm.getHttpPort()).build();
    appender.start();

    appender.append(errorEvent());
    appender.stop();

    verify(1, postRequestedFor(urlEqualTo("/alerts")));
    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    assertThat(requests.get(0).containsHeader("Click")).isFalse();
  }
}
