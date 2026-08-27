package com.example.ntfyzerocode;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The zero-code Logback path, reduced to what can be observed: log one ERROR through the SLF4J API,
 * then say which appenders the root logger ended up with. Nothing here mentions ntfy, and this
 * module ships no {@code logback.xml} — the appender can only be there if Logback's {@link
 * ch.qos.logback.classic.spi.Configurator} service lookup found {@code NtfyLogbackConfigurator} and
 * ran it.
 *
 * <p>That is the whole question this probe exists to answer in a GraalVM native image.
 * {@code ntfy-logback} ships no reachability metadata for its {@code META-INF/services} entry, and a
 * service that native-image cannot prove reachable is dropped without a word: the application still
 * starts, still logs, and simply never alerts. It is the exact failure mode the project's
 * "verifiable at boot" promise is meant to exclude, so leaving it to inference was not an option.
 *
 * <p>Configuration is read from the ambient environment by {@code ConfigLoader} ({@code NTFY_URL},
 * {@code NTFY_TOPIC}, {@code NTFY_ASYNC}) at image run time — the auto-install refuses to activate
 * without it, which is what makes "no appender" and "no configuration" distinguishable in the output
 * below rather than the same silence.
 */
public final class ZeroCodeNativeProbe {

  /** The name {@code NtfyLogbackConfigurator} registers its appender under. */
  static final String AUTO_APPENDER_NAME = "ntfy-auto";

  /** The SPI never ran, or ran and declined: whatever the reason, alerting is not installed. */
  static final int EXIT_NO_AUTO_INSTALL = 4;

  private ZeroCodeNativeProbe() {}

  public static void main(String[] args) {
    int status = run();
    if (status != 0) {
      System.exit(status);
    }
  }

  /**
   * Runs the probe and returns its exit status, so the JVM control test can call it in-process
   * without a {@link System#exit} taking the test JVM down with it. Deliberately not a {@code void}
   * that throws: the native leg reads a process exit code, and one status source for both legs is
   * what makes them comparable.
   */
  static int run() {
    // First touch of SLF4J in this process — this is what triggers Logback's default configuration,
    // and therefore the Configurator service lookup under test.
    Logger log = LoggerFactory.getLogger(ZeroCodeNativeProbe.class);
    log.error("Nightly batch failed", new IllegalStateException("row 42: malformed record"));

    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    ch.qos.logback.classic.Logger root =
        context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
    List<String> appenders = new ArrayList<>();
    for (Iterator<Appender<ILoggingEvent>> it = root.iteratorForAppenders(); it.hasNext(); ) {
      appenders.add(it.next().getName());
    }

    // Stopping the context drains any queued alert and flushes a pending digest while the HTTP
    // client is still live — the same guarantee logback.xml's shutdownHook gives the XML path.
    context.stop();

    System.out.println("probe: appenders=" + appenders);
    if (!appenders.contains(AUTO_APPENDER_NAME)) {
      System.err.println(
          "probe: the Configurator SPI did not install '" + AUTO_APPENDER_NAME + "'");
      return EXIT_NO_AUTO_INSTALL;
    }
    return 0;
  }
}
