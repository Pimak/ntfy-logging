# Spring Boot — `ntfy-spring-boot-starter`

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fpimak%2Fntfy-spring-boot-starter%2Fmaven-metadata.xml&label=Maven%20Central&logo=apachemaven)](https://central.sonatype.com/artifact/io.github.pimak/ntfy-spring-boot-starter)
[![Javadoc](https://img.shields.io/badge/Javadoc-this%20site-blue?logo=openjdk&logoColor=white)](apidocs/index.html)

Spring Boot auto-configuration that binds the `ntfy.*` properties, installs an `ntfy-auto` appender
onto the logging backend your application runs on — Logback or Log4j2 — so your ERROR logs publish
(and, with `ntfy.warn-topic`, your WARN logs too — see [level-routing.md](level-routing.md))
with no code, and exposes an injectable `NtfyClient` bean for manual notifications.

**Use this when** you run Spring Boot and want alerting configured from `application.yml` and an
`@Autowired NtfyClient` to send your own notifications.

**Runnable example:** [`examples/spring-boot`](https://github.com/Pimak/ntfy-logging/tree/main/examples/spring-boot) — a small app using this
starter, whose CI-run integration tests cover the base alert path, sync-vs-async delivery, and the
clean-shutdown digest flush.

## Install

- **Maven Central:** [io.github.pimak:ntfy-spring-boot-starter](https://central.sonatype.com/artifact/io.github.pimak/ntfy-spring-boot-starter)

```xml
<dependency>
  <groupId>io.github.pimak</groupId>
  <artifactId>ntfy-spring-boot-starter</artifactId>
  <version>2.0.0</version>
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
  <version>2.0.0</version>
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

## MDC context in alert bodies

To make an alert say *for whom* and *in which request* something broke, list the MDC keys you want
rendered into the body:

```yaml
ntfy:
  include-mdc-keys: correlation-id, tenant
```

Each named key that carries a non-blank value appears as its own `key: value` line just after the
`Logger:` line, in the order listed. Relaxed binding applies as it does to every other key, so
`ntfy.includeMdcKeys` and `NTFY_INCLUDE_MDC_KEYS` bind equally. The starter's appender is a Logback
appender, so values come from `ILoggingEvent.getMDCPropertyMap()` — captured at event construction
and therefore correct even behind an `AsyncAppender`.

This is an explicit allow-list with no wildcard and no "include everything" mode: nothing from the
MDC is published unless you name its key. Unset (the default) means alert bodies are exactly what
they were before. See [mdc-context.md](mdc-context.md) for the per-value guards and the rationale.

## Startup self-test

Alerting only speaks when something breaks, so a revoked token or a wrong topic stays invisible
until the first real error alert fails to deliver. The opt-in self-test makes one round-trip at
boot and reports a clear diagnostic instead:

```yaml
ntfy:
  startup-ping: probe
```

`probe` is read-only and publishes nothing; `publish` sends one low-priority test notification
through the production path and is the only mode that proves alerts are genuinely deliverable
(ntfy grants read and write separately). It runs in the background and never delays startup. Add
`ntfy.startup-ping-fail-fast: true` to turn a failure into a failed
startup in CI or staging. Full details, including the failure diagnostics, in
[configuration.md](configuration.md#startup-self-test).

`startup-ping` covers the primary topic only. The optional WARN route has its own independent flag —
ntfy grants permissions per topic, so a healthy ERROR route proves nothing about the WARN one:

```yaml
ntfy:
  warn-topic: my-app-warnings
  startup-ping-warn: probe
```

When one route fails and another passed, the diagnosis is published as a notification on the passing
route (`startup-ping-notify-failures`, on by default), so a broken route reaches you rather than only
a status log.

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

## The example, in full

The [runnable `examples/spring-boot` app](https://github.com/Pimak/ntfy-logging/tree/main/examples/spring-boot) boots on exactly this configuration — CI compiles it and its integration tests run against it, so nothing shown here
can drift from what is actually tested.

```yaml title="examples/spring-boot/src/main/resources/application.yml"
--8<-- "examples/spring-boot/src/main/resources/application.yml"
```

## Going further

The base config above covers the common case. Everything else is shared across all adapters and
lives in the cross-cutting reference pages:

- **[Configuration reference](configuration.md)** — every `ntfy.*` key (types, defaults) bound with
  Spring's relaxed binding, plus duration syntax.
- **[Authentication](authentication.md)** — `token` vs `username`/`password` and the token-wins rule.
- **[Alert behavior](alert-behavior.md)** — immediate alerts, storm suppression, and digests.
- **[MDC context](mdc-context.md)** — the `include-mdc-keys` allow-list, its guards, and why there
  is no wildcard.
- **[Level routing](level-routing.md)** — the `warn-topic` opt-in: which levels alert and where.
- **[Filtering](filtering.md)** — `excluded-loggers`, the `NO_ALERT` marker, and self-exclusion.
- **[Troubleshooting](troubleshooting.md)** — the diagnostics the engine emits and where Spring
  surfaces them (Logback's `StatusManager`, or Log4j2's `StatusLogger` on a Log4j2 stack).
- **[Compatibility](compatibility.md)** — tested Spring Boot / JDK / Logback / Log4j2 versions.
- **[Plain Log4j2](log4j2.md)** — the adapter the starter uses on a `spring-boot-starter-log4j2`
  classpath, and its standalone entry points.
