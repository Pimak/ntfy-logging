package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/**
 * Covers {@link ConfigLoader}'s resolution of the async delivery keys ({@code ntfy.async} /
 * {@code NTFY_ASYNC} and {@code ntfy.async-queue-capacity} / {@code NTFY_ASYNC_QUEUE_CAPACITY}),
 * including precedence and the malformed-value-keeps-default behavior that mirrors the other numeric
 * knobs.
 */
class ConfigLoaderAsyncTest {

  private static Properties sysProp(String key, String value) {
    Properties p = new Properties();
    p.setProperty(key, value);
    return p;
  }

  @Test
  void defaultsWhenUnset() {
    NtfyConfig config = ConfigLoader.load(k -> null, new Properties(), new Properties());
    assertThat(config.isAsyncEnabled()).isFalse();
    assertThat(config.getAsyncQueueCapacity()).isEqualTo(1024);
  }

  @Test
  void asyncEnabledFromSystemProperty() {
    NtfyConfig config =
        ConfigLoader.load(k -> null, new Properties(), sysProp("ntfy.async", "true"));
    assertThat(config.isAsyncEnabled()).isTrue();
  }

  @Test
  void asyncQueueCapacityFromEnv() {
    Map<String, String> env = new HashMap<>();
    env.put("NTFY_ASYNC_QUEUE_CAPACITY", "256");
    NtfyConfig config = ConfigLoader.load(env::get, new Properties(), new Properties());
    assertThat(config.getAsyncQueueCapacity()).isEqualTo(256);
  }

  @Test
  void systemPropertyWinsOverFileForAsync() {
    Properties file = new Properties();
    file.setProperty("ntfy.async", "false");
    NtfyConfig config = ConfigLoader.load(k -> null, file, sysProp("ntfy.async", "true"));
    assertThat(config.isAsyncEnabled()).isTrue();
  }

  @Test
  void malformedQueueCapacityKeepsDefault() {
    NtfyConfig config =
        ConfigLoader.load(
            k -> null, new Properties(), sysProp("ntfy.async-queue-capacity", "not-a-number"));
    assertThat(config.getAsyncQueueCapacity()).isEqualTo(1024);
  }
}
