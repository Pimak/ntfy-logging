package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;

/**
 * Verifies {@link NtfyClient} implements {@link AutoCloseable} so it can be used in a
 * try-with-resources block, and that closing it is safe and repeatable. Constructs the client
 * offline (no network, no ntfy server) from a minimal {@link NtfyConfig}.
 */
class NtfyClientAutoCloseableTest {

  private static NtfyConfig minimalConfig() {
    return NtfyConfig.builder().url("https://ntfy.example.com").topic("test-topic").build();
  }

  @Test
  void ntfyClient_isAutoCloseable() {
    assertThat(AutoCloseable.class.isAssignableFrom(NtfyClient.class)).isTrue();
  }

  @Test
  void usableInTryWithResources_closesWithoutThrowing() {
    assertThatCode(
            () -> {
              try (NtfyClient client = new NtfyClient(minimalConfig())) {
                assertThat(client).isNotNull();
              }
            })
        .doesNotThrowAnyException();
  }

  @Test
  void close_isIdempotent() {
    NtfyClient client = new NtfyClient(minimalConfig());

    assertThatCode(
            () -> {
              client.close();
              client.close();
            })
        .doesNotThrowAnyException();
  }
}
