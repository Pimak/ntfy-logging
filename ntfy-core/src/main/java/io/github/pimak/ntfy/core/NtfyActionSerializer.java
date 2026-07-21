package io.github.pimak.ntfy.core;

import java.util.List;

/**
 * Renders a list of {@link NtfyAction}s into the value of ntfy's {@code Actions} HTTP header, using
 * ntfy's documented short "simple" format: fields within one action separated by {@code ", "},
 * actions separated by {@code "; "}. User-supplied field values (label, url, method, body, and
 * {@code headers.*}/{@code extras.*}/intent values) are quoted only when they contain a delimiter
 * ({@code ,} / {@code ;} / {@code "} / {@code \}) or have
 * leading/trailing whitespace ntfy's parser would otherwise trim — an internal space (as in {@code
 * View logs}) needs no quoting.
 *
 * <p>Never throws: ntfy caps a notification at three actions, so this caps at the first three and
 * skips {@code null} elements rather than failing — the {@code NtfyClient.notify(...)} contract
 * (never throw) must hold even when handed an over-long or sparse list. An empty/blank result yields
 * {@code null}, i.e. "send no {@code Actions} header".
 */
final class NtfyActionSerializer {

  /** ntfy's per-notification action limit. */
  static final int MAX_ACTIONS = 3;

  private NtfyActionSerializer() {}

  /**
   * @return the {@code Actions} header value, or {@code null} when {@code actions} is null/empty (or
   *     contains only null elements) — meaning no header should be sent.
   */
  static String serialize(List<NtfyAction> actions) {
    if (actions == null || actions.isEmpty()) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    int rendered = 0;
    for (NtfyAction action : actions) {
      if (action == null) {
        continue;
      }
      if (rendered == MAX_ACTIONS) {
        break;
      }
      if (rendered > 0) {
        sb.append("; ");
      }
      render(sb, action);
      rendered++;
    }
    return sb.length() == 0 ? null : sb.toString();
  }

  private static void render(StringBuilder sb, NtfyAction action) {
    if (action instanceof NtfyAction.View view) {
      sb.append("view, ").append(quote(view.label())).append(", ").append(quote(view.url()));
      if (view.clear()) {
        sb.append(", clear=true");
      }
    } else if (action instanceof NtfyAction.Http http) {
      sb.append("http, ").append(quote(http.label())).append(", ").append(quote(http.url()));
      if (!isBlank(http.method())) {
        sb.append(", method=").append(quote(http.method()));
      }
      for (NtfyAction.HttpHeader header : http.headers()) {
        sb.append(", headers.").append(header.name()).append('=').append(quote(header.value()));
      }
      if (!isBlank(http.body())) {
        sb.append(", body=").append(quote(http.body()));
      }
      if (http.clear()) {
        sb.append(", clear=true");
      }
    } else if (action instanceof NtfyAction.Broadcast broadcast) {
      sb.append("broadcast, ").append(quote(broadcast.label()));
      for (NtfyAction.BroadcastExtra extra : broadcast.extras()) {
        sb.append(", extras.").append(extra.name()).append('=').append(quote(extra.value()));
      }
      if (!isBlank(broadcast.intent())) {
        sb.append(", intent=").append(quote(broadcast.intent()));
      }
      if (broadcast.clear()) {
        sb.append(", clear=true");
      }
    }
  }

  /**
   * Wraps {@code value} in double quotes (escaping {@code "} and {@code \}) only when it contains a
   * field/action delimiter or would lose leading/trailing whitespace to ntfy's field trimming;
   * otherwise returns it verbatim.
   */
  private static String quote(String value) {
    if (value == null) {
      return "";
    }
    if (!needsQuoting(value)) {
      return value;
    }
    StringBuilder sb = new StringBuilder(value.length() + 2);
    sb.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"' || c == '\\') {
        sb.append('\\');
      }
      sb.append(c);
    }
    sb.append('"');
    return sb.toString();
  }

  private static boolean needsQuoting(String value) {
    if (value.isEmpty()) {
      return true;
    }
    if (Character.isWhitespace(value.charAt(0))
        || Character.isWhitespace(value.charAt(value.length() - 1))) {
      return true;
    }
    return value.indexOf(',') >= 0
        || value.indexOf(';') >= 0
        || value.indexOf('"') >= 0
        || value.indexOf('\\') >= 0;
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
