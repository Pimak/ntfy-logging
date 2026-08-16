# Quarkus — `ntfy-quarkus-runtime`

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fpimak%2Fntfy-quarkus-runtime%2Fmaven-metadata.xml&label=Maven%20Central&logo=apachemaven)](https://central.sonatype.com/artifact/io.github.pimak/ntfy-quarkus-runtime)
[![Javadoc](https://javadoc.io/badge2/io.github.pimak/ntfy-quarkus-runtime/javadoc.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-quarkus-runtime)

A Quarkus 3.38 extension: a JUL log handler (the shared [`ntfy-jul`](jul.md) handler, installed
here through a `LogHandlerBuildItem`) bound to `quarkus.ntfy.*` that publishes your error logs,
plus an injectable `NtfyClient`. It is GraalVM-native ready — the HTTP client is created at
runtime-init — so it works in both JVM and native builds.

**Use this when** you run Quarkus (JVM or native) and want `quarkus.ntfy.*` config and an
`@Inject NtfyClient`.

**Runnable example:** [`ntfy-quarkus/integration-tests`](../ntfy-quarkus/integration-tests) — a real
Quarkus app using this extension, exercised by CI in JVM, fast-jar, and native mode (including an
async-delivery smoke test).

## Install

- **Maven Central:** [io.github.pimak:ntfy-quarkus-runtime](https://central.sonatype.com/artifact/io.github.pimak/ntfy-quarkus-runtime)

```xml
<dependency>
  <groupId>io.github.pimak</groupId>
  <artifactId>ntfy-quarkus-runtime</artifactId>
  <version>1.2.0</version>
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

Delivery is asynchronous by default (since 2.0): alerts are offloaded to a bounded queue drained by
a daemon worker (tune it with `quarkus.ntfy.async-queue-capacity`, default `1024`), so a slow or
unreachable ntfy server never blocks the logging thread. The worker is a plain platform daemon
thread, so it stays native-image safe. Set `quarkus.ntfy.async=false` for pre-2.0 synchronous,
inline delivery. See [alert-behavior.md](alert-behavior.md).

## MDC context in alert bodies

List the MDC keys you want rendered into each alert body and they appear as `key: value` lines just
after the `Logger:` line, in the order listed:

```properties
quarkus.ntfy.include-mdc-keys=correlation-id,tenant
```

Quarkus logs through **JBoss LogManager**, so the extension has a real MDC to read: values are taken
per key from `org.jboss.logmanager.ExtLogRecord`, and this works the same in JVM and native mode.
(This is the one capability the shared JUL handler gains under Quarkus that it cannot have on plain
`java.util.logging` — see [jul.md](jul.md).) It is an explicit allow-list with no wildcard: nothing
from the MDC is published unless you name its key. See [mdc-context.md](mdc-context.md) for the per-value
guards and the rationale, and [alert-behavior.md](alert-behavior.md) for where the block sits in the
body.

> `quarkus.ntfy.async=true` is safe for MDC fidelity: the alert payload — context block included — is
> built on the submitting thread, and only the blocking HTTP send is offloaded. What does need care
> is a JBoss `AsyncHandler` placed **in front of** the ntfy handler: the values that reach the alert
> are then only as accurate as that handler's own record hand-off, which must copy the record's MDC
> rather than resolve it on the draining thread.

## Manual notifications

Inject the client to send your own notifications:

```java
@Inject NtfyClient ntfy;
ntfy.notify("Deploy finished", "v1.2.3 is live");
```

`NtfyClient.notify(...)` never throws — every outcome is returned as a `PublishResult`.

## Metrics

If your application already uses `quarkus-micrometer`, the extension exports the ntfy pipeline
counters as three meters — the same three, under the same names, that the Spring Boot starter has
always exported:

| Metric | Meaning |
|--------|---------|
| `ntfy.pipeline.published` | Notifications successfully published (individual + digest). |
| `ntfy.pipeline.suppressed` | Events suppressed by the rate limiter. |
| `ntfy.pipeline.failed` | Failed publish attempts. |

There is nothing to configure and nothing to inject. Add the Micrometer extension and a registry the
way you normally would, and the meters appear alongside your own:

```xml
<dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-micrometer-registry-prometheus</artifactId>
</dependency>
```

```
$ curl -s localhost:8080/q/metrics | grep ntfy_pipeline
ntfy_pipeline_failed_total 0.0
ntfy_pipeline_published_total 3.0
ntfy_pipeline_suppressed_total 0.0
```

The binding is **conditional**: it activates only when the Micrometer extension is actually present.
Without it, `ntfy-quarkus` adds no Micrometer dependency of its own and registers nothing — the
extension never puts Micrometer on your classpath. The meters read `0` (rather than disappearing)
when no alert handler is installed, so a scrape is safe before boot completes and on a build where
`quarkus.ntfy.url`/`topic` are unset.

Alerting on `ntfy.pipeline.failed` is the point of the exercise: a spike there is usually a revoked
token or a changed topic ACL having silently broken delivery. See
[observability.md](observability.md) for what each counter counts and what is deliberately left
uncounted.

## Going further

The base config above covers the common case. Everything else is shared across all adapters and
lives in the cross-cutting reference pages:

- **[Configuration reference](configuration.md)** — every key under the `quarkus.ntfy.*` prefix
  (types, defaults), plus duration syntax.
- **[Authentication](authentication.md)** — `token` vs `username`/`password` and the token-wins rule.
- **[Alert behavior](alert-behavior.md)** — immediate alerts, storm suppression, and digests.
- **[MDC context](mdc-context.md)** — the `include-mdc-keys` allow-list, its guards, and why there
  is no wildcard.
- **[Filtering](filtering.md)** — `excluded-loggers` and the always-on self-exclusion.
- **[Observability](observability.md)** — the pipeline counters behind the meters above, and how to
  read them without Micrometer.
- **[Troubleshooting](troubleshooting.md)** — the diagnostics the engine emits; on Quarkus they go to
  `System.err`, each line prefixed `[ntfy]`.
- **[Compatibility](compatibility.md)** — tested Quarkus / JDK / GraalVM versions.
