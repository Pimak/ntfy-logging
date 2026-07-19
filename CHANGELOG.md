# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - Unreleased
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
