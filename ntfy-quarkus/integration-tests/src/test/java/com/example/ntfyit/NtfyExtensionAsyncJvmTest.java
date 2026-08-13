package com.example.ntfyit;

import static io.restassured.RestAssured.when;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * JVM smoke test of asynchronous delivery: with {@code quarkus.ntfy.async=true} (see {@link
 * AsyncNtfyProfile}), hitting {@code /boom} returns while the loopback ntfy server is still holding
 * its response open — the blocking send happens on the {@code ntfy-alert-delivery} daemon worker,
 * never on the request thread — and the alert still lands once the response is released. In
 * synchronous mode the same request would block on the held response until the stand-in's 15 s
 * safety timeout.
 */
@QuarkusTest
@TestProfile(AsyncNtfyProfile.class)
@QuarkusTestResource(LoopbackNtfyTestResource.class)
class NtfyExtensionAsyncJvmTest {

  @AfterEach
  void releaseHeldResponses() {
    System.clearProperty(LoopbackNtfyTestResource.HOLD_RESPONSES_PROP);
  }

  @Test
  void asyncBoomReturnsWhileTheNtfyResponseIsStillHeld() throws InterruptedException {
    System.setProperty(LoopbackNtfyTestResource.HOLD_RESPONSES_PROP, "true");

    long startNanos = System.nanoTime();
    when().get("/boom").then().statusCode(200);
    long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

    // Generous margin: an async /boom returns in milliseconds, a synchronous one only after the
    // held response's 15 s safety timeout.
    assertThat(elapsedMillis)
        .as("/boom must not wait for the (held) ntfy response when async is enabled")
        .isLessThan(5_000L);

    System.clearProperty(LoopbackNtfyTestResource.HOLD_RESPONSES_PROP);
    assertThat(waitForReceipt())
        .as("the alert still lands once the held response is released")
        .isEqualTo(LoopbackNtfyTestResource.expectedPath());
  }

  private static String waitForReceipt() throws InterruptedException {
    for (int i = 0;
        i < 50 && System.getProperty(LoopbackNtfyTestResource.RECEIVED_PATH_PROP) == null;
        i++) {
      TimeUnit.MILLISECONDS.sleep(100);
    }
    return System.getProperty(LoopbackNtfyTestResource.RECEIVED_PATH_PROP);
  }
}
