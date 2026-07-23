package io.github.pimak.ntfy.logback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Supply-chain guard for the zero-code auto-install: when the endpoint URL is supplied ONLY by a
 * classpath {@code ntfy.properties} (a file any jar on the classpath can ship), {@link
 * NtfyLogbackConfigurator} must REFUSE to attach the appender unless the operator explicitly opts in
 * via {@code ntfy.allow-classpath-endpoint} / {@code NTFY_ALLOW_CLASSPATH_ENDPOINT}. The classpath
 * file is simulated by pointing the thread context classloader (the loader {@code ConfigLoader}
 * reads resources from) at a temp directory containing a generated {@code ntfy.properties}.
 */
@WireMockTest
class NtfyLogbackConfiguratorClasspathEndpointTest {

  @TempDir Path tempDir;

  private static final String ALLOW_KEY = "ntfy.allow-classpath-endpoint";
  private static final String URL_KEY = "ntfy.url";
  private static final String TOPIC_KEY = "ntfy.topic";
  private String savedAllowClasspathEndpoint;
  private String savedUrl;
  private String savedTopic;

  @BeforeEach
  void isolateAmbientConfig() {
    // ConfigLoader prefers -Dntfy.url/-Dntfy.topic (and NTFY_URL/NTFY_TOPIC env) over the classpath
    // ntfy.properties, which would defeat the "classpath-only endpoint" premise. Env vars can't be
    // unset from within the JVM, so skip rather than exercise the wrong path; system properties we
    // save and clear so the generated file is the ONLY endpoint source (endpointFromClasspathFile).
    assumeTrue(
        System.getenv("NTFY_URL") == null && System.getenv("NTFY_TOPIC") == null,
        "NTFY_URL/NTFY_TOPIC set in the environment — cannot exercise the classpath-only path");
    savedUrl = System.getProperty(URL_KEY);
    savedTopic = System.getProperty(TOPIC_KEY);
    System.clearProperty(URL_KEY);
    System.clearProperty(TOPIC_KEY);
    // Make the "without opt-in" scenarios deterministic: force the opt-in OFF even if it is set in
    // the ambient environment (e.g. via MAVEN_OPTS). Tests that need it ON set it explicitly.
    savedAllowClasspathEndpoint = System.getProperty(ALLOW_KEY);
    System.setProperty(ALLOW_KEY, "false");
  }

  @AfterEach
  void restoreAmbientConfig() {
    restore(ALLOW_KEY, savedAllowClasspathEndpoint);
    restore(URL_KEY, savedUrl);
    restore(TOPIC_KEY, savedTopic);
  }

  private static void restore(String key, String saved) {
    if (saved == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, saved);
    }
  }

  private static Appender<ILoggingEvent> rootAppender(LoggerContext ctx) {
    Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
    return root.getAppender(NtfyLogbackConfigurator.APPENDER_NAME);
  }

  /** Runs the configurator with a classpath ntfy.properties visible via the TCCL. */
  private LoggerContext configureWithClasspathProperties(String url) throws IOException {
    Files.write(
        tempDir.resolve("ntfy.properties"),
        ("ntfy.url=" + url + "\nntfy.topic=alerts\n").getBytes(StandardCharsets.UTF_8));

    Thread thread = Thread.currentThread();
    ClassLoader original = thread.getContextClassLoader();
    LoggerContext ctx = new LoggerContext();
    try (URLClassLoader withProps =
        new URLClassLoader(new URL[] {tempDir.toUri().toURL()}, original)) {
      thread.setContextClassLoader(withProps);
      NtfyLogbackConfigurator configurator = new NtfyLogbackConfigurator();
      configurator.setContext(ctx);
      configurator.configure(ctx);
    } finally {
      thread.setContextClassLoader(original);
    }
    return ctx;
  }

  @Test
  void classpathOnlyEndpoint_withoutOptIn_isRefused(WireMockRuntimeInfo wm) throws Exception {
    LoggerContext ctx = configureWithClasspathProperties("http://localhost:" + wm.getHttpPort());
    try {
      assertThat(rootAppender(ctx))
          .as("a classpath-only endpoint must NOT auto-install without the explicit opt-in")
          .isNull();
      assertThat(ctx.getStatusManager().getCopyOfStatusList())
          .as("the refusal must be explained with a WARN status naming the opt-in")
          .anySatisfy(
              status -> {
                assertThat(status.getLevel()).isEqualTo(Status.WARN);
                assertThat(status.getMessage())
                    .contains("refusing to auto-install")
                    .contains("ntfy.allow-classpath-endpoint");
              });
    } finally {
      ctx.stop();
    }
  }

  @Test
  void classpathOnlyEndpoint_withOptIn_installs(WireMockRuntimeInfo wm) throws Exception {
    // @AfterEach restores the prior value; here we only flip the opt-in ON before the configurator
    // reads it.
    System.setProperty(ALLOW_KEY, "true");
    LoggerContext ctx = configureWithClasspathProperties("http://localhost:" + wm.getHttpPort());
    try {
      assertThat(rootAppender(ctx))
          .as("with the explicit opt-in, the classpath-configured appender installs as before")
          .isNotNull();
      assertThat(rootAppender(ctx).isStarted()).isTrue();
      assertThat(ctx.getStatusManager().getCopyOfStatusList())
          .as("the trust warning about the classpath file must still be emitted")
          .anySatisfy(
              status -> {
                assertThat(status.getLevel()).isEqualTo(Status.WARN);
                assertThat(status.getMessage()).contains("make sure that file is one you trust");
              });
    } finally {
      ctx.stop();
    }
  }

  @Test
  void refusalWarn_stripsUserinfoFromLoggedUrl(WireMockRuntimeInfo wm) throws Exception {
    LoggerContext ctx =
        configureWithClasspathProperties("http://user:secret@localhost:" + wm.getHttpPort());
    try {
      assertThat(ctx.getStatusManager().getCopyOfStatusList())
          .anySatisfy(
              status -> {
                assertThat(status.getMessage()).contains("refusing to auto-install");
                assertThat(status.getMessage()).doesNotContain("secret");
              });
    } finally {
      ctx.stop();
    }
  }
}
