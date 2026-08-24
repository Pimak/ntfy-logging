package io.github.pimak.ntfy.core;

/**
 * What a single publish carries — the half of a request assembled per event.
 *
 * <p>Package-private for the same reason as {@link NtfyTarget}: transport shape, free to change,
 * not a published contract. Note this is deliberately NOT named after a domain concept. A
 * notification and an alert are two different acts with different guarantees; both happen to travel
 * as one of these, and naming it {@code NtfyMessage} keeps it from accreting the semantics of
 * either.
 *
 * <p>Every field but {@code body} is optional: a blank or {@code null} value sends no corresponding
 * header. Beyond that, a value carrying a character outside printable ASCII is dropped rather than
 * failing the request — one bad configured value must not abort every publish at the header-build
 * boundary. {@code title} is the exception: the publisher RFC 2047 encodes it instead, since a
 * non-ASCII application name is ordinary rather than a mistake.
 *
 * @param title the ntfy {@code Title} header; non-ASCII titles are RFC 2047 encoded by the publisher
 * @param body the request body; {@code null} publishes an empty body
 * @param priority the ntfy {@code Priority} header
 * @param tags the ntfy {@code Tags} header
 * @param click the URL ntfy opens when the notification is tapped ({@code Click})
 * @param actions a pre-serialized ntfy {@code Actions} header value (see {@link
 *     NtfyActionSerializer})
 * @param icon URL of the notification icon ({@code Icon}); the subscriber's client downloads it
 */
record NtfyMessage(
    String title,
    String body,
    String priority,
    String tags,
    String click,
    String actions,
    String icon) {

  /** The startup self-test shape: a styled message with no click target, actions or icon. */
  NtfyMessage(String title, String body, String priority, String tags) {
    this(title, body, priority, tags, null, null, null);
  }
}
