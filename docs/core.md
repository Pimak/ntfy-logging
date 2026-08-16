# Core (any JVM app) — `ntfy-core`

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fpimak%2Fntfy-core%2Fmaven-metadata.xml&label=Maven%20Central&logo=apachemaven)](https://central.sonatype.com/artifact/io.github.pimak/ntfy-core)
[![Javadoc](https://javadoc.io/badge2/io.github.pimak/ntfy-core/javadoc.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-core)

The framework-neutral ntfy engine and its `NtfyClient` — no logging-framework dependency. It ships
GraalVM native-image metadata for hand-rolled native builds.

**Use this when** you want to send ntfy notifications programmatically from any JVM app, or you're
building your own adapter. Every other module in this family depends on `ntfy-core`.

**Runnable example:** [`examples/core`](../examples/core) — a plain-JVM app driving `NtfyClient` and
`AlertEngine`, whose CI-run integration tests cover the base publish path, sync-vs-async delivery,
and the clean-shutdown digest flush.

## Install

- **Maven Central:** [io.github.pimak:ntfy-core](https://central.sonatype.com/artifact/io.github.pimak/ntfy-core)

```xml
<dependency>
  <groupId>io.github.pimak</groupId>
  <artifactId>ntfy-core</artifactId>
  <version>1.2.0</version>
</dependency>
```

## Base configuration

There is no auto-install here — you build a `NtfyConfig` and drive the client yourself:

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

The engine can also resolve config from the environment (sysprop > env > classpath `ntfy.properties`)
via `ConfigLoader` — see the [configuration reference](configuration.md).

When you drive an `AlertEngine` directly (the log-adapter path, not the synchronous `NtfyClient`),
error-alert delivery is offloaded by default (since 2.0) to a bounded queue drained by a daemon
worker, so a slow ntfy server never blocks the submitting thread; tune it with
`NtfyConfig.builder().asyncQueueCapacity(1024)`, or set `asyncEnabled(false)` for pre-2.0
synchronous, inline delivery. `NtfyClient` itself stays synchronous by contract (it returns a
`PublishResult`). See [alert-behavior.md](alert-behavior.md) for the queue and overflow semantics.

## MDC context in alert bodies

To have per-event context rendered into alert bodies, name the MDC keys on the builder. Two forms are
available — a typed list, and the CSV form the string-based surfaces use:

```java
NtfyConfig config = NtfyConfig.builder()
    .url("https://ntfy.example.com")
    .topic("my-app-alerts")
    .includeMdcKeys(List.of("correlation-id", "tenant"))   // typed list
    // .includeMdcKeysCsv("correlation-id,tenant")         // ...or the CSV form
    .build();
```

Both spell the same setting; the CSV form exists so programmatic callers can pass through a value
they read from their own configuration without splitting it themselves. Prefer the `List` form when
you have the keys in hand — it is also the only way to name an **MDC key containing a comma**, which
is unreachable from every CSV surface.

Core itself defines the allow-list and the rendering; it is the *adapter* that supplies the values.
`AlertEvent` carries them in its `mdcValues` component, populated by the Logback adapter from
`ILoggingEvent.getMDCPropertyMap()`, by the Log4j2 appender from `LogEvent.getContextData()`, and by
the JUL handler from `ExtLogRecord` under JBoss LogManager.

If you build `AlertEvent`s yourself and drive `AlertEngine.submit` directly, **you own the
allow-list step**. The engine renders whatever `mdcValues` holds; it does not intersect that map
with `include-mdc-keys`. What you still get for free is the *bounding*: `AlertEvent`'s constructor
re-applies the scrubbing, the per-value truncation and the key/total caps to whatever you hand it,
so a hand-built event can never forge a body line or blow the payload budget. But selection is
yours — hand a raw MDC map to the constructor and every entry in it is published.

So project it explicitly, the same way the adapters do:

```java
Map<String, String> mdc =
    MdcProjector.project(config.getIncludeMdcKeys(), MDC::get);   // or any key -> value lookup
engine.submit(new AlertEvent(logger, message, millis, causes, frames, markers, mdc));
```

The six-argument constructor, which omits `mdcValues` entirely, remains the right choice when you
have no context to attach. See [mdc-context.md](mdc-context.md) for the guards themselves.

## Going further

The base config above covers the common case. Everything else is shared across all adapters and
lives in the cross-cutting reference pages:

- **[Configuration reference](configuration.md)** — every `NtfyConfig` / builder setting (types,
  defaults), `ConfigLoader` resolution, and duration syntax.
- **[Authentication](authentication.md)** — `token` vs `username`/`password` and the token-wins rule.
- **[Alert behavior](alert-behavior.md)** — immediate alerts, storm suppression, and digests.
- **[MDC context](mdc-context.md)** — the `include-mdc-keys` allow-list, its guards, and what a
  hand-built `AlertEvent` is and is not filtered by.
- **[Level routing](level-routing.md)** — the `warn-topic` opt-in: which levels alert and where.
- **[Filtering](filtering.md)** — `excluded-loggers` and the always-on self-exclusion.
- **[Troubleshooting](troubleshooting.md)** — the diagnostics the engine emits.
- **[Compatibility](compatibility.md)** — tested JDK / GraalVM versions and the ntfy server API
  surface.
