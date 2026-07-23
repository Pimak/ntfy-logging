# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]
### Security
- **Startup warning for a half-configured basic-auth pair.** When exactly one of
  `username`/`password` is set (and no `token` supersedes the pair), the engine used to silently
  fall back to `None` auth mode — every publish went out with no `Authorization` header while the
  operator believed auth was in effect (a common misconfig, e.g. a `${VAR}` that resolves empty).
  `start()` now emits a one-time, credential-safe warning: `username or password set but not both — basic auth is incomplete; publishing WITHOUT an Authorization header`. Activation still proceeds
  (same policy as the token-plus-basic overlap warning), and `AuthMode` derivation is unchanged.

## [1.1.0] - 2026-07-22
### Added
- **Opt-in asynchronous delivery mode.** A new `async` flag (default `false`) offloads individual
  error-alert publishing to a bounded work queue drained by a single daemon worker thread, so a slow
  or unreachable ntfy server no longer back-pressures your application/logging threads during an
  error storm — the first-class alternative to hand-wrapping the appender in an `AsyncAppender`.
  When the queue is full (capacity is tunable via `async-queue-capacity`, default `1024`), an alert
  is dropped but folded into the storm digest's suppressed count rather than lost silently, and
  shutdown drains any queued-but-unsent alerts into that same count. The flag is available on every
  surface: Logback `setAsync`/`setAsyncQueueCapacity` (XML `<async>`/`<asyncQueueCapacity>`), Spring
  `ntfy.async`/`ntfy.async-queue-capacity`, Quarkus `quarkus.ntfy.async`/
  `quarkus.ntfy.async-queue-capacity`, `NtfyConfig.Builder.asyncEnabled(...)`/`asyncQueueCapacity(...)`
  for programmatic core users, and `ConfigLoader` (`NTFY_ASYNC` / `NTFY_ASYNC_QUEUE_CAPACITY`).
  Default behavior is unchanged: with `async` off, delivery is synchronous and identical to before.
- **Pipeline observability counters** for the alert pipeline: the engine now tracks read-only,
  monotonic `published`, `suppressed`, and `failed` tallies so you can "alert on the alerter" (e.g.
  a spike in `failed` signalling a revoked token or a topic ACL change). Read them programmatically
  via `AlertEngine.counters()` (`ntfy-core`) or `LogbackAlertAppender.getCounters()`
  (`ntfy-logback`); the Logback appender keeps them monotonic across `stop()`/`start()` cycles and
  context resets. Counters are pulled, never logged, so reading them cannot re-enter the logging
  pipeline. The `suppressed` and `failed` counters are disjoint (a failed publish is never also
  counted suppressed). See [docs/observability.md](docs/observability.md).
- **Micrometer metrics in the Spring Boot starter**: when `micrometer-core` is on the classpath and
  a `MeterRegistry` bean is present, the starter exports `ntfy.pipeline.published`,
  `ntfy.pipeline.suppressed`, and `ntfy.pipeline.failed` as monotonic `FunctionCounter`s. The
  binding is classpath-conditional — consumers without Micrometer get no new dependency and
  `ntfy-core` stays dependency-free.

### Changed
- `NtfyClient` now implements `AutoCloseable`, so it can be used in a try-with-resources block and
  IDEs/linters can flag a leaked client. The existing `close()` method is unchanged; this is a
  source- and binary-compatible addition and makes the try-with-resources example in `docs/core.md`
  compile. The Javadoc notes that `AutoCloseable` does not mandate closing, so a long-lived,
  container-managed instance (e.g. a Spring/Quarkus bean) may be kept open for the application's
  lifetime without an IDE resource-leak warning being a real defect.

### Fixed
- Hardened the Logback appender's shutdown thread-leak regression coverage so it also watches for a
  leaked `ntfy-alert-delivery` async worker after `stop()`, keeping the no-leak-on-shutdown contract
  enforced for the async delivery path as well as the synchronous one.

## [1.0.2] - 2026-07-22
### Added
- **Regression test coverage** for `ntfy-core` config resolution: unit tests now pin the
  `ConfigLoader` precedence chain, `DurationParser` parsing rules, and `AuthMode` header
  construction. Behavior is unchanged.

### Fixed
- **Invalid HTTP timeouts no longer break engine startup or silently kill alerting.** A zero,
  negative, or unset `connectTimeout`/`requestTimeout` (e.g. from `NTFY_CONNECT_TIMEOUT=0` or a
  framework setter passing `null`) previously threw out of engine startup — leaking the
  `ntfy-alert-http` thread pool — or made every publish fail silently. The engine now validates both
  timeouts before acquiring any resource, falls back to the built-in defaults (5s connect, 10s
  request), and emits a one-time startup warning instead.
- **Endpoint URL validation at startup**: the engine now refuses to activate when the configured
  `url` is not a structurally valid http(s) endpoint (no scheme like `ntfy.sh`, a non-http(s)
  scheme like `ftp://…`, unparseable syntax, whitespace-padded, or missing a host/authority),
  emitting one specific startup diagnostic instead of failing every publish with an opaque generic
  error. Valid forms — reverse-proxy paths, non-standard ports, trailing slashes, `user:pass@host`
  basic-auth URLs, underscore hostnames, and IPv6 literals — continue to activate as before.

## [1.0.1] - 2026-07-21
### Added
- **Ergonomic factories** for `ntfy-core` types: `AlertEvent.of(loggerName, formattedMessage,
  timestampMillis)` builds the common "no throwable, no markers" event, and new
  `NtfyAction.http(...)` / `NtfyAction.broadcast(...)` overloads take an explicit `clear` flag
  (previously reachable only via the raw canonical constructor). The `AlertEvent` compact
  constructor now validates its mandatory fields and normalizes optional collections to empty for
  every construction path. The existing constructors and factories are unchanged, so these
  additions are backward-compatible.
- **Full ntfy action-button coverage** in `ntfy-core`: the typed `NtfyAction` model now supports all
  three ntfy action types — added `NtfyAction.broadcast(...)` (Android broadcast intents, with typed
  `BroadcastExtra` extras) and typed `HttpHeader`s on `http` actions (e.g. `Authorization`),
  alongside the existing `view` and `http` types.

### Changed
- The `NtfyAction.Http` record gained a trailing `List<HttpHeader> headers` component. Code using the
  `NtfyAction.http(...)` static factories is unaffected; direct `new NtfyAction.Http(...)` callers
  must add the new argument.

### Security
- **Logback adapter now gates at ERROR** (mirroring the Quarkus adapter's `SEVERE` gate): the
  appender submits only ERROR-and-above events, so a root-logger install can no longer publish
  INFO/WARN log content off-host.
- **Topic validation**: the publisher and engine reject topics outside ntfy's
  `[-_A-Za-z0-9]{1,64}` rule, closing a request-path injection through a `/`, `?`, `#`, or
  `..`-bearing topic value.
- **`Priority`/`Tags` header sanitization**: non-printable-ASCII configured values are omitted
  (with a startup warning) instead of forwarded, so CRLF safety no longer depends solely on the
  JDK client and one bad value can no longer abort every publish.
- **Cleartext-credentials warning**: configuring a token/password with an `http://` URL now warns
  loudly at startup.
- **Classpath-activation warning**: the Logback zero-code auto-install warns (naming the
  destination host) when the endpoint URL comes only from a classpath `ntfy.properties`, since any
  jar can ship one.
- **Bounded suppression tally**: the per-logger suppression map is capped at 100 distinct loggers
  (overflow folds into `(other loggers)`), so a long ntfy outage plus dynamically-named loggers
  can no longer grow it without bound.
- **Cycle-guarded JUL cause-chain walk** (Quarkus adapter): a circular exception cause chain can
  no longer loop the logging thread until OOM.
- **Credential-redaction fix**: the `ACTIVE` diagnostic's userinfo stripping now handles an
  unencoded `@` inside the password without leaking a password fragment.
- **Race-proof lifecycle**: `AlertEngine.start()`/`stop()` are now synchronized, so concurrent
  starts can no longer orphan live thread pools and duplicate digest timers.
- **Context-stop leak fix** (Logback): `LoggerContext.stop()` fires a reset before `onStop`, which
  resurrected the auto-installed appender mid-stop and leaked its engine threads and `HttpClient`
  forever; the reattach listener now tears the appender down on `onStop`.
- **No duplicate digest at shutdown**: `stop()` now shuts the digest scheduler down gracefully
  before force-cancelling, so an in-flight digest publish is no longer interrupted mid-send —
  which manufactured a spurious failure for a request the server had already accepted and made
  the stop-flush re-send the same digest.
- **Hardened config parsing**: malformed numeric/duration values from env/sysprops fall back to
  defaults instead of throwing during logging-framework initialization.
- **Supply-chain hardening**: GitHub Actions pinned to commit SHAs, `permissions: contents: read`
  on CI, release job resolves dependencies cold (no shared cache), Maven wrapper distribution
  pinned by SHA-256, Maven 3.9.9 release download pinned by inline SHA-512, strict Maven checksum
  policy, Dependabot enabled, and the Quarkus (3.15.7) / Spring Boot (3.4.13) BOMs moved to their
  latest security patch levels. CodeQL (Java + workflow files) and a PR dependency-review SCA
  gate run in CI, and the Central publish job is gated behind a reviewer-protected `release`
  environment.

## [1.0.0] - 2026-07-19
### Changed
- **Project split into a multi-module reactor and renamed** from `logback-ntfy` to the
  `ntfy-logging` family. The single `io.github.pimak:logback-ntfy` artifact is superseded by
  five focused, independently-versioned artifacts under `io.github.pimak` at `1.0.0`. The old
  `logback-ntfy` 0.1.x line is retired.

### Added
- **`ntfy-core`** — a framework-neutral, zero-dependency ntfy engine and client. The storm
  suppression, loop-safety, digest, truncation and auth logic that used to live in the Logback
  appender now lives here as `AlertEngine` + `NtfyClient`, driven by an immutable `NtfyConfig`
  and, when no framework config is present, a `ConfigLoader` that resolves settings from JVM
  system properties (`-Dntfy.*`), environment variables (`NTFY_*`), and a classpath
  `ntfy.properties`, in that precedence.
- **`ntfy-logback`** — the Logback appender (`LogbackAlertAppender`) plus **zero-code
  auto-install**: a `ch.qos.logback.classic.spi.Configurator` SPI that attaches an `ntfy-auto`
  appender to the root logger when `ntfy.url`/`ntfy.topic` are present, with a reset-resistant
  re-attach listener so it survives a Logback context reset. Classic XML `<appender>` wiring
  still works.
- **`ntfy-spring-boot-starter`** — Spring Boot auto-configuration that binds `ntfy.*` from the
  Spring `Environment` (relaxed binding, native `Duration` support), idempotently installs the
  `ntfy-auto` Logback appender, and exposes an injectable `NtfyClient` bean.
- **`ntfy-quarkus`** — a Quarkus 3.15 extension (runtime + deployment) that installs the alert
  handler via a `LogHandlerBuildItem` recorded at `RUNTIME_INIT`, binds `quarkus.ntfy.*` via
  `@ConfigMapping`, exposes an injectable `NtfyClient`, and is **GraalVM native-image ready**.
- **GraalVM native metadata** for `ntfy-core` (URL-protocol and `ntfy.properties` resource
  registration) for hand-rolled native builds of the core/Logback path, and a Quarkus
  `integration-tests` module that smoke-tests the extension end-to-end in both JVM and native.
- **Flexible duration syntax** everywhere via `DurationParser`: a bare integer is milliseconds
  (`500`), a suffixed integer uses its unit (`5s`, `3m`, `2h`, `1d`), and ISO-8601 (`PT5S`) is
  accepted too.

## [0.1.1] - 2026-07-18
### Added
- Initial release: a zero-dependency, storm-resistant, loop-safe Logback appender
  (`NtfyAlertAppender`) that publishes `ERROR`-level log events as [ntfy](https://ntfy.sh)
  push notifications.
- Rate-limited alert storms with a count-never-lost suppressed-count digest, so a burst of
  errors collapses into a single follow-up notification instead of flooding the ntfy topic.
- Three authentication modes (`BearerToken`, `BasicAuth`, `None`) with a "token wins"
  precedence rule when both are configured.
- Logger exclusion filtering (`excludedLoggers`, prefix-matched) plus a built-in `NO_ALERT`
  marker and an always-on self-exclusion to prevent feedback loops.
- Structured, truncated (4096-byte) payloads carrying the root-cause exception, and
  `StatusManager`-only diagnostics so the appender itself never re-enters the logging
  pipeline it publishes from.

[Unreleased]: https://github.com/Pimak/ntfy-logging/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/Pimak/ntfy-logging/compare/v0.1.1...v1.0.0
[0.1.1]: https://github.com/Pimak/ntfy-logging/releases/tag/v0.1.1
