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
 * End-to-end regression: an ERROR event carrying a REAL throwable must actually be delivered to
 * ntfy — the HTTP POST must reach the server, not die at the header-build boundary (the
 * ASCII-separator / RFC-2047 {@code Title}-encoding guarantees enforced in ntfy-core). The
 * proxy-only variant proves a relayed/serialized event produces an identical notification.
 */
@WireMockTest
class NtfyLog4j2AppenderExceptionDeliveryIT {

  private static NtfyLog4j2Appender.Builder builder(int wireMockPort) {
    return NtfyLog4j2Appender.newBuilder()
        .setName("ntfy")
        .setUrl("http://localhost:" + wireMockPort)
        .setTopic("alerts")
        // Pinned synchronous so the assertions below observe publish/suppress semantics
        // deterministically; the async default is covered by NtfyLog4j2AppenderAsyncIT.
        .setAsync("false");
  }

  private static RuntimeException wrappedException() {
    return new RuntimeException("surface", new IllegalStateException("root cause"));
  }

  private static Log4jLogEvent.Builder errorEvent() {
    return Log4jLogEvent.newBuilder()
        .setLoggerName("com.example.testapp.SimulatedConsumer")
        .setLevel(Level.ERROR)
        .setMessage(new SimpleMessage("boom"));
  }

  @Test
  void append_eventWithRealException_postReachesStubWithNonBlankTitle(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    NtfyLog4j2Appender appender = builder(wm.getHttpPort()).setTitle("MyApp").build();
    appender.start();

    appender.append(errorEvent().setThrown(wrappedException()).build());
    appender.stop();

    verify(1, postRequestedFor(urlEqualTo("/alerts")));
    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    assertThat(requests.get(0).getHeader("Title"))
        .isEqualTo("MyApp - java.lang.IllegalStateException");
  }

  @Test
  void append_nonAsciiAppNameWithRealException_stillDelivers(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    NtfyLog4j2Appender appender = builder(wm.getHttpPort()).setAppName("Café").build();
    appender.start();

    appender.append(errorEvent().setThrown(wrappedException()).build());
    appender.stop();

    verify(1, postRequestedFor(urlEqualTo("/alerts")));
    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    assertThat(requests.get(0).getHeader("Title")).startsWith("=?UTF-8?B?").endsWith("?=");
  }

  @Test
  void append_eventWithThrownProxyOnly_deliversTheSameNotification(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    NtfyLog4j2Appender appender = builder(wm.getHttpPort()).setTitle("MyApp").build();
    appender.start();

    // A serialization round trip is the only construction that yields a proxy-only throwable —
    // see RelayedLogEvents for why the Log4jLogEvent builder cannot fake one.
    LogEvent relayed = RelayedLogEvents.relay(errorEvent().setThrown(wrappedException()).build());
    appender.append(relayed);
    appender.stop();

    verify(1, postRequestedFor(urlEqualTo("/alerts")));
    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    assertThat(requests.get(0).getHeader("Title"))
        .isEqualTo("MyApp - java.lang.IllegalStateException");
    assertThat(requests.get(0).getBodyAsString()).contains("root cause");
  }
}
