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
 *
 * <p>Since the optional WARN route, the floor is ERROR by DEFAULT and WARN only once {@code
 * warnTopic} names a destination. Both halves are pinned here: the default path must stay exactly
 * as strict as it ever was, and the opt-in must move warnings — and only warnings — onto their own
 * topic.
 */
@WireMockTest
class LogbackAlertAppenderLevelGateIT {

  private static LogbackAlertAppender startedAppender(int wireMockPort) {
    return startedAppender(wireMockPort, null);
  }

  private static LogbackAlertAppender startedAppender(int wireMockPort, String warnTopic) {
    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(new LoggerContext());
    appender.setUrl("http://localhost:" + wireMockPort);
    appender.setTopic("alerts");
    if (warnTopic != null) {
      appender.setWarnTopic(warnTopic);
    }
    // Inline synchronous delivery (explicit 2.0 opt-out): this suite pins payload/digest/counter
    // semantics deterministically; the async default is covered by LogbackAlertAppenderAsyncIT.
    appender.setAsync(false);
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

  @Test
  void withoutWarnTopic_theAppenderDoesNotEvenOfferAWarnRoute(WireMockRuntimeInfo wm) {
    LogbackAlertAppender appender = startedAppender(wm.getHttpPort());
    try {
      assertThat(appender.warnRoutingActive()).isFalse();
    } finally {
      appender.stop();
    }
  }

  @Test
  void withWarnTopic_warnEventsPublishToTheWarnTopic_andSubWarnStillNeverDoes(
      WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    stubFor(post(urlEqualTo("/alerts-warn")).willReturn(aResponse().withStatus(200)));

    LogbackAlertAppender appender = startedAppender(wm.getHttpPort(), "alerts-warn");
    assertThat(appender.warnRoutingActive()).isTrue();
    appender.doAppend(eventAt(Level.TRACE, "trace line"));
    appender.doAppend(eventAt(Level.DEBUG, "debug line"));
    appender.doAppend(eventAt(Level.INFO, "user 42 logged in"));
    appender.doAppend(eventAt(Level.WARN, "disk almost full"));
    appender.doAppend(eventAt(Level.ERROR, "boom"));
    appender.stop();

    // Opening the WARN gate must not open anything below it: INFO and under stay unpublished.
    verify(1, postRequestedFor(urlEqualTo("/alerts")));
    verify(1, postRequestedFor(urlEqualTo("/alerts-warn")));

    LoggedRequest warn = findAll(postRequestedFor(urlEqualTo("/alerts-warn"))).get(0);
    assertThat(warn.getBodyAsString()).contains("disk almost full");
    assertThat(warn.getHeader("Priority")).isEqualTo("default");
    assertThat(warn.getHeader("Tags")).isEqualTo("warning");

    LoggedRequest error = findAll(postRequestedFor(urlEqualTo("/alerts"))).get(0);
    assertThat(error.getBodyAsString()).contains("boom");
    assertThat(error.getHeader("Priority")).isEqualTo("high");
  }

  @Test
  void warnTopicCanBeTheMainTopic_givingWarningsTheirOwnPriorityOnOneChannel(
      WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    // Naming the same topic is the documented way to alert on warnings without a second channel:
    // the level still decides the priority and tags.
    LogbackAlertAppender appender = startedAppender(wm.getHttpPort(), "alerts");
    appender.doAppend(eventAt(Level.WARN, "disk almost full"));
    appender.doAppend(eventAt(Level.ERROR, "boom"));
    appender.stop();

    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    assertThat(requests).hasSize(2);
    assertThat(requests.get(0).getHeader("Priority")).isEqualTo("default");
    assertThat(requests.get(1).getHeader("Priority")).isEqualTo("high");
  }

  @Test
  void anInvalidWarnTopic_leavesErrorAlertingIntact(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    LogbackAlertAppender appender = startedAppender(wm.getHttpPort(), "not/valid");
    // The appender still started, and it simply never offers the warn route.
    assertThat(appender.isStarted()).isTrue();
    assertThat(appender.warnRoutingActive()).isFalse();
    appender.doAppend(eventAt(Level.WARN, "disk almost full"));
    appender.doAppend(eventAt(Level.ERROR, "boom"));
    appender.stop();

    verify(1, postRequestedFor(urlEqualTo("/alerts")));
  }
}
