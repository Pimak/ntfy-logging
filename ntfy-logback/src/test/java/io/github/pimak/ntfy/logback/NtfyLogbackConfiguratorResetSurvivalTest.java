package io.github.pimak.ntfy.logback;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.Appender;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * The critical reset-survival guarantee: the auto-installing {@link NtfyLogbackConfigurator} attaches
 * a {@code ntfy-auto} appender to the root logger, and a subsequent application {@code
 * JoranConfigurator} run (which resets the context, detaching every programmatically-added appender)
 * must NOT lose it — the reset-resistant {@link NtfyLogbackReattachListener} re-attaches it.
 */
@WireMockTest
class NtfyLogbackConfiguratorResetSurvivalTest {

  private static final String APPENDER_NAME = "ntfy-auto";

  private static Appender<ILoggingEvent> rootAppender(LoggerContext ctx) {
    Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
    return root.getAppender(APPENDER_NAME);
  }

  @Test
  void autoInstalledAppender_survivesJoranContextReset(WireMockRuntimeInfo wm) throws Exception {
    stubFor(post(urlEqualTo("/alerts")).willReturn(aResponse().withStatus(200)));

    // Configure ntfy purely through the ambient environment (system properties), the same source
    // ConfigLoader reads — no XML wiring of the appender itself.
    System.setProperty("ntfy.url", "http://localhost:" + wm.getHttpPort());
    System.setProperty("ntfy.topic", "alerts");
    LoggerContext ctx = new LoggerContext();
    try {
      NtfyLogbackConfigurator configurator = new NtfyLogbackConfigurator();
      configurator.setContext(ctx);
      configurator.configure(ctx);

      // Auto-install attached the appender to root.
      assertThat(rootAppender(ctx))
          .as("configurator should attach the ntfy-auto appender to the root logger")
          .isNotNull();
      assertThat(rootAppender(ctx).isStarted()).isTrue();

      // Now an application runs its own logback.xml through Joran, which resets the context —
      // detaching every programmatically-added appender. The reset-resistant listener must
      // re-attach ours.
      String logbackXml =
          "<configuration>\n"
              + "  <appender name=\"APP\" class=\"ch.qos.logback.core.read.ListAppender\"/>\n"
              + "  <root level=\"INFO\">\n"
              + "    <appender-ref ref=\"APP\"/>\n"
              + "  </root>\n"
              + "</configuration>\n";
      JoranConfigurator joran = new JoranConfigurator();
      joran.setContext(ctx);
      joran.doConfigure(
          new ByteArrayInputStream(logbackXml.getBytes(StandardCharsets.UTF_8)));

      // The application's own appender is present...
      assertThat(rootAppender(ctx)).as("ntfy-auto must survive the Joran reset").isNotNull();
      assertThat(ctx.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("APP"))
          .as("the application's own appender should be installed by Joran")
          .isNotNull();
      // ...and, crucially, so is ours — re-attached by the reset-resistant listener.
      assertThat(rootAppender(ctx).getName()).isEqualTo(APPENDER_NAME);
      assertThat(rootAppender(ctx).isStarted()).isTrue();
    } finally {
      ctx.stop();
      System.clearProperty("ntfy.url");
      System.clearProperty("ntfy.topic");
    }
  }
}
