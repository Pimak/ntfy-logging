# Plain Log4j2 — `ntfy-log4j2`

[![Maven Central](https://img.shields.io/maven-metadata/v?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fpimak%2Fntfy-log4j2%2Fmaven-metadata.xml&label=Maven%20Central&logo=apachemaven)](https://central.sonatype.com/artifact/io.github.pimak/ntfy-log4j2)
[![Javadoc](https://javadoc.io/badge2/io.github.pimak/ntfy-log4j2/javadoc.svg)](https://javadoc.io/doc/io.github.pimak/ntfy-log4j2)

A Log4j2 appender plugin (`<Ntfy>`) plus a one-line programmatic installer. It maps Log4j2
`LogEvent`s onto the same framework-neutral `AlertEngine` the Logback adapter uses, so rate limiting,
storm digests, priorities/tags, truncation, sync-vs-async delivery, `excluded-loggers`, auth modes
and `locale` behave identically to every other adapter in the family.

**Use this when** you log through Log4j2 (with or without SLF4J in front of it) and want ERROR logs
to alert — declaratively in `log4j2.xml`, or with one line in `main`.

**Runnable example:** [`examples/log4j2`](../examples/log4j2) — a plain Log4j2 app wired through
[`log4j2.xml`](../examples/log4j2/src/main/resources/log4j2.xml), whose CI-run integration tests
cover the base alert path, sync-vs-async delivery, and the clean-shutdown digest flush.

## Install

- **Maven Central:** [io.github.pimak:ntfy-log4j2](https://central.sonatype.com/artifact/io.github.pimak/ntfy-log4j2)

```xml
<dependency>
  <groupId>io.github.pimak</groupId>
  <artifactId>ntfy-log4j2</artifactId>
  <version>1.2.0</version>
</dependency>
```

The appender pulls `ntfy-core` transitively — you only declare this one artifact. Log4j2 itself is a
`provided`-scope dependency (compiled against **2.26.1**), so the version your application already
declares is the one that runs; the module adds no Log4j2 of its own. Java 21+, like the rest of the
family.

## Base configuration

There are two entry points. Unlike Logback, there is **no fully zero-code auto-install** — see
[why](#why-there-is-no-zero-code-auto-install) below.

### Declarative: `log4j2.xml`

The module registers an `Ntfy` appender plugin, so you declare it like any other appender and
reference it from a logger:

```xml
<Configuration>
  <Appenders>
    <Ntfy name="ntfy" url="${env:NTFY_URL}" topic="${env:NTFY_TOPIC}"/>
  </Appenders>
  <Loggers>
    <Root level="info">
      <AppenderRef ref="ntfy"/>
    </Root>
  </Loggers>
</Configuration>
```

`url` and `topic` are the only required attributes; every other one is optional and, when unset,
keeps the engine default. All attributes are string-valued (durations use the same
`5s`/`3m`/`PT5S`/bare-millis syntax as everywhere else):

`url`, `topic`, `token`, `username`, `password`, `title`, `appName`, `maxStackFrames`,
`connectTimeout`, `requestTimeout`, `maxAlertsPerWindow`, `suppressionWindow`, `errorPriority`,
`digestPriority`, `errorTags`, `digestTags`, `clickUrl`, `actions`, `excludedLoggers`, `locale`,
`enabled`, `async`, `asyncQueueCapacity`, `requireHttpsForCredentials`.

This is the same roster as the Logback appender's XML setters — the camelCase spelling of each
canonical key — so the per-key reference in [configuration.md](configuration.md) applies unchanged.
Log4j2's own `${env:…}` / `${sys:…}` lookups work in any attribute value, as in the snippet above.

Built-in async delivery is an attribute rather than a wrapper appender:

```xml
<Ntfy name="ntfy" url="${env:NTFY_URL}" topic="${env:NTFY_TOPIC}"
      async="true" asyncQueueCapacity="1024"/>
```

With `async="true"`, each error alert is handed to the engine's bounded queue and published by a
daemon worker, so a slow or unreachable ntfy server never blocks the logging thread. Overflow drops
fold into the storm digest rather than being lost. See [alert-behavior.md](alert-behavior.md) for
the full semantics. Log4j2's own `<Async>` appender remains available if you prefer its offloading.

### Programmatic one-liner

```java
public static void main(String[] args) {
  NtfyLog4j2Installer.install(); // attaches to the root logger when the environment configures ntfy
  // ...
}
```

`install()` reads the ambient `ConfigLoader` chain shared with core, JUL and Logback — system
property `ntfy.<key>` > env var `NTFY_<KEY>` > classpath `ntfy.properties`:

```bash
export NTFY_URL=https://ntfy.example.com
export NTFY_TOPIC=my-app-alerts
export NTFY_TOKEN=tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

It returns `Optional<NtfyLog4j2Appender>` — empty when ntfy is not configured, so the call is always
safe — and it never throws. Use `install(LoggerContext)` to target a specific context, and
`uninstall(LoggerContext, appender)` on shutdown if you manage the lifecycle yourself. The installed
appender survives a Log4j2 reconfiguration: it re-attaches itself to the new configuration rather
than disappearing on the next `log4j2.xml` reload.

> **Classpath endpoints require an explicit opt-in.** If the endpoint `url` comes *only* from a
> classpath `ntfy.properties` (no `ntfy.url` sysprop or `NTFY_URL` env var), `install()` is refused
> with a warning: any jar on the classpath can ship such a file, so honoring it blindly would let a
> compromised dependency redirect your error logs. If the file is yours, opt in with
> `-Dntfy.allow-classpath-endpoint=true` or `NTFY_ALLOW_CLASSPATH_ENDPOINT=true` (the flag itself is
> intentionally ignored inside `ntfy.properties`), or set the URL via env/sysprop instead. This is
> the same guard the Logback and JUL auto-installs apply.

### Why there is no zero-code auto-install

The Logback adapter attaches itself with no configuration at all through Logback's
`ch.qos.logback.classic.spi.Configurator` SPI. Log4j2 has no equivalent for *augmenting* an existing
configuration: the only comparable hook is a `ConfigurationFactory`, and exactly one factory can win
for a given context — a delegating one shipped by a library would silently contend with the
application's own (or another library's), which is precisely the kind of surprise an alerting tool
must not introduce. So on Log4j2 you name the appender in `log4j2.xml` or call
`NtfyLog4j2Installer.install()`; both are one line, and the installer path still takes its whole
configuration from the environment.

## MDC context in alert bodies

log4j2's **`ThreadContext` is its MDC**. Name the keys you want attached to alerts with
`includeMdcKeys`, and each one is rendered as its own `key: value` line just after `Logger:`:

```xml
<Ntfy name="ntfy"
      url="https://ntfy.example.com"
      topic="my-app-alerts"
      includeMdcKeys="correlation-id,tenant"/>
```

```
Message: order settlement failed
Logger: com.acme.billing.SettlementService
correlation-id: 7f3a91c2-…
tenant: acme-eu
Caused by: java.lang.IllegalStateException: ledger closed
```

It is an **allow-list with no wildcard form**: a key you have not named is never published, so a
`ThreadContext` carrying a session token or an e-mail address cannot leak into a topic. Unset — the
default — bodies are byte-identical to before.

The values are read from the event's captured context data (`LogEvent.getContextData()`), not from
the `ThreadContext` thread-local. That distinction is what keeps the projection correct behind an
`<Async>` appender or this appender's own async delivery, where the publishing thread is not the
thread that logged. See **[filtering.md](filtering.md)** for the guards (key cap, value truncation,
control-character scrubbing) and **[alert-behavior.md](alert-behavior.md)** for how the context block
interacts with the 4096-byte body limit.

## Behavior notes specific to this adapter

- **Level gate:** the appender submits only events at `ERROR` or above
  (`Level.isMoreSpecificThan(Level.ERROR)`), matching the Logback appender's ERROR floor and the JUL
  handler's `SEVERE`. See [filtering.md](filtering.md).
- **`NO_ALERT` marker:** supported, including markers that reach it through `Marker.getParents()`.
  See [filtering.md](filtering.md).
- **Diagnostics** go to Log4j2's `StatusLogger`, never back through the logging pipeline the
  appender publishes from. See [troubleshooting.md](troubleshooting.md).
- **GraalVM native image:** reachability metadata for the plugin is generated at build time by
  log4j-core's own annotation processor and ships inside the jar — nothing to register by hand.

## Going further

The base config above covers the common case. Everything else is shared across all adapters and
lives in the cross-cutting reference pages:

- **[Configuration reference](configuration.md)** — every key and how it resolves (sysprop > env >
  `ntfy.properties`), the `<Ntfy>` attribute spellings, and duration syntax.
- **[Authentication](authentication.md)** — `token` vs `username`/`password` and the token-wins rule.
- **[Alert behavior](alert-behavior.md)** — immediate alerts, storm suppression, and digests.
- **[Filtering](filtering.md)** — `excluded-loggers`, the `NO_ALERT` marker, and self-exclusion.
- **[Troubleshooting](troubleshooting.md)** — the diagnostics the engine emits via Log4j2's
  `StatusLogger`, and how to surface them.
- **[Compatibility](compatibility.md)** — tested Log4j2 / JDK versions.
