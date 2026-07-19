# ntfy-logging

[![CI](https://github.com/Pimak/ntfy-logging/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/Pimak/ntfy-logging/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fpimak%2Fntfy-core%2Fmaven-metadata.xml&label=Maven%20Central&logo=apachemaven)](https://central.sonatype.com/artifact/io.github.pimak/ntfy-core)
[![Javadoc](https://javadoc.io/badge2/io.github.pimak/ntfy-spring-boot-starter/javadoc.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-spring-boot-starter)

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

| Artifact | Purpose | Use when | API docs |
|---|---|---|---|
| **`ntfy-core`** | Framework-neutral ntfy engine + `NtfyClient`; no logging-framework dependency. | You want to send ntfy notifications programmatically from any JVM app, or you're building your own adapter. | [![Javadoc](https://javadoc.io/badge2/io.github.pimak/ntfy-core/javadoc.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-core) |
| **`ntfy-logback`** | Logback appender + zero-code auto-install via a Logback `Configurator` SPI. | You use Logback (with or without Spring) and want ERROR logs to alert — via XML, or with no config at all. | [![Javadoc](https://javadoc.io/badge2/io.github.pimak/ntfy-logback/javadoc.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-logback) |
| **`ntfy-spring-boot-starter`** | Spring Boot auto-configuration binding `ntfy.*`, plus an injectable `NtfyClient` bean. | You use Spring Boot and want alerting configured from `application.yml` and a `NtfyClient` to `@Autowired`. | [![Javadoc](https://javadoc.io/badge2/io.github.pimak/ntfy-spring-boot-starter/javadoc.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-spring-boot-starter) |
| **`ntfy-quarkus-runtime`** | Quarkus 3.15 extension: a JUL log handler bound to `quarkus.ntfy.*`, native-ready, plus an injectable `NtfyClient`. | You use Quarkus (JVM or native) and want `quarkus.ntfy.*` config and `@Inject NtfyClient`. | [![Javadoc](https://javadoc.io/badge2/io.github.pimak/ntfy-quarkus-runtime/javadoc.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-quarkus-runtime) |

Each module pulls `ntfy-core` transitively — you only ever declare the one that matches your stack.

## Install & configure

### Spring Boot

```xml
<dependency>
  <groupId>io.github.pimak</groupId>
  <artifactId>ntfy-spring-boot-starter</artifactId>
  <version>1.0.0</version>
</dependency>
```

```yaml
# application.yml
ntfy:
  url: https://ntfy.example.com
  topic: my-app-alerts
  token: tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx   # or username/password; omit for a public topic
```

Error logs auto-publish (an `ntfy-auto` Logback appender is installed idempotently). For manual
notifications, inject the client:

```java
@Autowired NtfyClient ntfy;   // or constructor injection
ntfy.notify("Deploy finished", "v1.2.3 is live");
```

### Quarkus

```xml
<dependency>
  <groupId>io.github.pimak</groupId>
  <artifactId>ntfy-quarkus-runtime</artifactId>
  <version>1.0.0</version>
</dependency>
```

```properties
# application.properties
quarkus.ntfy.url=https://ntfy.example.com
quarkus.ntfy.topic=my-app-alerts
quarkus.ntfy.token=tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Error logs auto-publish through the extension's log handler; the extension is native-image ready
(the HTTP client is created at runtime-init). Inject the client for manual notifications:

```java
@Inject NtfyClient ntfy;
ntfy.notify("Deploy finished", "v1.2.3 is live");
```

### Plain Logback (no Spring)

```xml
<dependency>
  <groupId>io.github.pimak</groupId>
  <artifactId>ntfy-logback</artifactId>
  <version>1.0.0</version>
</dependency>
```

**Zero-code auto-install** — set config via the environment and the appender attaches itself to the
root logger at startup (via a Logback `Configurator` SPI), no `logback.xml` edit required:

```bash
export NTFY_URL=https://ntfy.example.com
export NTFY_TOPIC=my-app-alerts
export NTFY_TOKEN=tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Equivalently pass `-Dntfy.url=… -Dntfy.topic=…` as JVM system properties, or drop a classpath
`ntfy.properties` with `ntfy.url` / `ntfy.topic` keys. Or wire it explicitly in `logback.xml`:

```xml
<appender name="NTFY_ALERT_RAW" class="io.github.pimak.ntfy.logback.LogbackAlertAppender">
  <url>https://ntfy.example.com</url>
  <topic>my-app-alerts</topic>
  <token>tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx</token>
</appender>

<!-- Recommended production wrapper: async + never-block + ERROR-only + flush on shutdown. -->
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

### Core only (any JVM app, programmatic)

```xml
<dependency>
  <groupId>io.github.pimak</groupId>
  <artifactId>ntfy-core</artifactId>
  <version>1.0.0</version>
</dependency>
```

```java
NtfyConfig config = NtfyConfig.builder()
    .url("https://ntfy.example.com")
    .topic("my-app-alerts")
    .token("tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx")
    .build();

try (NtfyClient client = new NtfyClient(config)) {   // close() releases the HTTP client
  client.notify("Batch complete", "12,304 rows processed");
}
```

`NtfyClient.notify(...)` never throws — every outcome is returned as a `PublishResult`.

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
