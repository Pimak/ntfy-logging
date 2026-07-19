package com.example.ntfyit;

import static io.restassured.RestAssured.when;
import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * JVM smoke test: boots the real Quarkus app in-process with the ntfy extension active, hits {@code
 * /boom} (which logs an ERROR with an exception), and asserts the loopback ntfy stand-in received a
 * publish to the configured topic. This MUST pass under plain {@code ./mvnw verify} (no native).
 *
 * <p>The {@code *IT} subclass ({@link NtfyExtensionIT}) reuses this exact logic against the packaged
 * artifact.
 */
@QuarkusTest
@QuarkusTestResource(LoopbackNtfyTestResource.class)
class NtfyExtensionJvmTest {

  @Test
  void errorLogIsPublishedThroughTheExtensionHandler() throws InterruptedException {
    when().get("/boom").then().statusCode(200);

    assertThat(waitForReceipt())
        .as("loopback ntfy server should receive a publish to the configured topic")
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
