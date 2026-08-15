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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;

import io.github.pimak.ntfy.core.NtfyConfig;

/**
 * End-to-end coverage of {@code include-mdc-keys} on the Logback adapter: what an operator names in
 * configuration is what appears in the published body, and <strong>nothing else from the MDC ever
 * does</strong>. The assertions here are the ones that would fail loudest if the feature ever
 * regressed into a "publish the whole MDC" shape, so they inspect the real HTTP body WireMock
 * received rather than any intermediate object.
 */
@WireMockTest
class LogbackAlertAppenderMdcIT {

  /**
   * The MDC is a ThreadLocal shared with every other test running on this thread — a leaked entry
   * would silently change another test's published body. Cleared unconditionally, including after a
   * failing test.
   */
  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  private static LogbackAlertAppender startedAppender(int wireMockPort, String includeMdcKeys) {
    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(new LoggerContext());
    appender.setUrl("http://localhost:" + wireMockPort);
    appender.setTopic("alerts");
    // Delivery is asynchronous by default since 2.0, and stop() folds anything still queued into
    // the storm digest instead of delivering it — so an individual alert body would never be
    // observable here. These tests assert on that body, so they opt out explicitly, exactly as
    // LogbackAlertAppenderLevelGateIT does; the async path has its own coverage.
    appender.setAsync(false);
    if (includeMdcKeys != null) {
      appender.setIncludeMdcKeys(includeMdcKeys);
    }
    appender.start();
    return appender;
  }

  private static LoggingEvent errorEvent(String message) {
    LoggerContext eventContext = new LoggerContext();
    // A hand-built LoggerContext has no MDC adapter, so its events cannot read the MDC at all.
    // Wiring in the live one is what makes these events behave like the ones a real application
    // produces: getMDCPropertyMap() then returns the snapshot of what MDC.put set below.
    eventContext.setMDCAdapter(MDC.getMDCAdapter());
    Logger logger = eventContext.getLogger("com.example.testapp.OrderService");
    return new LoggingEvent(
        LogbackAlertAppenderMdcIT.class.getName(),
        logger,
        Level.ERROR,
        message,
        new IllegalStateException("order rejected"),
        null);
  }

  private static String publishedBody() {
    verify(1, postRequestedFor(urlEqualTo("/alerts")));
    List<LoggedRequest> requests = findAll(postRequestedFor(urlEqualTo("/alerts")));
    return requests.get(0).getBodyAsString();
  }

  @Test
  void configuredMdcKeys_areRenderedIntoThePublishedBody(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    MDC.put("correlation-id", "abc");
    MDC.put("tenant", "acme");
    MDC.put("password", "hunter2");

    LogbackAlertAppender appender = startedAppender(wm.getHttpPort(), "correlation-id, tenant");
    appender.doAppend(errorEvent("boom"));
    appender.stop();

    String body = publishedBody();
    assertThat(body).contains("correlation-id: abc");
    assertThat(body).contains("tenant: acme");
    // THE anti-leak assertion. "password" sat in the same MDC as the two allow-listed keys and was
    // not named in configuration, so neither its key nor its value may appear anywhere in the
    // payload that leaves this host. If this line ever fails, the feature has become a wildcard.
    assertThat(body).doesNotContain("hunter2");
    assertThat(body).doesNotContain("password");
  }

  @Test
  void mdcValueWithNewline_cannotForgeABodyLine(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    // A value under attacker influence (a tenant header, a user-supplied id) trying to inject a
    // second, fake body line that a reader would take for a real cause.
    MDC.put("tenant", "acme\nCaused by: java.lang.Forged");

    LogbackAlertAppender appender = startedAppender(wm.getHttpPort(), "tenant");
    appender.doAppend(errorEvent("boom"));
    appender.stop();

    String body = publishedBody();
    // Checked LINE BY LINE, not with contains(): the forged text legitimately appears inside the
    // one "tenant:" line (the newline having been neutralized to a space), and what must not exist
    // is a LINE that reads as a cause of its own.
    for (String line : body.split("\n", -1)) {
      assertThat(line).isNotEqualTo("Caused by: java.lang.Forged");
      assertThat(line.startsWith("Caused by: java.lang.Forged")).isFalse();
    }
    assertThat(body).contains("tenant: acme Caused by: java.lang.Forged");
  }

  @Test
  void mdcLines_sitBetweenTheLoggerAndCauseLines(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    MDC.put("correlation-id", "abc");
    MDC.put("tenant", "acme");

    LogbackAlertAppender appender = startedAppender(wm.getHttpPort(), "correlation-id, tenant");
    appender.doAppend(errorEvent("boom"));
    appender.stop();

    List<String> lines = List.of(publishedBody().split("\n", -1));
    int loggerLine = indexOfLineStartingWith(lines, "Logger: ");
    int causeLine = indexOfLineStartingWith(lines, "Caused by: ");
    // Position is a contract, not an accident: the context block sits high in the body because the
    // payload truncator keeps whole lines from the start, so a correlation id survives the byte
    // budget that a trailing stack frame does not.
    assertThat(loggerLine).isNotNegative();
    assertThat(causeLine).isNotNegative();
    assertThat(lines.get(loggerLine + 1)).isEqualTo("correlation-id: abc");
    assertThat(lines.get(loggerLine + 2)).isEqualTo("tenant: acme");
    assertThat(causeLine).isEqualTo(loggerLine + 3);
  }

  @Test
  void injectedConfigPath_alsoRendersMdc(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    MDC.put("tenant", "acme");
    MDC.put("password", "hunter2");

    LogbackAlertAppender appender = new LogbackAlertAppender();
    appender.setContext(new LoggerContext());
    // No JavaBean setter anywhere on this path — this is how NtfyLogbackConfigurator auto-installs,
    // and it works only because the appender resolves its allow-list from the BUILT config.
    appender.setConfig(
        NtfyConfig.builder()
            .url("http://localhost:" + wm.getHttpPort())
            .topic("alerts")
            .asyncEnabled(false) // observe the individual alert body, not the shutdown digest
            .includeMdcKeys(List.of("tenant"))
            .build());
    appender.start();
    appender.doAppend(errorEvent("boom"));
    appender.stop();

    String body = publishedBody();
    assertThat(body).contains("tenant: acme");
    assertThat(body).doesNotContain("hunter2");
  }

  @Test
  void withoutIncludeMdcKeys_noContextLinesAppear(WireMockRuntimeInfo wm) {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));
    MDC.put("correlation-id", "abc");
    MDC.put("tenant", "acme");

    LogbackAlertAppender appender = startedAppender(wm.getHttpPort(), null);
    appender.doAppend(errorEvent("boom"));
    appender.stop();

    String body = publishedBody();
    // Regression guard for every existing install: a populated MDC plus an unconfigured appender
    // must produce byte-for-byte the body it produced before this feature existed.
    assertThat(body).doesNotContain("correlation-id");
    assertThat(body).doesNotContain("abc");
    assertThat(body).doesNotContain("tenant");
    assertThat(body).doesNotContain("acme");
    assertThat(body).contains("boom");
  }

  private static int indexOfLineStartingWith(List<String> lines, String prefix) {
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).startsWith(prefix)) {
        return i;
      }
    }
    return -1;
  }
}
