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
 * Verifies the raw {@code actions} plugin attribute maps onto ntfy's {@code Actions} HTTP header:
 * forwarded verbatim when configured, entirely absent when left unset.
 */
@WireMockTest
class NtfyLog4j2AppenderActionsHeaderIT {

  private static final String ACTIONS = "view, View logs, https://grafana.example.com/d/abc";

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
  void append_withActionsConfigured_sendsActionsHeaderVerbatim(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    NtfyLog4j2Appender appender = builder(wm.getHttpPort()).setActions(ACTIONS).build();
    appender.start();

    appender.append(errorEvent());
    appender.stop();

    verify(1, postRequestedFor(urlEqualTo("/alerts")));
    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    assertThat(requests.get(0).getHeader("Actions")).isEqualTo(ACTIONS);
  }

  @Test
  void append_withoutActions_sendsNoActionsHeader(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    NtfyLog4j2Appender appender = builder(wm.getHttpPort()).build();
    appender.start();

    appender.append(errorEvent());
    appender.stop();

    verify(1, postRequestedFor(urlEqualTo("/alerts")));
    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    assertThat(requests.get(0).containsHeader("Actions")).isFalse();
  }
}
