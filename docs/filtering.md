# Filtering

The engine gives you three independent ways to control which log events actually generate a
notification. Two are configurable (available on every adapter), one is always on.

> **Level filtering is the framework's job, not the engine's.** The core `AlertEngine` does no
> level gating — it alerts on every event handed to it (subject to the gates below). The adapters
> differ in what they hand it:
>
> - **Quarkus** — the JUL handler forwards only `SEVERE` (ERROR-equivalent) records and above, so
>   `quarkus.ntfy.*` alerting is ERROR-only out of the box.
> - **Logback** (raw appender and the zero-code auto-install) — every event that reaches the
>   appender is submitted. Restrict to ERROR the Logback way: attach a `ThresholdFilter` set to
>   `ERROR` on the appender (as in the README quickstart), or attach the appender to an
>   already-ERROR-scoped logger. Without a level restriction, an appender on `root` alerts on every
>   event — immediately exhausting the rate limiter and producing noise digests.

## 1. `excluded-loggers` — configurable logger-name exclusion

`excluded-loggers` (see [configuration.md](configuration.md) for where the key lives in each
framework) is a single, comma-separated string of logger-name prefixes. Any event whose logger name
equals one of the prefixes, or is a descendant of one (matched at a package-hierarchy boundary —
`org.apache.kafka` excludes `org.apache.kafka.clients` but not a sibling like
`org.apache.kafkaconnect`), is dropped before it is ever published or counted.

```yaml
# Spring application.yml
ntfy:
  url: https://ntfy.example.com
  topic: my-app-alerts
  excluded-loggers: org.apache.kafka, com.zaxxer.hikari
```

Excluded events disappear entirely: they are not published individually, and they are not counted
toward the storm-suppression rate limiter or folded into a digest. Exclusion means "this is expected
noise, never tell me about it" — not "tell me about it later, in bulk."

On startup, the engine announces its exclusion configuration once (alongside its `ACTIVE`
diagnostic), so you can confirm which prefixes are actually in effect. See
[troubleshooting.md](troubleshooting.md) for the wording.

## 2. The `NO_ALERT` marker — per-event opt-out (Logback only)

Sometimes you want to suppress alerting for a single log statement rather than an entire logger. This
is a **Logback-only** feature (it rides on SLF4J markers, which the Quarkus JUL path does not carry).
`LogbackAlertAppender` exposes the constant `NO_ALERT_MARKER_NAME` (value `"NO_ALERT"`) naming an
SLF4J marker you can attach to any individual `error(...)` call:

```java
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import io.github.pimak.ntfy.logback.LogbackAlertAppender;

private static final Marker NO_ALERT =
    MarkerFactory.getMarker(LogbackAlertAppender.NO_ALERT_MARKER_NAME);

log.error(NO_ALERT, "Expected, already-handled failure — do not page anyone");
```

An event carrying this marker (directly, or as a child of a composite marker referencing it) is gated
out the same way an excluded-logger event is: never published, never counted, never folded into a
digest. Use it for genuinely expected, already-handled error-level lines.

## 3. Always-on self-exclusion — the anti-loop gate

The library's own package root, **`io.github.pimak.ntfy`**, is excluded from alerting
unconditionally — independent of `excluded-loggers` and independent of any framework-level filter.
A single root now covers every module in the family (core, logback, spring, quarkus). This is a
belt-and-suspenders guard against the engine ever generating a feedback loop by alerting on a failure
inside itself. (Diagnostics already go to a separate channel — Logback's `StatusManager`, or
`System.err` for the Quarkus/Spring paths — never back through the logging pipeline, so a loop could
not form anyway; the self-exclusion holds even if that invariant is ever violated by future code.)

You do not configure this; it cannot be turned off. It survives a blank or misconfigured
`excluded-loggers` value.

> Because of this rule, an application whose own logger names start with `io.github.pimak.ntfy` would
> be silently self-excluded. This effectively never happens for real apps, but it is why the
> integration-test app in this repo logs from a `com.example.*` package.

## Why an exclude-list, not an allowlist, for loggers

`excluded-loggers` is deliberately an exclude-list (deny-list), not an allowlist. For an alerting
tool an allowlist has an inverted failure mode: an error from a logger you never thought to add would
be silently dropped — exactly the event you most need to be paged about. An exclude-list fails safe
instead: everything alerts by default, and you opt specific, already-understood noise sources out by
name. To scope alerting to a specific subtree, attach the appender to that logger (Logback) rather
than adding an allowlist.

## See also

- [configuration.md](configuration.md) — where `excluded-loggers` lives in each framework.
- [alert-behavior.md](alert-behavior.md) — how surviving events are rate-limited and digested.
