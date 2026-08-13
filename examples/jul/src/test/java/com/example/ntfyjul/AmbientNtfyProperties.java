package com.example.ntfyjul;

import java.util.HashSet;
import java.util.Set;

/**
 * Scoped {@code -Dntfy.*} system properties for a test: the example installs through the real
 * zero-code path ({@code NtfyJulInstaller} reading the ambient {@code ConfigLoader} chain), so the
 * tests configure it exactly the way an operator would — via system properties — and this helper
 * guarantees they are cleared again even when the test fails.
 */
final class AmbientNtfyProperties implements AutoCloseable {

  private final Set<String> keys = new HashSet<>();

  AmbientNtfyProperties set(String key, String value) {
    System.setProperty(key, value);
    keys.add(key);
    return this;
  }

  @Override
  public void close() {
    keys.forEach(System::clearProperty);
  }
}
