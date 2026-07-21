package io.github.pimak.ntfy.core;

import java.util.List;

/**
 * A single ntfy notification action button, modeled as a sealed hierarchy so each action type
 * carries only the fields it needs (mirroring the {@link AuthMode} pattern). Actions are rendered
 * into ntfy's {@code Actions} HTTP header by {@link NtfyActionSerializer}; ntfy allows at most three
 * per notification.
 *
 * <p>All three ntfy action types are modeled: {@link View} (open a URL — e.g. a dashboard or log
 * link), {@link Http} (fire an HTTP request — e.g. an acknowledge webhook, with optional typed
 * {@link HttpHeader}s), and {@link Broadcast} (send an Android broadcast intent with optional typed
 * {@link BroadcastExtra}s — only actioned by ntfy's Android app).
 *
 * <p>{@code label} and {@code url} are validated non-blank at construction: a malformed action is a
 * programming error surfaced immediately, never a silent runtime publish failure.
 */
public sealed interface NtfyAction permits NtfyAction.View, NtfyAction.Http, NtfyAction.Broadcast {

  /** The button label shown in the notification. */
  String label();

  /** A {@code view} action opening {@code url} when tapped. */
  static View view(String label, String url) {
    return new View(label, url, false);
  }

  /** A {@code view} action opening {@code url}; {@code clear} dismisses the notification on tap. */
  static View view(String label, String url, boolean clear) {
    return new View(label, url, clear);
  }

  /** An {@code http} action sending a GET to {@code url} when tapped. */
  static Http http(String label, String url) {
    return new Http(label, url, null, null, false, List.of());
  }

  /** An {@code http} action sending {@code method} (with optional {@code body}) to {@code url}. */
  static Http http(String label, String url, String method, String body) {
    return new Http(label, url, method, body, false, List.of());
  }

  /**
   * An {@code http} action sending {@code method} (with optional {@code body}) to {@code url},
   * carrying the given request {@code headers} (e.g. {@code Authorization}).
   */
  static Http http(String label, String url, String method, String body, HttpHeader... headers) {
    return new Http(label, url, method, body, false, List.of(headers));
  }

  /**
   * An {@code http} action as {@link #http(String, String, String, String, HttpHeader...)} but with
   * an explicit {@code clear} flag dismissing the notification on tap.
   */
  static Http http(
      String label, String url, String method, String body, boolean clear, HttpHeader... headers) {
    return new Http(label, url, method, body, clear, List.of(headers));
  }

  /** A {@code broadcast} action sending ntfy's default intent, with optional {@code extras}. */
  static Broadcast broadcast(String label, BroadcastExtra... extras) {
    return new Broadcast(label, null, List.of(extras), false);
  }

  /** A {@code broadcast} action sending {@code intent} (blank uses ntfy's default), with extras. */
  static Broadcast broadcast(String label, String intent, BroadcastExtra... extras) {
    return new Broadcast(label, intent, List.of(extras), false);
  }

  /**
   * A {@code broadcast} action sending {@code intent} (blank uses ntfy's default) with an explicit
   * {@code clear} flag dismissing the notification on tap, plus optional {@code extras}.
   */
  static Broadcast broadcast(
      String label, String intent, boolean clear, BroadcastExtra... extras) {
    return new Broadcast(label, intent, List.of(extras), clear);
  }

  /** Opens {@code url} in the browser/app when the action button is tapped. */
  record View(String label, String url, boolean clear) implements NtfyAction {
    public View {
      requireField(label, "label");
      requireField(url, "url");
    }
  }

  /**
   * Sends an HTTP request to {@code url} when tapped. {@code method} defaults to ntfy's own default
   * (POST) when blank; {@code body} is sent as the request body when non-blank; each {@link
   * HttpHeader} in {@code headers} is sent as a request header.
   */
  record Http(
      String label, String url, String method, String body, boolean clear, List<HttpHeader> headers)
      implements NtfyAction {
    public Http {
      requireField(label, "label");
      requireField(url, "url");
      headers = copyOf(headers);
    }
  }

  /**
   * Sends an Android broadcast intent when tapped (only actioned by ntfy's Android app).
   * {@code intent} defaults to ntfy's own default ({@code io.heckel.ntfy.USER_ACTION}) when blank;
   * each {@link BroadcastExtra} in {@code extras} is passed as an intent extra.
   */
  record Broadcast(String label, String intent, List<BroadcastExtra> extras, boolean clear)
      implements NtfyAction {
    public Broadcast {
      requireField(label, "label");
      extras = copyOf(extras);
    }
  }

  /** A single request header ({@code name}/{@code value}) carried by an {@link Http} action. */
  record HttpHeader(String name, String value) {
    public HttpHeader {
      requireField(name, "header name");
      requireField(value, "header value");
    }
  }

  /** A single intent extra ({@code name}/{@code value}) carried by a {@link Broadcast} action. */
  record BroadcastExtra(String name, String value) {
    public BroadcastExtra {
      requireField(name, "extra name");
      requireField(value, "extra value");
    }
  }

  private static void requireField(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ntfy action " + name + " must not be blank");
    }
  }

  /**
   * Normalizes an optional field list into an immutable, null-free copy: a {@code null} list becomes
   * empty and {@code null} elements are dropped, so {@link NtfyActionSerializer} never trips on them.
   */
  private static <T> List<T> copyOf(List<T> values) {
    if (values == null || values.isEmpty()) {
      return List.of();
    }
    return values.stream().filter(v -> v != null).toList();
  }
}
