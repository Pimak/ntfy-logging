# Filtering

The engine gives you four independent ways to control which log events actually generate a
notification. Three are configurable — `excluded-loggers` and `excluded-exception-types` on every
adapter, the `NO_ALERT` marker on the adapters whose framework has markers — and one is always on.

> Looking for `include-mdc-keys`? It is the opposite mechanism — it does not gate events at all, it
> selects which MDC context is attached to an event that already alerts — and it has its own page:
> **[mdc-context.md](mdc-context.md)**. Looking for `warn-topic`? Level routing decides *which
> levels* alert and *where*, rather than filtering within a level, and it too has its own page:
> **[level-routing.md](level-routing.md)**.

> **Alerting is ERROR-only by default on every adapter.** The core `AlertEngine` does no level
> gating of its own beyond routing — it alerts on every event handed to it (subject to the gates
> below) — but each adapter gates before submitting:
>
> - **JUL** (`ntfy-jul`, standalone or installed by Quarkus) — the handler forwards only `SEVERE`
>   (ERROR-equivalent) records and above.
> - **Logback** (raw appender and the zero-code auto-install) — the appender submits only events
>   at `ERROR` or above. Without this floor, a root-logger auto-install would push every INFO/WARN
>   line — request details, user identifiers, anything the app logs — verbatim to the ntfy topic.
>   To alert on a *subset* of errors, scope the appender to specific loggers or use
>   `excluded-loggers`/the `NO_ALERT` marker below.
> - **Log4j2** (`<Ntfy>` appender and `NtfyLog4j2Installer`) — the same floor, expressed as
>   `Level.isMoreSpecificThan(Level.ERROR)`, so a `<Root level="info">` referencing the appender
>   still alerts only on ERROR and above.
>
> The one way to widen this is [`warn-topic`](level-routing.md), which adds a WARN route to its own
> topic with its own storm budget. It is an opt-in that must name its own destination, and it never
> touches the floor below it: **INFO and under can never alert, on any configuration.** That is not
> a validation rule but a property of the engine's level type, which has exactly two constants.

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

## 2. `excluded-exception-types` — configurable exception-type exclusion

Some failures are expected and already handled, and the exception type says so on its own: a client
that closed the connection mid-response, a cancelled request, a scheduled poll that timed out
against a service you already monitor elsewhere. `excluded-exception-types` names those types, and
an event whose exception chain contains any of them never alerts.

```yaml
ntfy:
  excluded-exception-types: org.apache.catalina.connector.ClientAbortException, java.util.concurrent.CancellationException
```

Two properties are worth being explicit about, because they are what make the key usable:

- **The whole cause chain is matched, not just the surface throwable.** A client disconnect almost
  never reaches a logger bare — it arrives wrapped in whatever the container, the framework or your
  own code threw around it. Matching only the outermost type would leave the key configured and
  inert, which is worse than not having it: the noise continues and the config says it should not.
  (The chain is capped at 64 links when the event is built, and stops at the first repeat, so a
  type buried below 64 wrappers is out of reach. No real stack goes there.)
- **Names are matched whole, and must be fully qualified.** Unlike `excluded-loggers`, this is not
  prefix matching. A logger name is hierarchical, so a prefix names a subtree you can reason about;
  a class name is not, so `java.io.IOException` here means that class and not everything whose name
  happens to start the same way. `ClientAbortException` on its own matches nothing — write the
  package.

Use it when the *type* is what makes an error uninteresting. When it is the *source* that is noisy,
`excluded-loggers` is the better tool; when it is one specific call site, the `NO_ALERT` marker
below is.

## 3. The `NO_ALERT` marker — per-event opt-out (Logback and Log4j2)

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

## 4. Always-on self-exclusion — the anti-loop gate

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

- [configuration.md](configuration.md) — where `excluded-loggers` and `excluded-exception-types`
  live in each framework.
- [level-routing.md](level-routing.md) — the `warn-topic` opt-in: which levels alert and where their
  alerts go. Everything on this page applies to both routes.
- [mdc-context.md](mdc-context.md) — the `include-mdc-keys` allow-list: attaching MDC context to the
  alerts that do get through.
- [alert-behavior.md](alert-behavior.md) — how surviving events are rate-limited and digested.
