# Observability: pipeline counters

The engine tracks three lightweight, read-only counters so you can *alert on the alerter* — for
example, wire an alert on a spike in `failed`, which usually means a revoked token or a topic ACL
change has silently broken delivery. The counters are always **pulled, never logged**, so reading
them can never re-enter the logging pipeline the engine publishes from.

## What each counter means

The counters cover the **alert pipeline** — `AlertEngine.submit()` plus the storm digest. The ad-hoc
`NtfyClient` publish path is a user-invoked API, not the pipeline, and is intentionally uncounted.

The opt-in [startup self-test](configuration.md#startup-self-test) is likewise uncounted, in either
mode. These counters describe application alert traffic; a boot-time self-check is not that, and
counting its publish as `published` (or its failure as `failed`) would put a fixed, meaningless
offset into every dashboard and alert-on-`failed` rule. For the same reason the self-test never
routes through the delivery path that folds failures into the storm digest, so it can never
manufacture a phantom suppressed error either. Its outcome is reported exclusively as a diagnostic
line — see [troubleshooting.md](troubleshooting.md). The failure notification it can publish on a
healthy route is uncounted for the same reason: it reports on the alerting pipeline rather than
being traffic through it.

| Counter | Increments when |
|---------|-----------------|
| `published` | A notification is actually accepted by ntfy: a successful individual alert publish **and** a successful digest publish. The digest site also covers the synchronous digest flush on shutdown, so a stop-flush publish counts identically. |
| `suppressed` | The storm rate-limiter gate rejects an event (over the `max-alerts-per-window` allowance). This is the **only** site that increments `suppressed`. |
| `failed` | A publish attempt fails: an unsuccessful individual publish, an unexpected exception during an individual publish, **or** an unsuccessful digest publish. |

The three counters are **disjoint**. In particular, a failed individual publish is folded into the
rate-limiter's *digest tally* (so the lost event still surfaces in the next digest, exactly as
before) — but for observability it is counted only as `failed`, never as `suppressed`. Likewise the
digest-restore on a failed digest publish affects only the digest tally, so a re-folded count is
never re-counted as `failed` on the next window. The digest tally (the "N errors suppressed" text)
and these observability counters are independent concerns.

The counters are backed by plain `AtomicLong`, so they are near-zero cost and GraalVM native-image
safe.

## Reading the counters programmatically

Every adapter drives the same engine, so the counters are available wherever you hold the engine or
the appender.

- **Core / any JVM app:** `engine.counters()` returns a `PipelineCounters` with `published()`,
  `suppressed()`, `failed()`, and a `snapshot()` returning an immutable
  `PipelineCounters.Snapshot(published, suppressed, failed)` triple.
- **Logback:** `appender.getCounters()` returns the same `PipelineCounters`. The appender owns a
  single counters instance for its whole lifetime and injects it into every engine it builds, so the
  tallies are **monotonic** — they survive a `stop()`/`start()` cycle and a Logback `LoggerContext`
  reset (the reset-resistant reattach listener keeps the same appender instance).
- **Log4j2:** `appender.getCounters()` on the `NtfyLog4j2Appender` returns the same
  `PipelineCounters`, with the same contract — one counters instance per appender, injected into
  every engine it builds, so the tallies survive a stop/start cycle. An appender installed with
  `NtfyLog4j2Installer` also survives a Log4j2 reconfiguration as the same instance, so its tallies
  stay monotonic across one.
- **JUL (plain `java.util.logging`, and therefore Quarkus):** `handler.counters()` on the
  `NtfyJulHandler`. A handler owns exactly one engine for its whole lifetime — it is built around an
  already-started engine and stops it on `close()` — so the returned instance is stable and its
  tallies are monotonic for as long as the handler is installed. In a Quarkus application the
  handler belongs to the framework and is not reachable from application code; use the Micrometer
  binding below instead.

## Micrometer (Spring Boot)

The Spring Boot starter binds the counters to Micrometer as three monotonic `FunctionCounter`s:

| Metric | Meaning |
|--------|---------|
| `ntfy.pipeline.published` | Notifications successfully published (individual + digest). |
| `ntfy.pipeline.suppressed` | Events suppressed by the rate limiter. |
| `ntfy.pipeline.failed` | Failed publish attempts. |

This binding is **classpath-conditional**: it activates only when `micrometer-core` is on the
classpath (any Spring Boot Actuator application) and a `MeterRegistry` bean exists. When Micrometer
is absent, the starter has no Micrometer dependency of its own and adds nothing — `ntfy-core` stays
dependency-free.

It is **not** backend-conditional: the starter installs onto whichever logging backend the
application is actually running on (Logback or Log4j2), and the meters — like the `NtfyClient` bean —
are exported either way. See [spring-boot.md](spring-boot.md).

The meters resolve the current appender lazily at scrape time, so they always reflect the active
appender and read `0` when none is installed. Note that a Spring re-install builds a **new** appender
with fresh counters, and the meters follow it: `FunctionCounter` reports whatever its source function
currently returns and does not clamp a decrease, so the exported value restarts from zero on that new
instance. That is an ordinary counter reset — indistinguishable, to the monitoring system consuming
it, from the process itself having restarted, and handled the same way (`rate()`/`increase()` in
Prometheus, `.diff()` elsewhere).

## Micronaut

`ntfy-micronaut` has **no metrics binding** — there is no Micrometer integration in the module and no
counters bean, so it is not at parity with the Spring Boot starter here. What you get is the same
counters the engine always tracks, reachable programmatically: the module installs the standard
`LogbackAlertAppender` under the name `ntfy-auto` on the root logger, so look it up and call
`getCounters()`.

```java
var root = ((ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory())
    .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
var appender = (io.github.pimak.ntfy.logback.LogbackAlertAppender) root.getAppender("ntfy-auto");
var snapshot = appender.getCounters().snapshot();   // null appender ⇒ nothing was installed
```

Note that the injectable `NtfyClient` bean is *not* a route to these numbers: the ad-hoc publish path
is deliberately uncounted (see above), and the bean exposes no counters. A Micronaut Micrometer
binding is a possible follow-up: `micronaut-micrometer` would let it take the same shape as the
Spring and Quarkus bindings.

## Micrometer (Quarkus)

`ntfy-quarkus` exports the same three meters, with the same names and meanings, as the Spring Boot
starter:

| Metric | Meaning |
|--------|---------|
| `ntfy.pipeline.published` | Notifications successfully published (individual + digest). |
| `ntfy.pipeline.suppressed` | Events suppressed by the rate limiter. |
| `ntfy.pipeline.failed` | Failed publish attempts. |

The binding is a CDI `MeterBinder` that the Quarkus Micrometer extension discovers and applies to
whichever registry the application configured, so there is nothing to enable, configure, or inject —
add `quarkus-micrometer` plus a registry (for example
`quarkus-micrometer-registry-prometheus`) and the meters appear under `/q/metrics` as
`ntfy_pipeline_published_total` and friends.

It is **extension-conditional**, the counterpart of the starter's classpath condition: the build step
registers the bean only when the application provides the `io.quarkus.metrics` capability *and*
Micrometer's `MeterRegistry` is on the runtime classpath. On a build without Micrometer the binder
class is never even loaded, and `ntfy-quarkus` declares `micrometer-core` as an optional dependency,
so an application that does not want metrics pulls in nothing.

The meters read the counters of the installed log handler **lazily at scrape time**. That matters
because of Quarkus's boot order: the handler is created at `RUNTIME_INIT`, long before the CDI
container the binding lives in. Reading late means there is no ordering dependency in either
direction, and a scrape that finds no handler — an inactive config (`quarkus.ntfy.enabled=false`, or
no `url`/`topic`), or simply a scrape before logging is configured — reports `0` instead of failing
or omitting the meters. A dev-mode reload installs a new handler whose counters restart from zero,
and the meters follow it down; see the note at the end of the Spring section, which applies verbatim.

The **ad-hoc `NtfyClient` bean is not covered**, by the same rule as everywhere else: `notify(...)` is
a user-invoked API, not the alert pipeline.
