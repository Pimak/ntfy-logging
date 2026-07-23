# Plain Logback — `ntfy-logback`

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fpimak%2Fntfy-logback%2Fmaven-metadata.xml&label=Maven%20Central&logo=apachemaven)](https://central.sonatype.com/artifact/io.github.pimak/ntfy-logback)
[![Javadoc](https://javadoc.io/badge2/io.github.pimak/ntfy-logback/javadoc.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-logback)

A Logback appender plus a zero-code auto-install via a Logback `Configurator` SPI. Because it
installs through the logging framework itself, alerting is live at startup — before any DI container
exists.

**Use this when** you use Logback (with or without Spring) and want ERROR logs to alert — via XML, or
with no config at all.

## Install

- **Maven Central:** [io.github.pimak:ntfy-logback](https://central.sonatype.com/artifact/io.github.pimak/ntfy-logback)

```xml
<dependency>
  <groupId>io.github.pimak</groupId>
  <artifactId>ntfy-logback</artifactId>
  <version>1.1.1</version>
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

Instead of hand-wrapping the appender in an `AsyncAppender`, set `async` on the appender itself:

```xml
<appender name="NTFY_ALERT" class="io.github.pimak.ntfy.logback.LogbackAlertAppender">
  <url>https://ntfy.example.com</url>
  <topic>my-app-alerts</topic>
  <async>true</async>
  <asyncQueueCapacity>1024</asyncQueueCapacity>
</appender>
```

With `async=true`, each error alert is handed to the engine's bounded queue and published by a
daemon worker, so a slow or unreachable ntfy server never blocks the logging thread. Overflow drops
fold into the storm digest rather than being lost. See [alert-behavior.md](alert-behavior.md) for the
full semantics. The `AsyncAppender` wrapper below remains available if you prefer Logback's own
offloading.

### Explicit `logback.xml`

Or wire it explicitly. The `AsyncAppender` wrapper is a production-grade alternative to the built-in
`async` flag — async + never-block + ERROR-only + flush on shutdown:

```xml
<appender name="NTFY_ALERT_RAW" class="io.github.pimak.ntfy.logback.LogbackAlertAppender">
  <url>https://ntfy.example.com</url>
  <topic>my-app-alerts</topic>
  <token>tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx</token>
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

## Going further

The base config above covers the common case. Everything else is shared across all adapters and
lives in the cross-cutting reference pages:

- **[Configuration reference](configuration.md)** — every key and how it resolves (sysprop > env >
  `ntfy.properties`), the Logback XML setters, and duration syntax.
- **[Authentication](authentication.md)** — `token` vs `username`/`password` and the token-wins rule.
- **[Alert behavior](alert-behavior.md)** — immediate alerts, storm suppression, and digests.
- **[Filtering](filtering.md)** — `excluded-loggers`, the Logback-only `NO_ALERT` marker, and
  self-exclusion.
- **[Troubleshooting](troubleshooting.md)** — the diagnostics the engine emits via Logback's
  `StatusManager`, and how to surface them.
- **[Compatibility](compatibility.md)** — tested Logback / JDK versions.
