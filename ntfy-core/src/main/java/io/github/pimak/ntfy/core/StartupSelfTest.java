package io.github.pimak.ntfy.core;

/**
 * The opt-in startup self-test: one round-trip against the configured endpoint at engine start, so
 * a revoked token or a wrong topic surfaces at deploy time rather than when the first real error
 * alert silently fails to deliver.
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

  private StartupSelfTest() {}

  /**
   * Performs the self-test round-trip and returns its outcome. Never throws — both publisher entry
   * points classify every failure into a {@link PublishResult} carrying an HTTP status (or {@code
   * null} when the request never reached the server) plus a fixed, credential-safe reason.
   *
   * <p>{@code mode} must not be {@link StartupPingMode#OFF}; the engine short-circuits that case
   * before any resource is touched, since {@code off} must produce no request AND no diagnostic.
   */
  static PublishResult execute(
      StartupPingMode mode, NtfyConfig config, NtfyPublisher publisher, AuthMode auth,
      AlertMessages messages) {
    if (mode == StartupPingMode.PROBE) {
      return publisher.probe(config.getUrl(), config.getTopic(), auth);
    }
    // Same title fallback the real alert path uses (title, else appName), so an operator whose
    // several services share one topic can tell which one just booted.
    String appLabel = !isBlank(config.getTitle()) ? config.getTitle() : config.getAppName();
    return publisher.publish(
        config.getUrl(),
        config.getTopic(),
        messages.selfTestTitle(appLabel),
        auth,
        messages.selfTestBody(),
        SELF_TEST_PRIORITY,
        SELF_TEST_TAGS);
  }

  /**
   * Renders {@code result} as a single diagnostic line: {@code info} on success, {@code warn} on
   * failure with the HTTP status already translated into a probable cause and a remedy (see {@link
   * AlertMessages#statusStartupPingFailed}).
   *
   * @return {@code true} when the self-test passed
   */
  static boolean report(
      StartupPingMode mode, PublishResult result, AlertMessages messages, Diagnostics diagnostics) {
    if (result.success()) {
      diagnostics.info(
          mode == StartupPingMode.PROBE
              ? messages.statusStartupPingProbePassed()
              : messages.statusStartupPingPublishPassed());
      return true;
    }
    diagnostics.warn(messages.statusStartupPingFailed(result.httpStatus(), result.message()));
    return false;
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
