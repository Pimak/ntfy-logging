package io.github.pimak.ntfy.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

/**
 * The topic is concatenated into the request path, so anything outside ntfy's own
 * {@code [-_A-Za-z0-9]{1,64}} rule must be rejected before a URI is built from it — a {@code /},
 * {@code ?}, {@code #}, or {@code ..}-bearing topic would otherwise rewrite the request target
 * (cross-topic publishing with the configured credential attached).
 */
class NtfyPublisherTopicValidationTest {

  @Test
  void validTopicNamesAreAccepted() {
    assertThat(NtfyPublisher.isValidTopic("alerts")).isTrue();
    assertThat(NtfyPublisher.isValidTopic("my-app_alerts2")).isTrue();
    assertThat(NtfyPublisher.isValidTopic("a".repeat(64))).isTrue();
  }

  @Test
  void pathQueryFragmentAndDotSegmentTopicsAreRejected() {
    assertThat(NtfyPublisher.isValidTopic("a/../b")).isFalse();
    assertThat(NtfyPublisher.isValidTopic("mytopic/other")).isFalse();
    assertThat(NtfyPublisher.isValidTopic("mytopic?since=all")).isFalse();
    assertThat(NtfyPublisher.isValidTopic("mytopic#x")).isFalse();
    assertThat(NtfyPublisher.isValidTopic("")).isFalse();
    assertThat(NtfyPublisher.isValidTopic(null)).isFalse();
    assertThat(NtfyPublisher.isValidTopic("a".repeat(65))).isFalse();
  }

  @Test
  void publishWithInvalidTopic_failsWithoutSendingAnyRequest() {
    // Port 9 (discard) is never contacted: the publisher must reject the topic before building a
    // URI, so this returns a failure result immediately rather than a connection error after a dial.
    NtfyPublisher publisher = new NtfyPublisher(HttpClient.newHttpClient());
    PublishResult result =
        publisher.publish(
            "http://127.0.0.1:9",
            "alerts/../secret-topic",
            "title",
            AuthMode.fromCredentials(null, null, null),
            "body");
    assertThat(result.success()).isFalse();
  }
}
