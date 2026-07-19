package io.github.pimak.ntfy.core;

/**
 * A single ntfy notification action button, modeled as a sealed hierarchy so each action type
 * carries only the fields it needs (mirroring the {@link AuthMode} pattern). Actions are rendered
 * into ntfy's {@code Actions} HTTP header by {@link NtfyActionSerializer}; ntfy allows at most three
 * per notification.
 *
 * <p>Only the two action types meaningful to a server-side JVM alerter are modeled: {@link View}
 * (open a URL — e.g. a dashboard or log link) and {@link Http} (fire an HTTP request — e.g. an
 * acknowledge webhook). ntfy's Android-only {@code broadcast} type is intentionally omitted.
 *
 * <p>{@code label} and {@code url} are validated non-blank at construction: a malformed action is a
 * programming error surfaced immediately, never a silent runtime publish failure.
 */
public sealed interface NtfyAction permits NtfyAction.View, NtfyAction.Http {

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
    return new Http(label, url, null, null, false);
  }

  /** An {@code http} action sending {@code method} (with optional {@code body}) to {@code url}. */
  static Http http(String label, String url, String method, String body) {
    return new Http(label, url, method, body, false);
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
   * (POST) when blank; {@code body} is sent as the request body when non-blank.
   */
  record Http(String label, String url, String method, String body, boolean clear)
      implements NtfyAction {
    public Http {
      requireField(label, "label");
      requireField(url, "url");
    }
  }

  private static void requireField(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("ntfy action " + name + " must not be blank");
    }
  }
}
