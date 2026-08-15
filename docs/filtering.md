# Filtering

The engine gives you three independent ways to control which log events actually generate a
notification. Two are configurable — `excluded-loggers` on every adapter, the `NO_ALERT` marker on
the adapters whose framework has markers — and one is always on. A fourth setting,
`include-mdc-keys`, belongs on this page too but works in the opposite direction: it does not gate
events at all, it selects which MDC context is attached to an event that already alerts.

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

## 4. `include-mdc-keys` — configurable MDC context allow-list

The three gates above decide **which events alert**. This one is the opposite mechanism: it decides
**which context is attached** to an event that has already survived every gate and is about to be
published. Nothing here can suppress an alert, and nothing here can create one.

| | `excluded-loggers` | `include-mdc-keys` |
|---|---|---|
| Direction | **deny-list** — everything alerts, named prefixes do not | **allow-list** — nothing is attached, named keys are |
| Decides | *which events* produce a notification | *which context* is rendered into a notification |
| Default (unset) | every event alerts | no MDC line is rendered at all |
| Matching | logger-name prefix, at package boundaries | exact MDC key, no prefixes and no wildcard |

They point in opposite directions and are easy to confuse, so it is worth stating the shape once:
adding a name to `excluded-loggers` makes you see *less*; adding a name to `include-mdc-keys` makes
each alert you already see say *more*.

By default an alert tells you WHAT broke — message, logger, cause chain, root-cause frames — but
never for WHOM or in WHICH request, which sends you back to centralized logs, exactly the trip the
alert exists to spare you. `include-mdc-keys` (see [configuration.md](configuration.md) for where the
key lives in each framework) is a single, comma-separated list of MDC keys whose values are rendered
into the alert body, one `key: value` line each, immediately after the `Logger:` line:

```yaml
# Spring application.yml
ntfy:
  url: https://ntfy.example.com
  topic: my-app-alerts
  include-mdc-keys: correlation-id, tenant
```

```
Message: order settlement failed
Logger: com.acme.billing.SettlementService
correlation-id: 7f3a91c2-...
tenant: acme-eu
Caused by: java.lang.IllegalStateException: ledger closed
  at com.acme.billing.Ledger.assertOpen(Ledger.java:88)
Time: 2026-08-15T09:14:22.417Z
```

Lines follow **the order you configured**, not the MDC map's own iteration order, so the block reads
the same in every alert. A key that is absent from the MDC — or whose value is null or blank —
produces no line at all rather than an empty one, so the block stays dense. Unset (the default) means
no context block and bodies byte-identical to previous versions.

**Framework support.** Full on **Logback** (and therefore Spring Boot), read from
`ILoggingEvent.getMDCPropertyMap()`, which Logback captures at event-construction time — so the
values are correct even behind an `AsyncAppender`. Full on **JBoss LogManager** (and therefore
**Quarkus**), read per key from `org.jboss.logmanager.ExtLogRecord`. On **plain
`java.util.logging`** the context block is always empty: JUL has no MDC concept at all, the same
situation as SLF4J markers, which the JUL path already does not carry (see [jul.md](jul.md)).

**Storm digests carry no MDC.** A digest aggregates N events from N different requests, so per-event
context there would be meaningless at best and misleading at worst. Only individual error alerts get
a context block.

### Why there is no wildcard

There is deliberately no `*`, no prefix match, and no "include everything" mode. That is the security
design of the feature, not a gap in it. An MDC is a free-form, application-populated map: whatever a
framework, a filter, or a library decided to put in it is in there, and on a typical web application
that includes session identifiers, authenticated user names, tokens and other things nobody chose to
publish. A wildcard would push all of it off-host to an ntfy topic — permanently, retroactively, and
with every future key a dependency starts setting silently included. Requiring the operator to name
each key inverts that: **no MDC value is ever published unless someone wrote its key in the
configuration**, and the set of things that can leave the process is fixed at configuration time
rather than by whatever the MDC happens to hold at the moment an error fires.

Note the asymmetry with `excluded-loggers`, which is a deny-list for exactly the mirrored reason.
Both choices follow from the same rule — fail towards the safe outcome — and the safe outcome
differs: for *alerting*, failing safe means an unknown error still pages you; for *content*, failing
safe means an unknown value stays home.

### Guards applied to every value

The rendering is bounded on four axes, all enforced by `MdcProjector` and none of them configurable:

- **At most 16 keys** are rendered, however many the allow-list names.
- **Every value is scrubbed** of control characters (`\n`, `\r`, `\t`, NUL, ANSI escapes), Unicode
  line/paragraph separators, and bidi/format characters — each is replaced by a space.
- **Every value is truncated to 256 characters**, the ASCII `...` suffix counting toward that budget.
- **The whole context block is capped at 1024 characters** in aggregate, whatever the per-value
  lengths add up to.

Keys are scrubbed and truncated on the same terms as values, because a key can itself come from an
environment variable rather than from source. The scrubbing is what makes the block structurally
trustworthy: an MDC value of `acme\nCaused by: java.lang.Forged` is rendered on one line with the
newline replaced by a space, so it cannot forge a body line and make an alert claim an exception
that never happened.

The four limits also serve the [4096-byte body budget](alert-behavior.md#content-truncation): the
1024-character aggregate is what keeps a context block to roughly a quarter of the payload. Read
[alert-behavior.md](alert-behavior.md#content-truncation) for the one surprising consequence — under
truncation pressure a large context block costs you stack frames, deliberately.

### One limitation

Because every string surface is comma-separated, an MDC key whose name **contains a comma** is
unreachable from the sysprop, environment, `ntfy.properties`, Logback XML, Spring and Quarkus
spellings. Use the programmatic list form for it:
`NtfyConfig.builder().includeMdcKeys(List.of("odd,key"))` — see [core.md](core.md).

### Confirming what is in effect

When the allow-list is non-empty the engine announces it once at startup, alongside its `ACTIVE` and
excluded-loggers lines, so you can confirm which keys are actually being published. It lists key
**names** only and never a value. See [troubleshooting.md](troubleshooting.md) for the wording.

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

- [configuration.md](configuration.md) — where `excluded-loggers` and `include-mdc-keys` live in each
  framework.
- [alert-behavior.md](alert-behavior.md) — how surviving events are rate-limited and digested, where
  the MDC context block sits in the body, and what truncation sacrifices first.
