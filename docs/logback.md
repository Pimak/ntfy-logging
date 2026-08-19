# Plain Logback — `ntfy-logback`

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fpimak%2Fntfy-logback%2Fmaven-metadata.xml&label=Maven%20Central&logo=apachemaven)](https://central.sonatype.com/artifact/io.github.pimak/ntfy-logback)
[![Javadoc](https://img.shields.io/badge/Javadoc-this%20site-blue?logo=openjdk&logoColor=white)](apidocs/index.html)

A Logback appender plus a zero-code auto-install via a Logback `Configurator` SPI. Because it
installs through the logging framework itself, alerting is live at startup — before any DI container
exists.

**Use this when** you use Logback (with or without Spring) and want ERROR logs to alert (and, with
`warnTopic`, WARN logs too — see [level-routing.md](level-routing.md)) — via XML, or
with no config at all.

**Runnable example:** [`examples/logback`](https://github.com/Pimak/ntfy-logging/tree/main/examples/logback) — a plain SLF4J app wired through
[`logback.xml`](https://github.com/Pimak/ntfy-logging/blob/main/examples/logback/src/main/resources/logback.xml), whose CI-run integration tests
cover the base alert path, sync-vs-async delivery, and the clean-shutdown digest flush.

## Install

- **Maven Central:** [io.github.pimak:ntfy-logback](https://central.sonatype.com/artifact/io.github.pimak/ntfy-logback)

```xml
<dependency>
  <groupId>io.github.pimak</groupId>
  <artifactId>ntfy-logback</artifactId>
  <version>1.2.0</version>
</dependency>
```

The appender pulls `ntfy-core` transitively — you only declare this one artifact.

## Base configuration

### Zero-code auto-install

Set config via the environment and the appender attaches itself to the root logger at startup (via a
Logback `Configurator` SPI) — no `logback.xml` edit required:

```bash
export NTFY_URL=https://ntfy.example.com
export NTFY_TOPIC=my-app-alerts
export NTFY_TOKEN=tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Equivalently pass `-Dntfy.url=… -Dntfy.topic=…` as JVM system properties, or drop a classpath
`ntfy.properties` with `ntfy.url` / `ntfy.topic` keys.

> **Classpath endpoints require an explicit opt-in.** If the endpoint `url` comes *only* from a
> classpath `ntfy.properties` (no `ntfy.url` sysprop or `NTFY_URL` env var), the auto-install is
> refused with a warn status: any jar on the classpath can ship such a file, so honoring it blindly
> would let a compromised dependency redirect your error logs. If the file is yours, opt in with
> `-Dntfy.allow-classpath-endpoint=true` or `NTFY_ALLOW_CLASSPATH_ENDPOINT=true` (the flag itself is
> intentionally ignored inside `ntfy.properties`), or set the URL via env/sysprop instead.

### Built-in async delivery

Delivery is asynchronous by default (since 2.0) — no `AsyncAppender` wrapper needed: each error
alert is handed to the engine's bounded queue and published by a daemon worker, so a slow or
unreachable ntfy server never blocks the logging thread. Overflow drops fold into the storm digest
rather than being lost. See [alert-behavior.md](alert-behavior.md) for the full semantics.

```xml
<appender name="NTFY_ALERT" class="io.github.pimak.ntfy.logback.LogbackAlertAppender">
  <url>https://ntfy.example.com</url>
  <topic>my-app-alerts</topic>
  <asyncQueueCapacity>1024</asyncQueueCapacity>
</appender>
```

Set `<async>false</async>` for pre-2.0 synchronous, inline delivery. The `AsyncAppender` wrapper
below remains available if you prefer Logback's own offloading (pair it with `<async>false</async>`
so delivery is not queued twice).

### MDC context in alert bodies

Name the MDC keys you want rendered into each alert body and they appear as `key: value` lines just
after the `Logger:` line, in the order listed:

```xml
<appender name="NTFY_ALERT" class="io.github.pimak.ntfy.logback.LogbackAlertAppender">
  <url>https://ntfy.example.com</url>
  <topic>my-app-alerts</topic>
  <includeMdcKeys>correlation-id,tenant</includeMdcKeys>
</appender>
```

This is an explicit allow-list with no wildcard: nothing from the MDC is published unless you name
its key. Values are read from `ILoggingEvent.getMDCPropertyMap()`, which Logback captures when the
event is constructed, so the context is correct even behind an `AsyncAppender`. Values are scrubbed
and length-capped before rendering. See [mdc-context.md](mdc-context.md) for the guards and
[alert-behavior.md](alert-behavior.md) for where the block sits in the body.

### Explicit `logback.xml`

Or wire it explicitly. The `AsyncAppender` wrapper is a production-grade alternative to the built-in
`async` flag — async + never-block + ERROR-only + flush on shutdown:

```xml
<appender name="NTFY_ALERT_RAW" class="io.github.pimak.ntfy.logback.LogbackAlertAppender">
  <url>https://ntfy.example.com</url>
  <topic>my-app-alerts</topic>
  <token>tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx</token>
  <async>false</async>
</appender>

<appender name="NTFY_ALERT" class="ch.qos.logback.classic.AsyncAppender">
  <appender-ref ref="NTFY_ALERT_RAW"/>
  <queueSize>256</queueSize>
  <neverBlock>true</neverBlock>
  <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
    <level>ERROR</level>
  </filter>
</appender>
<shutdownHook class="ch.qos.logback.core.hook.DefaultShutdownHook"/>

<root level="INFO">
  <appender-ref ref="NTFY_ALERT"/>
</root>
```

## Startup self-test

Alerting only speaks when something breaks, so a revoked token or a wrong topic stays invisible
until the first real error alert fails to deliver. The opt-in self-test makes one round-trip at
boot and reports a clear diagnostic instead:

```xml
<appender name="NTFY_ALERT" class="io.github.pimak.ntfy.logback.LogbackAlertAppender">
  <url>https://ntfy.example.com</url>
  <topic>my-app-alerts</topic>
  <startupPing>probe</startupPing>
</appender>
```

`probe` is read-only and publishes nothing; `publish` sends one low-priority test notification
through the production path and is the only mode that proves alerts are genuinely deliverable
(ntfy grants read and write separately). It runs in the background and never delays startup. Add
`<startupPingFailFast>true</startupPingFailFast>` to turn a failure into a failed
startup in CI or staging. Full details, including the failure diagnostics, in
[configuration.md](configuration.md#startup-self-test).

`startup-ping` covers the primary topic only. The optional WARN route has its own independent flag —
ntfy grants permissions per topic, so a healthy ERROR route proves nothing about the WARN one:

```xml
  <warnTopic>my-app-warnings</warnTopic>
  <startupPingWarn>probe</startupPingWarn>
```

When one route fails and another passed, the diagnosis is published as a notification on the passing
route (`startup-ping-notify-failures`, on by default), so a broken route reaches you rather than only
a status log.

## The example, in full

The [runnable `examples/logback` app](https://github.com/Pimak/ntfy-logging/tree/main/examples/logback) is wired by exactly this file — CI compiles it and its integration tests run against it, so nothing shown here
can drift from what is actually tested.

```xml title="examples/logback/src/main/resources/logback.xml"
--8<-- "examples/logback/src/main/resources/logback.xml"
```

## Going further

The base config above covers the common case. Everything else is shared across all adapters and
lives in the cross-cutting reference pages:

- **[Configuration reference](configuration.md)** — every key and how it resolves (sysprop > env >
  `ntfy.properties`), the Logback XML setters, and duration syntax.
- **[Authentication](authentication.md)** — `token` vs `username`/`password` and the token-wins rule.
- **[Alert behavior](alert-behavior.md)** — immediate alerts, storm suppression, and digests.
- **[MDC context](mdc-context.md)** — the `include-mdc-keys` allow-list, its guards, and why there
  is no wildcard.
- **[Level routing](level-routing.md)** — the `warn-topic` opt-in: which levels alert and where.
- **[Filtering](filtering.md)** — `excluded-loggers`, the `NO_ALERT` marker, and self-exclusion.
- **[Troubleshooting](troubleshooting.md)** — the diagnostics the engine emits via Logback's
  `StatusManager`, and how to surface them.
- **[Compatibility](compatibility.md)** — tested Logback / JDK versions.
