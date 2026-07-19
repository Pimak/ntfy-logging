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
 * The ERROR floor: a root-logger install (the zero-code/auto paths attach to root with no filter)
 * must publish only ERROR-and-above events — INFO/WARN log content (request details, user
 * identifiers, anything the app logs) must never leave the host, and sub-ERROR noise must not be
 * counted into the suppression digest either.
 */
@WireMockTest
class LogbackAlertAppenderLevelGateIT {

  private static LogbackAlertAppender startedAppender(int wireMockPort) {
    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(new LoggerContext());
    appender.setUrl("http://localhost:" + wireMockPort);
    appender.setTopic("alerts");
    appender.start();
    return appender;
  }

  private static LoggingEvent eventAt(Level level, String message) {
    LoggerContext eventContext = new LoggerContext();
    Logger logger = eventContext.getLogger("com.example.testapp.SimulatedConsumer");
    return new LoggingEvent(
        LogbackAlertAppenderLevelGateIT.class.getName(), logger, level, message, null, null);
  }

  @Test
  void subErrorEvents_areNeverPublished(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    LogbackAlertAppender appender = startedAppender(wm.getHttpPort());
    appender.doAppend(eventAt(Level.TRACE, "trace line"));
    appender.doAppend(eventAt(Level.DEBUG, "debug line"));
    appender.doAppend(eventAt(Level.INFO, "user 42 logged in"));
    appender.doAppend(eventAt(Level.WARN, "disk almost full"));
    appender.stop();

    verify(0, postRequestedFor(urlEqualTo("/alerts")));
  }

  @Test
  void errorEvent_isPublished_andBodyContainsOnlyTheErrorMessage(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    LogbackAlertAppender appender = startedAppender(wm.getHttpPort());
    appender.doAppend(eventAt(Level.INFO, "user 42 logged in"));
    appender.doAppend(eventAt(Level.ERROR, "boom"));
    appender.stop();

    verify(1, postRequestedFor(urlEqualTo("/alerts")));
    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    String body = requests.get(0).getBodyAsString();
    assertThat(body).contains("boom");
    assertThat(body).doesNotContain("user 42 logged in");
  }
}
