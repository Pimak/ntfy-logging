# MDC context in alert bodies

By default an alert tells you WHAT broke — message, logger, cause chain, root-cause frames — but
never for WHOM or in WHICH request. Acting on one therefore means going back to your centralized
logs to find the request it came from, which is exactly the trip the alert exists to spare you.

`include-mdc-keys` closes that gap: name the MDC keys you care about, and their values are rendered
into the alert body, one `key: value` line each, immediately after the `Logger:` line.

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

## Where the setting lives

The canonical key is `include-mdc-keys`, and its value is a comma-separated list. See
[configuration.md](configuration.md) for the full key reference; the spellings are:

| Surface | Spelling |
|---|---|
| System property | `-Dntfy.include-mdc-keys=correlation-id,tenant` |
| Environment | `NTFY_INCLUDE_MDC_KEYS=correlation-id,tenant` |
| `ntfy.properties` | `ntfy.include-mdc-keys=correlation-id,tenant` |
| Logback XML | `<includeMdcKeys>correlation-id,tenant</includeMdcKeys>` |
| Log4j2 XML | `<Ntfy ... includeMdcKeys="correlation-id,tenant"/>` |
| Spring Boot | `ntfy.include-mdc-keys: correlation-id, tenant` |
| Micronaut | `ntfy.include-mdc-keys: correlation-id, tenant` |
| Quarkus | `quarkus.ntfy.include-mdc-keys=correlation-id,tenant` |
| Programmatic | `NtfyConfig.builder().includeMdcKeys(List.of("correlation-id", "tenant"))` |

## Framework support

Every adapter reads the **event's own captured context**, never a thread-local. That distinction is
what keeps the values correct when the thread that publishes the alert is not the thread that logged
it — which is the normal case behind an async appender, and behind this engine's own asynchronous
delivery.

| Framework | Source | Notes |
|---|---|---|
| Logback (and Spring Boot, Micronaut) | `ILoggingEvent.getMDCPropertyMap()` | Captured at event construction, so correct behind an `AsyncAppender`. |
| Log4j2 (standalone, and Log4j2-backed Spring Boot) | `LogEvent.getContextData()` | Log4j2's `ThreadContext` *is* its MDC. Correct behind an `<Async>` appender. |
| JBoss LogManager (and Quarkus) | `org.jboss.logmanager.ExtLogRecord`, read per allow-listed key | Reached reflectively so `ntfy-jul` keeps its zero-dependency promise. |
| Plain `java.util.logging` | — | JUL has no MDC concept, so the block is always empty. Same situation as SLF4J markers, which the JUL path already does not carry (see [jul.md](jul.md)). |

One caveat for the JUL and Quarkus paths: this engine's own `async=true` mode is safe, because the
body is built on the submitting thread and only the blocking HTTP send is offloaded. But if a JBoss
`AsyncHandler` sits *in front* of the ntfy handler, MDC fidelity depends on that handler copying the
record before hand-off.

**Storm digests carry no MDC.** A digest aggregates N events from N different requests, so per-event
context there would be meaningless at best and misleading at worst. Only individual error alerts get
a context block.

## Why there is no wildcard

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

## Guards applied to every value

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

## Not to be confused with `excluded-loggers`

The two settings look similar and point in opposite directions, so it is worth stating the shape
once: adding a name to `excluded-loggers` makes you see *less*; adding a name to `include-mdc-keys`
makes each alert you already see say *more*.

| | `excluded-loggers` | `include-mdc-keys` |
|---|---|---|
| Direction | **deny-list** — everything alerts, named prefixes do not | **allow-list** — nothing is attached, named keys are |
| Decides | *which events* produce a notification | *which context* is rendered into a notification |
| Default (unset) | every event alerts | no MDC line is rendered at all |
| Matching | logger-name prefix, at package boundaries | exact MDC key, no prefixes and no wildcard |

Nothing on this page can suppress an alert, and nothing on it can create one. The gates that decide
which events alert at all live in [filtering.md](filtering.md).

## Building `AlertEvent`s yourself

If you drive `AlertEngine.submit` directly rather than through an adapter, **you own the allow-list
step**: the engine renders whatever the event's `mdcValues` holds and never intersects it with
`include-mdc-keys`. The bounding above still applies — `AlertEvent`'s constructor re-applies the
scrubbing, truncation and caps to anything you hand it — but selection is yours. Project explicitly,
the way the adapters do:

```java
Map<String, String> mdc =
    MdcProjector.project(config.getIncludeMdcKeys(), MDC::get);   // or any key -> value lookup
engine.submit(new AlertEvent(logger, message, millis, causes, frames, markers, mdc));
```

See [core.md](core.md) for the rest of the programmatic surface.

## One limitation

Because every string surface is comma-separated, an MDC key whose name **contains a comma** is
unreachable from the sysprop, environment, `ntfy.properties`, Logback XML, Log4j2 XML, Spring,
Micronaut and Quarkus spellings. Use the programmatic list form for it:
`NtfyConfig.builder().includeMdcKeys(List.of("odd,key"))`.

## Confirming what is in effect

When the allow-list is non-empty the engine announces it once at startup, alongside its `ACTIVE` and
excluded-loggers lines, so you can confirm which keys are actually being published. It lists key
**names** only and never a value. See [troubleshooting.md](troubleshooting.md) for the wording.

## See also

- [configuration.md](configuration.md) — the full key reference and per-framework examples.
- [filtering.md](filtering.md) — the gates that decide which events alert in the first place.
- [alert-behavior.md](alert-behavior.md) — where the context block sits in the body, and what
  truncation sacrifices first.
- [core.md](core.md) — the programmatic builder and `MdcProjector`.
