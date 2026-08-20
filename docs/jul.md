# java.util.logging — `ntfy-jul`

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fpimak%2Fntfy-jul%2Fmaven-metadata.xml&label=Maven%20Central&logo=apachemaven)](https://central.sonatype.com/artifact/io.github.pimak/ntfy-jul)
[![Javadoc](https://img.shields.io/badge/Javadoc-this%20site-blue?logo=openjdk&logoColor=white)](apidocs/index.html)

A `java.util.logging` (JUL) handler on top of the framework-neutral engine — pure JDK, no
logging-framework dependency at all. It is both a standalone adapter (plain JUL apps, Tomcat,
Helidon, zero-dependency tools) and the handler the [Quarkus extension](quarkus.md) installs.

**Use this when** your application logs through `java.util.logging` and you want SEVERE logs to
alert (and, with `warn-topic`, WARNING logs too — see [level-routing.md](level-routing.md)) —
declaratively via `logging.properties`, with one line of code, or fully wired by hand.
(On Quarkus, use the [extension](quarkus.md) instead: it installs this same handler with
`quarkus.ntfy.*` config and native support.)

**Runnable example:** [`examples/jul`](https://github.com/Pimak/ntfy-logging/tree/main/examples/jul) — a plain JUL app using the
`NtfyJulInstaller` one-liner, whose CI-run integration tests cover the base alert path,
sync-vs-async delivery, and the clean-shutdown digest flush.

## Install

- **Maven Central:** [io.github.pimak:ntfy-jul](https://central.sonatype.com/artifact/io.github.pimak/ntfy-jul)

```xml
<dependency>
  <groupId>io.github.pimak</groupId>
  <artifactId>ntfy-jul</artifactId>
  <version>2.0.0</version>
</dependency>
```

The handler pulls `ntfy-core` transitively — you only declare this one artifact.

## Base configuration

Whatever the entry point, configuration is the ambient `ConfigLoader` chain shared with core and
Logback — system property `ntfy.<key>` > env var `NTFY_<KEY>` > classpath `ntfy.properties`:

```bash
export NTFY_URL=https://ntfy.example.com
export NTFY_TOPIC=my-app-alerts
export NTFY_TOKEN=tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

### Declarative: `logging.properties`

Name the auto-handler in your JUL config file (this is also how Tomcat's
`conf/logging.properties` works) and `LogManager` installs it at logging init:

```properties
handlers = java.util.logging.ConsoleHandler, io.github.pimak.ntfy.jul.NtfyJulAutoHandler
```

`NtfyJulAutoHandler` self-configures from the environment above — deliberately *not* from
`LogManager` properties, so the keys stay identical across every adapter in the family. When
nothing configures ntfy, it degrades to a safe no-op and never breaks logging initialization.

> **Classpath endpoints require an explicit opt-in.** If the endpoint `url` comes *only* from a
> classpath `ntfy.properties` (no `ntfy.url` sysprop or `NTFY_URL` env var), the zero-code paths
> refuse to install, with a warning on stderr: any jar on the classpath can ship such a file, so
> honoring it blindly would let a compromised dependency redirect your error logs. If the file is
> yours, opt in with `-Dntfy.allow-classpath-endpoint=true` or `NTFY_ALLOW_CLASSPATH_ENDPOINT=true`
> (the flag itself is intentionally ignored inside `ntfy.properties`), or set the URL via
> env/sysprop instead.

### Programmatic one-liner

```java
public static void main(String[] args) {
  NtfyJulInstaller.install(); // attaches to the root logger when the environment configures ntfy
  // ...
}
```

`install()` returns `Optional<NtfyJulHandler>` — empty when ntfy is not configured, so the call is
always safe. To scope alerting to a subtree instead of the whole application, pass that subtree's
logger: `NtfyJulInstaller.install(Logger.getLogger("com.example"))`. Use
`NtfyJulInstaller.uninstall(logger, handler)` on shutdown if you manage the lifecycle yourself.

### Explicit wiring

For full control (e.g. config from your own source rather than the environment), build the handler
from an explicit `NtfyConfig`:

```java
NtfyConfig cfg = NtfyConfig.builder()
    .url("https://ntfy.example.com")
    .topic("my-app-alerts")
    .token("tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx")
    .build();
Logger.getLogger("").addHandler(NtfyJulHandler.forConfig(cfg));
```

As everywhere in the family, delivery is asynchronous by default (since 2.0): each alert is handed
to the engine's bounded queue drained by a daemon worker, so a slow or unreachable ntfy server
never blocks the logging thread. Set `async=false` (or `NTFY_ASYNC=false`) for synchronous, inline
delivery — the guarantee a short-lived process may prefer, since the publish completes before the
logging call returns.

## Startup self-test

Alerting only speaks when something breaks, so a revoked token or a wrong topic stays invisible
until the first real error alert fails to deliver. The opt-in self-test makes one round-trip at
boot and reports a clear diagnostic instead:

```bash
export NTFY_STARTUP_PING=probe
```

`probe` is read-only and publishes nothing; `publish` sends one low-priority test notification
through the production path and is the only mode that proves alerts are genuinely deliverable
(ntfy grants read and write separately). It runs in the background and never delays startup. Add
`NTFY_STARTUP_PING_FAIL_FAST=true` to turn a failure into a failed
startup in CI or staging. Full details, including the failure diagnostics, in
[configuration.md](configuration.md#startup-self-test).

`startup-ping` covers the primary topic only. The optional WARN route has its own independent flag —
ntfy grants permissions per topic, so a healthy ERROR route proves nothing about the WARN one:

```bash
export NTFY_WARN_TOPIC=my-app-warnings
export NTFY_STARTUP_PING_WARN=probe
```

When one route fails and another passed, the diagnosis is published as a notification on the passing
route (`startup-ping-notify-failures`, on by default), so a broken route reaches you rather than only
a status log.

## MDC context: not available on plain JUL

The `include-mdc-keys` setting binds here like any other key, but on **plain `java.util.logging` it
has no effect**: JUL has no MDC concept at all — there is no thread-scoped context map on a
`LogRecord` to read — so the context block in the alert body is always empty, whatever keys you
name. This is the same situation as SLF4J markers, which the JUL path already does not carry (and
which is why the `NO_ALERT` marker is Logback-only). Nothing fails and nothing warns; the setting is
simply inert.

The exception is **JBoss LogManager**, where this same handler *does* render MDC context, read per
key from `org.jboss.logmanager.ExtLogRecord`. That is the configuration [Quarkus](quarkus.md) uses,
so `quarkus.ntfy.include-mdc-keys` is fully supported there. If you run the standalone handler on
JBoss LogManager yourself, you get the same support. See [mdc-context.md](mdc-context.md) for the
allow-list semantics and the per-value guards.

> ntfy's own `async=true` is safe for MDC fidelity: the alert payload — context block included — is
> built on the submitting thread, and only the blocking HTTP send is offloaded. What does need care
> is a JBoss `AsyncHandler` placed **in front of** the ntfy handler: the values that reach the alert
> are then only as accurate as that handler's own record hand-off, which must copy the record's MDC
> rather than resolve it on the draining thread.

## The example, in full

The `main` method of the [runnable `examples/jul` app](https://github.com/Pimak/ntfy-logging/tree/main/examples/jul), in full — CI compiles it and its integration tests run against it, so nothing shown here
can drift from what is actually tested.

```java title="examples/jul/src/main/java/com/example/ntfyjul/JulExampleApp.java"
--8<-- "examples/jul/src/main/java/com/example/ntfyjul/JulExampleApp.java:main"
```

## Going further

The base config above covers the common case. Everything else is shared across all adapters and
lives in the cross-cutting reference pages:

- **[Configuration reference](configuration.md)** — every key and how it resolves (sysprop > env >
  `ntfy.properties`), and duration syntax.
- **[Authentication](authentication.md)** — `token` vs `username`/`password` and the token-wins rule.
- **[Alert behavior](alert-behavior.md)** — immediate alerts, storm suppression, and digests.
- **[MDC context](mdc-context.md)** — the `include-mdc-keys` allow-list, and why plain JUL always
  renders an empty block.
- **[Level routing](level-routing.md)** — the `warn-topic` opt-in: which levels alert and where.
- **[Filtering](filtering.md)** — `excluded-loggers` and self-exclusion (JUL has no marker concept,
  so the `NO_ALERT` marker — available on Logback and Log4j2 — does not apply; nor, on plain JUL,
  does the `include-mdc-keys` context allow-list — see [mdc-context.md](mdc-context.md)).
- **[Troubleshooting](troubleshooting.md)** — the diagnostics the engine emits on `System.err`
  (`[ntfy]`-prefixed), and what each line means.
- **[Compatibility](compatibility.md)** — tested JDK versions and GraalVM notes.
