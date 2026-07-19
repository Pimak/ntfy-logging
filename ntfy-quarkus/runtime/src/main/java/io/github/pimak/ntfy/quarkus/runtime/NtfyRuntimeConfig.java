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
 * <p>Declared as a {@link ConfigMapping} interface (the Quarkus 3.15 idiom — the legacy {@code
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

  /** URL opened when a notification is tapped (ntfy {@code Click} header). Omitted when unset. */
  Optional<String> clickUrl();

  /**
   * Action buttons as a raw ntfy {@code Actions} header value (short format, e.g. {@code "view, View
   * logs, https://grafana.example.com/d/abc"}). Omitted when unset.
   */
  Optional<String> actions();

  /** Comma-separated logger-name prefixes excluded from alerting. */
  Optional<String> excludedLoggers();

  /** Master switch; when {@code false} the handler is never installed. */
  @WithDefault("true")
  boolean enabled();
}
