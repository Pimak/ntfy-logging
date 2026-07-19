package io.github.pimak.ntfy.core;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Framework-pure alert engine: the extracted heart of the original Logback {@code
 * NtfyAlertAppender}, decoupled from any logging framework. It is configured once via an immutable
 * {@link NtfyConfig}, self-diagnoses exclusively through an injected {@link Diagnostics} sink (never
 * an SLF4J logger, so no feedback loop is possible and no credential ever appears in diagnostic
 * output), and is resource-clean across repeated {@link #start()}/{@link #stop()} cycles.
 *
 * <p>Adapters convert a framework event into an {@link AlertEvent} and call {@link #submit}; the
 * engine owns exclusion/marker gating, storm rate-limiting, payload assembly, HTTP publishing, and
 * the periodic storm digest.
 *
 * <p>Delivery is synchronous by design: {@code HttpClient.send()} blocks the calling (logging)
 * thread for up to connectTimeout + requestTimeout per event. The executor only services the
 * client's internal async tasks — it does NOT offload delivery. Non-blocking delivery is the
 * consumer/adapter's concern (e.g. an {@code AsyncAppender} wrapper).
 */
public final class AlertEngine {

  private static final Duration DEFAULT_SUPPRESSION_WINDOW = Duration.ofMillis(180_000L);

  /**
   * This library's own package root is always excluded from alerting, independent of any
   * configured exclusion list — a belt-and-suspenders anti-loop guard that survives even a
   * blank/misconfigured exclude-list. A single root now covers every ntfy-logging module.
   */
  private static final String SELF_PACKAGE_PREFIX = "io.github.pimak.ntfy";

  /**
   * Marker name an adapter surfaces in {@link AlertEvent#markerNames()} to opt a single event out
   * of alerting entirely. Public so callers have a stable handle instead of a magic string.
   */
  public static final String NO_ALERT_MARKER_NAME = "NO_ALERT";

  private final NtfyConfig config;
  private final Diagnostics diagnostics;

  // volatile: start()/stop() run on a config thread while submit() runs on application threads.
  private volatile boolean started = false;

  private ExecutorService executor;
  private HttpClient httpClient;

  // volatile: submit() runs on application threads while start()/stop() mutate this field on the
  // config thread. The volatile write in start() (last assignment) publishes url/topic/httpClient
  // too; submit() snapshots it into a local before use.
  private volatile NtfyPublisher publisher;

  // volatile: same publication pattern as `publisher` — built once in start() from the configured
  // token/username/password, read by submit()/emitDigest() (application threads), and nulled in
  // stop() alongside publisher.
  private volatile AuthMode authMode;

  // volatile: same submit()-vs-stop() concurrency model as `publisher` — a digest-timer tick or a
  // submit() call may race a concurrent stop() nulling this field.
  private volatile AlertRateLimiter rateLimiter;

  private ScheduledExecutorService digestScheduler;

  public AlertEngine(NtfyConfig config, Diagnostics diagnostics) {
    this.config = config;
    this.diagnostics = diagnostics;
  }

  /** True once {@link #start()} has activated the engine, until {@link #stop()} tears it down. */
  public boolean isStarted() {
    return started;
  }

  /**
   * url+topic both blank (or engine disabled) -&gt; silently inactive ({@code info}, {@code
   * isStarted()==false}). Exactly one of url/topic blank -&gt; loud {@code warn}, still inactive
   * (partial config, likely a typo). Both token and username+password configured -&gt; one-time
   * {@code warn}, but activation still proceeds (auth is optional and never blocks activation).
   * Already started -&gt; no-op, so a second {@code start()} without an intervening {@code stop()}
   * can never overwrite and orphan the first executor/HttpClient. Active: build one {@link
   * HttpClient} plus its daemon-thread executor and construct the {@link NtfyPublisher}.
   */
  public void start() {
    if (isStarted()) {
      return;
    }
    boolean urlSet = !isBlank(config.getUrl());
    boolean topicSet = !isBlank(config.getTopic());
    if (!config.isActive()) {
      // Fold the partial-config diagnostic into start(): exactly one endpoint half present is a
      // likely typo and warrants a loud warn; otherwise the engine is simply unconfigured/disabled.
      if (urlSet != topicSet) {
        diagnostics.warn(AlertMessages.STATUS_DISABLED_PARTIAL_CONFIG);
      } else {
        diagnostics.info(AlertMessages.STATUS_DISABLED_UNCONFIGURED);
      }
      return;
    }

    boolean hasToken = !isBlank(config.getToken());
    boolean hasBasic = !isBlank(config.getUsername()) && !isBlank(config.getPassword());
    if (hasToken && hasBasic) {
      diagnostics.warn(AlertMessages.STATUS_TOKEN_AND_BASIC_BOTH_SET);
    }
    this.authMode =
        AuthMode.fromCredentials(config.getToken(), config.getUsername(), config.getPassword());

    // Validate schedule parameters BEFORE acquiring any resource. A zero/negative (or null) window
    // would otherwise throw IllegalArgumentException out of scheduleWithFixedDelay AFTER the
    // executor/HttpClient were built but BEFORE the engine is marked started, leaving a
    // half-initialized engine holding live threads. Deliberate choice: fall back to the default
    // window (loudly) rather than refuse activation — a bad window value should not silence
    // alerting entirely.
    long windowMillis =
        config.getSuppressionWindow() == null ? 0L : config.getSuppressionWindow().toMillis();
    if (windowMillis <= 0) {
      windowMillis = DEFAULT_SUPPRESSION_WINDOW.toMillis();
      diagnostics.warn(AlertMessages.STATUS_INVALID_SUPPRESSION_WINDOW);
    }

    this.executor =
        Executors.newFixedThreadPool(
            2,
            r -> {
              Thread t = new Thread(r, "ntfy-alert-http");
              t.setDaemon(true);
              return t;
            });
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(config.getConnectTimeout())
            .executor(executor)
            .build();
    this.publisher = new NtfyPublisher(httpClient, config.getRequestTimeout());

    this.rateLimiter = new AlertRateLimiter(config.getMaxAlertsPerWindow(), windowMillis);
    this.digestScheduler =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "ntfy-alert-digest");
              t.setDaemon(true);
              return t;
            });
    // scheduleWithFixedDelay (not scheduleAtFixedRate): a slow publish never causes ticks to
    // stack up back-to-back once the digest publish itself is slow. The body is guarded here:
    // scheduleWithFixedDelay permanently cancels the periodic task if any execution
    // throws — an unguarded RuntimeException would silently kill the digest timer forever.
    // Fixed generic message, never e.getMessage() — the exception could embed a credential.
    digestScheduler.scheduleWithFixedDelay(
        () -> {
          try {
            emitDigest();
          } catch (RuntimeException e) {
            diagnostics.error(AlertMessages.PUBLISH_UNEXPECTED_ERROR, e);
          }
        },
        windowMillis,
        windowMillis,
        TimeUnit.MILLISECONDS);

    diagnostics.info(AlertMessages.statusActive(config.getUrl(), config.getTopic())); // never token
    diagnostics.info(
        AlertMessages.statusExclusions(config.getExcludedLoggerPrefixes())); // exactly once
    this.started = true;
  }

  /**
   * Releases every resource {@link #start()} acquired. Safe to call when never started (no NPE)
   * and safe to call twice in a row — every field is guarded and nulled out.
   */
  public void stop() {
    // Ordering matters here: (1) cancel the digest timer FIRST so no new tick can race the
    // flush below; (2) synchronously flush any pending digest while the HTTP resources are
    // STILL LIVE; (3) only then release executor/httpClient/publisher/rateLimiter. Flushing
    // after releasing the HTTP executor would leave the flush publish with no transport.
    if (digestScheduler != null) {
      digestScheduler.shutdownNow();
      awaitTerminationQuietly(digestScheduler);
    }
    digestScheduler = null;

    AlertRateLimiter rl = rateLimiter;
    if (rl != null && rl.hasPending()) {
      emitDigest();
    }

    if (executor != null) {
      executor.shutdownNow();
      // shutdownNow() only requests interruption; without awaiting termination the worker
      // threads (named "ntfy-alert-http") can still be observably alive for a short window
      // after stop() returns, which a global thread-leak scan (e.g. across repeated
      // start()/stop() cycles) can catch as a false leak. Bounded so a stuck thread
      // never hangs stop() itself.
      awaitTerminationQuietly(executor);
    }
    executor = null;
    if (httpClient != null) {
      // Java 21+: releases the client's internal HttpClient-N-SelectorManager thread
      // deterministically without blocking on in-flight requests — GC-driven reclaim is not
      // enough for repeated start/stop cycles.
      httpClient.shutdownNow();
    }
    httpClient = null;
    publisher = null;
    authMode = null;
    rateLimiter = null;
    this.started = false;
  }

  /**
   * Assembles the alert payload, delegates to {@link NtfyPublisher#publish}, and reports any
   * failure exclusively through the injected {@link Diagnostics} — never an SLF4J logger, never the
   * token. Exclusion and marker gates run BEFORE the publisher snapshot: gated events never publish
   * and are never counted. A concurrent {@code stop()} that nulls the volatile fields simply causes
   * a benign no-op return rather than an NPE misreported as an unexpected failure.
   */
  public void submit(AlertEvent event) {
    if (isExcluded(event.loggerName()) || hasNoAlertMarker(event)) {
      return;
    }
    // Snapshot the volatile fields once: a concurrent stop() nulling `publisher`/`rateLimiter`
    // between a null-check and use would otherwise NPE and be misreported as an ERROR-level
    // "unexpected" failure during a benign shutdown race.
    NtfyPublisher p = publisher;
    if (p == null) {
      return;
    }
    AlertRateLimiter rl = rateLimiter;
    if (rl == null) {
      return;
    }
    AuthMode auth = authMode;
    if (auth == null) {
      return;
    }
    // The rate-limiter gate runs AFTER the exclusion/marker gates above, so it only
    // ever sees non-excluded events. Over-allowance events are counted, never individually
    // published — they surface later as part of the aggregated digest.
    if (!rl.tryAcquire()) {
      rl.recordSuppressed(event.loggerName());
      return;
    }
    try {
      AlertPayload payload = buildPayload(event);
      PublishResult result =
          p.publish(
              config.getUrl(),
              config.getTopic(),
              payload.title(),
              auth,
              payload.body(),
              config.getErrorPriority(),
              config.getErrorTags());
      if (!result.success()) {
        diagnostics.warn(AlertMessages.publishFailed(config.getTopic(), result.httpStatus()));
        // A failed individual publish folds into the suppression count instead of being
        // lost — it will surface in the next digest.
        rl.recordSuppressed(event.loggerName());
      }
    } catch (RuntimeException e) {
      // Fixed generic message — never e.getMessage() here, it could embed a credential.
      diagnostics.error(AlertMessages.PUBLISH_UNEXPECTED_ERROR, e);
    }
  }

  /**
   * Assembles the {@link AlertPayload} for {@code event}. The title suffix and the body's stack
   * frames are always drawn from the ROOT cause (the last element of {@link
   * AlertEvent#causeChain()}); {@code maxStackFrames} caps the number of root-cause frames before
   * the body is passed through {@link PayloadTruncator}.
   */
  private AlertPayload buildPayload(AlertEvent event) {
    AlertEvent.Cause rootCause =
        event.causeChain().isEmpty()
            ? null
            : event.causeChain().get(event.causeChain().size() - 1);
    String title = buildTitle(rootCause);
    String body = buildBody(event, rootCause);
    return new AlertPayload(
        title, PayloadTruncator.truncate(body, PayloadTruncator.NTFY_MAX_BYTES));
  }

  private String buildTitle(AlertEvent.Cause rootCause) {
    String base = !isBlank(config.getTitle()) ? config.getTitle() : config.getAppName();
    if (base == null) {
      base = "";
    }
    if (rootCause == null) {
      return base;
    }
    // ASCII " - " (U+002D) separator, never a non-ASCII dash: the JDK HttpClient rejects HTTP
    // header values containing chars > 0xFF, so a non-ASCII separator would abort every
    // exception-alert publish at the header-build boundary.
    return base + " - " + rootCause.className();
  }

  /**
   * Body order: log message, logger name, full cause chain (one "Caused by" line per link, surface
   * to root), up to {@code maxStackFrames} entries of the ROOT cause's frames, then the event
   * timestamp. All labels come from {@link AlertMessages}.
   */
  private String buildBody(AlertEvent event, AlertEvent.Cause rootCause) {
    StringBuilder sb = new StringBuilder();
    sb.append(AlertMessages.LABEL_MESSAGE).append(event.formattedMessage()).append('\n');
    sb.append(AlertMessages.LABEL_LOGGER).append(event.loggerName()).append('\n');

    if (!event.causeChain().isEmpty()) {
      for (AlertEvent.Cause cause : event.causeChain()) {
        sb.append(AlertMessages.LABEL_CAUSE)
            .append(cause.className())
            .append(": ")
            .append(cause.message())
            .append('\n');
      }
      List<String> frames = rootCause == null ? List.of() : event.rootCauseFrames();
      int frameCount = frames.size();
      int limit = Math.min(Math.max(config.getMaxStackFrames(), 0), frameCount);
      for (int i = 0; i < limit; i++) {
        sb.append("  at ").append(frames.get(i)).append('\n');
      }
    }

    sb.append(AlertMessages.LABEL_TIMESTAMP).append(Instant.ofEpochMilli(event.timestampMillis()));
    return sb.toString();
  }

  /**
   * The single shared digest-publish code path, invoked both by the digest-scheduler tick and by
   * {@link #stop()}'s synchronous flush. Snapshots {@link #rateLimiter}/{@link #publisher}
   * defensively (a tick racing {@code stop()} must no-op). Drains the accumulated suppression
   * tally; a zero count means nothing to report, so no digest is sent. If the digest publish itself
   * fails, the drained count is re-folded back into {@link #rateLimiter} rather than silently
   * dropped, so it survives into the next window/flush.
   */
  private void emitDigest() {
    AlertRateLimiter rl = rateLimiter;
    NtfyPublisher p = publisher;
    AuthMode auth = authMode;
    if (rl == null || p == null || auth == null) {
      return;
    }
    AlertRateLimiter.DigestSnapshot snap = rl.drainAndReset();
    if (snap.count() == 0) {
      return;
    }
    String digestTitleText =
        AlertMessages.digestTitle(
            config.getTitle() != null ? config.getTitle() : config.getAppName(), snap.count());
    String body =
        AlertMessages.digestBody(
            snap.count(), snap.perLoggerTally(), describeWindow(config.getSuppressionWindow()));
    String truncatedBody = PayloadTruncator.truncate(body, PayloadTruncator.NTFY_MAX_BYTES);
    PublishResult r =
        p.publish(
            config.getUrl(),
            config.getTopic(),
            digestTitleText,
            auth,
            truncatedBody,
            config.getDigestPriority(),
            config.getDigestTags());
    if (!r.success()) {
      // Mirror the submit() path — a persistently failing digest (auth revoked, topic
      // ACL change, sustained 429) must be visible in the engine's own diagnostics.
      // Composer interpolates only topic + HTTP status, never a credential.
      diagnostics.warn(AlertMessages.publishFailed(config.getTopic(), r.httpStatus()));
      // Carry the lost count forward instead of dropping it — one atomic bulk
      // merge restores the global count from the snapshot's own count() (single source of truth)
      // and the per-logger breakdown, so the next window's digest stays accurate.
      rl.restore(snap);
    }
  }

  /** Human-readable window description for the digest body (e.g. {@code "3 minutes"}). */
  private static String describeWindow(Duration duration) {
    long ms = duration == null ? DEFAULT_SUPPRESSION_WINDOW.toMillis() : duration.toMillis();
    long minutes = ms / 60_000L;
    if (minutes >= 1) {
      return minutes + (minutes == 1 ? " minute" : " minutes");
    }
    long seconds = Math.max(ms / 1_000L, 1L);
    return seconds + (seconds == 1 ? " second" : " seconds");
  }

  /**
   * True when {@code loggerName} is the built-in self-package (always) or matches one of the
   * configured excluded prefixes at a logger-hierarchy boundary (exact match or {@code prefix +
   * "."} — a bare {@code startsWith} would wrongly match a sibling package like {@code
   * org.apache.kafkaconnect} against the prefix {@code org.apache.kafka}).
   */
  boolean isExcluded(String loggerName) {
    if (matchesPrefix(loggerName, SELF_PACKAGE_PREFIX)) {
      return true;
    }
    for (String prefix : config.getExcludedLoggerPrefixes()) {
      if (matchesPrefix(loggerName, prefix)) {
        return true;
      }
    }
    return false;
  }

  private static boolean matchesPrefix(String loggerName, String prefix) {
    return loggerName.equals(prefix) || loggerName.startsWith(prefix + ".");
  }

  /** True when {@code event} carries the {@link #NO_ALERT_MARKER_NAME} marker. */
  boolean hasNoAlertMarker(AlertEvent event) {
    return event.markerNames().contains(NO_ALERT_MARKER_NAME);
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  /**
   * {@code shutdownNow()} only requests interruption of worker threads — it does not wait for them
   * to actually exit. Bounding the wait to 500ms keeps {@code stop()} from ever hanging on a stuck
   * thread while still making teardown deterministic for the common case (interrupted daemon
   * threads normally exit in low single-digit milliseconds).
   */
  private static void awaitTerminationQuietly(ExecutorService service) {
    try {
      service.awaitTermination(500, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
