package com.example.ntfylog4j2;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.core.LoggerContext;

/**
 * Builds a fresh {@link LoggerContext} from the example's real {@code log4j2.xml}. The {@code
 * properties} map is published as system properties for the duration of the configuration parse,
 * which is what feeds the {@code ${sys:ntfy.url:-${env:NTFY_URL:-…}}} substitutions in that file —
 * the way the tests point the very same configuration a user would ship at the loopback ntfy
 * stand-in and flip its knobs (async, storm-suppression) per test.
 *
 * <p>Two deliberate details:
 *
 * <ul>
 *   <li>The properties are cleared again as soon as the context has started. Log4j2 resolves plugin
 *       attributes once, while parsing, so nothing needs them afterwards — and a leaked {@code
 *       ntfy.url} would silently bleed into the next test.
 *   <li>Every context gets a unique name. The configuration is loaded into a private context rather
 *       than the JVM's global one (which {@code log4j2-test.xml} keeps ntfy-free), so contexts from
 *       different tests coexist and must stay individually identifiable in log4j2's own diagnostics.
 * </ul>
 */
final class ExampleLoggerContexts {

  /** The example application's shipped configuration, from {@code src/main/resources}. */
  private static final String EXAMPLE_CONFIG = "/log4j2.xml";

  private static final AtomicInteger CONTEXT_SEQUENCE = new AtomicInteger();

  private ExampleLoggerContexts() {}

  static LoggerContext startFromExampleXml(Map<String, String> properties) {
    properties.forEach(System::setProperty);
    try {
      LoggerContext context =
          new LoggerContext(
              "ntfy-example-it-" + CONTEXT_SEQUENCE.incrementAndGet(), null, exampleConfigUri());
      // Parses the XML and starts the appenders — the only moment the substitutions above matter.
      context.start();
      return context;
    } finally {
      properties.keySet().forEach(System::clearProperty);
    }
  }

  private static URI exampleConfigUri() {
    URL resource = ExampleLoggerContexts.class.getResource(EXAMPLE_CONFIG);
    Objects.requireNonNull(resource, EXAMPLE_CONFIG + " missing from the test classpath");
    try {
      return resource.toURI();
    } catch (URISyntaxException e) {
      throw new IllegalStateException("failed to locate the example " + EXAMPLE_CONFIG, e);
    }
  }
}
