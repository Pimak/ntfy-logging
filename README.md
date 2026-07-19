# ntfy-logging

[![CI](https://github.com/Pimak/ntfy-logging/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Pimak/ntfy-logging/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fpimak%2Fntfy-core%2Fmaven-metadata.xml&label=Maven%20Central&logo=apachemaven)](https://central.sonatype.com/artifact/io.github.pimak/ntfy-core)
[![Javadoc: ntfy-core](https://javadoc.io/badge2/io.github.pimak/ntfy-core/ntfy--core.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-core)
[![Javadoc: ntfy-logback](https://javadoc.io/badge2/io.github.pimak/ntfy-logback/ntfy--logback.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-logback)
[![Javadoc: ntfy-spring-boot-starter](https://javadoc.io/badge2/io.github.pimak/ntfy-spring-boot-starter/ntfy--spring--boot--starter.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-spring-boot-starter)
[![Javadoc: ntfy-quarkus-runtime](https://javadoc.io/badge2/io.github.pimak/ntfy-quarkus-runtime/ntfy--quarkus--runtime.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-quarkus-runtime)

**ntfy notifications for the JVM: a framework-neutral engine with zero-code adapters for
Logback and Spring Boot, a native-ready Quarkus extension, and a plain programmatic client.**

Publish your application's ERROR-level log events as [ntfy](https://ntfy.sh) push notifications.
The engine is:

- **storm-resistant** — rate-limited with a count-never-lost suppressed-count digest, so a burst
  of errors collapses into one follow-up notification instead of flooding your topic;
- **loop-safe** — it never logs its own diagnostics back through the logging pipeline it publishes
  from, so a failure inside the notifier can never trigger itself;
- **early** — the Logback and Quarkus adapters install through the logging framework itself, so
  alerting is live before your Spring `ApplicationContext` (or any DI container) exists — exactly
  the startup window where early fatal errors are otherwise invisible;
- **GraalVM-native ready** — the Quarkus extension builds its HTTP client at runtime-init, and
  `ntfy-core` ships native-image metadata for hand-rolled native builds.

> Formerly published as the single-artifact `io.github.pimak:logback-ntfy` (0.1.x, now retired).
> As of **1.0.0** it is a multi-module family under `io.github.pimak`; see the table below.

## Modules

| Artifact | Guide | Purpose | Use when |
|---|---|---|---|
| **`ntfy-spring-boot-starter`** | [Spring Boot](docs/spring-boot.md) | Spring Boot auto-configuration binding `ntfy.*`, plus an injectable `NtfyClient` bean. | You use Spring Boot and want alerting configured from `application.yml` and a `NtfyClient` to `@Autowired`. |
| **`ntfy-quarkus-runtime`** | [Quarkus](docs/quarkus.md) | Quarkus 3.15 extension: a JUL log handler bound to `quarkus.ntfy.*`, native-ready, plus an injectable `NtfyClient`. | You use Quarkus (JVM or native) and want `quarkus.ntfy.*` config and `@Inject NtfyClient`. |
| **`ntfy-logback`** | [Plain Logback](docs/logback.md) | Logback appender + zero-code auto-install via a Logback `Configurator` SPI. | You use Logback (with or without Spring) and want ERROR logs to alert — via XML, or with no config at all. |
| **`ntfy-core`** | [Core](docs/core.md) | Framework-neutral ntfy engine + `NtfyClient`; no logging-framework dependency. | You want to send ntfy notifications programmatically from any JVM app, or you're building your own adapter. |

Each module pulls `ntfy-core` transitively — you only ever declare the one that matches your stack.
**Pick your stack's guide above** for install, the Maven Central link, and the base config; the
shared reference pages (configuration, authentication, alert behavior, …) are linked from each guide
and listed under [Documentation](#documentation).

## Install & configure

Each library has its own guide with the dependency snippet, the Maven Central link, and the base
config to get error alerts publishing. Pick the one that matches your stack:

| Guide | Artifact | The 30-second version |
|---|---|---|
| **[Spring Boot](docs/spring-boot.md)** | `ntfy-spring-boot-starter` | Add the starter, set `ntfy.url`/`ntfy.topic` in `application.yml` — error logs auto-publish; `@Autowired NtfyClient` for manual ones. |
| **[Quarkus](docs/quarkus.md)** | `ntfy-quarkus-runtime` | Add the extension, set `quarkus.ntfy.url`/`.topic` — error logs auto-publish (native-ready); `@Inject NtfyClient` for manual ones. |
| **[Plain Logback](docs/logback.md)** | `ntfy-logback` | Add the appender, set `NTFY_URL`/`NTFY_TOPIC` — zero-code auto-install, or wire it explicitly in `logback.xml`. |
| **[Core](docs/core.md)** | `ntfy-core` | Build a `NtfyConfig`, drive `NtfyClient` yourself from any JVM app. |

Everything beyond the base config — the full key reference, authentication, alert behavior,
filtering, troubleshooting — is shared across all four and lives in the reference pages under
[Documentation](#documentation), linked from each guide.

## Filtering: exclude-list, not allowlist

You can exclude specific loggers (`excluded-loggers`, prefix-matched) from ever alerting, but there
is deliberately no "only alert for these loggers" allowlist. For an alerting tool an allowlist has
the wrong failure mode: a novel error from a logger nobody thought to list would be silently
dropped — exactly the event you most need to see. The exclude-list is fail-safe instead: everything
alerts by default except noise you've explicitly declared expected. To scope alerting to a subtree,
attach the appender to that specific logger rather than to `root`. See
[docs/filtering.md](docs/filtering.md), including the Logback-only `NO_ALERT` marker and the
always-on self-exclusion of the library's own package.

## Compatibility

Java 21+ · Logback 1.5.x · Spring Boot 3.4.x · Quarkus 3.15.x · GraalVM native (via the Quarkus
extension). See [docs/compatibility.md](docs/compatibility.md) for the full matrix.

## Documentation

**Per-library guides** — start here; each has install, the Maven Central link, and the base config:

| Guide | For |
|------|--------|
| [docs/spring-boot.md](docs/spring-boot.md) | Spring Boot (`ntfy-spring-boot-starter`) |
| [docs/quarkus.md](docs/quarkus.md) | Quarkus (`ntfy-quarkus-runtime`) |
| [docs/logback.md](docs/logback.md) | Plain Logback (`ntfy-logback`) |
| [docs/core.md](docs/core.md) | Any JVM app, programmatic (`ntfy-core`) |

**Reference pages** — shared across every library, linked from each guide:

| Page | Covers |
|------|--------|
| [docs/configuration.md](docs/configuration.md) | The unified `ntfy.*` key reference (types, defaults) and the per-framework mapping (env/sysprop/properties, Spring `ntfy.*`, Quarkus `quarkus.ntfy.*`), plus `DurationParser` syntax |
| [docs/alert-behavior.md](docs/alert-behavior.md) | Why alerting behaves the way it does: immediate single-error alerts, storm suppression, digest-on-window-close, digest-on-shutdown |
| [docs/authentication.md](docs/authentication.md) | The three auth modes (`BearerToken`, `BasicAuth`, `None`) and the "token wins" precedence rule |
| [docs/filtering.md](docs/filtering.md) | `excluded-loggers`, the Logback `NO_ALERT` marker, and the always-on self-exclusion |
| [docs/troubleshooting.md](docs/troubleshooting.md) | Diagnostic messages the engine emits, where each framework surfaces them, and what to do |
| [docs/compatibility.md](docs/compatibility.md) | Tested JDK/Logback/Spring/Quarkus/GraalVM versions and the ntfy server API surface |

## License

[Apache-2.0](LICENSE)
