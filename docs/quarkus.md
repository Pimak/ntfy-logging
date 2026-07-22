# Quarkus — `ntfy-quarkus-runtime`

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fpimak%2Fntfy-quarkus-runtime%2Fmaven-metadata.xml&label=Maven%20Central&logo=apachemaven)](https://central.sonatype.com/artifact/io.github.pimak/ntfy-quarkus-runtime)
[![Javadoc: ntfy-quarkus-runtime](https://javadoc.io/badge2/io.github.pimak/ntfy-quarkus-runtime/ntfy--quarkus--runtime.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-quarkus-runtime)

A Quarkus 3.15 extension: a JUL log handler bound to `quarkus.ntfy.*` that publishes your error logs,
plus an injectable `NtfyClient`. It is GraalVM-native ready — the HTTP client is created at
runtime-init — so it works in both JVM and native builds.

**Use this when** you run Quarkus (JVM or native) and want `quarkus.ntfy.*` config and an
`@Inject NtfyClient`.

## Install

- **Maven Central:** [io.github.pimak:ntfy-quarkus-runtime](https://central.sonatype.com/artifact/io.github.pimak/ntfy-quarkus-runtime)

```xml
<dependency>
  <groupId>io.github.pimak</groupId>
  <artifactId>ntfy-quarkus-runtime</artifactId>
  <version>1.1.0</version>
</dependency>
```

The extension pulls `ntfy-core` transitively — you only declare this one artifact.

## Base configuration

```properties
# application.properties
quarkus.ntfy.url=https://ntfy.example.com
quarkus.ntfy.topic=my-app-alerts
quarkus.ntfy.token=tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

As soon as `url` and `topic` are set, error logs auto-publish through the extension's log handler.
The extension is native-image ready out of the box.

Set `quarkus.ntfy.async=true` (optionally `quarkus.ntfy.async-queue-capacity`, default `1024`) to
offload delivery to a bounded queue drained by a daemon worker, so a slow or unreachable ntfy server
never blocks the logging thread. The worker is a plain platform daemon thread, so it stays
native-image safe. See [alert-behavior.md](alert-behavior.md).

## Manual notifications

Inject the client to send your own notifications:

```java
@Inject NtfyClient ntfy;
ntfy.notify("Deploy finished", "v1.2.3 is live");
```

`NtfyClient.notify(...)` never throws — every outcome is returned as a `PublishResult`.

## Going further

The base config above covers the common case. Everything else is shared across all adapters and
lives in the cross-cutting reference pages:

- **[Configuration reference](configuration.md)** — every key under the `quarkus.ntfy.*` prefix
  (types, defaults), plus duration syntax.
- **[Authentication](authentication.md)** — `token` vs `username`/`password` and the token-wins rule.
- **[Alert behavior](alert-behavior.md)** — immediate alerts, storm suppression, and digests.
- **[Filtering](filtering.md)** — `excluded-loggers` and the always-on self-exclusion.
- **[Troubleshooting](troubleshooting.md)** — the diagnostics the engine emits; on Quarkus they go to
  `System.err`, each line prefixed `[ntfy]`.
- **[Compatibility](compatibility.md)** — tested Quarkus / JDK / GraalVM versions.
