# Core (any JVM app) — `ntfy-core`

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fpimak%2Fntfy-core%2Fmaven-metadata.xml&label=Maven%20Central&logo=apachemaven)](https://central.sonatype.com/artifact/io.github.pimak/ntfy-core)
[![Javadoc](https://javadoc.io/badge2/io.github.pimak/ntfy-core/javadoc.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-core)

The framework-neutral ntfy engine and its `NtfyClient` — no logging-framework dependency. It ships
GraalVM native-image metadata for hand-rolled native builds.

**Use this when** you want to send ntfy notifications programmatically from any JVM app, or you're
building your own adapter. Every other module in this family depends on `ntfy-core`.

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
`NtfyConfig.builder().asyncEnabled(true).asyncQueueCapacity(1024)` offloads error-alert delivery to a
bounded queue drained by a daemon worker, so a slow ntfy server never blocks the submitting thread.
`NtfyClient` itself stays synchronous by contract (it returns a `PublishResult`). See
[alert-behavior.md](alert-behavior.md) for the queue and overflow semantics.

## Going further

The base config above covers the common case. Everything else is shared across all adapters and
lives in the cross-cutting reference pages:

- **[Configuration reference](configuration.md)** — every `NtfyConfig` / builder setting (types,
  defaults), `ConfigLoader` resolution, and duration syntax.
- **[Authentication](authentication.md)** — `token` vs `username`/`password` and the token-wins rule.
- **[Alert behavior](alert-behavior.md)** — immediate alerts, storm suppression, and digests.
- **[Filtering](filtering.md)** — `excluded-loggers` and the always-on self-exclusion.
- **[Troubleshooting](troubleshooting.md)** — the diagnostics the engine emits.
- **[Compatibility](compatibility.md)** — tested JDK / GraalVM versions and the ntfy server API
  surface.
