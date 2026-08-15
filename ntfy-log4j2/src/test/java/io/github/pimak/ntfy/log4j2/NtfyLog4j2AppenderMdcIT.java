package io.github.pimak.ntfy.log4j2;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.findAll;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.util.SortedArrayStringMap;
import org.apache.logging.log4j.util.StringMap;
import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import io.github.pimak.ntfy.core.NtfyConfig;

/**
 * End-to-end coverage for {@code includeMdcKeys} on the log4j2 appender: allow-listed
 * {@code ThreadContext} entries reach the published body, and — the assertion that matters most —
 * an unlisted entry never does.
 *
 * <p>log4j2's {@code ThreadContext} is its MDC. The values read here come from the EVENT's captured
 * context data rather than the thread-local, which is what keeps the projection correct behind an
 * {@code <Async>} appender.
 */
@WireMockTest
class NtfyLog4j2AppenderMdcIT {

  private static final String LOGGER = "com.example.testapp.OrderService";

  private static NtfyLog4j2Appender.Builder builder(int wireMockPort) {
    return NtfyLog4j2Appender.newBuilder()
        .setName("ntfy")
        .setUrl("http://localhost:" + wireMockPort)
        .setTopic("alerts")
        // Delivery is asynchronous by default since 2.0, and stop() folds anything still queued
        // into the storm digest instead of delivering it — so an individual alert body would never
        // be observable here. Pinned synchronous exactly as the sibling ITs do.
        .setAsync("false");
  }

  private static LogEvent errorEvent(Map<String, ?> context) {
    StringMap contextData = new SortedArrayStringMap();
    context.forEach(contextData::putValue);
    return Log4jLogEvent.newBuilder()
        .setLoggerName(LOGGER)
        .setLevel(Level.ERROR)
        .setMessage(new SimpleMessage("boom"))
        .setContextData(contextData)
        .build();
  }

  private static String publishedBody() {
    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    assertThat(requests).hasSize(1);
    return requests.get(0).getBodyAsString();
  }

  @Test
  void allowListedContextKeys_areRenderedIntoThePublishedBody(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    NtfyLog4j2Appender appender =
        builder(wm.getHttpPort()).setIncludeMdcKeys("correlation-id, tenant").build();
    appender.start();
    appender.append(
        errorEvent(
            Map.of("correlation-id", "abc123", "tenant", "acme", "password", "hunter2")));
    appender.stop();

    String body = publishedBody();
    assertThat(body).contains("correlation-id: abc123");
    assertThat(body).contains("tenant: acme");
    // THE anti-leak assertion: an unlisted key reaches neither the body's keys nor its values.
    assertThat(body).doesNotContain("hunter2");
    assertThat(body).doesNotContain("password");
  }

  @Test
  void contextValueWithNewline_cannotForgeABodyLine(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    NtfyLog4j2Appender appender = builder(wm.getHttpPort()).setIncludeMdcKeys("tenant").build();
    appender.start();
    appender.append(errorEvent(Map.of("tenant", "acme\nCaused by: java.lang.Forged")));
    appender.stop();

    // Checked line-by-line, not with contains(): the point is that the value cannot introduce a
    // NEW line that reads as part of the alert's own structure.
    assertThat(Arrays.asList(publishedBody().split("\n")))
        .noneMatch(line -> line.startsWith("Caused by: java.lang.Forged"));
  }

  @Test
  void withoutIncludeMdcKeys_noContextLinesAppear(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    NtfyLog4j2Appender appender = builder(wm.getHttpPort()).build();
    appender.start();
    appender.append(errorEvent(Map.of("correlation-id", "abc123")));
    appender.stop();

    // The regression guard for every existing user: unset means byte-identical to before.
    assertThat(publishedBody()).doesNotContain("correlation-id");
  }

  @Test
  void injectedConfigPath_alsoRendersContext(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    // No plugin attribute anywhere on this path — this is how the programmatic installer builds the
    // appender, and it works only because the allow-list is resolved from the BUILT config.
    NtfyLog4j2Appender appender =
        NtfyLog4j2Appender.forConfig(
            "ntfy",
            NtfyConfig.builder()
                .url("http://localhost:" + wm.getHttpPort())
                .topic("alerts")
                .asyncEnabled(false)
                .includeMdcKeys(List.of("tenant"))
                .build());
    appender.start();
    appender.append(errorEvent(Map.of("tenant", "acme")));
    appender.stop();

    assertThat(publishedBody()).contains("tenant: acme");
  }
}
