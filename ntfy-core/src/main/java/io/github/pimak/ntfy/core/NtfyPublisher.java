package io.github.pimak.ntfy.core;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Stateless, never-throws HTTP publisher for the ntfy wire format.
 *
 * <p>Zero framework dependencies — this class is plain-constructed by {@code AlertEngine}/{@code
 * NtfyClient}, not managed by any DI container. It never calls an SLF4J logger and never posts to a
 * Logback {@code StatusManager}; the caller owns all diagnostics and only consumes the returned
 * {@link PublishResult}.
 *
 * <p>No SSRF guard is included here — that concern is out of scope for a plain HTTP publisher and
 * is the consumer's responsibility if the target URL is derived from untrusted input. The topic,
 * however, IS validated (ntfy's own {@code [-_A-Za-z0-9]{1,64}} rule): it is concatenated into the
 * request path, and a topic containing {@code /}, {@code ?}, {@code #}, or {@code ..} would
 * otherwise rewrite the request target (cross-topic publishing with the configured credential).
 */
public class NtfyPublisher {

  private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

  private static final String GENERIC_INVALID_REQUEST_MESSAGE =
      "invalid request: malformed URL, topic, or header value";

  /**
   * ntfy's documented topic-name rule. Anything outside it (path separators, query/fragment
   * characters, dot segments) is rejected before a URI is ever built from it.
   */
  private static final java.util.regex.Pattern TOPIC_PATTERN =
      java.util.regex.Pattern.compile("[-_A-Za-z0-9]{1,64}");

  private final HttpClient httpClient;
  private final Duration requestTimeout;

  public NtfyPublisher(HttpClient httpClient) {
    this(httpClient, DEFAULT_REQUEST_TIMEOUT);
  }

  public NtfyPublisher(HttpClient httpClient, Duration requestTimeout) {
    this.httpClient = httpClient;
    this.requestTimeout = requestTimeout;
  }

  /**
   * Publishes {@code title}/{@code body} to {@code {url}/{topic}} with no {@code Priority}/{@code
   * Tags}/{@code Click} headers. Delegates to {@link #publish(String, String, String, AuthMode,
   * String, String, String, String)} with {@code priority}/{@code tags}/{@code click} all {@code
   * null}.
   *
   * @return a {@link PublishResult} describing the outcome; never throws
   */
  public PublishResult publish(
      String url, String topic, String title, AuthMode auth, String body) {
    return publish(url, topic, title, auth, body, null, null, null);
  }

  /**
   * Priority/tags overload with no {@code Click} header. Delegates to {@link #publish(String,
   * String, String, AuthMode, String, String, String, String)} with {@code click} {@code null}.
   *
   * @return a {@link PublishResult} describing the outcome; never throws
   */
  public PublishResult publish(
      String url,
      String topic,
      String title,
      AuthMode auth,
      String body,
      String priority,
      String tags) {
    return publish(url, topic, title, auth, body, priority, tags, null);
  }

  /**
   * Priority/tags/click overload with no {@code Actions} header. Delegates to {@link
   * #publish(String, String, String, AuthMode, String, String, String, String, String)} with
   * {@code actions} {@code null}.
   *
   * @return a {@link PublishResult} describing the outcome; never throws
   */
  public PublishResult publish(
      String url,
      String topic,
      String title,
      AuthMode auth,
      String body,
      String priority,
      String tags,
      String click) {
    return publish(url, topic, title, auth, body, priority, tags, click, null);
  }

  /**
   * Publishes {@code title}/{@code body} to {@code {url}/{topic}}, additionally forwarding {@code
   * priority}, {@code tags}, {@code click} and {@code actions} as ntfy's {@code Priority}, {@code
   * Tags}, {@code Click} and {@code Actions} HTTP headers. {@code click} is the URL ntfy opens when
   * the notification is tapped; {@code actions} is a pre-serialized ntfy {@code Actions} header
   * value (see {@code NtfyActionSerializer}).
   *
   * <p>A blank/null value for any of {@code priority}/{@code tags}/{@code click}/{@code actions}
   * sends no corresponding header. Values containing non-printable-ASCII characters are omitted
   * rather than forwarded: the CRLF-injection guard must not depend solely on the JDK client's
   * header validation, and a single invalid configured value must not abort every publish at the
   * header-build boundary.
   *
   * <p>The {@code Authorization} header (if any) comes entirely from the supplied {@link
   * AuthMode}: {@code auth.buildHeader()} returns the header value to send, or {@code
   * Optional.empty()} to send no {@code Authorization} header at all (a valid anonymous publish).
   *
   * @return a {@link PublishResult} describing the outcome; never throws
   */
  public PublishResult publish(
      String url,
      String topic,
      String title,
      AuthMode auth,
      String body,
      String priority,
      String tags,
      String click,
      String actions) {
    if (!isValidTopic(topic)) {
      return PublishResult.failure(GENERIC_INVALID_REQUEST_MESSAGE);
    }
    try {
      String base = url.replaceAll("/+$", "");
      URI uri = URI.create(base + "/" + topic);

      HttpRequest.Builder builder =
          HttpRequest.newBuilder()
              .uri(uri)
              .timeout(requestTimeout)
              .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));

      auth.buildHeader().ifPresent(header -> builder.header("Authorization", header));

      if (!isBlank(title)) {
        builder.header("Title", asciiSafeTitle(title));
      }

      if (!isBlank(priority) && isAsciiPrintable(priority)) {
        builder.header("Priority", priority);
      }

      if (!isBlank(tags) && isAsciiPrintable(tags)) {
        builder.header("Tags", tags);
      }

      if (!isBlank(click) && isAsciiPrintable(click)) {
        builder.header("Click", click);
      }

      if (!isBlank(actions) && isAsciiPrintable(actions)) {
        builder.header("Actions", actions);
      }

      HttpResponse<String> response =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      int status = response.statusCode();
      if (status >= 200 && status < 300) {
        return PublishResult.success(status);
      }
      return PublishResult.failure(status, "ntfy server returned HTTP " + status);
    } catch (HttpTimeoutException e) {
      return PublishResult.failure("timeout");
    } catch (ConnectException e) {
      return PublishResult.failure("connection refused");
    } catch (IllegalArgumentException e) {
      // Thrown by URI.create() (malformed URL/topic) or HttpRequest.Builder.header() (a
      // credential/value containing an illegal control character embeds that value verbatim in
      // its own message) — never surface e.getMessage() here, it may contain the plaintext
      // credential.
      return PublishResult.failure(GENERIC_INVALID_REQUEST_MESSAGE);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return PublishResult.failure("interrupted");
    } catch (Exception e) {
      // Classify by exception type only — never e.getMessage(), which can embed the full URI,
      // proxy details, or other request internals.
      return PublishResult.failure("publish failed: " + e.getClass().getSimpleName());
    }
  }

  /**
   * Returns {@code title} verbatim when it is printable ASCII; otherwise wraps it in an RFC 2047
   * encoded-word ({@code =?UTF-8?B?...?=}, the form ntfy documents for non-ASCII titles). The JDK
   * {@code HttpClient} rejects header values containing chars &gt; 0xFF, so without this a
   * non-ASCII configured title/appName would make every publish fail before any request is sent.
   */
  private static String asciiSafeTitle(String title) {
    if (isAsciiPrintable(title)) {
      return title;
    }
    return "=?UTF-8?B?"
        + Base64.getEncoder().encodeToString(title.getBytes(StandardCharsets.UTF_8))
        + "?=";
  }

  private static boolean isAsciiPrintable(String s) {
    return s.chars().allMatch(c -> c >= 0x20 && c <= 0x7E);
  }

  /**
   * True when {@code topic} is a valid ntfy topic name ({@code [-_A-Za-z0-9]{1,64}}).
   * Package-visible so {@code AlertEngine} can refuse activation on an invalid topic instead of
   * failing every publish.
   */
  static boolean isValidTopic(String topic) {
    return topic != null && TOPIC_PATTERN.matcher(topic).matches();
  }

  /**
   * True when {@code url} is a structurally valid http(s) endpoint the publisher can actually
   * consume — non-blank, parseable, with an {@code http}/{@code https} scheme and a non-blank
   * authority. Package-visible so {@code AlertEngine} can refuse activation on a malformed /
   * non-http(s) URL instead of failing every publish quietly (where the {@code URI.create}
   * failure is deliberately collapsed into a generic no-leak message).
   *
   * <p>The URL is normalized with the character-for-character identical expression the publish
   * path uses ({@code replaceAll("/+$", "")} — see {@link #publish(String, String, String,
   * AuthMode, String, String, String, String, String)}), and deliberately nothing more. In
   * particular there is no {@code trim()}: neither {@code NtfyConfig} nor the publisher trims, so a
   * whitespace-padded URL fails {@code URI} parsing on every publish; trimming here would accept a
   * config the publisher cannot consume and recreate the silent-failure gap this guard closes. Any
   * future change to the publisher's normalization must be mirrored here.
   *
   * <p>The authority (not the host) is what is checked: {@code URI.getHost()} returns {@code null}
   * for the documented {@code https://user:pass@host} basic-auth form and for underscore
   * hostnames (registry-based host parsing fails on the unencoded {@code @} / {@code _}), so a
   * {@code getHost() != null} rule would over-reject those already-supported forms.
   * {@code getAuthority()} is present for all of them and absent for a scheme-less string like
   * {@code ntfy.sh}.
   */
  static boolean isValidEndpointUrl(String url) {
    if (isBlank(url)) {
      return false;
    }
    String normalized = url.replaceAll("/+$", "");
    try {
      URI uri = new URI(normalized);
      String scheme = uri.getScheme();
      return scheme != null
          && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
          && !isBlank(uri.getAuthority());
    } catch (java.net.URISyntaxException e) {
      return false;
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
