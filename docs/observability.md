# Observability: pipeline counters

The engine tracks three lightweight, read-only counters so you can *alert on the alerter* — for
example, wire an alert on a spike in `failed`, which usually means a revoked token or a topic ACL
change has silently broken delivery. The counters are always **pulled, never logged**, so reading
them can never re-enter the logging pipeline the engine publishes from.

## What each counter means

The counters cover the **alert pipeline** — `AlertEngine.submit()` plus the storm digest. The ad-hoc
`NtfyClient` publish path is a user-invoked API, not the pipeline, and is intentionally uncounted.

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

The meters resolve the current appender lazily at scrape time, so they always reflect the active
appender and read `0` when none is installed. Note that a Spring re-install builds a **new** appender
with fresh counters; because `FunctionCounter` ignores decreases in its source, the exported metric
never goes backwards even though the raw Java-level counters you would read directly via
`appender.getCounters()` restart from zero on that new instance.

## Quarkus

Quarkus gets the counters at the core level (the engine tracks them), but a Micrometer/MicroProfile
Metrics binding in `ntfy-quarkus` is not yet wired and is planned as a follow-up.
