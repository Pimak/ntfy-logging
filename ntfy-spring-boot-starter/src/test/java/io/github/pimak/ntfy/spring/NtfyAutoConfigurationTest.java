package io.github.pimak.ntfy.spring;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.pimak.ntfy.core.NtfyClient;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Boots a minimal Spring context wired to a loopback WireMock server and verifies the three
 * contract points of the starter:
 *
 * <ol>
 *   <li>(a) the root Logback logger carries a started appender named {@code "ntfy-auto"};
 *   <li>(b) an {@link NtfyClient} bean is present and injectable;
 *   <li>(c) logging an ERROR through SLF4J results in a POST to the ntfy topic endpoint.
 * </ol>
 */
@SpringBootTest(classes = NtfyAutoConfigurationTest.TestApp.class)
class NtfyAutoConfigurationTest {

  static final WireMockServer WIREMOCK =
      new WireMockServer(WireMockConfiguration.options().dynamicPort());

  @Configuration
  @EnableAutoConfiguration
  static class TestApp {
  }

  @BeforeAll
  static void startWireMock() {
    WIREMOCK.start();
    WIREMOCK.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)));
  }

  @AfterAll
  static void stopWireMock() {
    WIREMOCK.stop();
  }

  @DynamicPropertySource
  static void ntfyProperties(DynamicPropertyRegistry registry) {
    registry.add("ntfy.url", WIREMOCK::baseUrl);
    registry.add("ntfy.topic", () -> "alerts");
    registry.add("ntfy.app-name", () -> "starter-test");
    // Keep the rate limiter from suppressing the single ERROR we send.
    registry.add("ntfy.max-alerts-per-window", () -> "10");
  }

  @Autowired(required = false)
  NtfyClient ntfyClient;

  @Test
  void rootLoggerHasStartedNtfyAutoAppender() {
    LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();
    ch.qos.logback.classic.Logger root = lc.getLogger(Logger.ROOT_LOGGER_NAME);
    Appender<?> appender = root.getAppender(NtfyAutoConfiguration.APPENDER_NAME);

    assertThat(appender).as("ntfy-auto appender on root logger").isNotNull();
    assertThat(appender.isStarted()).as("ntfy-auto appender started").isTrue();
  }

  @Test
  void ntfyClientBeanIsInjectable() {
    assertThat(ntfyClient).as("NtfyClient bean").isNotNull();
  }

  @Test
  void errorLogEventIsPublishedToNtfy() {
    // NOTE: must not start with "io.github.pimak.ntfy" — the engine always self-excludes its own
    // package root as an anti-loop guard, which would gate the event out before publishing.
    Logger appLogger = LoggerFactory.getLogger("com.example.demo.ItLogger");
    appLogger.error("boom for the starter integration test");

    await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
        WIREMOCK.verify(postRequestedFor(urlPathEqualTo("/alerts"))));
  }
}
