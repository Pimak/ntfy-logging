# ntfy-logging

[![CI](https://github.com/Pimak/ntfy-logging/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Pimak/ntfy-logging/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fpimak%2Fntfy-core%2Fmaven-metadata.xml&label=Maven%20Central&logo=apachemaven)](https://central.sonatype.com/artifact/io.github.pimak/ntfy-core)
[![Javadoc: ntfy-core](https://javadoc.io/badge2/io.github.pimak/ntfy-core/ntfy--core.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-core)
[![Javadoc: ntfy-jul](https://javadoc.io/badge2/io.github.pimak/ntfy-jul/ntfy--jul.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-jul)
[![Javadoc: ntfy-logback](https://javadoc.io/badge2/io.github.pimak/ntfy-logback/ntfy--logback.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-logback)
[![Javadoc: ntfy-log4j2](https://javadoc.io/badge2/io.github.pimak/ntfy-log4j2/ntfy--log4j2.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-log4j2)
[![Javadoc: ntfy-spring-boot-starter](https://javadoc.io/badge2/io.github.pimak/ntfy-spring-boot-starter/ntfy--spring--boot--starter.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-spring-boot-starter)
[![Javadoc: ntfy-quarkus-runtime](https://javadoc.io/badge2/io.github.pimak/ntfy-quarkus-runtime/ntfy--quarkus--runtime.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-quarkus-runtime)
[![Javadoc: ntfy-micronaut](https://javadoc.io/badge2/io.github.pimak/ntfy-micronaut/ntfy--micronaut.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-micronaut)

**ntfy notifications for the JVM: a framework-neutral engine with zero-code adapters for
`java.util.logging`, Logback, Spring Boot and Micronaut, a one-line Log4j2 appender, a native-ready
Quarkus extension, and a plain programmatic client.**

Publish your application's ERROR-level log events as [ntfy](https://ntfy.sh) push notifications.
The engine is:

- **storm-resistant** — rate-limited with a count-never-lost suppressed-count digest, so a burst
  of errors collapses into one follow-up notification instead of flooding your topic;
- **loop-safe** — it never logs its own diagnostics back through the logging pipeline it publishes
  from, so a failure inside the notifier can never trigger itself;
- **non-blocking by default** — delivery is offloaded to a bounded queue drained by a daemon worker
  (the first-class alternative to hand-wrapping an `AsyncAppender`), so a slow or unreachable ntfy
  server never back-pressures your application threads during an error storm (`async=false` restores
  synchronous delivery);
- **early** — the JUL, Logback, Log4j2 and Quarkus adapters install through the logging framework
  itself, so alerting is live before your Spring `ApplicationContext` (or any DI container) exists —
  exactly the startup window where early fatal errors are otherwise invisible;
- **GraalVM-native ready** — the Quarkus extension builds its HTTP client at runtime-init, and
  `ntfy-core` ships native-image metadata for hand-rolled native builds.

> Formerly published as the single-artifact `io.github.pimak:logback-ntfy` (0.1.x, now retired).
> As of **1.0.0** it is a multi-module family under `io.github.pimak`; see the table below.

## Modules

| Artifact | Guide | Purpose | Use when |
|---|---|---|---|
| **`ntfy-spring-boot-starter`** | [Spring Boot](docs/spring-boot.md) | Spring Boot auto-configuration binding `ntfy.*`, plus an injectable `NtfyClient` bean. | You use Spring Boot and want alerting configured from `application.yml` and a `NtfyClient` to `@Autowired`. |
| **`ntfy-quarkus-runtime`** | [Quarkus](docs/quarkus.md) | Quarkus 3.38 extension: a JUL log handler bound to `quarkus.ntfy.*`, native-ready, plus an injectable `NtfyClient`. | You use Quarkus (JVM or native) and want `quarkus.ntfy.*` config and `@Inject NtfyClient`. |
| **`ntfy-micronaut`** | [Micronaut](docs/micronaut.md) | Micronaut 4.10 integration binding `ntfy.*`, installing the appender on the root Logback logger at startup, plus an injectable `NtfyClient`. Logback only. | You use Micronaut on its default Logback stack and want alerting configured from `application.yml` and an `@Inject NtfyClient`. |
| **`ntfy-logback`** | [Plain Logback](docs/logback.md) | Logback appender + zero-code auto-install via a Logback `Configurator` SPI. | You use Logback (with or without Spring) and want ERROR logs to alert — via XML, or with no config at all. |
| **`ntfy-log4j2`** | [Plain Log4j2](docs/log4j2.md) | Log4j2 `<Ntfy>` appender plugin + a one-line `NtfyLog4j2Installer.install()`; Log4j2 at `provided` scope. | You use Log4j2 and want ERROR logs to alert — declared in `log4j2.xml`, or installed with one line in `main`. |
| **`ntfy-jul`** | [java.util.logging](docs/jul.md) | JUL `Handler` + zero-code auto-handler for `logging.properties` and a one-line programmatic installer; pure JDK. Also the handler the Quarkus extension installs. | You log through `java.util.logging` (plain JUL, Tomcat, Helidon, zero-dependency tools) and want SEVERE logs to alert. |
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
| **[Micronaut](docs/micronaut.md)** | `ntfy-micronaut` | Add the module, set `ntfy.url`/`ntfy.topic` in `application.yml` — error logs auto-publish on the Logback root logger; `@Inject NtfyClient` for manual ones. |
| **[Plain Logback](docs/logback.md)** | `ntfy-logback` | Add the appender, set `NTFY_URL`/`NTFY_TOPIC` — zero-code auto-install, or wire it explicitly in `logback.xml`. |
| **[Plain Log4j2](docs/log4j2.md)** | `ntfy-log4j2` | Add the appender, set `NTFY_URL`/`NTFY_TOPIC` — declare `<Ntfy .../>` in `log4j2.xml`, or `NtfyLog4j2Installer.install()` in `main`. |
| **[java.util.logging](docs/jul.md)** | `ntfy-jul` | Add the handler, set `NTFY_URL`/`NTFY_TOPIC` — declare it in `logging.properties`, or `NtfyJulInstaller.install()` in `main`. |
| **[Core](docs/core.md)** | `ntfy-core` | Build a `NtfyConfig`, drive `NtfyClient` yourself from any JVM app. |

Everything beyond the base config — the full key reference, authentication, alert behavior,
filtering, troubleshooting — is shared across all seven and lives in the reference pages under
[Documentation](#documentation), linked from each guide.

## Runnable examples

Each adapter has a small, realistic example application under [`examples/`](examples/) (the Quarkus
one lives in [`ntfy-quarkus/integration-tests/`](ntfy-quarkus/integration-tests/)). They double as
the adapters' functional test-beds: their integration tests boot the example apps against a loopback
ntfy stand-in in CI — covering the base alert path, the synchronous-vs-asynchronous delivery
difference, and the clean-shutdown digest flush — so the examples can never drift from the code.
See [examples/README.md](examples/README.md).

## Filtering: exclude-list for loggers, allow-list for context

You can exclude specific loggers (`excluded-loggers`, prefix-matched) from ever alerting, but there
is deliberately no "only alert for these loggers" allowlist. For an alerting tool an allowlist has
the wrong failure mode: a novel error from a logger nobody thought to list would be silently
dropped — exactly the event you most need to see. The exclude-list is fail-safe instead: everything
alerts by default except noise you've explicitly declared expected. To scope alerting to a subtree,
attach the appender to that specific logger rather than to `root`. See
[docs/filtering.md](docs/filtering.md), including the `NO_ALERT` marker (Logback and Log4j2) and the
always-on self-exclusion of the library's own package.

Pointing the other way, `include-mdc-keys` is an explicit **allow-list** — of MDC keys, not of
loggers — whose values are rendered into the alert body as `key: value` lines, so an alert can say
*for whom* and *in which request* something broke instead of sending you back to centralized logs.
There is deliberately no wildcard: nothing from the MDC leaves the process unless you named its key,
and the setting is unset by default. Values are scrubbed and length-capped before rendering.
Supported on Logback/Spring Boot and on Quarkus (JBoss LogManager); plain `java.util.logging` has no
MDC, so the block is empty there. See [docs/filtering.md](docs/filtering.md).

## Notification language

Alert bodies and the engine's own diagnostics default to English but can be produced in another
language with a single `locale` setting (`ntfy.locale` / `NTFY_LOCALE` / `quarkus.ntfy.locale` /
`<locale>` / `NtfyConfig.builder().locale(...)`). The language is deterministic — it never follows
the host JVM's default locale — and an unknown or unshipped locale silently falls back to English.
Shipped languages: English (base) and French; adding one is a pure additive drop of an
`AlertMessages_<lang>.properties` file. Credentials can never be interpolated into a translated
string (a build-time invariant test enforces this). See
[docs/configuration.md](docs/configuration.md#notification-language-translations).

## Compatibility

Java 21+ (JUL included) · Logback 1.6.x · Log4j2 2.x (compiled against 2.26.1, `provided`) · Spring
Boot 4.1.x · Quarkus 3.38.x · Micronaut 4.10.x (Micronaut 5 not supported — it ships Java 25
bytecode) · GraalVM native (via the Quarkus extension). See
[docs/compatibility.md](docs/compatibility.md) for the full matrix.

## Documentation

**Per-library guides** — start here; each has install, the Maven Central link, and the base config:

| Guide | For |
|------|--------|
| [docs/spring-boot.md](docs/spring-boot.md) | Spring Boot (`ntfy-spring-boot-starter`) |
| [docs/quarkus.md](docs/quarkus.md) | Quarkus (`ntfy-quarkus-runtime`) |
| [docs/micronaut.md](docs/micronaut.md) | Micronaut (`ntfy-micronaut`) |
| [docs/logback.md](docs/logback.md) | Plain Logback (`ntfy-logback`) |
| [docs/log4j2.md](docs/log4j2.md) | Plain Log4j2 (`ntfy-log4j2`) |
| [docs/jul.md](docs/jul.md) | java.util.logging (`ntfy-jul`) |
| [docs/core.md](docs/core.md) | Any JVM app, programmatic (`ntfy-core`) |

**Reference pages** — shared across every library, linked from each guide:

| Page | Covers |
|------|--------|
| [docs/configuration.md](docs/configuration.md) | The unified `ntfy.*` key reference (types, defaults) and the per-framework mapping (env/sysprop/properties, Spring and Micronaut `ntfy.*`, Quarkus `quarkus.ntfy.*`), plus `DurationParser` syntax |
| [docs/alert-behavior.md](docs/alert-behavior.md) | Why alerting behaves the way it does: immediate single-error alerts, storm suppression, digest-on-window-close, digest-on-shutdown |
| [docs/observability.md](docs/observability.md) | The read-only `published`/`suppressed`/`failed` pipeline counters, reading them programmatically, and the classpath-conditional Micrometer binding in the Spring Boot starter |
| [docs/authentication.md](docs/authentication.md) | The three auth modes (`BearerToken`, `BasicAuth`, `None`) and the "token wins" precedence rule |
| [docs/filtering.md](docs/filtering.md) | `excluded-loggers`, the `NO_ALERT` marker (Logback and Log4j2), the always-on self-exclusion, and the opposite-direction `include-mdc-keys` allow-list for MDC context in alert bodies |
| [docs/troubleshooting.md](docs/troubleshooting.md) | Diagnostic messages the engine emits, where each framework surfaces them, and what to do |
| [docs/compatibility.md](docs/compatibility.md) | Tested JDK/Logback/Log4j2/Spring/Quarkus/Micronaut/GraalVM versions and the ntfy server API surface |

## License

[Apache-2.0](LICENSE)
