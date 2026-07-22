package io.github.pimak.ntfy.core;

import java.net.http.HttpClient;
import java.util.List;

/**
 * Generic, standalone ntfy notification client — the "send an arbitrary notification" surface,
 * independent of {@link AlertEngine}'s log-alert lifecycle. Builds its own {@link HttpClient},
 * {@link NtfyPublisher}, and {@link AuthMode} from the supplied {@link NtfyConfig} and publishes to
 * that config's {@code url}/{@code topic}.
 *
 * <p>Not thread-lifecycle managed the way the engine is: construct it, call {@link #notify} as
 * needed, and {@link #close} it to release the underlying HTTP client. Because it implements
 * {@link AutoCloseable}, it can be managed with a try-with-resources block for guaranteed disposal.
 * {@code AutoCloseable} does not mandate closing, so a long-lived, container-managed instance (e.g.
 * a Spring/Quarkus bean) may be kept open for the application's lifetime — any IDE resource-leak
 * inspection flagging such a bean is a false positive.
 * Publishing never throws — every outcome is reported through the returned {@link PublishResult}.
 */
public final class NtfyClient implements AutoCloseable {

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
   * Tags} headers. The configured {@link NtfyConfig#getClickUrl() clickUrl} (if any) is sent as the
   * {@code Click} header.
   *
   * @return the publish outcome; never throws
   */
  public PublishResult notify(String title, String message) {
    return publisher.publish(
        config.getUrl(), config.getTopic(), title, authMode, message, null, null,
        config.getClickUrl(), config.getActions());
  }

  /**
   * Publishes {@code title}/{@code message} to the configured topic, forwarding {@code priority}
   * and {@code tags} as ntfy's {@code Priority} and {@code Tags} headers (blank/null values send no
   * corresponding header). The configured {@link NtfyConfig#getClickUrl() clickUrl} and {@link
   * NtfyConfig#getActions() actions} (if any) are sent as the {@code Click}/{@code Actions} headers.
   *
   * @return the publish outcome; never throws
   */
  public PublishResult notify(String title, String message, String priority, String tags) {
    return publisher.publish(
        config.getUrl(), config.getTopic(), title, authMode, message, priority, tags,
        config.getClickUrl(), config.getActions());
  }

  /**
   * Publishes {@code title}/{@code message} to the configured topic, forwarding {@code priority},
   * {@code tags} and {@code click} as ntfy's {@code Priority}, {@code Tags} and {@code Click}
   * headers (blank/null values send no corresponding header). {@code click} overrides the configured
   * {@link NtfyConfig#getClickUrl() clickUrl} for this call; the configured {@link
   * NtfyConfig#getActions() actions} (if any) are still sent as the {@code Actions} header.
   *
   * @return the publish outcome; never throws
   */
  public PublishResult notify(
      String title, String message, String priority, String tags, String click) {
    return publisher.publish(
        config.getUrl(), config.getTopic(), title, authMode, message, priority, tags, click,
        config.getActions());
  }

  /**
   * Publishes {@code title}/{@code message} to the configured topic with the given action buttons,
   * serialized to ntfy's {@code Actions} header (ntfy caps a notification at three actions; extras
   * and {@code null} elements are dropped). {@code actions} overrides the configured {@link
   * NtfyConfig#getActions() actions} for this call; the configured {@link NtfyConfig#getClickUrl()
   * clickUrl} (if any) is still sent as the {@code Click} header.
   *
   * @return the publish outcome; never throws
   */
  public PublishResult notify(String title, String message, List<NtfyAction> actions) {
    return publisher.publish(
        config.getUrl(), config.getTopic(), title, authMode, message, null, null,
        config.getClickUrl(), NtfyActionSerializer.serialize(actions));
  }

  /**
   * Releases the underlying {@link HttpClient} (Java 21+ deterministic shutdown). May be invoked
   * automatically via a try-with-resources block, and is safe to call more than once.
   */
  @Override
  public void close() {
    httpClient.shutdownNow();
  }
}
