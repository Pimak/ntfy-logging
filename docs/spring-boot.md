# Spring Boot — `ntfy-spring-boot-starter`

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fpimak%2Fntfy-spring-boot-starter%2Fmaven-metadata.xml&label=Maven%20Central&logo=apachemaven)](https://central.sonatype.com/artifact/io.github.pimak/ntfy-spring-boot-starter)
[![Javadoc](https://javadoc.io/badge2/io.github.pimak/ntfy-spring-boot-starter/javadoc.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-spring-boot-starter)

Spring Boot auto-configuration that binds the `ntfy.*` properties, installs an `ntfy-auto` appender
onto the logging backend your application runs on — Logback or Log4j2 — so your ERROR logs publish
with no code, and exposes an injectable `NtfyClient` bean for manual notifications.

**Use this when** you run Spring Boot and want alerting configured from `application.yml` and an
`@Autowired NtfyClient` to send your own notifications.

**Runnable example:** [`examples/spring-boot`](../examples/spring-boot) — a small app using this
starter, whose CI-run integration tests cover the base alert path, sync-vs-async delivery, and the
clean-shutdown digest flush.

## Install

- **Maven Central:** [io.github.pimak:ntfy-spring-boot-starter](https://central.sonatype.com/artifact/io.github.pimak/ntfy-spring-boot-starter)

```xml
<dependency>
  <groupId>io.github.pimak</groupId>
  <artifactId>ntfy-spring-boot-starter</artifactId>
  <version>1.2.0</version>
</dependency>
```

The starter pulls `ntfy-core` and `ntfy-logback` transitively — on the default (Logback) Spring Boot
stack you only declare this one artifact.

### On `spring-boot-starter-log4j2`

If you swapped Spring Boot's default backend for Log4j2, add the Log4j2 adapter alongside the
starter; everything else is unchanged:

```xml
<dependency>
  <groupId>io.github.pimak</groupId>
  <artifactId>ntfy-log4j2</artifactId>
  <version>1.2.0</version>
</dependency>
```

The starter is **backend-agnostic**: it installs the `ntfy-auto` appender onto whichever backend is
actually on the classpath and bound, and the `NtfyClient` bean and the Micrometer meters below are
available either way. (Earlier releases gated the whole auto-configuration on Logback being present,
so a Log4j2 application got no appender, no `NtfyClient` bean, and no metrics at all.)
`ntfy-logback` still arrives transitively, unchanged, and is inert on a Log4j2 stack: it declares
Logback itself at `provided` scope, so it never pulls `logback-classic` onto your classpath and its
appender is simply never installed.

## Base configuration

```yaml
# application.yml
ntfy:
  url: https://ntfy.example.com
  topic: my-app-alerts
  token: tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx   # or username/password; omit for a public topic
```

That is all you need. As soon as `url` and `topic` are set, error logs auto-publish (an `ntfy-auto`
appender is installed idempotently on the bound backend) — no `logback.xml` / `log4j2.xml` edit
required.

Delivery is asynchronous by default (since 2.0): alerts are offloaded to a bounded queue drained by
a daemon worker (tune it with `ntfy.async-queue-capacity`, default `1024`), so a slow or unreachable
ntfy server never blocks your application threads. Set `ntfy.async: false` for pre-2.0 synchronous,
inline delivery. See [alert-behavior.md](alert-behavior.md).

## Manual notifications

Inject the client to send your own notifications:

```java
@Autowired NtfyClient ntfy;   // or constructor injection
ntfy.notify("Deploy finished", "v1.2.3 is live");
```

`NtfyClient.notify(...)` never throws — every outcome is returned as a `PublishResult`.

## Metrics

When `micrometer-core` is on the classpath (it is in any Spring Boot Actuator app) and a
`MeterRegistry` bean exists, the starter automatically exports three monotonic counters —
`ntfy.pipeline.published`, `ntfy.pipeline.suppressed`, `ntfy.pipeline.failed` — so you can alert on a
spike in publish failures. The binding is classpath-conditional and adds no dependency when
Micrometer is absent; it does not depend on which logging backend you run. See
**[Observability](observability.md)** for the full semantics.

## Going further

The base config above covers the common case. Everything else is shared across all adapters and
lives in the cross-cutting reference pages:

- **[Configuration reference](configuration.md)** — every `ntfy.*` key (types, defaults) bound with
  Spring's relaxed binding, plus duration syntax.
- **[Authentication](authentication.md)** — `token` vs `username`/`password` and the token-wins rule.
- **[Alert behavior](alert-behavior.md)** — immediate alerts, storm suppression, and digests.
- **[Filtering](filtering.md)** — `excluded-loggers`, the `NO_ALERT` marker, and self-exclusion.
- **[Troubleshooting](troubleshooting.md)** — the diagnostics the engine emits and where Spring
  surfaces them (Logback's `StatusManager`, or Log4j2's `StatusLogger` on a Log4j2 stack).
- **[Compatibility](compatibility.md)** — tested Spring Boot / JDK / Logback / Log4j2 versions.
- **[Plain Log4j2](log4j2.md)** — the adapter the starter uses on a `spring-boot-starter-log4j2`
  classpath, and its standalone entry points.
