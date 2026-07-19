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
 * End-to-end regression: an ERROR event carrying a REAL throwable must actually be delivered to ntfy
 * — the HTTP POST must reach the server, not die at the header-build boundary (the ASCII-separator /
 * RFC-2047 Title-encoding guarantees now enforced in ntfy-core). Ported from the original {@code
 * NtfyAlertAppenderExceptionDeliveryIT} against the thin appender.
 */
@WireMockTest
class LogbackAlertAppenderExceptionDeliveryIT {

  private static LogbackAlertAppender startedAppender(int wireMockPort) {
    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(new LoggerContext());
    appender.setUrl("http://localhost:" + wireMockPort);
    appender.setTopic("alerts");
    return appender;
  }

  private static LoggingEvent exceptionEvent() {
    LoggerContext eventContext = new LoggerContext();
    Logger logger = eventContext.getLogger("com.example.testapp.SimulatedConsumer");
    IllegalStateException root = new IllegalStateException("root cause");
    RuntimeException surface = new RuntimeException("surface", root);
    return new LoggingEvent(
        LogbackAlertAppenderExceptionDeliveryIT.class.getName(),
        logger,
        Level.ERROR,
        "boom",
        surface,
        null);
  }

  @Test
  void append_eventWithRealException_postReachesStubWithNonBlankTitle(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    LogbackAlertAppender appender = startedAppender(wm.getHttpPort());
    appender.setTitle("MyApp");
    appender.start();

    appender.doAppend(exceptionEvent());
    appender.stop();

    verify(1, postRequestedFor(urlEqualTo("/alerts")));
    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    assertThat(requests.get(0).getHeader("Title"))
        .isEqualTo("MyApp - java.lang.IllegalStateException");
  }

  @Test
  void append_nonAsciiAppNameWithRealException_stillDelivers(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    LogbackAlertAppender appender = startedAppender(wm.getHttpPort());
    appender.setAppName("Café");
    appender.start();

    appender.doAppend(exceptionEvent());
    appender.stop();

    verify(1, postRequestedFor(urlEqualTo("/alerts")));
    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    assertThat(requests.get(0).getHeader("Title")).startsWith("=?UTF-8?B?").endsWith("?=");
  }
}
