package io.github.pimak.ntfy.core;

/**
 * The opt-in startup self-test: one round-trip per configured route at engine start, so a revoked
 * token or a wrong topic surfaces at deploy time rather than when the first real alert silently
 * fails to deliver.
 *
 * <p>Routes are tested independently because they are configured independently: {@code
 * startup-ping} covers the primary ERROR topic and {@code startup-ping-warn} the optional WARN
 * topic, each with its own {@link StartupPingMode} and each defaulting to {@code off}. ntfy grants
 * permissions per topic, so a working ERROR route says nothing about the WARN one — testing only
 * the primary would leave exactly the gap this feature exists to close.
 *
 * <p>Split into {@link #execute} and {@link #report} on purpose. The engine must be able to run the
 * request, then decide whether the outcome is still worth reporting — an asynchronous self-test can
 * land after a concurrent {@code stop()}, and warning about an engine the operator just shut down
 * would be noise, not diagnosis.
 *
 * <p>Deliberately calls {@link NtfyPublisher} directly and never {@code AlertEngine.deliver()}: that
 * path folds failures into the rate limiter's suppressed count and the storm digest, so routing a
 * self-test through it would manufacture a phantom application error and corrupt the digest
 * arithmetic. For the same reason nothing here touches {@link PipelineCounters} — those tallies
 * describe application alert traffic, and a boot-time self-check is not that.
 */
final class StartupSelfTest {

  /**
   * Priority of the {@code publish}-mode test notification. Deliberately NOT {@code error-priority}:
   * a self-test that passes is good news, and good news must never wake anyone. {@code low} rather
   * than {@code min} so the notification still appears in the client's list — half the point of
   * {@code publish} mode is being able to confirm on the receiving device that alerts arrive — but
   * arrives without sound or vibration.
   */
  private static final String SELF_TEST_PRIORITY = "low";

  /** Fixed tag for the test notification, so it is visually separable from real alerts. */
  private static final String SELF_TEST_TAGS = "white_check_mark";

  /**
   * Priority of the failure notification. Higher than the passing test — a broken alerting route is
   * worth seeing — but deliberately below the error route's own priority: it repeats on every boot
   * until fixed, so it must inform without paging.
   */
  private static final String FAILURE_PRIORITY = "default";

  /** Fixed tag for the failure notification. */
  private static final String FAILURE_TAGS = "warning";

  private StartupSelfTest() {}

  /**
   * One route under test: the topic to hit, and whether it is the WARN route (which only changes
   * how the outcome is worded, never how it is tested).
   */
  record Target(String topic, boolean warn) {}

  /**
   * Performs the self-test round-trip for {@code target} and returns its outcome. Never throws —
   * both publisher entry points classify every failure into a {@link PublishResult} carrying an HTTP
   * status (or {@code null} when the request never reached the server) plus a fixed,
   * credential-safe reason.
   *
   * <p>{@code mode} must not be {@link StartupPingMode#OFF}; the engine short-circuits that case
   * before any resource is touched, since {@code off} must produce no request AND no diagnostic.
   */
  static PublishResult execute(
      StartupPingMode mode,
      Target target,
      NtfyConfig config,
      NtfyPublisher publisher,
      AuthMode auth,
      AlertMessages messages) {
    NtfyTarget destination =
        new NtfyTarget(
            config.getUrl(), target.topic(), auth, config.isCache(), config.isFirebase());
    if (mode == StartupPingMode.PROBE) {
      return publisher.probe(destination);
    }
    return publisher.publish(
        destination,
        new NtfyMessage(
            messages.selfTestTitle(appLabel(config)),
            messages.selfTestBody(),
            SELF_TEST_PRIORITY,
            SELF_TEST_TAGS));
  }

  /**
   * Renders {@code result} as a single diagnostic line: {@code info} on success, {@code warn} on
   * failure with the HTTP status already translated into a probable cause and a remedy (see {@link
   * AlertMessages#statusStartupPingFailed}). The WARN route's line is the same text wrapped with
   * its route and topic, so the two routes can never be confused for one another.
   *
   * @return {@code true} when the self-test passed
   */
  static boolean report(
      StartupPingMode mode,
      Target target,
      PublishResult result,
      AlertMessages messages,
      Diagnostics diagnostics) {
    String line = line(mode, target, result, messages);
    if (result.success()) {
      diagnostics.info(line);
      return true;
    }
    diagnostics.warn(line);
    return false;
  }

  /** The diagnostic text for one route's outcome, without emitting it. */
  static String line(
      StartupPingMode mode, Target target, PublishResult result, AlertMessages messages) {
    String base =
        result.success()
            ? (mode == StartupPingMode.PROBE
                ? messages.statusStartupPingProbePassed()
                : messages.statusStartupPingPublishPassed())
            : messages.statusStartupPingFailed(result.httpStatus(), result.message());
    return target.warn() ? messages.statusStartupPingWarnRoute(base, target.topic()) : base;
  }

  /**
   * Publishes {@code failures} to {@code healthy} — a route whose own self-test just PASSED, which
   * is the only reason to believe the notification can arrive at all.
   *
   * <p>This is what makes a broken route visible where operators actually look. A diagnostic line
   * goes to a status manager or stderr that nobody greps; if one route still works, it can carry
   * the news about the other.
   *
   * @return the publish outcome, so the caller can diagnose a failure to report the failure
   */
  static PublishResult notifyFailure(
      Target healthy,
      String failures,
      NtfyConfig config,
      NtfyPublisher publisher,
      AuthMode auth,
      AlertMessages messages) {
    return publisher.publish(
        new NtfyTarget(
            config.getUrl(), healthy.topic(), auth, config.isCache(), config.isFirebase()),
        new NtfyMessage(
            messages.selfTestFailureTitle(appLabel(config)),
            messages.selfTestFailureBody(failures),
            FAILURE_PRIORITY,
            FAILURE_TAGS));
  }

  /**
   * Same title fallback the real alert path uses (title, else appName), so an operator whose several
   * services share one topic can tell which one just booted.
   */
  private static String appLabel(NtfyConfig config) {
    return !isBlank(config.getTitle()) ? config.getTitle() : config.getAppName();
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
