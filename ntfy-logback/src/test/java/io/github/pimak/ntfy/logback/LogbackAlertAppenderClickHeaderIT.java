package io.github.pimak.ntfy.logback;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;

/**
 * Verifies the {@code clickUrl} config maps onto ntfy's {@code Click} HTTP header: present and
 * verbatim when configured, entirely absent when left unset (a blank/null value must send no
 * {@code Click} header rather than an empty one).
 */
@WireMockTest
class LogbackAlertAppenderClickHeaderIT {

  private static LogbackAlertAppender startedAppender(int wireMockPort) {
    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(new LoggerContext());
    appender.setUrl("http://localhost:" + wireMockPort);
    appender.setTopic("alerts");
    return appender;
  }

  private static LoggingEvent errorEvent() {
    LoggerContext eventContext = new LoggerContext();
    Logger logger = eventContext.getLogger("com.example.testapp.SimulatedConsumer");
    return new LoggingEvent(
        LogbackAlertAppenderClickHeaderIT.class.getName(), logger, Level.ERROR, "boom", null, null);
  }

  @Test
  void append_withClickUrlConfigured_sendsClickHeaderVerbatim(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    LogbackAlertAppender appender = startedAppender(wm.getHttpPort());
    appender.setClickUrl("https://grafana.example.com/d/abc123");
    appender.start();

    appender.doAppend(errorEvent());
    appender.stop();

    verify(1, postRequestedFor(urlEqualTo("/alerts")));
    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    assertThat(requests.get(0).getHeader("Click"))
        .isEqualTo("https://grafana.example.com/d/abc123");
  }

  @Test
  void append_withoutClickUrl_sendsNoClickHeader(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    LogbackAlertAppender appender = startedAppender(wm.getHttpPort());
    appender.start();

    appender.doAppend(errorEvent());
    appender.stop();

    verify(1, postRequestedFor(urlEqualTo("/alerts")));
    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    assertThat(requests.get(0).containsHeader("Click")).isFalse();
  }
}
