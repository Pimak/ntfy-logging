package io.github.pimak.ntfy.core;

/**
 * Everything {@link AlertEngine} needs to publish one {@link AlertLevel}'s alerts: where they go
 * ({@code topic}), how they look ({@code priority}/{@code tags} for individual alerts, {@code
 * digestPriority}/{@code digestTags} for the storm digest), and the storm budget they draw from
 * ({@code limiter}).
 *
 * <p>Built once per active level in {@link AlertEngine#start()} and released in {@code stop()}.
 * The ERROR route always exists on a started engine; the WARN route exists only when the operator
 * configured a {@code warn-topic}.
 *
 * <p><strong>Each route owns its own {@link AlertRateLimiter}</strong>, constructed with the same
 * {@code max-alerts-per-window}/{@code suppression-window} as the other. That separation is the
 * whole point of routing by level rather than merely lowering a threshold: sharing one budget would
 * let a flood of warnings exhaust the allowance and push genuine errors into a digest — the exact
 * failure the ERROR-only floor existed to prevent.
 *
 * @param level the level this route serves
 * @param topic the ntfy topic alerts on this route are published to
 * @param priority the ntfy {@code Priority} header for individual alerts on this route
 * @param tags the ntfy {@code Tags} header for individual alerts on this route
 * @param digestPriority the ntfy {@code Priority} header for this route's storm digest
 * @param digestTags the ntfy {@code Tags} header for this route's storm digest
 * @param limiter this route's own storm budget and suppression tally
 */
record RouteState(
    AlertLevel level,
    String topic,
    String priority,
    String tags,
    String digestPriority,
    String digestTags,
    AlertRateLimiter limiter) {

  /** The always-on ERROR route: individual alerts and digests are styled independently. */
  static RouteState forError(NtfyConfig config, AlertRateLimiter limiter) {
    return new RouteState(
        AlertLevel.ERROR,
        config.getTopic(),
        config.getErrorPriority(),
        config.getErrorTags(),
        config.getDigestPriority(),
        config.getDigestTags(),
        limiter);
  }

  /**
   * The opt-in WARN route. Its digest deliberately reuses {@code warn-priority}/{@code warn-tags}
   * rather than the global {@code digest-priority}/{@code digest-tags}: the point of a warn topic
   * is a channel quieter than the error one, and a digest arriving at {@code urgent}/🔥 would undo
   * that on the worst possible occasion — a burst of warnings. On this route a digest is told apart
   * from an individual alert by its title and body, not by how loudly the phone rings.
   */
  static RouteState forWarn(NtfyConfig config, AlertRateLimiter limiter) {
    return new RouteState(
        AlertLevel.WARN,
        config.getWarnTopic(),
        config.getWarnPriority(),
        config.getWarnTags(),
        config.getWarnPriority(),
        config.getWarnTags(),
        limiter);
  }
}
