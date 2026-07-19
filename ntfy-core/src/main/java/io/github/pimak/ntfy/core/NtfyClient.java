package io.github.pimak.ntfy.core;

import java.net.http.HttpClient;

/**
 * Generic, standalone ntfy notification client — the "send an arbitrary notification" surface,
 * independent of {@link AlertEngine}'s log-alert lifecycle. Builds its own {@link HttpClient},
 * {@link NtfyPublisher}, and {@link AuthMode} from the supplied {@link NtfyConfig} and publishes to
 * that config's {@code url}/{@code topic}.
 *
 * <p>Not thread-lifecycle managed the way the engine is: construct it, call {@link #notify} as
 * needed, and {@link #close} it to release the underlying HTTP client. Publishing never throws —
 * every outcome is reported through the returned {@link PublishResult}.
 */
public final class NtfyClient {

  private final NtfyConfig config;
  private final HttpClient httpClient;
  private final NtfyPublisher publisher;
  private final AuthMode authMode;

  public NtfyClient(NtfyConfig config) {
    this.config = config;
    this.httpClient = HttpClient.newBuilder().connectTimeout(config.getConnectTimeout()).build();
    this.publisher = new NtfyPublisher(httpClient, config.getRequestTimeout());
    this.authMode =
        AuthMode.fromCredentials(config.getToken(), config.getUsername(), config.getPassword());
  }

  /**
   * Publishes {@code title}/{@code message} to the configured topic with no {@code Priority}/{@code
   * Tags} headers.
   *
   * @return the publish outcome; never throws
   */
  public PublishResult notify(String title, String message) {
    return publisher.publish(config.getUrl(), config.getTopic(), title, authMode, message);
  }

  /**
   * Publishes {@code title}/{@code message} to the configured topic, forwarding {@code priority}
   * and {@code tags} as ntfy's {@code Priority} and {@code Tags} headers (blank/null values send no
   * corresponding header).
   *
   * @return the publish outcome; never throws
   */
  public PublishResult notify(String title, String message, String priority, String tags) {
    return publisher.publish(
        config.getUrl(), config.getTopic(), title, authMode, message, priority, tags);
  }

  /** Releases the underlying {@link HttpClient} (Java 21+ deterministic shutdown). */
  public void close() {
    httpClient.shutdownNow();
  }
}
