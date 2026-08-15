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
 * The ERROR floor: the appender must publish only ERROR-and-above events even when it is referenced
 * from a {@code <Root level="info">} — INFO/WARN log content (request details, user identifiers,
 * anything the app logs) must never leave the host, and sub-ERROR noise must not be counted into
 * the suppression digest either. FATAL, being MORE specific than ERROR, must pass.
 */
@WireMockTest
class NtfyLog4j2AppenderLevelGateIT {

  private static NtfyLog4j2Appender startedAppender(int wireMockPort) {
    NtfyLog4j2Appender appender =
        NtfyLog4j2Appender.newBuilder()
            .setName("ntfy")
            .setUrl("http://localhost:" + wireMockPort)
            .setTopic("alerts")
            // Pinned synchronous so the assertions below observe publish/suppress semantics
            // deterministically; the async default is covered by NtfyLog4j2AppenderAsyncIT.
            .setAsync("false")
            .build();
    appender.start();
    return appender;
  }

  private static LogEvent eventAt(Level level, String message) {
    return Log4jLogEvent.newBuilder()
        .setLoggerName("com.example.testapp.SimulatedConsumer")
        .setLevel(level)
        .setMessage(new SimpleMessage(message))
        .build();
  }

  @Test
  void subErrorEvents_areNeverPublished(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    NtfyLog4j2Appender appender = startedAppender(wm.getHttpPort());
    appender.append(eventAt(Level.TRACE, "trace line"));
    appender.append(eventAt(Level.DEBUG, "debug line"));
    appender.append(eventAt(Level.INFO, "user 42 logged in"));
    appender.append(eventAt(Level.WARN, "disk almost full"));
    appender.stop();

    verify(0, postRequestedFor(urlEqualTo("/alerts")));
  }

  @Test
  void errorEvent_isPublished_andBodyContainsOnlyTheErrorMessage(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    NtfyLog4j2Appender appender = startedAppender(wm.getHttpPort());
    appender.append(eventAt(Level.INFO, "user 42 logged in"));
    appender.append(eventAt(Level.ERROR, "boom"));
    appender.stop();

    verify(1, postRequestedFor(urlEqualTo("/alerts")));
    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    String body = requests.get(0).getBodyAsString();
    assertThat(body).contains("boom");
    assertThat(body).doesNotContain("user 42 logged in");
  }

  @Test
  void fatalEvent_isPublished(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    NtfyLog4j2Appender appender = startedAppender(wm.getHttpPort());
    appender.append(eventAt(Level.FATAL, "the end"));
    appender.stop();

    verify(1, postRequestedFor(urlEqualTo("/alerts")));
  }
}
