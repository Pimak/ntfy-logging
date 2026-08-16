# Filtering

The engine gives you three independent ways to control which log events actually generate a
notification. Two are configurable — `excluded-loggers` on every adapter, the `NO_ALERT` marker on
the adapters whose framework has markers — and one is always on.

> Looking for `include-mdc-keys`? It is the opposite mechanism — it does not gate events at all, it
> selects which MDC context is attached to an event that already alerts — and it has its own page:
> **[mdc-context.md](mdc-context.md)**.

> **Alerting is ERROR-only on every adapter.** The core `AlertEngine` does no level gating — it
> alerts on every event handed to it (subject to the gates below) — but each adapter gates before
> submitting:
>
> - **JUL** (`ntfy-jul`, standalone or installed by Quarkus) — the handler forwards only `SEVERE`
>   (ERROR-equivalent) records and above.
> - **Logback** (raw appender and the zero-code auto-install) — the appender submits only events
>   at `ERROR` or above. This is a hard floor, not a default: without it, a root-logger
>   auto-install would push every INFO/WARN line — request details, user identifiers, anything
>   the app logs — verbatim to the ntfy topic. To alert on a *subset* of errors, scope the
>   appender to specific loggers or use `excluded-loggers`/the `NO_ALERT` marker below.
> - **Log4j2** (`<Ntfy>` appender and `NtfyLog4j2Installer`) — the same hard floor, expressed as
>   `Level.isMoreSpecificThan(Level.ERROR)`, so a `<Root level="info">` referencing the appender
>   still alerts only on ERROR and above.

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

## 2. The `NO_ALERT` marker — per-event opt-out (Logback and Log4j2)

Sometimes you want to suppress alerting for a single log statement rather than an entire logger. This
rides on the marker concept, so it is available on the **Logback** and **Log4j2** adapters; the JUL
path — standalone or under Quarkus — has no markers at all and therefore no per-event opt-out.
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

On Log4j2 the same marker name works with Log4j2's own `MarkerManager` markers, including a marker
that reaches `NO_ALERT` indirectly through its parents (`Marker.getParents()`):

```java
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;

private static final Marker NO_ALERT = MarkerManager.getMarker("NO_ALERT");

log.error(NO_ALERT, "Expected, already-handled failure — do not page anyone");
```

## 3. Always-on self-exclusion — the anti-loop gate

The library's own package root, **`io.github.pimak.ntfy`**, is excluded from alerting
unconditionally — independent of `excluded-loggers` and independent of any framework-level filter.
A single root now covers every module in the family (core, jul, logback, log4j2, spring, quarkus).
This is a belt-and-suspenders guard against the engine ever generating a feedback loop by alerting on
a failure inside itself. (Diagnostics already go to a separate channel — Logback's `StatusManager`,
Log4j2's `StatusLogger`, or `System.err` for the JUL/Quarkus paths — never back through the logging
pipeline, so a loop could not form anyway; the self-exclusion holds even if that invariant is ever
violated by future code.)

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
name. To scope alerting to a specific subtree, attach the appender (Logback, or an `<AppenderRef>` on
a non-root `<Logger>` in Log4j2) or handler (JUL, e.g.
`NtfyJulInstaller.install(Logger.getLogger("com.example"))`) to that logger rather than adding an
allowlist.

## See also

- [configuration.md](configuration.md) — where `excluded-loggers` lives in each framework.
- [mdc-context.md](mdc-context.md) — the `include-mdc-keys` allow-list: attaching MDC context to the
  alerts that do get through.
- [alert-behavior.md](alert-behavior.md) — how surviving events are rate-limited and digested.
