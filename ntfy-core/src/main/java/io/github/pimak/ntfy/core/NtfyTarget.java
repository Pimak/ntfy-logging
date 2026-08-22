package io.github.pimak.ntfy.core;

/**
 * Where a publish goes and under what delivery policy — the half of a request that is fixed at
 * {@code start()} and does not change from one alert to the next.
 *
 * <p>Package-private on purpose. This is transport shape, not domain vocabulary, and keeping it
 * internal lets it change without engaging any compatibility guarantee: {@code NtfyPublisher}'s
 * public overloads stay the published surface until they are removed in the next major.
 *
 * <p>Split from {@link NtfyMessage} by LIFETIME rather than by header category. Everything here is
 * decided once, when the engine starts, and a {@code RouteState} can hold one for the whole run;
 * everything in {@link NtfyMessage} is assembled per event. That is also why {@code cache} and
 * {@code firebase} sit on this side despite travelling as message headers on the wire — what they
 * describe is how the SERVER should handle delivery, and an operator sets them once for a
 * deployment, never per alert.
 *
 * @param url the ntfy endpoint, with or without trailing slashes
 * @param topic the ntfy topic; validated by the publisher before any URI is built from it
 * @param auth the credential to send, or {@link AuthMode} yielding no header for anonymous publish
 * @param cache {@code false} sends {@code Cache: no}, so the server stores nothing and only
 *     connected subscribers ever receive the message; {@code true} (the default) sends no header
 * @param firebase {@code false} sends {@code Firebase: no}, so the server does not forward to FCM;
 *     {@code true} (the default) sends no header
 */
record NtfyTarget(String url, String topic, AuthMode auth, boolean cache, boolean firebase) {

  /** The plain target: server-side caching and Firebase forwarding both left at ntfy's default. */
  NtfyTarget(String url, String topic, AuthMode auth) {
    this(url, topic, auth, true, true);
  }
}
