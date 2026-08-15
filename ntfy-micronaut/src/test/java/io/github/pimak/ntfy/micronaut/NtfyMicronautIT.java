package io.github.pimak.ntfy.micronaut;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.pimak.ntfy.core.NtfyClient;
import io.micronaut.context.ApplicationContext;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end check of the module against a loopback ntfy stand-in: a real Micronaut context, the
 * real Logback root logger, and a WireMock server standing in for the ntfy topic endpoint.
 *
 * <p>Each test opens and closes its own context (try-with-resources) rather than sharing one, so
 * the install is exercised repeatedly through its idempotent-replace path and no test can leave an
 * appender attached to the JVM-wide root logger for the next one.
 */
class NtfyMicronautIT {

  private static final WireMockServer WIREMOCK =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  @BeforeAll
  static void startWireMock() {
    WIREMOCK.start();
    WIREMOCK.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
  }

  @AfterAll
  static void stopWireMock() {
    WIREMOCK.stop();
  }

  @Test
  void urlAndTopicConfigured_startedNtfyAutoAppenderIsOnTheRootLogger() {
    try (ApplicationContext context = ApplicationContext.run(ntfyProperties())) {
      // Resolving the installer is not what installs it — StartupEvent already did, during run().
      // The lookup only proves the bean the event reached is the one under test.
      assertThat(context.getBean(NtfyLogbackInstaller.class)).isNotNull();

      Appender<?> appender = rootAppender();
      assertThat(appender).as("ntfy-auto appender on root logger").isNotNull();
      assertThat(appender.isStarted()).as("ntfy-auto appender started").isTrue();
    }
  }

  @Test
  @Timeout(60)
  void errorLogEvent_isPublishedToTheConfiguredNtfyTopic() {
    WIREMOCK.resetRequests();
    try (ApplicationContext context = ApplicationContext.run(ntfyProperties())) {
      assertThat(context.getBean(NtfyLogbackInstaller.class)).isNotNull();

      // NOTE: the logger name must not start with "io.github.pimak.ntfy" — the engine always
      // self-excludes its own package root as an anti-loop guard, which would gate the event out
      // before publishing.
      Logger appLogger = LoggerFactory.getLogger("com.example.demo.MicronautItLogger");
      appLogger.error("boom for the micronaut integration test");

      // Delivery is async by default, so poll rather than assert once. The configured error
      // priority and tags are asserted on the wire: that is the proof that ntfy.* values bound by
      // Micronaut actually reached the engine's built configuration.
      awaitUntilAsserted(() -> WIREMOCK.verify(postRequestedFor(urlPathEqualTo("/alerts"))
          .withHeader("Priority", equalTo("max"))
          .withHeader("Tags", equalTo("boom"))));
    }
  }

  @Test
  @Timeout(60)
  void injectedNtfyClient_publishesToTheConfiguredTopic() {
    WIREMOCK.resetRequests();
    try (ApplicationContext context = ApplicationContext.run(ntfyProperties())) {
      NtfyClient client = context.getBean(NtfyClient.class);

      client.notify("manual title", "manual message");

      // The client is synchronous, so a single verify is enough here.
      WIREMOCK.verify(postRequestedFor(urlPathEqualTo("/alerts")));
    }
  }

  @Test
  void contextShutdown_detachesTheAppenderFromTheRootLogger() {
    try (ApplicationContext context = ApplicationContext.run(ntfyProperties())) {
      assertThat(context.getBean(NtfyLogbackInstaller.class)).isNotNull();
      assertThat(rootAppender()).as("ntfy-auto appender while the context is up").isNotNull();
    }

    assertThat(rootAppender()).as("ntfy-auto appender after context close").isNull();
  }

  /** Configuration pointing the module at the loopback WireMock stand-in. */
  private static Map<String, Object> ntfyProperties() {
    Map<String, Object> properties = new LinkedHashMap<>();
    properties.put("ntfy.url", WIREMOCK.baseUrl());
    properties.put("ntfy.topic", "alerts");
    properties.put("ntfy.app-name", "micronaut-it");
    properties.put("ntfy.error-priority", "max");
    properties.put("ntfy.error-tags", "boom");
    // Keep the storm rate limiter from suppressing the single ERROR each test sends.
    properties.put("ntfy.max-alerts-per-window", 10);
    return properties;
  }

  private static Appender<?> rootAppender() {
    LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
    return lc.getLogger(Logger.ROOT_LOGGER_NAME).getAppender(NtfyLogbackInstaller.APPENDER_NAME);
  }

  /**
   * Polls {@code assertion} until it stops throwing, up to 10 seconds. A hand-rolled two-liner
   * instead of an Awaitility dependency: this is the only place in the module that needs to wait.
   */
  private static void awaitUntilAsserted(Runnable assertion) {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    RuntimeException last = null;
    while (System.nanoTime() < deadline) {
      try {
        assertion.run();
        return;
      } catch (RuntimeException | AssertionError e) {
        last = e instanceof RuntimeException re ? re : new IllegalStateException(e);
        try {
          Thread.sleep(100L);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("interrupted while awaiting", interrupted);
        }
      }
    }
    throw last == null ? new IllegalStateException("assertion never ran") : last;
  }
}
