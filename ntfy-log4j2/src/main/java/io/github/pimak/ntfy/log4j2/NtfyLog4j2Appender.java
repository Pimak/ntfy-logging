package io.github.pimak.ntfy.log4j2;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Core;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginBuilderFactory;

import io.github.pimak.ntfy.core.AlertEngine;
import io.github.pimak.ntfy.core.Diagnostics;
import io.github.pimak.ntfy.core.DurationParser;
import io.github.pimak.ntfy.core.NtfyConfig;
import io.github.pimak.ntfy.core.PipelineCounters;

/**
 * A thin log4j2 {@link AbstractAppender} shell over the framework-neutral {@link AlertEngine},
 * exposed to {@code log4j2.xml} as the {@code <Ntfy .../>} element. Configuration arrives either
 * through the plugin {@link Builder} (declarative XML/JSON/YAML config) or as a pre-built {@link
 * NtfyConfig} injected by {@link NtfyLog4j2Installer} for the programmatic one-liner install.
 *
 * <p>Every decision that matters — logger exclusion (including this library's own package), the
 * {@code NO_ALERT} marker gate, storm rate-limiting, digesting, payload assembly, and HTTP
 * publishing — lives in the engine; this class only maps a log4j2 {@code LogEvent} onto an {@code
 * AlertEvent} and calls {@link AlertEngine#submit}.
 *
 * <p>Self-diagnoses exclusively through log4j2's {@code StatusLogger} (via {@link
 * Log4j2Diagnostics}), so no feedback loop through the application's own logging pipeline is
 * possible and no credential ever appears in diagnostic output.
 *
 * <p>Minimal {@code log4j2.xml}:
 *
 * <pre>{@code
 * <Configuration>
 *   <Appenders>
 *     <Ntfy name="ntfy" url="https://ntfy.sh" topic="my-alerts"/>
 *   </Appenders>
 *   <Loggers>
 *     <Root level="info">
 *       <AppenderRef ref="ntfy"/>
 *     </Root>
 *   </Loggers>
 * </Configuration>
 * }</pre>
 */
// printObject is deliberately left at its default (false): a printed plugin would render every
// configured attribute into log4j2's configuration dump, and `token`/`password` are among them.
// This codebase never lets a credential reach a status channel, so the element stays unprintable.
@Plugin(name = "Ntfy", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE)
public final class NtfyLog4j2Appender extends AbstractAppender {

  /**
   * Marker name a caller attaches to a single log call to opt it out of alerting entirely
   * ({@code logger.error(MarkerManager.getMarker(NtfyLog4j2Appender.NO_ALERT_MARKER_NAME), ...)}).
   * Re-exposed from {@link AlertEngine#NO_ALERT_MARKER_NAME} so callers keep a stable handle
   * instead of a magic string.
   */
  public static final String NO_ALERT_MARKER_NAME = AlertEngine.NO_ALERT_MARKER_NAME;

  // StatusLogger-backed sink for the engine's self-diagnostics. One per appender; cheap and
  // stateless, and never routed back through the application logging pipeline.
  private final Diagnostics diagnostics = new Log4j2Diagnostics();

  // Read-only pipeline observability counters. Owned by the APPENDER (which outlives individual
  // engines) and handed to every engine built in start(), so the tallies survive stop()/start()
  // cycles and the reconfiguration handled by NtfyLog4j2ReattachListener — i.e. they are monotonic
  // for this appender instance's whole lifetime.
  private final PipelineCounters counters = new PipelineCounters();

  // Exactly one of these two is non-null. `settings` holds the raw plugin attributes (parsed in
  // start(), not in build(), so a malformed value is reported through the same channel and at the
  // same moment as the engine's own activation diagnostics); `injectedConfig` is the installer's
  // already-resolved, already-vetted ambient config.
  private final Builder settings;
  private final NtfyConfig injectedConfig;

  // volatile: start()/stop() run on the configuration thread while append() reads this on every
  // application logging thread.
  private volatile AlertEngine engine;

  // The reconfiguration hook registered by NtfyLog4j2Installer, parked here so uninstall() can
  // remove it again — a LoggerContext exposes no way to enumerate its PropertyChangeListeners.
  private NtfyLog4j2ReattachListener reattachListener;

  private NtfyLog4j2Appender(
      String name,
      Filter filter,
      boolean ignoreExceptions,
      Builder settings,
      NtfyConfig injectedConfig) {
    // No layout: the notification body is assembled by ntfy-core from the framework-neutral
    // AlertEvent, so a log4j2 Layout would have nothing to format and must not be required.
    super(name, filter, null, ignoreExceptions, Property.EMPTY_ARRAY);
    this.settings = settings;
    this.injectedConfig = injectedConfig;
  }

  /**
   * Construction path for {@link NtfyLog4j2Installer}: an appender whose configuration is already
   * resolved from the ambient environment rather than from plugin attributes. The log4j2 analogue
   * of the Logback appender's {@code setConfig(NtfyConfig)}.
   *
   * @param name the appender name it will be registered under
   * @param config the fully resolved, already vetted configuration
   * @return a not-yet-started appender
   */
  static NtfyLog4j2Appender forConfig(String name, NtfyConfig config) {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(config, "config");
    // ignoreExceptions=true: a programmatic install must never turn an alerting hiccup into an
    // AppenderLoggingException on the application's logging thread.
    return new NtfyLog4j2Appender(name, null, true, null, config);
  }

  /**
   * The plugin builder log4j2 instantiates for an {@code <Ntfy .../>} element.
   *
   * @return a fresh builder
   */
  @PluginBuilderFactory
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * Builds an {@link AlertEngine} from the injected config (if present) or from the plugin
   * attributes, starts it, and marks this appender started ONLY if the engine actually activated. A
   * silently/partially unconfigured engine leaves the appender {@code isStarted() == false} (the
   * engine already reported why on the StatusLogger), which is what keeps a misconfigured {@code
   * <Ntfy>} element from pretending to alert.
   */
  @Override
  public void start() {
    setStarting();
    NtfyConfig config = injectedConfig != null ? injectedConfig : settings.toConfig(diagnostics);
    AlertEngine started = new AlertEngine(config, diagnostics, counters);
    started.start();
    if (started.isStarted()) {
      this.engine = started;
      // super.start() (AbstractFilterable) starts any attached Filter and flips the state to
      // STARTED; calling it only on the success path is what makes isStarted() mirror the engine.
      super.start();
    } else {
      this.engine = null;
      setStopped();
    }
  }

  /**
   * Gates on ERROR, maps the event eagerly, and hands it to the engine, which owns all further
   * gating and publishing. The level gate lives here (mirroring the Logback appender's ERROR gate
   * and the Quarkus adapter's SEVERE gate) so the documented "ERROR-level events alert" contract
   * holds even when the element is referenced from a {@code <Root level="info">} — without it, a
   * root reference would push every INFO/WARN line verbatim to the ntfy topic.
   *
   * @param event the event log4j2 is dispatching to this appender
   */
  @Override
  public void append(LogEvent event) {
    AlertEngine current = engine;
    if (current == null) {
      return;
    }
    Level level = event.getLevel();
    if (level == null || !level.isMoreSpecificThan(Level.ERROR)) {
      return;
    }
    try {
      current.submit(Log4j2EventMapper.map(event));
    } catch (RuntimeException e) {
      // An appender must never propagate into the logging caller: a broken alert would otherwise
      // become an exception at an unrelated logger.error() call site. AbstractAppender.error routes
      // this to the StatusLogger through the configured ErrorHandler.
      error("ntfy: failed to submit alert for log event", e);
    }
  }

  /**
   * Tears the engine down — which flushes any pending digest and drains the async queue — and then
   * delegates to {@code super.stop} for the normal appender/filter shutdown. Null-safe when never
   * started and safe to call twice; {@code AlertEngine.stop()} is itself idempotent. log4j2 routes
   * the no-argument {@code stop()} here too, so this is the single teardown path.
   *
   * @param timeout the shutdown timeout log4j2 allows
   * @param timeUnit the unit of {@code timeout}
   * @return whether the appender stopped cleanly
   */
  @Override
  public boolean stop(long timeout, TimeUnit timeUnit) {
    AlertEngine current = engine;
    this.engine = null;
    if (current != null) {
      current.stop();
    }
    return super.stop(timeout, timeUnit);
  }

  /**
   * Read-only pipeline observability counters (published / suppressed / failed) for this appender.
   * The returned holder is stable for the appender's whole lifetime — the same instance before and
   * after {@code start()}/{@code stop()} cycles and across a log4j2 reconfiguration — so the
   * tallies are monotonic and never reset. Counters are pulled here, never logged, preserving the
   * loop-safe design.
   *
   * @return this appender's counters, never {@code null}
   */
  public PipelineCounters getCounters() {
    return counters;
  }

  /** Remembers the reconfiguration hook so {@code uninstall} can deregister exactly that instance. */
  void setReattachListener(NtfyLog4j2ReattachListener listener) {
    this.reattachListener = listener;
  }

  /** The reconfiguration hook registered for this appender, or {@code null} if there is none. */
  NtfyLog4j2ReattachListener reattachListener() {
    return reattachListener;
  }

  /**
   * Plugin builder for the {@code <Ntfy .../>} element. {@code name}, {@code filter} and {@code
   * ignoreExceptions} come from {@link AbstractAppender.Builder}; everything below is ntfy's own.
   *
   * <p><strong>Every attribute is typed {@code String} on purpose.</strong> log4j2 leaves an absent
   * attribute at its field default, so a primitive {@code int}/{@code boolean} field would arrive
   * as {@code 0}/{@code false} and be indistinguishable from an operator who really wrote {@code
   * maxStackFrames="0"} or {@code enabled="false"} — silently clobbering ntfy-core's defaults. A
   * {@code String} stays {@code null} when unset, so the corresponding {@code NtfyConfig.Builder}
   * setter is applied ONLY when the operator actually supplied a value. This is the same
   * "boxed, applied only when set" logic the Logback appender uses.
   *
   * <p>Malformed values never throw during logging initialization: each parse is guarded, warns on
   * the StatusLogger, and leaves the core default in place.
   */
  public static class Builder extends AbstractAppender.Builder<Builder>
      implements org.apache.logging.log4j.core.util.Builder<NtfyLog4j2Appender> {

    @PluginBuilderAttribute private String url;
    @PluginBuilderAttribute private String topic;

    // sensitive=true keeps the value out of log4j2's own plugin/attribute dumps, on top of this
    // module never printing the plugin (printObject=false) and ntfy-core never echoing credentials.
    @PluginBuilderAttribute(sensitive = true)
    private String token;

    @PluginBuilderAttribute(sensitive = true)
    private String username;

    @PluginBuilderAttribute(sensitive = true)
    private String password;

    @PluginBuilderAttribute private String title;
    @PluginBuilderAttribute private String appName;
    @PluginBuilderAttribute private String maxStackFrames;
    @PluginBuilderAttribute private String connectTimeout;
    @PluginBuilderAttribute private String requestTimeout;
    @PluginBuilderAttribute private String maxAlertsPerWindow;
    @PluginBuilderAttribute private String suppressionWindow;
    @PluginBuilderAttribute private String errorPriority;
    @PluginBuilderAttribute private String digestPriority;
    @PluginBuilderAttribute private String errorTags;
    @PluginBuilderAttribute private String digestTags;
    @PluginBuilderAttribute private String clickUrl;
    @PluginBuilderAttribute private String actions;
    @PluginBuilderAttribute private String excludedLoggers;
    @PluginBuilderAttribute private String locale;
    @PluginBuilderAttribute private String enabled;
    @PluginBuilderAttribute private String async;
    @PluginBuilderAttribute private String asyncQueueCapacity;
    @PluginBuilderAttribute private String requireHttpsForCredentials;

    /**
     * Base URL of the ntfy server, e.g. {@code https://ntfy.sh}.
     *
     * @param url the server base URL
     * @return this builder
     */
    public Builder setUrl(String url) {
      this.url = url;
      return asBuilder();
    }

    /**
     * Topic alerts are published to.
     *
     * @param topic the ntfy topic
     * @return this builder
     */
    public Builder setTopic(String topic) {
      this.topic = topic;
      return asBuilder();
    }

    /**
     * Bearer token authentication (takes precedence over username/password).
     *
     * @param token the ntfy access token
     * @return this builder
     */
    public Builder setToken(String token) {
      this.token = token;
      return asBuilder();
    }

    /**
     * Basic-authentication user name.
     *
     * @param username the user name
     * @return this builder
     */
    public Builder setUsername(String username) {
      this.username = username;
      return asBuilder();
    }

    /**
     * Basic-authentication password.
     *
     * @param password the password
     * @return this builder
     */
    public Builder setPassword(String password) {
      this.password = password;
      return asBuilder();
    }

    /**
     * Fixed notification title; overrides the derived {@code appName - RootCause} title.
     *
     * @param title the notification title
     * @return this builder
     */
    public Builder setTitle(String title) {
      this.title = title;
      return asBuilder();
    }

    /**
     * Application name used as the title prefix when no explicit title is set.
     *
     * @param appName the application name
     * @return this builder
     */
    public Builder setAppName(String appName) {
      this.appName = appName;
      return asBuilder();
    }

    /**
     * Maximum root-cause stack frames included in the notification body (integer).
     *
     * @param maxStackFrames the frame cap, as a decimal string
     * @return this builder
     */
    public Builder setMaxStackFrames(String maxStackFrames) {
      this.maxStackFrames = maxStackFrames;
      return asBuilder();
    }

    /**
     * HTTP connect timeout as a duration string (bare int = ms, {@code Nms/Ns/Nm/Nh/Nd}, or
     * ISO-8601).
     *
     * @param connectTimeout the connect timeout
     * @return this builder
     */
    public Builder setConnectTimeout(String connectTimeout) {
      this.connectTimeout = connectTimeout;
      return asBuilder();
    }

    /**
     * HTTP request timeout as a duration string (bare int = ms, {@code Nms/Ns/Nm/Nh/Nd}, or
     * ISO-8601).
     *
     * @param requestTimeout the request timeout
     * @return this builder
     */
    public Builder setRequestTimeout(String requestTimeout) {
      this.requestTimeout = requestTimeout;
      return asBuilder();
    }

    /**
     * Individual alerts allowed per suppression window before digesting kicks in (integer).
     *
     * @param maxAlertsPerWindow the per-window allowance, as a decimal string
     * @return this builder
     */
    public Builder setMaxAlertsPerWindow(String maxAlertsPerWindow) {
      this.maxAlertsPerWindow = maxAlertsPerWindow;
      return asBuilder();
    }

    /**
     * Length of the rate-limiting/digest window as a duration string (bare int = ms, {@code
     * Nms/Ns/Nm/Nh/Nd}, or ISO-8601).
     *
     * @param suppressionWindow the window length
     * @return this builder
     */
    public Builder setSuppressionWindow(String suppressionWindow) {
      this.suppressionWindow = suppressionWindow;
      return asBuilder();
    }

    /**
     * ntfy {@code Priority} header for individual error alerts.
     *
     * @param errorPriority the priority value
     * @return this builder
     */
    public Builder setErrorPriority(String errorPriority) {
      this.errorPriority = errorPriority;
      return asBuilder();
    }

    /**
     * ntfy {@code Priority} header for the aggregated digest.
     *
     * @param digestPriority the priority value
     * @return this builder
     */
    public Builder setDigestPriority(String digestPriority) {
      this.digestPriority = digestPriority;
      return asBuilder();
    }

    /**
     * ntfy {@code Tags} header for individual error alerts.
     *
     * @param errorTags the comma-separated tag list
     * @return this builder
     */
    public Builder setErrorTags(String errorTags) {
      this.errorTags = errorTags;
      return asBuilder();
    }

    /**
     * ntfy {@code Tags} header for the aggregated digest.
     *
     * @param digestTags the comma-separated tag list
     * @return this builder
     */
    public Builder setDigestTags(String digestTags) {
      this.digestTags = digestTags;
      return asBuilder();
    }

    /**
     * URL opened when a notification is tapped (ntfy {@code Click} header).
     *
     * @param clickUrl the click-through URL
     * @return this builder
     */
    public Builder setClickUrl(String clickUrl) {
      this.clickUrl = clickUrl;
      return asBuilder();
    }

    /**
     * Action buttons as a raw ntfy {@code Actions} header value (short format, e.g. {@code "view,
     * View logs, https://grafana.example.com/d/abc"}).
     *
     * @param actions the raw Actions header value
     * @return this builder
     */
    public Builder setActions(String actions) {
      this.actions = actions;
      return asBuilder();
    }

    /**
     * A single comma-separated value of logger-name prefixes to exclude from alerting.
     *
     * @param excludedLoggers the comma-separated prefix list
     * @return this builder
     */
    public Builder setExcludedLoggers(String excludedLoggers) {
      this.excludedLoggers = excludedLoggers;
      return asBuilder();
    }

    /**
     * Language of notification bodies and diagnostics as a BCP 47 tag (e.g. {@code fr}, {@code
     * de-DE}). Defaults to English; an unknown/unshipped locale silently uses English.
     *
     * @param locale the BCP 47 language tag
     * @return this builder
     */
    public Builder setLocale(String locale) {
      this.locale = locale;
      return asBuilder();
    }

    /**
     * Master switch; {@code "false"} keeps the appender inert even with a complete configuration.
     *
     * @param enabled {@code "true"} or {@code "false"}
     * @return this builder
     */
    public Builder setEnabled(String enabled) {
      this.enabled = enabled;
      return asBuilder();
    }

    /**
     * Opt into asynchronous (offloaded) delivery: individual error alerts are handed to the
     * engine's bounded work queue and published by a daemon worker, so a slow/unreachable ntfy
     * server never blocks the logging thread. Off by default (synchronous delivery).
     *
     * @param async {@code "true"} or {@code "false"}
     * @return this builder
     */
    public Builder setAsync(String async) {
      this.async = async;
      return asBuilder();
    }

    /**
     * Bounded async delivery queue capacity; only consulted when {@code async} is {@code "true"}.
     *
     * @param asyncQueueCapacity the capacity, as a decimal string
     * @return this builder
     */
    public Builder setAsyncQueueCapacity(String asyncQueueCapacity) {
      this.asyncQueueCapacity = asyncQueueCapacity;
      return asBuilder();
    }

    /**
     * Strict mode: refuse engine activation (rather than only warn) when credentials would be sent
     * over a cleartext {@code http://} endpoint. Off by default (warn-and-activate).
     *
     * @param requireHttpsForCredentials {@code "true"} or {@code "false"}
     * @return this builder
     */
    public Builder setRequireHttpsForCredentials(String requireHttpsForCredentials) {
      this.requireHttpsForCredentials = requireHttpsForCredentials;
      return asBuilder();
    }

    /**
     * Instantiates the appender. Returns {@code null} — log4j2's contract for "this element cannot
     * be built", which drops the appender with a status error instead of failing the whole
     * configuration — when no {@code name} was given.
     *
     * @return the configured, not-yet-started appender, or {@code null} when unbuildable
     */
    @Override
    public NtfyLog4j2Appender build() {
      String name = getName();
      if (name == null) {
        new Log4j2Diagnostics()
            .warn("ntfy: <Ntfy> element requires a name attribute — appender not created");
        return null;
      }
      return new NtfyLog4j2Appender(name, getFilter(), isIgnoreExceptions(), this, null);
    }

    /**
     * Folds the raw attributes into an {@link NtfyConfig}. Called from {@code start()}, not from
     * {@code build()}, so a parse warning lands on the StatusLogger next to the engine's own
     * activation diagnostics.
     */
    NtfyConfig toConfig(Diagnostics diagnostics) {
      // Plain string pass-throughs: ntfy-core treats null as "not set" for each of these, so they
      // can be applied unconditionally exactly as the Logback appender does.
      NtfyConfig.Builder builder =
          NtfyConfig.builder()
              .url(url)
              .topic(topic)
              .token(token)
              .username(username)
              .password(password)
              .title(title)
              .appName(appName)
              .excludedLoggers(excludedLoggers);

      // Setters whose core default must survive an absent attribute: apply only when set.
      applyString(errorPriority, builder::errorPriority);
      applyString(digestPriority, builder::digestPriority);
      applyString(errorTags, builder::errorTags);
      applyString(digestTags, builder::digestTags);
      applyString(clickUrl, builder::clickUrl);
      applyString(actions, builder::actionsHeader);
      applyString(locale, builder::locale);

      applyInt(maxStackFrames, "maxStackFrames", builder::maxStackFrames, diagnostics);
      applyInt(maxAlertsPerWindow, "maxAlertsPerWindow", builder::maxAlertsPerWindow, diagnostics);
      applyInt(asyncQueueCapacity, "asyncQueueCapacity", builder::asyncQueueCapacity, diagnostics);

      applyDuration(connectTimeout, "connectTimeout", builder::connectTimeout, diagnostics);
      applyDuration(requestTimeout, "requestTimeout", builder::requestTimeout, diagnostics);
      applyDuration(suppressionWindow, "suppressionWindow", builder::suppressionWindow, diagnostics);

      applyBoolean(enabled, "enabled", builder::enabled, diagnostics);
      applyBoolean(async, "async", builder::asyncEnabled, diagnostics);
      applyBoolean(
          requireHttpsForCredentials,
          "requireHttpsForCredentials",
          builder::requireHttpsForCredentials,
          diagnostics);

      return builder.build();
    }

    private static void applyString(String raw, Consumer<String> setter) {
      if (raw != null) {
        setter.accept(raw);
      }
    }

    /**
     * Applies an integer attribute, or warns and keeps the core default. Never throws: a typo in
     * {@code log4j2.xml} must degrade to "default value plus a status warning", not to a
     * {@code NumberFormatException} thrown during logging initialization.
     */
    private static void applyInt(
        String raw, String attribute, IntConsumer setter, Diagnostics diagnostics) {
      if (raw == null) {
        return;
      }
      try {
        setter.accept(Integer.parseInt(raw.trim()));
      } catch (NumberFormatException e) {
        diagnostics.warn(malformed(attribute, raw, "an integer"));
      }
    }

    /** Applies a duration attribute (see {@link DurationParser}), or warns and keeps the default. */
    private static void applyDuration(
        String raw, String attribute, Consumer<Duration> setter, Diagnostics diagnostics) {
      if (raw == null) {
        return;
      }
      try {
        setter.accept(DurationParser.parse(raw));
      } catch (IllegalArgumentException e) {
        diagnostics.warn(
            malformed(attribute, raw, "a duration (bare int = ms, Nms/Ns/Nm/Nh/Nd, or ISO-8601)"));
      }
    }

    /**
     * Applies a boolean attribute, or warns and keeps the default.
     *
     * <p>The literal check before {@link Boolean#parseBoolean} is the whole point: {@code
     * parseBoolean} maps EVERYTHING that is not {@code "true"} to {@code false}, so an
     * {@code enabled="yes"} typo would silently disable alerting with no diagnostic at all.
     */
    private static void applyBoolean(
        String raw, String attribute, Consumer<Boolean> setter, Diagnostics diagnostics) {
      if (raw == null) {
        return;
      }
      String trimmed = raw.trim();
      if (trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false")) {
        setter.accept(Boolean.parseBoolean(trimmed));
        return;
      }
      diagnostics.warn(malformed(attribute, raw, "'true' or 'false'"));
    }

    private static String malformed(String attribute, String raw, String expected) {
      return "ntfy: <Ntfy "
          + attribute
          + "=\""
          + raw
          + "\"> is not "
          + expected
          + " — ignoring the attribute and keeping the default";
    }
  }
}
