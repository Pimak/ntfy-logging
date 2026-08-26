package io.github.pimak.ntfy.core;

import java.net.ConnectException;
import java.net.URI;
import java.net.URISyntaxException;
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
 * <p><strong>Internal transport.</strong> This class is public only because {@code AlertEngine},
 * {@code NtfyClient} and {@code StartupSelfTest} needed it before there was anywhere better to put
 * it — not because it was designed as an entry point. It is NOT covered by this library's
 * binary-compatibility guarantee, its shape is free to change within 2.x, and it is scheduled to
 * stop being public in 3.0. Publish through {@link NtfyClient} instead, which is the supported
 * surface for sending a notification of your own.
 *
 * <p><strong>What {@link NtfyClient} does not replace.</strong> An {@code NtfyClient} publishes to
 * the destination its {@link NtfyConfig} fixed at construction, which covers every caller that has
 * one destination — the ordinary case. It does not cover publishing to a {@code url}/{@code topic}
 * that varies from call to call, one per tenant or one per environment, for which the deprecated
 * overloads below are currently the only route. That case has no supported replacement, and 3.0
 * removes the overloads. If you depend on it, please open an issue saying so: what replaces it has
 * not been designed yet, and a stated use case is what would decide its shape.
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
   * @deprecated internal transport, not covered by this library's binary-compatibility guarantee
   *     and not public in 3.0. Publish through {@link NtfyClient}, whose destination is fixed at
   *     construction; publishing to a {@code url}/{@code topic} that varies per call has no
   *     supported replacement — see the class documentation.
   */
  @Deprecated(since = "2.1.0")
  public PublishResult publish(
      String url, String topic, String title, AuthMode auth, String body) {
    return publish(url, topic, title, auth, body, null, null, null);
  }

  /**
   * Priority/tags overload with no {@code Click} header. Delegates to {@link #publish(String,
   * String, String, AuthMode, String, String, String, String)} with {@code click} {@code null}.
   *
   * @return a {@link PublishResult} describing the outcome; never throws
   * @deprecated internal transport, not covered by this library's binary-compatibility guarantee
   *     and not public in 3.0. Publish through {@link NtfyClient}, whose destination is fixed at
   *     construction; publishing to a {@code url}/{@code topic} that varies per call has no
   *     supported replacement — see the class documentation.
   */
  @Deprecated(since = "2.1.0")
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
   * @deprecated internal transport, not covered by this library's binary-compatibility guarantee
   *     and not public in 3.0. Publish through {@link NtfyClient}, whose destination is fixed at
   *     construction; publishing to a {@code url}/{@code topic} that varies per call has no
   *     supported replacement — see the class documentation.
   */
  @Deprecated(since = "2.1.0")
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
   * @deprecated internal transport, not covered by this library's binary-compatibility guarantee
   *     and not public in 3.0. Publish through {@link NtfyClient}, whose destination is fixed at
   *     construction; publishing to a {@code url}/{@code topic} that varies per call has no
   *     supported replacement — see the class documentation.
   */
  @Deprecated(since = "2.1.0")
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
    return publish(
        new NtfyTarget(url, topic, auth),
        new NtfyMessage(title, body, priority, tags, click, actions, null));
  }

  /**
   * The single publish path. Every overload above collapses onto this one, and so does every caller
   * inside this package.
   *
   * <p>Package-private, and that is the point: the arguments a publish needs keep growing as ntfy
   * grows headers, and threading them positionally through public overloads meant a new overload
   * per header, deprecated forever under the binary-compatibility guard. Taking two objects instead
   * moves that churn somewhere it costs nothing — nothing outside this package can see the shape,
   * so it is free to change.
   *
   * <p>A blank/null value for any optional header sends no corresponding header. Beyond that the
   * two kinds of header part company. {@code Title} is RFC 2047 encoded when it is not printable
   * ASCII, because a non-ASCII application name is ordinary and dropping it would be a surprise.
   * Every OTHER optional header carrying a character outside printable ASCII is omitted rather than
   * forwarded: the CRLF-injection guard must not depend solely on the JDK client's header
   * validation, and a single invalid configured value must not abort every publish at the
   * header-build boundary.
   *
   * <p>The {@code Authorization} header (if any) comes entirely from the target's {@link AuthMode}:
   * {@code target.auth().buildHeader()} returns the header value to send, or {@code
   * Optional.empty()} to send no {@code Authorization} header at all (a valid anonymous publish).
   *
   * @return a {@link PublishResult} describing the outcome; never throws
   */
  PublishResult publish(NtfyTarget target, NtfyMessage message) {
    if (!isValidTopic(target.topic())) {
      return PublishResult.failure(GENERIC_INVALID_REQUEST_MESSAGE);
    }
    try {
      String base = target.url().replaceAll("/+$", "");
      URI uri = URI.create(base + "/" + target.topic());

      String body = message.body();
      HttpRequest.Builder builder =
          HttpRequest.newBuilder()
              .uri(uri)
              .timeout(requestTimeout)
              .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));

      target.auth().buildHeader().ifPresent(header -> builder.header("Authorization", header));

      if (!isBlank(message.title())) {
        builder.header("Title", asciiSafeTitle(message.title()));
      }

      header(builder, "Priority", message.priority());
      header(builder, "Tags", message.tags());
      header(builder, "Click", message.click());
      header(builder, "Actions", message.actions());
      header(builder, "Icon", message.icon());

      // Only the opt-OUT is ever expressed on the wire. ntfy caches and forwards to Firebase by
      // default, so sending "Cache: yes"/"Firebase: yes" would be a no-op that merely widens the
      // request; the absent header and the affirmative header mean the same thing to the server.
      if (!target.cache()) {
        builder.header("Cache", "no");
      }
      if (!target.firebase()) {
        builder.header("Firebase", "no");
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
   * Sets {@code name} to {@code value} unless it is blank or carries a character outside printable
   * ASCII. Collapses the identical guard the optional headers each repeated.
   */
  private static void header(HttpRequest.Builder builder, String name, String value) {
    if (!isBlank(value) && isAsciiPrintable(value)) {
      builder.header(name, value);
    }
  }

  /**
   * Read-only reachability check against {@code {url}/{topic}/json?poll=1&since=none}, used by the
   * opt-in startup self-test. Sends the configured {@code Authorization} header but publishes
   * nothing, so subscribers of the topic see no notification.
   *
   * <p>{@code poll=1} makes ntfy answer and close instead of holding the connection open as a live
   * stream — without it this request would block until the request timeout on every healthy server.
   * {@code since=none} suppresses replay of cached messages, so a busy topic does not return a
   * large body just to prove the endpoint answers.
   *
   * <p>Deliberately shares {@link #publish(NtfyTarget, NtfyMessage)}'s URL normalization, topic
   * validation and — most importantly — its catch-chain, which classifies failures by exception TYPE and never by {@code
   * e.getMessage()}, because that message can embed the request URI or a plaintext credential.
   * Reuses {@link PublishResult} rather than introducing a parallel result type: it already carries
   * exactly what the caller needs, an HTTP status (or {@code null} when the request never reached
   * the server) plus a fixed, credential-safe description.
   *
   * @return a {@link PublishResult} describing the outcome; never throws
   */
  PublishResult probe(NtfyTarget target) {
    if (!isValidTopic(target.topic())) {
      return PublishResult.failure(GENERIC_INVALID_REQUEST_MESSAGE);
    }
    try {
      String base = target.url().replaceAll("/+$", "");
      URI uri = URI.create(base + "/" + target.topic() + "/json?poll=1&since=none");

      HttpRequest.Builder builder = HttpRequest.newBuilder().uri(uri).timeout(requestTimeout).GET();

      target.auth().buildHeader().ifPresent(header -> builder.header("Authorization", header));

      // discarding(), not ofString(): only the status code is ever inspected, and a self-test is
      // precisely the situation where the endpoint may NOT be an ntfy server — a typo'd url can
      // answer with an arbitrarily large HTML error page that there is no reason to materialize.
      HttpResponse<Void> response =
          httpClient.send(builder.build(), HttpResponse.BodyHandlers.discarding());
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
      // URI.create() on a malformed URL/topic, or header() on a credential containing an illegal
      // control character (which embeds that value verbatim in its own message) — never surface
      // e.getMessage() here.
      return PublishResult.failure(GENERIC_INVALID_REQUEST_MESSAGE);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return PublishResult.failure("interrupted");
    } catch (Exception e) {
      // Classify by exception type only — never e.getMessage(), which can embed the full URI,
      // proxy details, or other request internals.
      return PublishResult.failure("probe failed: " + e.getClass().getSimpleName());
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
   * path uses ({@code replaceAll("/+$", "")} — see {@link #publish(NtfyTarget, NtfyMessage)}), and
   * deliberately nothing more. In
   * particular there is no {@code trim()}: neither {@code NtfyConfig} nor the publisher trims, so a
   * whitespace-padded URL fails {@code URI} parsing on every publish; trimming here would accept a
   * config the publisher cannot consume and recreate the silent-failure gap this guard closes. Any
   * future change to the publisher's normalization must be mirrored here.
   *
   * <p>The authority (not the host) is what is checked: {@code URI.getHost()} returns {@code null}
   * for an underscore hostname, registry-based host parsing having no place for {@code _}, so a
   * {@code getHost() != null} rule would over-reject a form this library already supports.
   * {@code getAuthority()} is present for it and absent for a scheme-less string like {@code
   * ntfy.sh}. (Userinfo is NOT a reason: {@code getHost()} resolves those perfectly well — the
   * two only interact when a credential precedes an underscore host.)
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
    } catch (URISyntaxException e) {
      return false;
    }
  }

  /**
   * True when {@code icon} is a URL a subscriber's client could fetch: an {@code http}/{@code
   * https} URL with an authority.
   *
   * <p>Nothing about the FORMAT is checked here, deliberately. ntfy renders JPEG and PNG only,
   * but that is a property of the bytes served, not of how the path is spelt: content-negotiated
   * and extension-less URLs — {@code https://avatars.example.com/u/9919?v=4} and its kind — serve
   * PNGs perfectly well. An earlier version of this method required a {@code .png}/{@code
   * .jpg}/{@code .jpeg} suffix and rejected exactly those, which is a rule this library invented
   * and ntfy does not have. See {@link #isLikelyUnrenderableIcon} for the part that can honestly
   * be judged from a URL.
   *
   * <p>"With an authority" is not quite the requirement: the authority has to NAME A HOST.
   * {@code http://:80/logo.png} has an authority and no host, and nothing could fetch it.
   *
   * <p>Printable ASCII is required too, and that one is about this library rather than about
   * URLs. Header values outside printable ASCII are dropped at the header-build boundary — so
   * without checking it here, an internationalised domain or a unicode path would pass startup
   * validation, be declared usable, and then never reach the wire, with no diagnostic anywhere.
   * That is exactly the invisible failure this check exists to close, and it would have been
   * this check letting it through. Such a URL has to be punycode/percent-encoded by the
   * operator.
   *
   * <p>Package-visible so {@code AlertEngine} can refuse an unusable value at {@code start()} and
   * {@code NtfyClient} can drop it, rather than sending a header nothing could ever load.
   */
  static boolean isValidIconUrl(String icon) {
    if (isBlank(icon)) {
      return false;
    }
    try {
      if (!isAsciiPrintable(icon)) {
        return false;
      }
      URI uri = new URI(icon);
      String scheme = uri.getScheme();
      return scheme != null
          && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
          && hasHost(uri.getAuthority());
    } catch (URISyntaxException e) {
      return false;
    }
  }

  /**
   * True when {@code authority} names a host, rather than merely being non-blank.
   *
   * <p>The host PART of the authority is what is checked, not {@link URI#getHost()}. That method
   * returns {@code null} for an underscore hostname, which is perfectly fetchable, so testing it
   * would reject a working URL. Testing the authority alone, on the other hand, accepts {@code
   * http://:80/x}, whose authority is present and whose host is not — hence the middle course of
   * reading the host out of the authority by hand.
   *
   * <p>An IPv6 literal is bracketed and full of colons, so the port cannot be found by looking
   * for the last one: in {@code [2001:db8::1]} that lands inside the address and leaves {@code
   * [2001:db8:} behind as the supposed host. The bracket is what delimits it.
   */
  private static boolean hasHost(String authority) {
    if (isBlank(authority)) {
      return false;
    }
    String hostAndPort = authority.substring(authority.lastIndexOf('@') + 1);
    if (hostAndPort.startsWith("[")) {
      int close = hostAndPort.indexOf(']');
      // Unclosed bracket: not a host anyone can resolve.
      return close > 1;
    }
    int colon = hostAndPort.indexOf(':');
    String host = colon < 0 ? hostAndPort : hostAndPort.substring(0, colon);
    return !host.isBlank();
  }

  /**
   * True when {@code icon}'s path ends in an image extension ntfy is documented not to render.
   *
   * <p>A closed list of formats that certainly will not work, rather than a guess at which ones
   * will: an {@code .svg} is a mistake worth naming, while a URL with no extension at all says
   * nothing either way. This only ever produces a warning — the value is still sent, because
   * being wrong about it costs the operator an icon they could have had.
   */
  static boolean isLikelyUnrenderableIcon(String icon) {
    if (!isValidIconUrl(icon)) {
      return false;
    }
    String path = URI.create(icon).getPath();
    if (path == null) {
      return false;
    }
    String lower = path.toLowerCase(java.util.Locale.ROOT);
    return lower.endsWith(".svg")
        || lower.endsWith(".tif")
        || lower.endsWith(".gif")
        || lower.endsWith(".webp")
        || lower.endsWith(".bmp")
        || lower.endsWith(".ico")
        || lower.endsWith(".avif")
        || lower.endsWith(".tiff");
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
