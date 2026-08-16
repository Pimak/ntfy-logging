# Level Routing

Alerting is **ERROR-only by default**. Setting `warn-topic` adds a second, independent route for
WARN-level events — their own topic, their own priority and tags, and their own storm budget.

Nothing below WARN can ever alert, on any configuration. There is no setting that lowers the floor
further, and that is structural rather than a matter of validation: the engine's level type has
exactly two constants (`WARN` and `ERROR`), so "route INFO" is not a thing an adapter can express,
now or in a future release.

## The opt-in is a destination, not a flag

```yaml
# Spring / Micronaut application.yml
ntfy:
  url: https://ntfy.example.com
  topic: my-app-alerts
  warn-topic: my-app-warnings     # ← this line is the entire opt-in
```

There is deliberately no `alert-on-warn: true` beside it. A route needs somewhere to go, so naming
the destination is the only thing the engine has to be told — and it means you cannot half-enable
the feature by setting a boolean and forgetting the topic.

Leave `warn-topic` unset and **everything is byte-identical to before the option existed**: the same
gates, the same alert bodies, the same startup diagnostics, the same number of HTTP requests.

To alert on warnings without running a second channel, point `warn-topic` at the main topic:

```yaml
ntfy:
  topic: my-app-alerts
  warn-topic: my-app-alerts       # one channel, but warnings still arrive quieter
  warn-priority: min
```

The level still decides the priority and tags, so warnings stay distinguishable from errors in the
ntfy client even though both land on one topic.

## What each level does

| Level (Logback / Log4j2 / JUL) | Without `warn-topic` | With `warn-topic` |
|---|---|---|
| `ERROR` / `FATAL` / `SEVERE` and above | alerts on `topic` | alerts on `topic` |
| `WARN` / `WARNING` | dropped | alerts on `warn-topic` |
| `INFO` and below | dropped | dropped |

Styling defaults are chosen so a warning never looks or feels like a page:

| | Priority | Tags |
|---|---|---|
| Error alert | `high` | `rotating_light` 🚨 |
| Error digest | `urgent` | `fire` 🔥 |
| **Warn alert** | `default` | `warning` ⚠️ |
| **Warn digest** | `default` | `warning` ⚠️ |

## The two routes never share a storm budget

Each route gets its own rate limiter, both sized by the same `max-alerts-per-window` and
`suppression-window`. A flood of warnings draws down the *warn* allowance and leaves the error
allowance untouched.

This is the reason level routing is a second route rather than a lowered threshold. With one shared
budget, a service logging warnings in a loop would spend the allowance and push genuine errors into
a digest — the precise failure the ERROR-only floor existed to prevent. Opening the WARN gate must
not make error alerting *worse*, so the budgets are separate.

Each route also digests separately, on its own topic and in its own words ("N errors suppressed" vs
"N warnings suppressed"). One scheduler drives both: they share the same suppression window, so a
second timer would buy nothing but another thread.

### Why the warn digest is not `urgent`

The error digest escalates to `urgent`/🔥 on purpose — a storm of errors is worse than one error.
The warn digest deliberately does **not**: escalating there would undo the whole point of routing
warnings to a quiet channel, and it would do so at the worst possible moment, when a burst of
warnings is exactly the thing you did not want paging you. On the warn route a digest is told apart
from an individual alert by its title and body, not by how loudly the phone rings.

If you do want a distinct look for warn digests, open an issue — `warn-digest-priority` /
`warn-digest-tags` would slot in without breaking anything.

## Two floors on Log4j2

On Log4j2 the level is enforced twice, and the first one is the one that matters:

1. **The attach level.** `NtfyLog4j2Installer`, the reattach listener, and the Spring starter's
   Log4j2 backend attach the appender at `WARN` when a warn route is active and at `ERROR`
   otherwise. An appender attached at `ERROR` is never handed a WARN event at all.
2. **The appender's own gate**, so a direct `append()` call or a hand-written `<AppenderRef>` cannot
   bypass the contract.

Both derive from one accessor on the appender, so a reconfiguration can never re-attach at a
different floor than the original install used.

If you declare `<Ntfy warnTopic="…">` in your own `log4j2.xml` and reference it from a
`<Logger>`/`<Root>` element, remember that log4j2's own `level` on that element applies first — set
it to `warn` or lower, or warnings never reach the appender.

## When the route is withdrawn

Two conditions drop the WARN route while **leaving ERROR alerting fully active**. A mistyped
optional extra must never cost you your error alerts, which is why neither refuses activation the
way an invalid `topic` does:

- **`warn-topic` is not a valid ntfy topic name** (`[-_A-Za-z0-9]{1,64}`). Diagnostic:
  `warn-topic is not a valid ntfy topic name … WARN routing disabled, ERROR alerting unaffected`.
- **`warn-topic` came only from a classpath `ntfy.properties`** — no `NTFY_WARN_TOPIC` environment
  variable and no `ntfy.warn-topic` system property — and `allow-classpath-endpoint` is not set.

The second guard exists because `warn-topic` **names a destination**, which puts it on the same
footing as `url`: any jar on your classpath can ship a `ntfy.properties`, and one that silently adds
a warn route both increases how much log content leaves the host and sends it somewhere you did not
choose. (Contrast `include-mdc-keys`, which is read from all three configuration layers precisely
because it cannot redirect where alerts go.) Opt in with `-Dntfy.allow-classpath-endpoint=true` or
`NTFY_ALLOW_CLASSPATH_ENDPOINT=true` if the file is yours, or set the topic yourself through the
environment. Values you write explicitly — a `<warnTopic>` element, a `warnTopic="…"` attribute,
`application.yml` — never pass through this guard at all.

When the route *is* active, the engine says so once at startup, naming the destination:

```
ntfy alert engine: WARN events are routed to topic 'my-app-warnings'
```

Absent that line, the route is not active — check it first when warnings are not arriving.

## Where the key lives on each surface

Same names, types and defaults everywhere, as with every other setting:

| Surface | How to set it |
|---|---|
| Core / JUL / plain Logback | `-Dntfy.warn-topic=…`, `NTFY_WARN_TOPIC=…`, or `ntfy.warn-topic=…` in `ntfy.properties`; XML `<warnTopic>…</warnTopic>`; `NtfyConfig.builder().warnTopic(…)` |
| Plain Log4j2 | the same ambient chain, or the attribute `<Ntfy warnTopic="…" …/>` |
| Spring Boot | `ntfy.warn-topic: …` |
| Micronaut | `ntfy.warn-topic: …` |
| Quarkus | `quarkus.ntfy.warn-topic=…` |

## See also

- [configuration.md](configuration.md) — the full key table, including `warn-priority`/`warn-tags`.
- [filtering.md](filtering.md) — `excluded-loggers`, the `NO_ALERT` marker, and the always-on
  self-exclusion, all of which apply to both routes.
- [alert-behavior.md](alert-behavior.md) — rate limiting, digests, and truncation.
- [troubleshooting.md](troubleshooting.md) — the diagnostics quoted above.
