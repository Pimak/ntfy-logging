# Configuration Reference

Every adapter in the `ntfy-logging` family configures the same framework-neutral engine, so there
is **one set of settings** with the same names, types, and defaults everywhere. What differs is only
*where* you write them:

- **Core / plain Logback** — resolved by `ConfigLoader` from, highest precedence first: a JVM
  system property `ntfy.<key>`, then an environment variable `NTFY_<KEY>`, then a classpath
  `ntfy.properties` entry `ntfy.<key>`. (You can also set them explicitly through
  `LogbackAlertAppender`'s XML setters or `NtfyConfig.builder()`.)
- **Spring Boot** — your application's own config under the `ntfy.*` prefix (`application.yml` /
  `application.properties`, environment, etc.), bound with Spring's relaxed binding.
- **Quarkus** — your application's own config under the `quarkus.ntfy.*` prefix.

> The engine no longer refuses to read the process environment: config is resolved from
> **sysprop > env > `ntfy.properties`** (core/Logback), or from **your framework's native config**
> (Spring `ntfy.*`, Quarkus `quarkus.ntfy.*`). The old "never reads `getenv`" guarantee was a
> property of the single Logback appender; it is intentionally gone.

## Key reference

`Key` is the canonical kebab-case name. Read the per-surface columns as:

- **sysprop** — `-Dntfy.<key>` (core/Logback)
- **env** — `NTFY_<KEY>` with `-` → `_` and upper-cased (core/Logback)
- **`ntfy.properties`** — `ntfy.<key>` (core/Logback)
- **Spring** — `ntfy.<key>` (relaxed: `ntfy.app-name`, `ntfy.appName`, and `NTFY_APP_NAME` all bind)
- **Quarkus** — `quarkus.ntfy.<key>`

| Key | Type | Default | Meaning |
|---|---|---|---|
| `url` | String | *(none — required)* | Base URL of the ntfy server (e.g. `https://ntfy.example.com`). Alerting is inactive until both `url` and `topic` are set. |
| `topic` | String | *(none — required)* | ntfy topic to publish to. Must match ntfy's topic rule `[-_A-Za-z0-9]{1,64}` — anything else disables the engine with a warning. |
| `token` | String | *(none)* | Bearer/access token (`tk_…`). Takes precedence over `username`/`password`. |
| `username` | String | *(none)* | HTTP Basic username (used with `password` when no `token` is set). |
| `password` | String | *(none)* | HTTP Basic password. |
| `title` | String | *(none)* | Notification title prefix; the root-cause exception class is appended for error alerts. Falls back to `app-name` when unset. |
| `app-name` | String | *(none)* | Application name used as the title fallback when `title` is unset. |
| `max-stack-frames` | int | `5` | Maximum root-cause stack frames rendered into an alert body. |
| `connect-timeout` | Duration | `5s` | HTTP connect timeout. |
| `request-timeout` | Duration | `10s` | HTTP request/response round-trip timeout. |
| `max-alerts-per-window` | int | `3` | Individual alerts allowed per `suppression-window` before storm rate-limiting kicks in (`0`/negative disables the limiter). See [alert-behavior.md](alert-behavior.md). |
| `suppression-window` | Duration | `3m` | Rolling window for the burst allowance and the periodic digest timer. |
| `error-priority` | String | `high` | ntfy `Priority` header for individual error alerts. |
| `digest-priority` | String | `urgent` | ntfy `Priority` header for storm digests. |
| `error-tags` | String | `rotating_light` | ntfy `Tags` header (comma-separated) for individual error alerts. |
| `digest-tags` | String | `fire` | ntfy `Tags` header for storm digests. |
| `click-url` | String | *(none)* | URL ntfy opens when the notification is tapped (ntfy `Click` header). Applies to both error alerts and digests; sent as-is (no header when unset). |
| `excluded-loggers` | String (csv) | *(none)* | Comma-separated logger-name prefixes excluded from alerting entirely. See [filtering.md](filtering.md). |
| `enabled` | boolean | `true` | Master switch; when `false` the adapter installs nothing / stays inactive. |

`url` and `topic` are the only two settings without which alerting stays inactive (silently if both
are unset; with a warning if only one is set — see [troubleshooting.md](troubleshooting.md)). Every
other setting has a safe default and can be omitted.

## Duration syntax

`connect-timeout`, `request-timeout`, and `suppression-window` are durations. For the
core/Logback/Quarkus surfaces they are parsed by `DurationParser`, which accepts:

- a **bare integer** — interpreted as **milliseconds** (`500` → 500 ms);
- a **suffixed integer** — `ms`, `s`, `m`, `h`, `d` (`5s`, `3m`, `2h`, `1d`);
- an **ISO-8601** duration — parsed by `java.time.Duration.parse` (`PT5S`, `PT3M`).

An unparseable value throws `IllegalArgumentException` at startup rather than silently defaulting.

In **Spring Boot**, these bind as native `java.time.Duration` values using Spring's own duration
syntax (`5s`, `3m`, `PT5S`, or a bare number of milliseconds), so you get the same spellings through
Spring's converter.

## Per-framework examples

### Core / plain Logback (environment)

```bash
export NTFY_URL=https://ntfy.example.com
export NTFY_TOPIC=my-app-alerts
export NTFY_TOKEN=tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx
export NTFY_APP_NAME=my-app
export NTFY_SUPPRESSION_WINDOW=3m
```

or a classpath `ntfy.properties`:

```properties
ntfy.url=https://ntfy.example.com
ntfy.topic=my-app-alerts
ntfy.app-name=my-app
ntfy.max-alerts-per-window=3
ntfy.suppression-window=3m
```

or explicit Logback XML (setters map JavaBean-style, `set<Foo>` → `<foo>`):

```xml
<appender name="NTFY_ALERT_RAW" class="io.github.pimak.ntfy.logback.LogbackAlertAppender">
  <url>https://ntfy.example.com</url>
  <topic>my-app-alerts</topic>
  <token>tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx</token>
  <appName>my-app</appName>
  <suppressionWindow>3m</suppressionWindow>
</appender>
```

### Spring Boot (`application.yml`)

```yaml
ntfy:
  url: https://ntfy.example.com
  topic: my-app-alerts
  token: tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx
  app-name: my-app
  suppression-window: 3m
  excluded-loggers: org.apache.kafka, com.zaxxer.hikari
```

### Quarkus (`application.properties`)

```properties
quarkus.ntfy.url=https://ntfy.example.com
quarkus.ntfy.topic=my-app-alerts
quarkus.ntfy.token=tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx
quarkus.ntfy.app-name=my-app
quarkus.ntfy.suppression-window=3m
```

## See also

- [authentication.md](authentication.md) — `token` vs `username`/`password` precedence and the
  `None` (unauthenticated) mode.
- [filtering.md](filtering.md) — how `excluded-loggers` combines with the always-on self-exclusion,
  and the Logback-only `NO_ALERT` per-event opt-out.
- [alert-behavior.md](alert-behavior.md) — how the rate-limiting, digest, priority, and tag settings
  combine at runtime.
