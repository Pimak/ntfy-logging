# Spring Boot — `ntfy-spring-boot-starter`

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fpimak%2Fntfy-spring-boot-starter%2Fmaven-metadata.xml&label=Maven%20Central&logo=apachemaven)](https://central.sonatype.com/artifact/io.github.pimak/ntfy-spring-boot-starter)
[![Javadoc](https://javadoc.io/badge2/io.github.pimak/ntfy-spring-boot-starter/javadoc.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-spring-boot-starter)

Spring Boot auto-configuration that binds the `ntfy.*` properties, installs an `ntfy-auto` Logback
appender so your ERROR logs publish with no code, and exposes an injectable `NtfyClient` bean for
manual notifications.

**Use this when** you run Spring Boot and want alerting configured from `application.yml` and an
`@Autowired NtfyClient` to send your own notifications.

## Install

- **Maven Central:** [io.github.pimak:ntfy-spring-boot-starter](https://central.sonatype.com/artifact/io.github.pimak/ntfy-spring-boot-starter)

```xml
<dependency>
  <groupId>io.github.pimak</groupId>
  <artifactId>ntfy-spring-boot-starter</artifactId>
  <version>1.0.2</version>
</dependency>
```

The starter pulls `ntfy-core` transitively — you only declare this one artifact.

## Base configuration

```yaml
# application.yml
ntfy:
  url: https://ntfy.example.com
  topic: my-app-alerts
  token: tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx   # or username/password; omit for a public topic
```

That is all you need. As soon as `url` and `topic` are set, error logs auto-publish (an `ntfy-auto`
Logback appender is installed idempotently) — no `logback.xml` edit required.

To keep a slow or unreachable ntfy server from blocking your application threads, set `ntfy.async:
true` (optionally tune `ntfy.async-queue-capacity`, default `1024`); delivery is then offloaded to a
bounded queue drained by a daemon worker. See [alert-behavior.md](alert-behavior.md).

## Manual notifications

Inject the client to send your own notifications:

```java
@Autowired NtfyClient ntfy;   // or constructor injection
ntfy.notify("Deploy finished", "v1.2.3 is live");
```

`NtfyClient.notify(...)` never throws — every outcome is returned as a `PublishResult`.

## Going further

The base config above covers the common case. Everything else is shared across all adapters and
lives in the cross-cutting reference pages:

- **[Configuration reference](configuration.md)** — every `ntfy.*` key (types, defaults) bound with
  Spring's relaxed binding, plus duration syntax.
- **[Authentication](authentication.md)** — `token` vs `username`/`password` and the token-wins rule.
- **[Alert behavior](alert-behavior.md)** — immediate alerts, storm suppression, and digests.
- **[Filtering](filtering.md)** — `excluded-loggers`, the `NO_ALERT` marker, and self-exclusion.
- **[Troubleshooting](troubleshooting.md)** — the diagnostics the engine emits and where Spring
  surfaces them.
- **[Compatibility](compatibility.md)** — tested Spring Boot / JDK / Logback versions.
