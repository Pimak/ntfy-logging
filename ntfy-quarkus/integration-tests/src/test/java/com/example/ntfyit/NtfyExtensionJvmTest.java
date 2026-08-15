package com.example.ntfyit;

import static io.restassured.RestAssured.when;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * JVM smoke test: boots the real Quarkus app in-process with the ntfy extension active, hits {@code
 * /boom} (which logs an ERROR with an exception), and asserts the loopback ntfy stand-in received a
 * publish to the configured topic. This MUST pass under plain {@code ./mvnw verify} (no native).
 *
 * <p>It also asserts the {@code include-mdc-keys} allow-list against the REAL {@code
 * org.jboss.logmanager.ExtLogRecord}: {@code ntfy-jul} reads the MDC reflectively and its own unit
 * tests can only exercise a stub record, so this is the test that keeps that stub honest. {@code
 * /boom} puts both an allow-listed and a non-allow-listed entry into the MDC; the published body
 * must contain the first and never the second.
 *
 * <p>The {@code *IT} subclass ({@link NtfyExtensionIT}) reuses this exact logic against the packaged
 * artifact.
 */
@QuarkusTest
@QuarkusTestResource(LoopbackNtfyTestResource.class)
class NtfyExtensionJvmTest {

  /**
   * Both tests hit {@code /boom}, so the receipt slots are cleared first: {@link #waitForReceipt()}
   * polls for the path being set, and a leftover value from the previous test would let it return
   * immediately with a stale body.
   */
  @BeforeEach
  void clearPreviousReceipt() {
    System.clearProperty(LoopbackNtfyTestResource.RECEIVED_PATH_PROP);
    System.clearProperty(LoopbackNtfyTestResource.RECEIVED_BODY_PROP);
  }

  @Test
  void errorLogIsPublishedThroughTheExtensionHandler() throws InterruptedException {
    when().get("/boom").then().statusCode(200);

    assertThat(waitForReceipt())
        .as("loopback ntfy server should receive a publish to the configured topic")
        .isEqualTo(LoopbackNtfyTestResource.expectedPath());
  }

  @Test
  void onlyAllowListedMdcKeysReachTheAlertBody() throws InterruptedException {
    when().get("/boom").then().statusCode(200);

    assertThat(waitForReceipt())
        .as("the alert must have been published before its body can be inspected")
        .isEqualTo(LoopbackNtfyTestResource.expectedPath());

    String body = System.getProperty(LoopbackNtfyTestResource.RECEIVED_BODY_PROP);
    assertThat(body)
        .as("the allow-listed MDC key must be rendered into the alert body")
        .contains("correlation-id: req-4711");
    assertThat(body)
        .as("an MDC value whose key is not allow-listed must never be published")
        .doesNotContain("super-secret");
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
