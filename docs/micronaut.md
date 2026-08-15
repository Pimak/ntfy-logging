# Micronaut — `ntfy-micronaut`

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fpimak%2Fntfy-micronaut%2Fmaven-metadata.xml&label=Maven%20Central&logo=apachemaven)](https://central.sonatype.com/artifact/io.github.pimak/ntfy-micronaut)
[![Javadoc](https://javadoc.io/badge2/io.github.pimak/ntfy-micronaut/javadoc.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-micronaut)

A Micronaut integration that binds the `ntfy.*` keys from your application configuration, installs an
`ntfy-auto` appender on the root **Logback** logger when the context starts so your ERROR logs
publish with no code, and exposes an injectable `NtfyClient` singleton for manual notifications.

**Use this when** you run Micronaut on its default Logback stack and want alerting configured from
`application.yml` and an `@Inject NtfyClient` to send your own notifications.

> **Do you need it?** The value this module adds over plain [`ntfy-logback`](logback.md) is exactly
> two things: `ntfy.*` bound from *Micronaut's* configuration (so profiles, `@Value`-style
> placeholders, config clients and the rest of Micronaut's property resolution apply), and the
> injectable client bean. If you configure ntfy from environment variables alone and never send a
> manual notification, `ntfy-logback`'s zero-code auto-install already does the whole job — and it
> does it *earlier*, before the container exists.

## Install

- **Maven Central:** [io.github.pimak:ntfy-micronaut](https://central.sonatype.com/artifact/io.github.pimak/ntfy-micronaut)

```xml
<dependency>
  <groupId>io.github.pimak</groupId>
  <artifactId>ntfy-micronaut</artifactId>
  <version>1.2.0</version>
</dependency>
```

The module pulls `ntfy-core` and `ntfy-logback` transitively — on a standard Micronaut application
you declare this one artifact and nothing else.

You do **not** declare Micronaut for it: your application already brings `micronaut-inject` /
`micronaut-context`, and because your build imports a Micronaut BOM, your platform version is the one
that wins for those transitives. Logback is a **`provided`-scope** dependency here, exactly as in
`ntfy-logback`: the module compiles against it but never puts a `logback-classic` of its own on your
classpath — the one `micronaut-logging` already brings is the one that runs.

No annotation-processor setup is needed on your side. Micronaut is a compile-time DI framework, and
this module's bean definitions are generated when *it* is built and ship inside the jar.

## Base configuration

```yaml
# application.yml
ntfy:
  url: https://ntfy.example.com
  topic: my-app-alerts
  token: tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx   # or username/password; omit for a public topic
```

That is all you need. As soon as `url` and `topic` are set, error logs auto-publish: on
`StartupEvent` the module attaches an appender named `ntfy-auto` to the root Logback logger, and
detaches and stops it again when the context shuts down — no `logback.xml` edit required. The install
is an idempotent *replace*: if `ntfy-logback`'s zero-code `Configurator` SPI already attached an
`ntfy-auto` appender from environment variables before the context existed, the Micronaut-bound
values take over that one slot rather than adding a second appender.

The key roster, types and defaults are identical to the Spring starter's, so an `ntfy.*` block can be
lifted from one framework's `application.yml` into the other unchanged. Micronaut's relaxed binding
accepts `ntfy.app-name`, `ntfy.appName` and `NTFY_APP_NAME` alike, and converts the duration keys
natively from `5s` / `3m` / `500ms` / `PT10S`. See the full
**[configuration reference](configuration.md)**.

Delivery is asynchronous by default (since 2.0): alerts are offloaded to a bounded queue drained by a
daemon worker (tune it with `ntfy.async-queue-capacity`, default `1024`), so a slow or unreachable
ntfy server never blocks your application threads. Set `ntfy.async: false` for pre-2.0 synchronous,
inline delivery. See [alert-behavior.md](alert-behavior.md).

Setting `ntfy.enabled: false` installs no appender; the `NtfyClient` bean is still injectable, built
from an inactive configuration that publishes nothing.

## Manual notifications

Inject the client to send your own notifications:

```java
@Inject NtfyClient ntfy;   // or constructor injection
ntfy.notify("Deploy finished", "v1.2.3 is live");
```

The bean is a `@Singleton` whose lifecycle belongs to the container: it is closed on context
shutdown, which stops the async delivery worker. `NtfyClient.notify(...)` never throws — every
outcome is returned as a `PublishResult`.

## Scope: Logback only

The automatic appender install is **Logback-specific, and only Logback**. Micronaut ships Logback
through `micronaut-logging` as its default SLF4J binding, so for the overwhelming majority of
applications this is invisible; there is deliberately no Log4j2 path in this module. Precisely what
that means:

| Situation | `NtfyClient` bean | `ntfy-auto` appender |
|---|---|---|
| Logback present and bound (the Micronaut default) | Available | Installed on `StartupEvent` |
| Logback on the classpath but SLF4J bound to another provider | Available | **Skipped** — `NtfyLogbackInstaller` logs a warning naming the bound factory, and startup proceeds |
| No Logback on the classpath at all (e.g. a Log4j2 application) | Available | Not installed — the installer bean is not contributed to the context |

The client bean carries no Logback type anywhere in its factory or its signature, so it is
contributed whatever backend you bind. Nothing here ever fails startup: a missing or non-Logback
backend downgrades automatic alerting to manual notifications, it does not break the context.

A Micronaut application running on **Log4j2** can still get automatic alerting — just not from this
module. Add `io.github.pimak:ntfy-log4j2` and declare its `<Ntfy .../>` element in your `log4j2.xml`
(or call `NtfyLog4j2Installer.install()`); that appender reads its own configuration rather than
Micronaut's. See **[log4j2.md](log4j2.md)**.

## Supported Micronaut versions

**Micronaut 4.10.x only.** The module is compiled and tested against `micronaut-platform` 4.10.17
(core 4.10.26). Micronaut 5 is **not supported yet**: its `micronaut-inject` and its
`micronaut-inject-java` annotation processor ship Java 25 bytecode (class file version 69), which
cannot be compiled or run against this project's Java 21 baseline. See
[compatibility.md](compatibility.md#micronaut-ntfy-micronaut).

## Going further

The base config above covers the common case. Everything else is shared across all adapters and
lives in the cross-cutting reference pages:

- **[Configuration reference](configuration.md)** — every `ntfy.*` key (types, defaults) bound with
  Micronaut's relaxed binding, plus duration syntax.
- **[Authentication](authentication.md)** — `token` vs `username`/`password` and the token-wins rule.
- **[Alert behavior](alert-behavior.md)** — immediate alerts, storm suppression, and digests.
- **[Filtering](filtering.md)** — `excluded-loggers`, the `NO_ALERT` marker, and self-exclusion.
- **[Observability](observability.md)** — the pipeline counters, and why there is no Micrometer
  binding on Micronaut yet.
- **[Troubleshooting](troubleshooting.md)** — the engine's diagnostics go to Logback's
  `StatusManager`; the module's own install/skip messages are ordinary SLF4J logs from
  `io.github.pimak.ntfy.micronaut.NtfyLogbackInstaller`.
- **[Compatibility](compatibility.md)** — tested Micronaut / JDK / Logback versions.
- **[Plain Logback](logback.md)** — the appender this module installs, and its standalone entry
  points.
