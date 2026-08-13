package com.example.ntfyit;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Test profile flipping the extension to asynchronous delivery ({@code quarkus.ntfy.async=true}) —
 * exactly the one property a user sets in {@code application.properties} to keep a slow ntfy server
 * from ever blocking application threads. Used by {@link NtfyExtensionAsyncJvmTest}.
 */
public class AsyncNtfyProfile implements QuarkusTestProfile {

  @Override
  public Map<String, String> getConfigOverrides() {
    return Map.of("quarkus.ntfy.async", "true");
  }
}
