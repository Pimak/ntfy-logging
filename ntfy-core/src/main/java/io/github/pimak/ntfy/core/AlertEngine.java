package io.github.pimak.ntfy.core;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
 * <p>Delivery is asynchronous by default (since 2.0): individual error publishes are handed to a
 * bounded work queue drained by a single daemon worker thread ({@code ntfy-alert-delivery}), so a
 * slow or unreachable ntfy server can never back-pressure application threads during an error
 * storm. When the queue is full an alert is dropped but folded into the storm digest's suppression
 * count (never lost silently), and {@link #stop()} drains any queued-but-unsent events into that
 * same count before the final synchronous flush. Disabling {@linkplain
 * NtfyConfig#isAsyncEnabled() async delivery} restores inline synchronous publishing: {@code
 * HttpClient.send()} then blocks the calling (logging) thread for up to connectTimeout +
 * requestTimeout per event — the guarantee short-lived processes may prefer, since a queued alert
 * that never reaches the worker before the JVM exits is not delivered.
 */
public final class AlertEngine {

  private static final Duration DEFAULT_SUPPRESSION_WINDOW = Duration.ofMillis(180_000L);

  // Kept in sync with the NtfyConfig.Builder defaults (connectTimeout = 5s, requestTimeout = 10s):
  // a null/invalid override degrades to the same value an untouched builder would have produced.
  private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

  /** Smallest bounded-queue capacity the async delivery worker accepts (see {@link #start()}). */
  private static final int MIN_ASYNC_QUEUE_CAPACITY = 1;

  /**
   * How long {@code stop()} waits for any single worker (executor threads, the startup self-test
   * thread) to actually exit before giving up on it. Bounded so shutdown can never hang on a stuck
   * HTTP round-trip, but long enough that a normally-terminating worker is gone before {@code
   * stop()} returns and a thread-leak scan sees a clean slate.
   */
  private static final long TERMINATION_AWAIT_MILLIS = 500L;

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

  // Read-only pipeline observability counters. Injected so an adapter can hand the SAME instance to
  // every engine it builds, keeping the tallies monotonic across start()/stop() cycles and
  // LoggerContext resets. The engine only ever increments; callers only read.
  private final PipelineCounters counters;

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
  // submit() call may race a concurrent stop() nulling this field. Non-null exactly while the
  // engine is started, so it doubles as the "engine is live" sentinel the old `rateLimiter` field
  // was.
  private volatile RouteState errorRoute;

  // volatile: same model as `errorRoute`. Non-null ONLY when a warn-topic is configured — a null
  // here is what makes a WARN event a no-op, so submit() needs no separate feature flag.
  private volatile RouteState warnRoute;

  private ScheduledExecutorService digestScheduler;

  // The one-shot startup self-test worker (async mode only), retained solely so stop() can interrupt
  // and briefly join it. volatile: written by start() on the config thread, read by stop() which may
  // run on a different one. Null whenever no self-test is in flight.
  private volatile Thread selfTestThread;

  // volatile: submit() reads it on application threads while start()/stop() mutate it on the config
  // thread — the exact publication model that forces publisher/authMode/the routes to be volatile.
  // A plain field risks a stale-null read (silent fallback to sync delivery) or a stale non-null
  // read of a torn-down executor after stop(). Non-null only when async delivery is enabled;
  // submit() snapshots it into a local before branching.
  private volatile ThreadPoolExecutor deliveryExecutor;

  // Throttle guard for the async overflow warning so a sustained outage cannot turn the rejection
  // handler into its own diagnostics storm: at most one warn per suppression window. The per-event
  // suppression accounting still happens on every rejection.
  private final AtomicLong lastAsyncOverflowWarnMillis = new AtomicLong(0L);

  // The locale-bound message catalog. Eagerly initialized to English so it is NEVER null on any
  // path (early start() diagnostics, and late async/stop-race calls after stop()), removing the need
  // for null guards. Reassigned to the configured locale as the FIRST statement of start(), before
  // the earliest diagnostic is emitted. volatile: start() writes it on the config thread while
  // submit()/deliver()/emitDigest() read it on application/worker threads — the same publication
  // model as publisher/the routes. It is immutable and stateless, so stop() deliberately does NOT
  // null it: a late async-path call after stop() must render a message rather than NPE.
  private volatile AlertMessages messages = AlertMessages.forLocale(Locale.ENGLISH);

  public AlertEngine(NtfyConfig config, Diagnostics diagnostics) {
    this(config, diagnostics, new PipelineCounters());
  }

  /**
   * Constructs an engine sharing an externally-owned {@link PipelineCounters}. Adapters that outlive
   * individual engines (e.g. the Logback appender across {@code start()}/{@code stop()} cycles and
   * context resets) inject one long-lived holder here so the observability tallies stay monotonic
   * across engine instances.
   */
  public AlertEngine(NtfyConfig config, Diagnostics diagnostics, PipelineCounters counters) {
    this.config = config;
    this.diagnostics = diagnostics;
    this.counters = counters;
  }

  /** True once {@link #start()} has activated the engine, until {@link #stop()} tears it down. */
  public boolean isStarted() {
    return started;
  }

  /**
   * True when this started engine actually activated a WARN route, i.e. WARN events submitted to
   * it will be published. Read this AFTER {@link #start()} rather than {@link
   * NtfyConfig#isWarnRoutingEnabled()}: {@code start()} may withdraw the route that config asked
   * for (an invalid warn topic, or one that reached the engine only through a classpath {@code
   * ntfy.properties}), and an adapter that gated on the request rather than the outcome would keep
   * submitting warnings the engine silently drops — and, on Log4j2, would attach its appender a
   * level too low. This is the single source of truth every adapter's WARN gate reads.
   */
  public boolean isWarnRoutingActive() {
    return warnRoute != null;
  }

  /**
   * Read-only pipeline observability counters (published / suppressed / failed). Callers only read;
   * the increment methods are package-private so nothing outside the engine can mutate the tallies.
   * The counters are pulled, never logged, and monotonic for this engine's lifetime — and, when an
   * external {@link PipelineCounters} was injected, across engine instances sharing that holder.
   */
  public PipelineCounters counters() {
    return counters;
  }

  /**
   * url+topic both blank (or engine disabled) -&gt; silently inactive ({@code info}, {@code
   * isStarted()==false}). Exactly one of url/topic blank -&gt; loud {@code warn}, still inactive
   * (partial config, likely a typo). Both token and username+password configured -&gt; one-time
   * {@code warn}, but activation still proceeds (auth is optional and never blocks activation).
   * Already started -&gt; no-op, so a second {@code start()} without an intervening {@code stop()}
   * can never overwrite and orphan the first executor/HttpClient. Active: build one {@link
   * HttpClient} plus its daemon-thread executor and construct the {@link NtfyPublisher}.
   *
   * <p>{@code synchronized} (like {@link #stop()}): two threads racing {@code start()} — e.g. a
   * Logback reset listener vs. a Spring installer — would otherwise both pass the started check
   * and both acquire an executor/HttpClient/digest scheduler, orphaning the loser's live threads
   * and leaving its digest timer ticking forever. Cold path, so the lock costs nothing.
   */
  public synchronized void start() {
    if (isStarted()) {
      return;
    }
    // Bind the message catalog to the configured locale BEFORE the earliest diagnostic below. Every
    // diagnostics.warn/info from here on renders through this instance.
    this.messages = AlertMessages.forLocale(config.getLocale());
    boolean urlSet = !isBlank(config.getUrl());
    boolean topicSet = !isBlank(config.getTopic());
    if (!config.isActive()) {
      // Fold the partial-config diagnostic into start(): exactly one endpoint half present is a
      // likely typo and warrants a loud warn; otherwise the engine is simply unconfigured/disabled.
      if (urlSet != topicSet) {
        diagnostics.warn(messages.statusDisabledPartialConfig());
      } else {
        diagnostics.info(messages.statusDisabledUnconfigured());
      }
      return;
    }

    // A valid http(s) scheme + authority is the most fundamental endpoint precondition. A URL with
    // no scheme ("ntfy.sh"), a non-http(s) scheme ("ftp://host"), unparseable syntax, or no
    // authority can never produce a successful publish — the URI failure is deliberately collapsed
    // into a generic no-leak message inside publish(), so the operator would otherwise see only
    // opaque repeated failures. Refuse activation loudly with a specific diagnostic instead.
    if (!NtfyPublisher.isValidEndpointUrl(config.getUrl())) {
      diagnostics.warn(messages.statusInvalidUrl());
      return;
    }

    // The topic is concatenated into the request path by the publisher; a value ntfy itself would
    // reject ('/', '?', '#', dot segments, over-long) can only ever fail — or worse, rewrite the
    // request target — so refuse activation loudly rather than fail every publish quietly.
    if (!NtfyPublisher.isValidTopic(config.getTopic())) {
      diagnostics.warn(messages.statusInvalidTopic());
      return;
    }

    boolean hasToken = !isBlank(config.getToken());
    boolean hasUsername = !isBlank(config.getUsername());
    boolean hasPassword = !isBlank(config.getPassword());
    boolean hasBasic = hasUsername && hasPassword;
    if (hasToken && hasBasic) {
      diagnostics.warn(messages.statusTokenAndBasicBothSet());
    }
    if (!hasToken && (hasUsername != hasPassword)) {
      // Exactly one half of the basic-auth pair is set and no token supersedes it:
      // AuthMode.fromCredentials silently ignores the incomplete pair and falls back to None, so
      // every publish would go out with NO Authorization header while the operator believes auth
      // is in effect. Warn loudly but still activate — same policy as the overlap warning above,
      // auth configuration is never a reason to block activation.
      diagnostics.warn(messages.statusIncompleteBasicAuth());
    }
    // Credentials over cleartext HTTP: a configured token/basic pair (sent as an Authorization
    // header) OR userinfo embedded in the URL itself (http://user:pass@host — a secret in the
    // request target even when no separate credential is configured).
    boolean credentialsOverPlainHttp =
        (hasToken || hasBasic || urlHasUserinfo(config.getUrl())) && isPlainHttp(config.getUrl());
    if (credentialsOverPlainHttp) {
      if (config.isRequireHttpsForCredentials()) {
        // Strict mode (the default since 2.0): refuse activation rather than transmit a secret
        // readable by any on-path observer. Mirrors the invalid-url/invalid-topic refusals — no
        // resource is acquired and `started` stays false.
        diagnostics.warn(messages.statusCredentialsOverPlainHttpRefused());
        return;
      }
      // Explicit opt-out: the Authorization header/userinfo is readable by any on-path observer.
      // Warn loudly but still activate — a self-hosted plain-HTTP setup is the operator's
      // deliberate (if risky) choice, and refusing would silence alerting.
      diagnostics.warn(messages.statusCredentialsOverPlainHttp());
    }
    // Whether the opt-in WARN route survives its own preconditions. Both failures below withdraw
    // ONLY the warn route and let activation continue: unlike `topic`, which IS the alerting, a
    // misconfigured optional extra must never cost the operator their error alerts.
    boolean warnRoutingEnabled = config.isWarnRoutingEnabled();
    if (warnRoutingEnabled && !NtfyPublisher.isValidTopic(config.getWarnTopic())) {
      diagnostics.warn(messages.statusInvalidWarnTopic());
      warnRoutingEnabled = false;
    }
    if (warnRoutingEnabled
        && config.isWarnTopicFromClasspathFile()
        && !config.isAllowClasspathEndpoint()) {
      // Same supply-chain reasoning as the endpoint guard the auto-installs apply: any jar on the
      // classpath can ship a ntfy.properties, and warn-topic both names a destination and widens
      // how much log content leaves the host. Withdraw the route rather than refuse the install —
      // the questionable extra goes, ERROR alerting stays.
      diagnostics.warn(messages.statusWarnTopicFromClasspathRefused());
      warnRoutingEnabled = false;
    }
    // Deliberately AFTER the two withdrawal guards above, so `warnRoutingEnabled` here means "the
    // warn route SURVIVED", not merely "a warn-topic was set". The warn values are documented as
    // consulted only while that route is live, and the publisher never sends them otherwise —
    // warning about a value that cannot reach a request would contradict that and make the
    // ERROR-only default's diagnostic stream depend on settings it ignores. Checking before the
    // guards left exactly one case doing that: an invalid or classpath-refused warn-topic, whose
    // route is withdrawn a few lines later.
    if (!isSendableHeaderValue(config.getErrorPriority())
        || !isSendableHeaderValue(config.getDigestPriority())
        || !isSendableHeaderValue(config.getErrorTags())
        || !isSendableHeaderValue(config.getDigestTags())
        || !isSendableHeaderValue(config.getClickUrl())
        || !isSendableHeaderValue(config.getActions())
        || (warnRoutingEnabled
            && (!isSendableHeaderValue(config.getWarnPriority())
                || !isSendableHeaderValue(config.getWarnTags())))) {
      // One-time, specific diagnostic: the publisher silently omits such headers, and without
      // this warning a mistyped value (e.g. a literal emoji instead of a shortcode) would be
      // invisible.
      diagnostics.warn(messages.statusInvalidPriorityOrTags());
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
      diagnostics.warn(messages.statusInvalidSuppressionWindow());
    }

    // Validate both HTTP timeouts BEFORE acquiring any resource, for the same reason as the
    // suppression window above. A zero/negative/null connectTimeout would throw
    // IllegalArgumentException/NullPointerException out of HttpClient.newBuilder().connectTimeout(..)
    // AFTER the executor was built but BEFORE the engine is marked started, leaking the daemon
    // thread pool into a half-initialized engine (callers call start() bare and never stop() it). A
    // bad requestTimeout would not leak threads but would silently fail every later publish. Policy
    // matches the window guard: fall back to the default (loudly) rather than refuse activation.
    Duration effectiveConnectTimeout = config.getConnectTimeout();
    if (effectiveConnectTimeout == null
        || effectiveConnectTimeout.isZero()
        || effectiveConnectTimeout.isNegative()) {
      effectiveConnectTimeout = DEFAULT_CONNECT_TIMEOUT;
      diagnostics.warn(messages.statusInvalidConnectTimeout());
    }
    Duration effectiveRequestTimeout = config.getRequestTimeout();
    if (effectiveRequestTimeout == null
        || effectiveRequestTimeout.isZero()
        || effectiveRequestTimeout.isNegative()) {
      effectiveRequestTimeout = DEFAULT_REQUEST_TIMEOUT;
      diagnostics.warn(messages.statusInvalidRequestTimeout());
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
            .connectTimeout(effectiveConnectTimeout)
            .executor(executor)
            .build();
    this.publisher = new NtfyPublisher(httpClient, effectiveRequestTimeout);

    // One limiter per route, each with the same allowance: a warning storm draws down its own
    // budget and can never push a genuine error into a digest. See RouteState.
    this.errorRoute =
        RouteState.forError(
            config, new AlertRateLimiter(config.getMaxAlertsPerWindow(), windowMillis));
    this.warnRoute =
        warnRoutingEnabled
            ? RouteState.forWarn(
                config, new AlertRateLimiter(config.getMaxAlertsPerWindow(), windowMillis))
            : null;
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
            // One tick drives both routes: they share the same suppression window, so a second
            // scheduler would buy nothing but another thread.
            emitDigest(errorRoute);
            emitDigest(warnRoute);
          } catch (RuntimeException e) {
            diagnostics.error(messages.publishUnexpectedError(), e);
          }
        },
        windowMillis,
        windowMillis,
        TimeUnit.MILLISECONDS);

    // Async delivery worker (the default since 2.0): a single daemon thread draining a bounded
    // queue, so a slow or unreachable ntfy server never back-pressures application threads. Built
    // AFTER publisher and the routes are assigned (the worker and the rejection handler read
    // them) and BEFORE started
    // is set. A ThreadPoolExecutor gives us two free primitives: shutdownNow() returns the list of
    // never-started queued tasks (what stop() must drain) and a RejectedExecutionHandler is the
    // natural overflow hook. GraalVM-native-safe: plain platform daemon threads, no reflection.
    if (config.isAsyncEnabled()) {
      int capacity = config.getAsyncQueueCapacity();
      if (capacity < MIN_ASYNC_QUEUE_CAPACITY) {
        capacity = MIN_ASYNC_QUEUE_CAPACITY;
        diagnostics.warn(messages.statusInvalidAsyncQueueCapacity());
      }
      long overflowWarnWindowMillis = windowMillis;
      this.deliveryExecutor =
          new ThreadPoolExecutor(
              1,
              1,
              0L,
              TimeUnit.MILLISECONDS,
              new ArrayBlockingQueue<>(capacity),
              r -> {
                Thread t = new Thread(r, "ntfy-alert-delivery");
                t.setDaemon(true);
                return t;
              },
              asyncOverflowHandler(overflowWarnWindowMillis));
    }

    diagnostics.info(messages.statusActive(config.getUrl(), config.getTopic())); // never token
    diagnostics.info(
        messages.statusExclusions(config.getExcludedLoggerPrefixes())); // exactly once
    // Conditional, unlike the exclusions line above (which always reports even the "none" case):
    // include-mdc-keys is opt-in, so a "no MDC keys configured" line on every boot would be pure
    // noise, and staying silent keeps the default path's diagnostic stream byte-identical to before
    // this feature existed. Key NAMES only — a VALUE is never diagnosed.
    if (!config.getIncludeMdcKeys().isEmpty()) {
      diagnostics.info(messages.statusIncludeMdcKeys(config.getIncludeMdcKeys()));
    }
    // Conditional for the same reason as the include-mdc-keys line: WARN routing is opt-in, and the
    // default path's diagnostic stream must stay byte-identical to before this feature existed.
    // Naming the destination is the point — an unexpected warn topic should be caught on sight.
    RouteState wr = this.warnRoute;
    if (wr != null) {
      diagnostics.info(messages.statusWarnRoute(wr.topic()));
    }

    runStartupSelfTest();
  }

  /**
   * Runs the opt-in startup self-test and marks the engine started.
   *
   * <p>Called as the LAST statement of {@link #start()}, which is what gives the self-test every
   * activation guard above it for free: an engine that is disabled, unconfigured, pointed at an
   * invalid URL or topic, or refused for sending credentials over cleartext has already returned,
   * so it can never issue a self-test request against a configuration the engine itself rejected.
   *
   * <p>Two execution shapes, because "never slow down startup" and "abort startup on failure" are
   * not simultaneously satisfiable — a start() that has already returned cannot be aborted:
   *
   * <ul>
   *   <li><b>default (fail-fast off)</b> — the engine is marked started FIRST and the round-trip
   *       runs on a one-shot daemon thread, so boot is never delayed and the diagnostic simply
   *       lands a moment later;
   *   <li><b>fail-fast on</b> — the round-trip runs inline (bounded by the already-validated
   *       connect + request timeouts) and, on failure, every resource {@code start()} acquired is
   *       released BEFORE {@link NtfyStartupSelfTestException} propagates. Throwing without that
   *       teardown would leave a half-initialized engine holding a live HTTP pool and a ticking
   *       digest timer — the very leak the resource-ordering guards above are written to prevent.
   * </ul>
   */
  private void runStartupSelfTest() {
    if (config.isStartupPingValueRejected()) {
      diagnostics.warn(messages.statusStartupPingInvalidMode());
    }
    if (config.isStartupPingWarnValueRejected()) {
      diagnostics.warn(messages.statusStartupPingWarnInvalidMode());
    }

    // Each route is tested only when its OWN mode asks for it, and the WARN route additionally only
    // when it survived the withdrawal guards above — probing a route the engine has already
    // retracted would report on something that will never carry an alert.
    List<Leg> legs = new ArrayList<>();
    if (config.getStartupPing() != StartupPingMode.OFF) {
      legs.add(new Leg(config.getStartupPing(), new StartupSelfTest.Target(config.getTopic(), false)));
    }
    RouteState warn = this.warnRoute;
    if (config.getStartupPingWarn() != StartupPingMode.OFF && warn != null) {
      legs.add(new Leg(config.getStartupPingWarn(), new StartupSelfTest.Target(warn.topic(), true)));
    }

    if (legs.isEmpty()) {
      // Warn about the always-a-mistake combination, then stay completely silent: with the feature
      // unset the diagnostic stream must be byte-identical to a build without it (same policy as
      // the conditional include-mdc-keys line above).
      if (config.isStartupPingFailFast()) {
        diagnostics.warn(messages.statusStartupPingFailFastWithoutMode());
      }
      this.started = true;
      return;
    }

    // Snapshot what the self-test needs. The async path must not re-read these fields later: a
    // concurrent stop() nulls them, which would turn a benign shutdown race into an NPE.
    NtfyPublisher p = this.publisher;
    AuthMode auth = this.authMode;
    AlertMessages msgs = this.messages;

    if (config.isStartupPingFailFast()) {
      if (!runLegs(legs, p, auth, msgs, true)) {
        String refusal = msgs.statusStartupPingFailFastRefused();
        diagnostics.warn(refusal);
        // Reentrant on the monitor already held by start() (both are synchronized on this), and
        // safe on a never-started engine: every field stop() touches is null-guarded.
        stop();
        throw new NtfyStartupSelfTestException(refusal);
      }
      this.started = true;
      return;
    }

    this.started = true;
    Thread selfTest =
        new Thread(() -> runLegs(legs, p, auth, msgs, false), "ntfy-alert-selftest");
    selfTest.setDaemon(true);
    this.selfTestThread = selfTest;
    selfTest.start();
  }

  /** One route under test and the mode its own flag selected for it. */
  private record Leg(StartupPingMode mode, StartupSelfTest.Target target) {}

  /**
   * Runs every enabled route in turn, reports each, and — when any failed and any other passed —
   * publishes the failure report through the passing one.
   *
   * <p>The notification is deliberately gated on a route having PASSED rather than merely being
   * configured: that is what makes it impossible to ping a topic this self-test has not just
   * verified, and it is why an operator who never enabled the WARN self-test can never receive an
   * unexpected publish on their ERROR topic.
   *
   * @param inline whether the caller is the fail-fast path (which reports unconditionally) rather
   *     than the background worker (which stays silent once the engine has been stopped underneath
   *     it, since the failure it would report is an artifact of that shutdown)
   * @return {@code true} when every enabled route passed
   */
  private boolean runLegs(
      List<Leg> legs, NtfyPublisher p, AuthMode auth, AlertMessages msgs, boolean inline) {
    List<String> failures = new ArrayList<>();
    StartupSelfTest.Target healthy = null;
    for (Leg leg : legs) {
      PublishResult result = StartupSelfTest.execute(leg.mode(), leg.target(), config, p, auth, msgs);
      if (!inline && !started) {
        return true;
      }
      if (StartupSelfTest.report(leg.mode(), leg.target(), result, msgs, diagnostics)) {
        if (healthy == null) {
          healthy = leg.target();
        }
      } else {
        failures.add(StartupSelfTest.line(leg.mode(), leg.target(), result, msgs));
      }
    }
    if (failures.isEmpty()) {
      return true;
    }
    if (config.isStartupPingNotifyFailures() && healthy != null) {
      PublishResult sent =
          StartupSelfTest.notifyFailure(
              healthy, String.join("\n\n", failures), config, p, auth, msgs);
      if (!sent.success()) {
        // Never swallowed: an operator who left failure notifications on needs to know one was owed
        // and never arrived, or they will read the silence as "nothing was wrong".
        diagnostics.warn(msgs.statusStartupPingNotifyFailed());
      }
    }
    return false;
  }

  /**
   * Releases every resource {@link #start()} acquired. Safe to call when never started (no NPE)
   * and safe to call twice in a row — every field is guarded and nulled out. {@code synchronized}
   * so it can never interleave with a concurrent {@link #start()} and tear down a half-built
   * engine.
   */
  public synchronized void stop() {
    // Clear `started` FIRST so an in-flight async self-test observes a stopping engine and reports
    // nothing: its HTTP exchange is about to be torn down underneath it, and the resulting failure
    // would be an artifact of this shutdown rather than a real configuration problem.
    this.started = false;

    // Then interrupt and briefly join the self-test worker, so it cannot still be running (and
    // observably alive to a thread-leak scan) after stop() returns. Bounded by the same 500ms the
    // executor awaits use, so a stuck round-trip delays shutdown but never hangs it. No deadlock
    // risk despite holding the monitor: the worker never acquires this engine's lock.
    Thread selfTest = selfTestThread;
    if (selfTest != null) {
      selfTest.interrupt();
      try {
        selfTest.join(TERMINATION_AWAIT_MILLIS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    selfTestThread = null;

    // Ordering matters here: (1) cancel the digest timer FIRST so no new tick can race the
    // flush below; (2) synchronously flush any pending digest while the HTTP resources are
    // STILL LIVE; (3) only then release executor/httpClient/publisher/the routes. Flushing
    // after releasing the HTTP executor would leave the flush publish with no transport.
    if (digestScheduler != null) {
      // Graceful shutdown() first, shutdownNow() second: an in-flight digest tick must be allowed
      // to finish its HTTP publish. Interrupting it mid-send manufactures a spurious failure for a
      // request the server may have already accepted — the tick then restores the drained count
      // and the flush below re-sends it, duplicating the digest. Termination stays bounded: the
      // await is 500ms, then shutdownNow() force-cancels anything genuinely stuck.
      digestScheduler.shutdown();
      awaitTerminationQuietly(digestScheduler);
      digestScheduler.shutdownNow();
    }
    digestScheduler = null;

    // Drain the async delivery worker BEFORE the digest flush, so queued-but-unsent alerts fold into
    // the suppression count rather than being dropped; HTTP resources are still live here. Graceful
    // shutdown() like the digest scheduler above, so the IN-FLIGHT send finishes: interrupting it
    // manufactures a failure that deliver() folds into the suppression count, making the flush below
    // publish a phantom "1 errors suppressed" digest. Drain either side of shutdown() — before,
    // because shutdown() runs whatever is still queued; after, to catch a submit() racing those two
    // lines (past shutdown(), rejections fold through asyncOverflowHandler instead).
    ThreadPoolExecutor de = deliveryExecutor;
    if (de != null) {
      List<Runnable> unsent = new ArrayList<>();
      de.getQueue().drainTo(unsent);
      de.shutdown();
      de.getQueue().drainTo(unsent);
      awaitTerminationQuietly(de);
      unsent.addAll(de.shutdownNow());
      awaitTerminationQuietly(de);
      for (Runnable r : unsent) {
        // Each task folds into the budget of the route it was headed for, so the two digests
        // flushed below each report their own losses.
        if (r instanceof DeliveryTask t && t.route() != null) {
          t.route().limiter().recordSuppressed(t.loggerName());
          // A queued-but-unsent event never published — count it `failed`, consistent with the
          // overflow handler below and with every other never-published path.
          counters.incrementFailed();
        }
      }
    }

    // Per route, so a shutdown mid-incident reports warnings and errors on their own topics
    // instead of merging them into one count.
    emitDigestIfPending(errorRoute);
    emitDigestIfPending(warnRoute);

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
    errorRoute = null;
    warnRoute = null;
    deliveryExecutor = null;
    this.started = false;
  }

  /** Flushes {@code route}'s digest when it has a pending suppression count; no-op otherwise. */
  private void emitDigestIfPending(RouteState route) {
    if (route != null && route.limiter().hasPending()) {
      emitDigest(route);
    }
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
    // Snapshot the volatile fields once: a concurrent stop() nulling `publisher`/the routes
    // between a null-check and use would otherwise NPE and be misreported as an ERROR-level
    // "unexpected" failure during a benign shutdown race.
    NtfyPublisher p = publisher;
    if (p == null) {
      return;
    }
    // Route selection. A WARN event arriving while `warnRoute` is null is dropped here — the
    // adapters already gate on the same condition, so this is the engine's own half of the
    // double floor: no configuration mistake, and no third-party adapter, can publish warnings to
    // a topic the operator never named.
    RouteState route = event.level() == AlertLevel.WARN ? warnRoute : errorRoute;
    if (route == null) {
      return;
    }
    AlertRateLimiter rl = route.limiter();
    AuthMode auth = authMode;
    if (auth == null) {
      return;
    }
    // The rate-limiter gate runs AFTER the exclusion/marker gates above, so it only
    // ever sees non-excluded events. Over-allowance events are counted, never individually
    // published — they surface later as part of the aggregated digest. The budget is this ROUTE's:
    // warnings and errors never draw from the same allowance.
    if (!rl.tryAcquire()) {
      rl.recordSuppressed(event.loggerName());
      // Observability tally: the rate-limiter gate is the ONLY site incrementing `suppressed`, so
      // it can never overlap with `failed` — the two counters stay disjoint.
      counters.incrementSuppressed();
      return;
    }
    // Rate-limit gating (above) runs on the submitting thread, so the async queue only ever holds
    // events that already won a publish slot — keeping the bounded queue small. Payload assembly
    // also stays here; the worker (when enabled) does only the blocking HTTP send.
    try {
      AlertPayload payload = buildPayload(event);
      ThreadPoolExecutor de = deliveryExecutor;
      if (de != null) {
        // Async: hand off to the daemon worker. A full queue routes to the overflow handler (drop
        // + fold into the suppression count), never blocking the submitting thread. The task
        // carries its route so a drop or a late drain folds into the right budget.
        de.execute(new DeliveryTask(payload, event.loggerName(), route));
      } else {
        // Sync (explicit opt-out): publish inline on the calling thread.
        deliver(payload, event.loggerName(), route);
      }
    } catch (RuntimeException e) {
      // Fixed generic message — never e.getMessage() here, it could embed a credential.
      diagnostics.error(messages.publishUnexpectedError(), e);
    }
  }

  /**
   * The single shared error-alert delivery path, invoked inline by {@link #submit} in sync mode and
   * from the {@code ntfy-alert-delivery} worker in async mode. Snapshots {@link #publisher}/{@link
   * #authMode} defensively (a worker task may run after a concurrent {@code
   * stop()} nulled them, and must no-op rather than NPE). A failed publish folds into the
   * suppression count so it surfaces in the next digest rather than being lost; an unexpected
   * exception is reported through diagnostics with a fixed, credential-safe message.
   */
  private void deliver(AlertPayload payload, String loggerName, RouteState route) {
    NtfyPublisher p = publisher;
    AuthMode auth = authMode;
    if (p == null || auth == null || route == null) {
      return;
    }
    AlertRateLimiter rl = route.limiter();
    try {
      PublishResult result =
          p.publish(
              config.getUrl(),
              route.topic(),
              payload.title(),
              auth,
              payload.body(),
              route.priority(),
              route.tags(),
              config.getClickUrl(),
              config.getActions());
      if (result.success()) {
        counters.incrementPublished();
      } else {
        counters.incrementFailed();
        diagnostics.warn(messages.publishFailed(route.topic(), result.httpStatus()));
        // A failed individual publish folds into the suppression count instead of being
        // lost — it will surface in the next digest. This digest-accounting fold is separate from
        // the observability counters: the event is counted `failed` (above), never `suppressed`.
        rl.recordSuppressed(loggerName);
      }
    } catch (RuntimeException e) {
      counters.incrementFailed();
      // Fixed generic message — never e.getMessage() here, it could embed a credential.
      diagnostics.error(messages.publishUnexpectedError(), e);
    }
  }

  /**
   * Carries a gated, payload-assembled error alert from {@link #submit} to the async delivery
   * worker. An inner (non-static) class so {@code run()} can call the enclosing engine's {@link
   * #deliver}; {@link #loggerName()} and {@link #route()} are read back by the overflow handler and
   * by {@link #stop()}'s drain so a dropped/unsent task still folds into the suppression count of
   * the route it was headed for.
   */
  private final class DeliveryTask implements Runnable {
    private final AlertPayload payload;
    private final String loggerName;
    private final RouteState route;

    DeliveryTask(AlertPayload payload, String loggerName, RouteState route) {
      this.payload = payload;
      this.loggerName = loggerName;
      this.route = route;
    }

    String loggerName() {
      return loggerName;
    }

    RouteState route() {
      return route;
    }

    @Override
    public void run() {
      deliver(payload, loggerName, route);
    }
  }

  /**
   * Builds the async worker's {@link RejectedExecutionHandler}. On a full queue it folds the dropped
   * event into the suppression count (so it still surfaces in the next digest — never a silent drop)
   * and emits a throttled overflow warning (at most once per {@code warnWindowMillis} so the handler
   * cannot become its own diagnostics storm). Two hardening cases: (1) the limiter is read off the
   * task's own captured route rather than an engine field, so a {@code submit()} racing a
   * concurrent {@code stop()} still folds its count into the right budget instead of finding a
   * nulled field; (2) a rejection caused by executor
   * shutdown is teardown, not overflow, so the warn is skipped (the count is still recorded, since a
   * shutdown-rejected task is not in {@code stop()}'s drained queue list).
   */
  private RejectedExecutionHandler asyncOverflowHandler(long warnWindowMillis) {
    return (r, executor) -> {
      if (r instanceof DeliveryTask t && t.route() != null) {
        t.route().limiter().recordSuppressed(t.loggerName());
        // A dropped event never published — count it `failed`, mirroring the stop()-drain path.
        counters.incrementFailed();
      }
      if (executor.isShutdown()) {
        return;
      }
      long now = System.currentTimeMillis();
      long last = lastAsyncOverflowWarnMillis.get();
      if (now - last >= warnWindowMillis
          && lastAsyncOverflowWarnMillis.compareAndSet(last, now)) {
        diagnostics.warn(messages.statusAsyncQueueOverflow());
      }
    };
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
   * Body order: log message, logger name, the allow-listed MDC context block (one {@code key: value}
   * line each, in configured order), full cause chain (one "Caused by" line per link, surface to
   * root), up to {@code maxStackFrames} entries of the ROOT cause's frames, then the event
   * timestamp. All labels come from {@link AlertMessages}; the MDC block deliberately has none — the
   * operator-chosen key name IS the label, so no bundle key can go stale against a config value.
   */
  private String buildBody(AlertEvent event, AlertEvent.Cause rootCause) {
    StringBuilder sb = new StringBuilder();
    sb.append(messages.labelMessage()).append(event.formattedMessage()).append('\n');
    sb.append(messages.labelLogger()).append(event.loggerName()).append('\n');

    // Context lines sit HIGH in the body on purpose. PayloadTruncator (applied in buildPayload)
    // keeps whole lines from the START and drops from the END, so under budget pressure the tail is
    // sacrificed in this order — the "Time:" line first, then stack frames bottom-up, then "Caused
    // by:" lines — while the context block survives. A correlation id beats the 5th stack frame for
    // diagnosing a production incident. MdcProjector's MAX_TOTAL_CHARS is what keeps that privilege
    // bounded: the block can claim at most ~1 KiB of the 4096-byte body budget. maxStackFrames is
    // unaffected either way; it is applied below, before truncation ever runs.
    for (Map.Entry<String, String> entry : event.mdcValues().entrySet()) {
      sb.append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
    }

    if (!event.causeChain().isEmpty()) {
      for (AlertEvent.Cause cause : event.causeChain()) {
        sb.append(messages.labelCause())
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

    sb.append(messages.labelTimestamp()).append(Instant.ofEpochMilli(event.timestampMillis()));
    return sb.toString();
  }

  /**
   * The single shared digest-publish code path, invoked both by the digest-scheduler tick and by
   * {@link #stop()}'s synchronous flush. Snapshots {@link #publisher}
   * defensively (a tick racing {@code stop()} must no-op). Drains the accumulated suppression
   * tally; a zero count means nothing to report, so no digest is sent. If the digest publish itself
   * fails, the drained count is re-folded back into the route's own limiter rather than silently
   * dropped, so it survives into the next window/flush.
   */
  private void emitDigest(RouteState route) {
    NtfyPublisher p = publisher;
    AuthMode auth = authMode;
    if (route == null || p == null || auth == null) {
      return;
    }
    AlertRateLimiter rl = route.limiter();
    AlertRateLimiter.DigestSnapshot snap = rl.drainAndReset();
    if (snap.count() == 0) {
      return;
    }
    String name = config.getTitle() != null ? config.getTitle() : config.getAppName();
    String digestTitleText = messages.digestTitle(name, snap.count(), route.level());
    String window =
        messages.describeWindow(config.getSuppressionWindow(), DEFAULT_SUPPRESSION_WINDOW);
    String body =
        messages.digestBody(snap.count(), snap.perLoggerTally(), window, route.level());
    String truncatedBody = PayloadTruncator.truncate(body, PayloadTruncator.NTFY_MAX_BYTES);
    PublishResult r =
        p.publish(
            config.getUrl(),
            route.topic(),
            digestTitleText,
            auth,
            truncatedBody,
            route.digestPriority(),
            route.digestTags(),
            config.getClickUrl(),
            config.getActions());
    if (r.success()) {
      // A digest notification accepted by ntfy counts as one published notification. This site also
      // covers the stop()-flush path, which routes through this same method.
      counters.incrementPublished();
      return;
    }
    counters.incrementFailed();
    // Mirror the submit() path — a persistently failing digest (auth revoked, topic
    // ACL change, sustained 429) must be visible in the engine's own diagnostics.
    // Composer interpolates only topic + HTTP status, never a credential.
    diagnostics.warn(messages.publishFailed(route.topic(), r.httpStatus()));
    // Carry the lost count forward instead of dropping it — one atomic bulk
    // merge restores the global count from the snapshot's own count() (single source of truth)
    // and the per-logger breakdown, so the next window's digest stays accurate. This affects only
    // the digest tally, never the observability counters, so the restored count is not re-counted
    // as `failed` on the next window.
    rl.restore(snap);
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

  /** True when {@code url} uses the cleartext {@code http://} scheme. */
  private static boolean isPlainHttp(String url) {
    return url != null && url.trim().regionMatches(true, 0, "http://", 0, "http://".length());
  }

  /**
   * True when the URL's authority component carries userinfo ({@code //user[:pass]@host...}) — an
   * {@code @} after the {@code //} and before the authority terminator (the first {@code /},
   * {@code ?}, or {@code #}). Bounding the authority at the query/fragment delimiters too means a
   * later {@code @} in a query param (e.g. {@code ?email=a@b.com}) is not misread as embedded
   * credentials. Any {@code @} genuinely inside the authority (including an unencoded {@code @} in
   * the password) still marks embedded credentials.
   */
  private static boolean urlHasUserinfo(String url) {
    if (url == null) {
      return false;
    }
    String trimmed = url.trim();
    int authorityStart = trimmed.indexOf("//");
    if (authorityStart < 0) {
      return false;
    }
    int authorityEnd = trimmed.length();
    for (int i = authorityStart + 2; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (c == '/' || c == '?' || c == '#') {
        authorityEnd = i;
        break;
      }
    }
    String authority = trimmed.substring(authorityStart + 2, authorityEnd);
    return authority.indexOf('@') >= 0;
  }

  /**
   * True when {@code value} is absent (no header sent) or printable ASCII (header sent verbatim by
   * the publisher) — i.e. anything except a value the publisher will silently omit.
   */
  private static boolean isSendableHeaderValue(String value) {
    return isBlank(value) || value.chars().allMatch(c -> c >= 0x20 && c <= 0x7E);
  }

  /**
   * {@code shutdownNow()} only requests interruption of worker threads — it does not wait for them
   * to actually exit. Bounding the wait to 500ms keeps {@code stop()} from ever hanging on a stuck
   * thread while still making teardown deterministic for the common case (interrupted daemon
   * threads normally exit in low single-digit milliseconds).
   */
  private static void awaitTerminationQuietly(ExecutorService service) {
    try {
      service.awaitTermination(TERMINATION_AWAIT_MILLIS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
