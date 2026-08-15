package io.github.pimak.ntfy.log4j2;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URL;
import java.util.Objects;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.junit.jupiter.api.Test;

/**
 * The regression lock on plugin discovery — the single highest-risk part of this module.
 *
 * <p>An {@code <Ntfy .../>} element only resolves because log4j-core's {@code PluginProcessor} ran
 * at compile time and emitted {@code
 * META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat}. That descriptor is
 * invisible to every other test in this module: an appender built through {@code
 * NtfyLog4j2Appender.newBuilder()} works perfectly whether or not the descriptor exists. If the
 * annotation-processor wiring in this module's pom ever regresses (a JDK that stops scanning the
 * classpath for processors, a {@code -proc:none} added in the wrong place, a plugin-name typo),
 * only this test notices — and it notices the way a user would: by loading real XML.
 *
 * <p>The configuration is loaded into a private, named {@link LoggerContext} rather than the JVM's
 * global one, so the surrounding test JVM's logging setup is untouched.
 */
class NtfyLog4j2PluginDiscoveryIT {

  private static final String CONFIG_RESOURCE = "log4j2-ntfy-plugin-discovery.xml";

  private static URI configUri() throws Exception {
    URL resource = NtfyLog4j2PluginDiscoveryIT.class.getClassLoader().getResource(CONFIG_RESOURCE);
    return Objects.requireNonNull(resource, CONFIG_RESOURCE + " missing from the test classpath")
        .toURI();
  }

  @Test
  void ntfyElementInLog4j2Xml_resolvesToTheAppenderAndAttachesToRoot() throws Exception {
    try (LoggerContext context =
        new LoggerContext("ntfy-plugin-discovery-it", null, configUri())) {
      context.start();

      Configuration configuration = context.getConfiguration();
      Appender appender = configuration.getAppender("ntfy");

      assertThat(appender)
          .as("<Ntfy> must resolve through Log4j2Plugins.dat to this module's appender")
          .isInstanceOf(NtfyLog4j2Appender.class);
      assertThat(configuration.getRootLogger().getAppenders())
          .as("the resolved appender must be attached to the root logger via <AppenderRef>")
          .containsKey("ntfy");
      // Started proves the plugin attributes really reached NtfyConfig: with url/topic missing the
      // engine would have declined and the appender would report itself stopped.
      assertThat(appender.isStarted()).isTrue();
    }
  }

  @Test
  void resolvedAppenderCarriesTheDeclaredAttributes() throws Exception {
    try (LoggerContext context =
        new LoggerContext("ntfy-plugin-discovery-attributes-it", null, configUri())) {
      context.start();

      NtfyLog4j2Appender appender = context.getConfiguration().getAppender("ntfy");

      assertThat(appender.getName()).isEqualTo("ntfy");
      // No alert has been submitted, so the counters must be pristine — this also proves the
      // appender exposes its observability handle through a real XML-built instance.
      assertThat(appender.getCounters().published()).isZero();
      assertThat(appender.getCounters().suppressed()).isZero();
      assertThat(appender.getCounters().failed()).isZero();
    }
  }
}
