package com.example.ntfylogback;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import java.util.Map;

/**
 * Builds a fresh {@link LoggerContext} from the example's real {@code logback.xml}. The {@code
 * properties} map feeds Logback's variable substitution ({@code ${NTFY_URL:-…}} etc.), which is how
 * the tests point the very same configuration file a user would ship at the loopback ntfy stand-in
 * and flip its knobs (async, storm-suppression) per test.
 */
final class ExampleLoggerContexts {

  private ExampleLoggerContexts() {}

  static LoggerContext startFromExampleXml(Map<String, String> properties) {
    LoggerContext context = new LoggerContext();
    properties.forEach(context::putProperty);
    JoranConfigurator configurator = new JoranConfigurator();
    configurator.setContext(context);
    try {
      configurator.doConfigure(ExampleLoggerContexts.class.getResource("/logback.xml"));
    } catch (JoranException e) {
      throw new IllegalStateException("failed to configure LoggerContext from logback.xml", e);
    }
    return context;
  }
}
