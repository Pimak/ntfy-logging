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
| `topic is not a valid ntfy topic name (allowed: A-Z, a-z, 0-9, '-', '_'; max 64 chars) — engine disabled` | warn | The configured `topic` contains characters ntfy itself rejects (or is over 64 chars). The engine refuses activation rather than build request paths from it. The rejected value is deliberately not echoed. | Fix the `topic` to match `[-_A-Za-z0-9]{1,64}`. |
| `credentials configured with a plain http:// URL — the token/password and alert content are sent unencrypted; use https://` | warn | A token or username/password is configured but `url` is `http://` — the `Authorization` header and every alert body travel in cleartext. Activation still proceeds. | Switch the ntfy server URL to `https://`, or accept the risk knowingly (private network). |
| `configured priority/tags value contains non-printable-ASCII characters — the invalid header will be omitted from publishes` | warn | One of `error-priority`/`digest-priority`/`error-tags`/`digest-tags` contains a character outside printable ASCII (e.g. a literal emoji instead of a shortcode). That header is omitted from publishes instead of aborting them. | Use ntfy shortcodes (e.g. `rotating_light`), not literal emoji, and ASCII priority names/numbers. |
| `ntfy: endpoint URL comes ONLY from a classpath ntfy.properties … refusing to auto-install alerting … for supply-chain safety` | warn (Logback auto-install only) | The endpoint URL was supplied only by a `ntfy.properties` found on the classpath (no `NTFY_URL` env var or `ntfy.url` system property), and the `allow-classpath-endpoint` opt-in is not set. Any jar can carry such a file, so the auto-install refuses to activate rather than send your error logs to a destination the classpath chose. | If the file is yours, opt in with `-Dntfy.allow-classpath-endpoint=true` / `NTFY_ALLOW_CLASSPATH_ENDPOINT=true`, or set the URL via env/sysprop. If you don't recognize it, find which dependency ships it — it is trying to redirect your error logs. |
| `ntfy: endpoint URL comes from a classpath ntfy.properties … make sure that file is one you trust` | warn (Logback auto-install only) | The zero-code auto-install activated from a `ntfy.properties` found on the classpath (with the `allow-classpath-endpoint` opt-in set), with no `NTFY_URL` env var or `ntfy.url` system property set. The destination is named loudly. | If the file is yours, no action. If you don't recognize it, find which dependency ships it — it is redirecting your error logs. |

## Common scenarios

**"I configured it but nothing happens."** Look for `ntfy alert engine not configured …` or `url set
but topic missing …` — one of `url`/`topic` is probably missing. On Quarkus, grep stderr for
`[ntfy]`.

**"My Logback appender doesn't alert on WARN."** By design: every adapter gates at ERROR
(`SEVERE` on Quarkus) before submitting, so sub-ERROR log content is never published off-host.
See [filtering.md](filtering.md).

**"My own logs from `io.github.pimak.ntfy.*` never alert."** That package root is always
self-excluded to prevent feedback loops — rename the logger or see [filtering.md](filtering.md).

**"Alerts stopped during a burst of errors."** Expected storm-resilience (rate limiting), not a
failure — the suppressed count is folded into the next periodic digest. See
[alert-behavior.md](alert-behavior.md).

## See also

- [authentication.md](authentication.md) — auth modes and the token-wins precedence.
- [filtering.md](filtering.md) — `excluded-loggers`, the `NO_ALERT` marker, and self-exclusion.
