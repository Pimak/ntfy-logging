# Troubleshooting

The ntfy engine reports its own health through an injected diagnostics sink — **never** back through
an application logger. This is deliberate: if the engine logged its own diagnostics through the same
pipeline it publishes from, a persistent internal failure could re-trigger the very logger it is
attached to, creating a feedback loop. The sink is a completely separate channel, so it never can.

## Where the diagnostics appear

The channel depends on the adapter:

| Adapter | Channel | How to see it |
|---|---|---|
| **Logback** (raw appender, zero-code auto-install) and **Spring Boot** (via the installed `ntfy-auto` appender) | Logback's `StatusManager` (`addInfo`/`addWarn`/`addError`) | Add a status listener, or dump the status list programmatically (below) |
| **Quarkus** | `System.err`, each line prefixed `[ntfy]` (warnings as `[ntfy] WARN:`, errors as `[ntfy] ERROR:`) | Read the application's console/stderr |

For the Logback/Spring path, the simplest way to surface the status lines is Logback's console
listener:

```xml
<configuration>
  <statusListener class="ch.qos.logback.core.status.OnConsoleStatusListener"/>
  <!-- ... your appenders ... -->
</configuration>
```

or dump them programmatically at any point:

```java
((ch.qos.logback.classic.LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory())
    .getStatusManager()
    .getCopyOfStatusList()
    .forEach(status -> System.out.println(status));
```

## Message reference

Message text is identical across adapters (only the channel differs). `<...>` are substituted at
runtime.

| Message (exact wording) | Level | Meaning | Fix |
|---|---|---|---|
| `ntfy alert engine not configured (url/topic unset) — inactive` | info | Neither `url` nor `topic` is set. Alerting is silently inactive — a normal, supported state. | If you intended alerting, set both `url` and `topic`. Otherwise no action needed. |
| `url set but topic missing — engine disabled` | warn | Exactly one of `url`/`topic` is set — a likely typo or incomplete config. Alerting stays inactive. | Set the missing one (or unset the one you did set, if alerting should stay off here). |
| `both token and username/password configured — token takes precedence` | warn | Both a `token` and a `username`/`password` pair are configured. The token wins for the `Authorization` header; startup still proceeds. | Not an error — remove the unused credentials to eliminate the overlap, or leave it during a migration. See [authentication.md](authentication.md). |
| `suppressionWindow must be positive — falling back to default (3 minutes)` | warn | `suppression-window` is unset, zero, or negative. The engine falls back to the 3-minute default rather than failing. | Set `suppression-window` to a positive duration, or leave it unset to accept the default silently. |
| `ntfy alert engine ACTIVE (url=<url>, topic=<topic>)` | info | The engine started and is live. `<url>` has any embedded userinfo (`user:pass@`) stripped; credentials are never shown. | Informational — confirms the `url`/`topic` in effect. |
| `ntfy alert engine excluded loggers: <p1>, <p2>, …` (or `ntfy alert engine: no excluded loggers configured`) | info | Lists the logger-name prefixes from `excluded-loggers`, emitted once at startup alongside `ACTIVE`. | Informational — confirms which prefixes are excluded. See [filtering.md](filtering.md). |
| `ntfy publish failed for topic '<topic>' (HTTP <status>)` (the HTTP part is omitted for a non-HTTP failure, e.g. a connection error) | warn | A publish attempt (individual or digest) did not succeed. The failure is folded into the suppression count so it surfaces in the next digest rather than being lost. | Check the server/topic and status: 401/403 → auth (see [authentication.md](authentication.md)); 404 → topic/URL wrong; 429 → ntfy is rate-limiting you; 5xx → server-side. |
| `ntfy publish threw unexpectedly` | error (with the causing exception attached) | An unexpected `RuntimeException` occurred during publish. The text is always this fixed string — the real exception is attached separately, but its message is never concatenated in, since it could embed a credential. | Inspect the attached exception's stack trace for the real cause. |

## Common scenarios

**"I configured it but nothing happens."** Look for `ntfy alert engine not configured …` or `url set
but topic missing …` — one of `url`/`topic` is probably missing. On Quarkus, grep stderr for
`[ntfy]`.

**"My Logback zero-code / raw appender alerts on non-errors."** The Logback path does no level
filtering (only Quarkus forwards `SEVERE`+ automatically). Add a `ThresholdFilter` set to `ERROR`, or
scope the appender to an ERROR logger. See [filtering.md](filtering.md).

**"My own logs from `io.github.pimak.ntfy.*` never alert."** That package root is always
self-excluded to prevent feedback loops — rename the logger or see [filtering.md](filtering.md).

**"Alerts stopped during a burst of errors."** Expected storm-resilience (rate limiting), not a
failure — the suppressed count is folded into the next periodic digest. See
[alert-behavior.md](alert-behavior.md).

## See also

- [authentication.md](authentication.md) — auth modes and the token-wins precedence.
- [filtering.md](filtering.md) — `excluded-loggers`, the `NO_ALERT` marker, and self-exclusion.
