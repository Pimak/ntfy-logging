package io.github.pimak.ntfy.quarkus.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

/**
 * Run-time configuration surface for the ntfy Quarkus extension, mapped from the {@code
 * quarkus.ntfy.*} config namespace.
 *
 * <p>Declared as a {@link ConfigMapping} interface (the Quarkus 3.37 idiom — the legacy {@code
 * @ConfigItem} field style may not be mixed with mappings in the same module) and marked {@link
 * ConfigPhase#RUN_TIME} so every value — including the endpoint {@code url}/{@code topic} and the
 * credentials — is read when the application starts, never baked into the build. This is what keeps
 * the extension native-image-safe: no HTTP client or thread is created at build/static-init time.
 *
 * <p>Durations are surfaced as raw {@code String}s and parsed via {@code DurationParser} in the
 * recorder/producer, so the flexible ntfy spellings ({@code "5s"}, {@code "3m"}, {@code "500"},
 * {@code "PT5S"}) all work rather than being constrained to Quarkus' own duration converter.
 */
@ConfigMapping(prefix = "quarkus.ntfy")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface NtfyRuntimeConfig {

  /** Base URL of the ntfy server, e.g. {@code https://ntfy.sh}. Alerting is inactive when unset. */
  Optional<String> url();

  /** Topic to publish to. Alerting is inactive when unset. */
  Optional<String> topic();

  /** Bearer/access token (ntfy {@code tk_...}); takes precedence over username/password. */
  Optional<String> token();

  /** Basic-auth username (used with {@link #password()} when no {@link #token()} is set). */
  Optional<String> username();

  /** Basic-auth password. */
  Optional<String> password();

  /** Fixed notification title; falls back to {@link #appName()} when unset. */
  Optional<String> title();

  /** Application name, used as the title base when {@link #title()} is unset. */
  Optional<String> appName();

  /** Maximum root-cause stack frames rendered into an alert body. */
  @WithDefault("5")
  int maxStackFrames();

  /** HTTP connect timeout (ntfy duration spelling, e.g. {@code 5s}). */
  @WithDefault("5s")
  String connectTimeout();

  /** HTTP request timeout (ntfy duration spelling, e.g. {@code 10s}). */
  @WithDefault("10s")
  String requestTimeout();

  /** Maximum individually-published alerts per suppression window before digesting kicks in. */
  @WithDefault("3")
  int maxAlertsPerWindow();

  /** Storm-suppression window (ntfy duration spelling, e.g. {@code 3m}). */
  @WithDefault("3m")
  String suppressionWindow();

  /** ntfy priority header for individual error alerts. */
  @WithDefault("high")
  String errorPriority();

  /** ntfy priority header for the periodic storm digest. */
  @WithDefault("urgent")
  String digestPriority();

  /** ntfy tags header for individual error alerts. */
  @WithDefault("rotating_light")
  String errorTags();

  /** ntfy tags header for the periodic storm digest. */
  @WithDefault("fire")
  String digestTags();

  /**
   * The ntfy topic WARN-level events are published to. Unset by default, which keeps alerting
   * ERROR-only; a non-blank value is the entire opt-in for WARN alerting. Set it to the same value
   * as {@code quarkus.ntfy.topic} to alert on warnings through the main topic at a different
   * priority.
   */
  Optional<String> warnTopic();

  /** ntfy priority header for WARN alerts and the WARN digest. */
  @WithDefault("default")
  String warnPriority();

  /** ntfy tags header for WARN alerts and the WARN digest. */
  @WithDefault("warning")
  String warnTags();

  /** URL opened when a notification is tapped (ntfy {@code Click} header). Omitted when unset. */
  Optional<String> clickUrl();

  /**
   * Action buttons as a raw ntfy {@code Actions} header value (short format, e.g. {@code "view, View
   * logs, https://grafana.example.com/d/abc"}). Omitted when unset.
   */
  Optional<String> actions();

  /** Comma-separated logger-name prefixes excluded from alerting. */
  Optional<String> excludedLoggers();

  /**
   * Comma-separated fully qualified exception class names. An event whose cause chain contains any
   * of them never alerts — matched anywhere in the chain, not just on the surface throwable.
   */
  Optional<String> excludedExceptionTypes();

  /**
   * Comma-separated allow-list of MDC keys whose values are rendered into alert bodies, one {@code
   * key: value} line each, in the order given here.
   *
   * <p>Empty by default: unless an operator names a key, no MDC value ever leaves the application.
   * There is deliberately no wildcard form — an MDC routinely holds session tokens, user identifiers
   * and other data that must not be pushed to a notification server, so the only way to publish a
   * value is to name its key explicitly. Unknown keys and keys absent from a given record are simply
   * skipped.
   *
   * <p>Values are read from the JBoss LogManager MDC that Quarkus already funnels every log record
   * through, so the usual {@code org.jboss.logmanager.MDC} / {@code org.slf4j.MDC} writes are picked
   * up with no further wiring.
   */
  Optional<String> includeMdcKeys();

  /**
   * Language of notification bodies and diagnostics as a BCP 47 tag (e.g. {@code fr}, {@code
   * de-DE}). Defaults to English when unset; an unknown/unshipped locale silently uses English.
   */
  Optional<String> locale();

  /** Master switch; when {@code false} the handler is never installed. */
  @WithDefault("true")
  boolean enabled();

  /**
   * Asynchronous (offloaded) delivery: individual error alerts are handed to the engine's bounded
   * work queue and published by a daemon worker, so a slow/unreachable ntfy server never blocks
   * the logging thread. On by default; set {@code false} for pre-2.0 synchronous delivery.
   */
  @WithDefault("true")
  boolean async();

  /** Bounded async delivery queue capacity; only consulted when {@link #async()} is {@code true}. */
  @WithDefault("1024")
  int asyncQueueCapacity();

  /**
   * Strict mode: refuse engine activation (rather than only warn) when credentials would be sent
   * over a cleartext {@code http://} endpoint. On by default; set {@code false} for the pre-2.0
   * warn-and-activate behavior.
   */
  @WithDefault("true")
  boolean requireHttpsForCredentials();

  /**
   * Opt-in startup self-test: {@code off} (default), {@code probe} (read-only reachability check
   * that publishes nothing) or {@code publish} (a real low-priority test notification). Verifies at
   * boot that the configured endpoint, credentials and topic actually work, instead of finding out
   * when the first real error alert fails to deliver.
   *
   * <p>Typed {@code String} rather than the {@code StartupPingMode} enum deliberately: SmallRye
   * Config fails the whole application on an unconvertible enum value, so a single typo in
   * {@code application.properties} would take the service down. The core parses it leniently and
   * warns instead, which is also what the other five surfaces do.
   */
  @WithDefault("off")
  String startupPing();

  /**
   * Whether a failed startup self-test aborts application startup instead of only warning. Off by
   * default; enabling it also makes the self-test run inline, adding at most connect+request
   * timeout to boot. Intended for CI/staging rather than production.
   */
  @WithDefault("false")
  boolean startupPingFailFast();

  /**
   * Startup self-test for the optional WARN route: {@code off} (default), {@code probe} or {@code
   * publish}. Independent of {@link #startupPing()} — ntfy grants permissions per topic, so a
   * healthy ERROR route proves nothing about the WARN one.
   */
  @WithDefault("off")
  String startupPingWarn();

  /**
   * Whether a failed self-test is announced as a notification on a route that PASSED. On by
   * default; it can never fire unless some route passed, so it cannot publish to a topic the
   * self-test has not just verified.
   */
  @WithDefault("true")
  boolean startupPingNotifyFailures();
}
