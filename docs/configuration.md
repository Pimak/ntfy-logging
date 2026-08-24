# Configuration Reference

Every adapter in the `ntfy-logging` family configures the same framework-neutral engine, so there
is **one set of settings** with the same names, types, and defaults everywhere. What differs is only
*where* you write them:

- **Core / JUL / plain Logback / plain Log4j2** — resolved by `ConfigLoader` from, highest
  precedence first: a JVM system property `ntfy.<key>`, then an environment variable `NTFY_<KEY>`,
  then a classpath `ntfy.properties` entry `ntfy.<key>`. (You can also set them explicitly through
  `LogbackAlertAppender`'s XML setters, the Log4j2 `<Ntfy>` element's attributes, or
  `NtfyConfig.builder()` — the latter feeds `NtfyJulHandler.forConfig` on the JUL side.)
- **Spring Boot** — your application's own config under the `ntfy.*` prefix (`application.yml` /
  `application.properties`, environment, etc.), bound with Spring's relaxed binding.
- **Micronaut** — your application's own config under the same `ntfy.*` prefix (`application.yml` /
  `application.properties`, environment, etc.), bound with Micronaut's relaxed binding. Same key
  names, types and defaults as the Spring surface.
- **Quarkus** — your application's own config under the `quarkus.ntfy.*` prefix.

> The engine no longer refuses to read the process environment: config is resolved from
> **sysprop > env > `ntfy.properties`** (core/JUL/Logback/Log4j2), or from **your framework's native config**
> (Spring / Micronaut `ntfy.*`, Quarkus `quarkus.ntfy.*`). The old "never reads `getenv`" guarantee was a
> property of the single Logback appender; it is intentionally gone.

## Key reference

`Key` is the canonical kebab-case name. Read the per-surface columns as:

- **sysprop** — `-Dntfy.<key>` (core/JUL/Logback/Log4j2)
- **env** — `NTFY_<KEY>` with `-` → `_` and upper-cased (core/JUL/Logback/Log4j2)
- **`ntfy.properties`** — `ntfy.<key>` (core/JUL/Logback/Log4j2)
- **Logback XML / Log4j2 XML** — the camelCase form of the key: a `<appName>` element on
  `LogbackAlertAppender`, an `appName="…"` attribute on `<Ntfy>`
- **Spring** — `ntfy.<key>` (relaxed: `ntfy.app-name`, `ntfy.appName`, and `NTFY_APP_NAME` all bind)
- **Micronaut** — `ntfy.<key>` (relaxed the same way: `ntfy.app-name`, `ntfy.appName` and
  `NTFY_APP_NAME` all bind)
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
| `warn-topic` | String | *(none)* | ntfy topic that WARN-level events are published to. **Setting this is the entire opt-in for WARN alerting** — unset, alerting stays ERROR-only and every path is byte-identical to before the option existed. Same topic rule as `topic`; an invalid value drops the WARN route with a warning and leaves ERROR alerting untouched. See [level-routing.md](level-routing.md). |
| `warn-priority` | String | `default` | ntfy `Priority` header for WARN alerts **and the WARN digest**. Only consulted when `warn-topic` is set. |
| `warn-tags` | String | `warning` | ntfy `Tags` header for WARN alerts and the WARN digest. Only consulted when `warn-topic` is set. |
| `click-url` | String | *(none)* | URL ntfy opens when the notification is tapped (ntfy `Click` header). Applies to both error alerts and digests; sent as-is (no header when unset). |
| `actions` | String | *(none)* | Action buttons as a raw ntfy `Actions` header value in the short format (e.g. `view, View logs, https://grafana.example.com/d/abc`; up to 3, separated by `;`). Applies to both error alerts and digests; sent as-is (no header when unset). Programmatic core users can instead build typed `NtfyAction`s via `NtfyConfig.Builder.actions(List)` / `NtfyClient.notify(title, message, actions)`. |
| `excluded-loggers` | String (csv) | *(none)* | Comma-separated logger-name prefixes excluded from alerting entirely. See [filtering.md](filtering.md). |
| `excluded-exception-types` | String (csv) | *(none)* | Comma-separated fully qualified exception class names. An event whose cause chain contains any of them never alerts — matched anywhere in the chain, and matched whole rather than by prefix. See [filtering.md](filtering.md). |
| `cache` | boolean | `true` | `false` sends `Cache: no` — the server stores nothing, so only subscribers connected at that moment ever receive the message. See [Delivery privacy](#delivery-privacy). |
| `firebase` | boolean | `true` | `false` sends `Firebase: no` — the server does not forward the message to Firebase Cloud Messaging. See [Delivery privacy](#delivery-privacy). |
| `icon` | String | *(none)* | URL of the icon shown beside the notification (Android subscribers only). `http`/`https`; checked at startup, and a value that is not a fetchable URL is dropped with a diagnostic while alerting continues. See [Icons](#icons). |
| `icons-by-logger` | String (csv) | *(none)* | Per-logger icon overrides as `prefix=url` pairs. The longest prefix matching an alert's logger wins; unmatched loggers keep `icon`. Alerts only. See [Icons](#icons). |
| `include-mdc-keys` | String (csv) | *(none)* | Comma-separated **allow-list** of MDC keys whose values are rendered into the alert body, one `key: value` line each, in the order listed. Opt-in and explicit: there is no wildcard, so no MDC value is ever published unless you name its key. Unset ⇒ bodies are byte-identical to before. Bounded by hard guards (16 keys, 256 chars per value, 1024 chars total). Sourced per adapter: the Logback event's MDC snapshot, log4j2's `ThreadContext`, and `ExtLogRecord` under JBoss LogManager/Quarkus. No effect on plain `java.util.logging`, which has no MDC. See [mdc-context.md](mdc-context.md). |
| `locale` | String (BCP 47 tag) | `en` | Language of notification bodies and self-diagnostic messages (e.g. `fr`, `de-DE`). Defaults to English and **never** follows the host JVM's default locale, so alert language is deterministic. An unknown/unshipped locale silently falls back to English. See [Notification language](#notification-language-translations). |
| `enabled` | boolean | `true` | Master switch; when `false` the adapter installs nothing / stays inactive. |
| `allow-classpath-endpoint` | boolean | `false` | Opt-in for the zero-code auto-installs (Logback `Configurator`, JUL auto-handler/installer) and `NtfyLog4j2Installer` to accept an endpoint `url` that comes **only** from a classpath `ntfy.properties`. Without it, auto-install is refused (with a warn status) because any jar on the classpath can ship such a file and redirect your error logs. Deliberately **not** readable from `ntfy.properties` itself — set it as `-Dntfy.allow-classpath-endpoint=true` or `NTFY_ALLOW_CLASSPATH_ENDPOINT=true`. |
| `async` | boolean | `true` | Delivery is offloaded to a bounded queue drained by a daemon worker, so a slow/unreachable ntfy server never blocks application threads. On by default since 2.0; set `false` for pre-2.0 synchronous, inline delivery (each publish then blocks the logging thread until the HTTP exchange completes — the guarantee short-lived batch/CLI processes may prefer). See [alert-behavior.md](alert-behavior.md). |
| `async-queue-capacity` | int | `1024` | Maximum pending alerts the async queue holds before overflow (dropped alerts fold into the storm digest count). Only consulted when `async` is `true`; a non-positive value is clamped to a minimum of `1`. |
| `startup-ping` | enum (`off`/`probe`/`publish`) | `off` | **Opt-in startup self-test.** Verifies at boot that the configured endpoint, credentials and topic actually work, instead of discovering a revoked token when the first real error alert silently fails to deliver. `off` is a true no-op — no request, and no extra diagnostic line, so output stays byte-identical. `probe` makes a read-only `GET {url}/{topic}/json?poll=1&since=none`: it validates DNS, reachability, TLS and *read* access, and publishes nothing, so subscribers see no noise. `publish` sends a real `low`-priority test notification through the production publish path, which is the only mode that proves alerts are actually deliverable. Runs asynchronously and never delays startup (unless `startup-ping-fail-fast` is on). An unrecognized value keeps the default and is warned about. See [Startup self-test](#startup-self-test). |
| `startup-ping-warn` | enum (`off`/`probe`/`publish`) | `off` | The same self-test for the optional **WARN route** (`warn-topic`), configured completely independently of `startup-ping`. ntfy grants permissions per topic, so a healthy ERROR route is no evidence at all about the WARN one — and in `publish` mode each route costs its own notification on every boot, which is why neither flag opts the other in. Only consulted when `warn-topic` is set and its route survived its own preconditions; testing a route the engine has withdrawn would report on something that will never carry an alert. |
| `startup-ping-notify-failures` | boolean | `true` | **The one opt-out here.** When a route's self-test fails and another route PASSED, the diagnosis is published as a notification on the passing route — so a broken route reaches you where you actually look instead of only in a status log nobody greps. It can never fire unless some route passed, which is what stops it from publishing to a topic the self-test has not just verified: with the WARN self-test off, nothing about that route is tested, nothing fails, and your ERROR topic is never pinged. See the caveat under [Startup self-test](#startup-self-test) about what this means for `probe`. |
| `startup-ping-fail-fast` | boolean | `false` | Whether a failed self-test **aborts application startup** (throwing `NtfyStartupSelfTestException` out of engine start) rather than only warning. Off by default: a logging backend must never be able to kill the application it instruments. Enabling it also switches the self-test from background to **inline** execution — a start that has already returned cannot be aborted — which adds at most `connect-timeout + request-timeout` to boot. Intended for CI/staging; leaving it off in production means a flaky ntfy server can never block a deployment. Covers **every route whose self-test you enabled** — a route you explicitly asked to have verified is one whose failure gates the deploy. Inert (and warned about) when both mode flags are `off`. |
| `require-https-for-credentials` | boolean | `true` | Strict transport mode, available on every surface like any other key (Logback XML `<requireHttpsForCredentials>`, Log4j2 `<Ntfy requireHttpsForCredentials="false">`, Spring and Micronaut `ntfy.require-https-for-credentials`, Quarkus `quarkus.ntfy.require-https-for-credentials`, env `NTFY_REQUIRE_HTTPS_FOR_CREDENTIALS`, sysprop `ntfy.require-https-for-credentials`). When `true` (the default since 2.0) and credentials would traverse a cleartext `http://` endpoint — a configured `token`, a `username`/`password` pair, or userinfo embedded in the URL itself (`http://user:pass@host`) — the engine refuses activation with a fixed diagnostic instead of warning and proceeding. Set `false` to restore the pre-2.0 warn-and-activate behavior for deliberate self-hosted plain-HTTP setups. See [authentication.md](authentication.md). |

`url` and `topic` are the only two settings without which alerting stays inactive (silently if both
are unset; with a warning if only one is set — see [troubleshooting.md](troubleshooting.md)). Every
other setting has a safe default and can be omitted.

`warn-topic` is the one setting whose *presence* changes which events alert at all. There is
deliberately no `alert-on-warn` boolean beside it: a route needs a destination, so naming the
destination is the only thing the engine actually has to be told.

## Delivery privacy

Two keys control what the ntfy **server** is allowed to do with a message once it has accepted it.
Both default to ntfy's own behaviour, and both are only ever sent as an opt-out — leaving them on
puts no header on the wire at all.

### `cache`

By default an ntfy server keeps messages on disk for 12 hours, so a subscriber whose phone was in a
tunnel still gets them on reconnect. `cache: false` sends `Cache: no` and the server stores nothing.

What it costs is not subtle: the message reaches **connected subscribers only**. A subscriber with a
momentary network problem never receives it at all, and `since=`/`poll=1` stop returning it. For an
alerting tool that is a real trade — you are choosing privacy over delivery to anyone who was not
listening at the time.

It does not change what the [startup self-test](#startup-self-test) proves. That test reads the
server's response, not a subscriber's; a publish under `Cache: no` still demonstrates the
endpoint, the credentials, the topic and — unlike a probe — write permission.

### `firebase`

An ntfy server can be configured to deliver to Android through Firebase Cloud Messaging, which is
how it keeps the app's battery footprint small. **ntfy.sh is configured this way**, so on the default
endpoint every alert this library publishes — stack traces, logger names and allow-listed MDC values
included — also transits Google's infrastructure. `firebase: false` sends `Firebase: no` and the
server keeps it to itself.

The cost is bounded and known: on the Google Play Android client with instant delivery switched off,
messages can arrive up to 15 minutes late. Turning instant delivery on removes the delay. On a
self-hosted server with no FCM configured the header does nothing at all.

### Why two keys and not one

They answer the same worry and have nothing else in common. Disabling the cache costs you offline
subscribers; disabling Firebase costs you push latency on one platform. A single "private delivery"
switch would also make a perfectly ordinary setup unreachable — a self-hosted server with no FCM
configured wants `firebase: false` **and** `cache: true`, so alerts still reach a phone that was
offline.

## Icons

`icon` puts your application's logo beside every notification. `icons-by-logger` lets that logo
depend on which part of the application failed:

```yaml
ntfy:
  icon: https://cdn.example.com/acme.png
  icons-by-logger: com.acme.billing=https://cdn.example.com/billing.png, com.acme.shipping=https://cdn.example.com/shipping.png
```

An alert from `com.acme.billing.Ledger` carries the billing icon; one from anywhere else carries
`acme.png`. Matching is on logger-hierarchy boundaries, so `com.acme.billingsystem` is *not* a match
for `com.acme.billing`, and when several prefixes match the longest one wins.

Two things it deliberately does not do:

- **A notification never uses the table.** A notification you publish yourself through `NtfyClient`
  has no logger to match on, so it always carries `icon`. Neither does a storm digest, which covers
  many loggers at once and cannot honestly claim any one of their icons.
- **There is no interpolation.** You cannot build an icon URL out of the logger name or anything
  else from the running application. That is a security boundary, not a missing feature: the
  subscriber's client downloads the icon automatically when it *displays* the notification — no tap
  required — so an interpolated URL would send whatever it interpolated, plus the subscriber's IP,
  to a third-party host on every single notification. A table only ever selects among URLs you wrote
  out in full.

### Validation

Both keys are checked at startup by the engine, because an icon is the one setting here
that fails invisibly: the ntfy server never fetches the URL, the subscriber's client does,
so a wrong one produces no error and no diagnostic — just a notification with no icon,
forever.

What is checked is that the value is a URL a client could fetch: `http` or `https`, with a
host. A value that is not is dropped with a diagnostic and alerting continues — decoration
never stops the paging. In the table, a bad entry drops itself and leaves the rest working,
and a pair mistyped with a space instead of an `=` is reported too, since swallowing it
would leave it looking exactly like an unset key.

The image **format** is deliberately not checked. ntfy renders JPEG and PNG only, but that
is a property of the bytes served, not of how the path is spelt: plenty of perfectly good
icon URLs carry no extension at all. What you do get is a warning — not a rejection — when
an extension names a format ntfy is documented not to render, `.svg` and `.gif` among them.

!!! note "Two limits worth knowing"

    ntfy documents icons as **supported on Android only**. iOS and web subscribers see no
    icon whatever you configure.

    Notifications published through `NtfyClient` outside an engine get no startup at all,
    and so no diagnostic: an unfetchable `icon` is dropped there silently.

## Duration syntax

`connect-timeout`, `request-timeout`, and `suppression-window` are durations. For the
core/JUL/Logback/Log4j2/Quarkus surfaces they are parsed by `DurationParser`, which accepts:

- a **bare integer** — interpreted as **milliseconds** (`500` → 500 ms);
- a **suffixed integer** — `ms`, `s`, `m`, `h`, `d` (`5s`, `3m`, `2h`, `1d`);
- an **ISO-8601** duration — parsed by `java.time.Duration.parse` (`PT5S`, `PT3M`).

An unparseable value throws `IllegalArgumentException` at startup rather than silently defaulting.

In **Spring Boot**, these bind as native `java.time.Duration` values using Spring's own duration
syntax (`5s`, `3m`, `PT5S`, or a bare number of milliseconds), so you get the same spellings through
Spring's converter.

In **Micronaut** they likewise bind as native `java.time.Duration` values through Micronaut's own
converter (`5s`, `3m`, `500ms`, `PT10S`), and the integration hands them to the appender in
milliseconds — so the same spellings work there too.

## Startup self-test

Alerting is silent by design: it only speaks when something breaks. That makes a broken *alerting*
configuration invisible — a revoked token, a renamed topic, a typo'd base URL and a blocked egress
route all behave identically at boot (nothing happens) and are only discovered when the first real
production error fails to deliver and nobody is paged.

The engine's start-up validation is entirely local: URL syntax, topic charset, auth-pair
completeness, transport policy, timeouts. It never touches the network, so it cannot tell a working
setup apart from one whose token was revoked last week.

`startup-ping` closes that gap by making one real round-trip at engine start:

```properties
ntfy.startup-ping=probe
```

| Mode | Request | Publishes? | Proves |
|---|---|---|---|
| `off` *(default)* | none | no | nothing — a true no-op, not even a diagnostic line |
| `probe` | `GET {url}/{topic}/json?poll=1&since=none` | no\* | DNS, reachability, TLS, that the endpoint is really ntfy, and *read* access |
| `publish` | the production `POST {url}/{topic}` | yes, one `low`-priority notification per boot | that alerts are genuinely deliverable end-to-end |

On success you get one `info` line. On failure you get one `warn` line that names the probable cause
and the fix rather than making you look the status code up:

```
ntfy startup self-test FAILED (HTTP 401) — the server rejected the credentials; the token may be
revoked or expired, or it may not grant access to this topic
```

`404` points at the most common ntfy mistake (appending the topic to `url`, which must be the base
URL), `429` and `5xx` explicitly absolve your configuration and blame the server, and any failure
that never got an HTTP response at all — a refused connection, DNS, a timeout, **or a TLS handshake
/ certificate-trust failure** — points at `url`, DNS, TLS trust and egress rules, with the reason
word naming which (`SSLHandshakeException` for an untrusted certificate, say). No diagnostic ever
echoes a token, password or username.

\* `probe` publishes nothing **of its own**. With `startup-ping-notify-failures` left on (the
default), a *different* route failing can still cause exactly one publish — the failure report, sent
through this healthy route. Set `startup-ping-notify-failures=false` to keep `probe` absolutely
publish-free.

### Testing the WARN route

`startup-ping` covers the primary topic. The optional [WARN route](level-routing.md) has its own
flag, configured completely independently:

```properties
ntfy.warn-topic=my-app-warnings
ntfy.startup-ping=publish
ntfy.startup-ping-warn=publish
```

They are separate because ntfy grants permissions **per topic** — a token that publishes fine to
your ERROR topic may have no write access to the WARN one, which is exactly the blind spot a
self-test is for — and because in `publish` mode each route costs its own notification on every
boot. Neither flag opts the other in, and both default to `off`.

The WARN self-test is skipped when the engine has *withdrawn* the warn route (an invalid
`warn-topic`, or a classpath-only one without `allow-classpath-endpoint`). There is no point
verifying a route that will never carry an alert; the withdrawal itself is already reported.

### When one route breaks and another works

If a route's self-test fails while another **passed**, the diagnosis is published as a notification
on the passing route:

```
[my-app — ntfy startup self-test FAILED]
A startup self-test failed for this application's ntfy alerting. This notification was sent
through a route that still works, so the route named below is the one that is broken:

WARN route (topic 'my-app-warnings') — ntfy startup self-test FAILED (HTTP 403) — the server
rejected the credentials; the token may be revoked or expired, or it may not grant access to
this topic
```

This is on by default (`startup-ping-notify-failures`), because a diagnostic line lands on a status
manager or stderr that nobody reads until they are already investigating, while the entire point of
alerting is to reach people.

It is gated on a route having **passed**, never merely being configured. That single rule is what
guarantees it can never publish to a topic the self-test has not just verified — with the WARN
self-test off, nothing about that route is tested, so nothing about it can fail, and your ERROR
topic is never pinged. If *no* route passed, there is no channel worth trying and the diagnostics
are the only report (the engine says so rather than staying silent).

### Which mode should I use?

**`publish`, if you can tolerate one notification per boot.** ntfy ACLs grant read and write
separately, so `probe` has a real blind spot: a read-only token — or one revoked for writes only —
passes the probe and still fails every real alert. And on an open server, where reads are anonymous,
a probe proves nothing whatsoever about credentials. `probe` answers *"can I reach this server?"*;
only `publish` answers *"will my alerts actually arrive?"*.

Use `probe` when publishing on every boot would be too noisy for the topic's subscribers — a
frequently-restarting service, or a topic real humans watch.

### Fail-fast

By default a failed self-test only warns: a logging backend must never be able to take down the
application it instruments.

```properties
ntfy.startup-ping=probe
ntfy.startup-ping-fail-fast=true
```

turns a failed self-test into a failed startup (`NtfyStartupSelfTestException` out of engine start,
after every resource the engine had acquired is released). It covers **every route whose self-test
you enabled**: a route you explicitly asked to have verified is one whose failure should gate the
deploy. The failure notification, if enabled, still goes out before the abort — aborting must not
cost you the report.

Two consequences worth knowing before you enable it:

- **It makes the self-test synchronous.** A start that has already returned cannot be aborted, so
  fail-fast runs the round-trip inline and adds up to `connect-timeout + request-timeout` (15s with
  the defaults) to boot. Without fail-fast the ping runs on a daemon thread and startup is never
  delayed.
- **It couples your deployment to ntfy's availability.** A `5xx` or a network blip on the ntfy side
  will block a rollout. That is usually the wrong trade in production and the right one in CI or
  staging, which is what this flag is for.

## Notification language (translations)

Every visible string the engine produces — the body labels (`Message:`, `Logger:`, `Caused by:`,
`Time:`), the storm-digest title/body, and the self-diagnostic status/warning lines — can be
rendered in a language other than English. Select it with the `locale` setting, spelled the same way
as every other key on each surface:

| Surface | How to set it |
|---|---|
| Core / JUL / plain Logback | `-Dntfy.locale=fr`, `NTFY_LOCALE=fr`, or `ntfy.locale=fr` in `ntfy.properties`; XML `<locale>fr</locale>`; `NtfyConfig.builder().locale("fr")` (or `.locale(Locale.FRENCH)`) |
| Plain Log4j2 | the same ambient chain (`-Dntfy.locale=fr` / `NTFY_LOCALE=fr` / `ntfy.properties`), or the attribute `<Ntfy locale="fr" …/>` |
| Spring Boot | `ntfy.locale: fr` |
| Micronaut | `ntfy.locale: fr` |
| Quarkus | `quarkus.ntfy.locale=fr` |

**Semantics**

- **Default is `en`.** The language is deterministic and, deliberately, never inherits
  `Locale.getDefault()` from the host JVM.
- **Fallback is per-key.** A locale with no shipped bundle falls back wholesale to English; a partial
  translation falls back to English for each individual missing key. An unknown/garbage tag keeps
  English rather than throwing.
- **Credentials are never translated in.** The fixed status/warning strings take no arguments at all,
  and the composed lines (ACTIVE line, publish-failure line, digest) keep their URL/credential
  scrubbing in Java — a translation may move the already-safe `{0}`/`{1}` placeholders around in the
  text but must keep exactly that placeholder set, never adding or removing one. This is enforced by
  `AlertMessagesBundleSafetyTest`.

**Shipped languages:** English (`en`, base) and French (`fr`).

**Contributing a translation**

1. Copy `ntfy-core/src/main/resources/io/github/pimak/ntfy/core/AlertMessages.properties` to
   `AlertMessages_<lang>.properties` (e.g. `AlertMessages_de.properties`) and translate every value.
2. In the **MessageFormat pattern keys** (`status.active`, `publish.failed.*`,
   `status.exclusions.list`, `status.includeMdcKeys.list`, `digest.*`, `window.*`) **double every
   literal single quote** (`''`) —
   French `l'`/`d'` need this — and keep the same `{0}`/`{1}` argument set. **Never** add a `{`
   placeholder to a no-placeholder key (the labels and fixed status strings); the safety test fails
   the build if you do.
3. Add the locale to `ntfy-core/.../META-INF/native-image/.../resource-config.json` (`bundles[0].locales`)
   so GraalVM native images ship the bundle — otherwise native builds silently keep only English.
4. Extend `AlertMessagesBundleSafetyTest.TRANSLATION_RESOURCES` with the new file so it is checked.

*Limitation:* `MessageFormat` has no CLDR plural categories, so the "N errors suppressed" phrasing
uses one form (with a simple singular/plural split only for the window unit). This is acceptable for
diagnostic strings; languages with richer plural rules (Polish, Russian, Arabic) can only approximate
it.

## Per-framework examples

Pick your stack once: the choice is remembered on your next visit, and the three framework tabs —
Spring Boot, Micronaut, Quarkus — carry over to the
[metrics bindings](observability.md#metrics-bindings), the other tabbed section on this site.

=== "Core / JUL"

    ```bash
    export NTFY_URL=https://ntfy.example.com
    export NTFY_TOPIC=my-app-alerts
    export NTFY_TOKEN=tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx
    export NTFY_APP_NAME=my-app
    export NTFY_SUPPRESSION_WINDOW=3m
    export NTFY_STARTUP_PING=probe
    ```

    or a classpath `ntfy.properties`:

    ```properties
    ntfy.url=https://ntfy.example.com
    ntfy.topic=my-app-alerts
    ntfy.app-name=my-app
    ntfy.max-alerts-per-window=3
    ntfy.suppression-window=3m
    ntfy.include-mdc-keys=correlation-id,tenant
    ntfy.warn-topic=my-app-warnings
    ntfy.startup-ping=probe
    ```

    Plain Logback and plain Log4j2 read these same two sources, so an environment or
    `ntfy.properties` setup carries over to them unchanged.

=== "Logback XML"

    Environment variables and a classpath `ntfy.properties` work here too (see the **Core / JUL**
    tab). Explicit XML overrides them; setters map JavaBean-style, `set<Foo>` → `<foo>`:

    ```xml
    <appender name="NTFY_ALERT_RAW" class="io.github.pimak.ntfy.logback.LogbackAlertAppender">
      <url>https://ntfy.example.com</url>
      <topic>my-app-alerts</topic>
      <token>tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx</token>
      <appName>my-app</appName>
      <suppressionWindow>3m</suppressionWindow>
      <includeMdcKeys>correlation-id,tenant</includeMdcKeys>
      <warnTopic>my-app-warnings</warnTopic>
      <startupPing>probe</startupPing>
    </appender>
    ```

=== "Log4j2 XML"

    The `<Ntfy>` element takes the same roster of settings as attributes, in the same camelCase
    spelling; unset attributes keep the engine defaults. See [log4j2.md](log4j2.md).

    ```xml
    <Appenders>
      <Ntfy name="ntfy"
            url="https://ntfy.example.com"
            topic="my-app-alerts"
            token="tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx"
            appName="my-app"
            suppressionWindow="3m"
            includeMdcKeys="correlation-id,tenant"
            warnTopic="my-app-warnings"
            startupPing="probe"/>
    </Appenders>
    ```

=== "Spring Boot"

    In `application.yml`:

    ```yaml
    ntfy:
      url: https://ntfy.example.com
      topic: my-app-alerts
      token: tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx
      app-name: my-app
      suppression-window: 3m
      excluded-loggers: org.apache.kafka, com.zaxxer.hikari
      include-mdc-keys: correlation-id, tenant
      warn-topic: my-app-warnings
      startup-ping: probe
    ```

=== "Micronaut"

    Same prefix and same spellings as Spring Boot — an `ntfy.*` block moves between the two
    unchanged. In `application.yml`; see [micronaut.md](micronaut.md).

    ```yaml
    ntfy:
      url: https://ntfy.example.com
      topic: my-app-alerts
      token: tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx
      app-name: my-app
      suppression-window: 3m
      excluded-loggers: io.micronaut.http.server, com.zaxxer.hikari
      include-mdc-keys: correlation-id, tenant
      warn-topic: my-app-warnings
      startup-ping: probe
    ```

=== "Quarkus"

    In `application.properties`:

    ```properties
    quarkus.ntfy.url=https://ntfy.example.com
    quarkus.ntfy.topic=my-app-alerts
    quarkus.ntfy.token=tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx
    quarkus.ntfy.app-name=my-app
    quarkus.ntfy.suppression-window=3m
    quarkus.ntfy.include-mdc-keys=correlation-id,tenant
    quarkus.ntfy.warn-topic=my-app-warnings
    quarkus.ntfy.startup-ping=probe
    ```

## See also

- [authentication.md](authentication.md) — `token` vs `username`/`password` precedence and the
  `None` (unauthenticated) mode.
- [level-routing.md](level-routing.md) — the `warn-topic` opt-in: which levels alert, where their
  alerts go, and why the two routes keep separate storm budgets.
- [filtering.md](filtering.md) — how `excluded-loggers` and `excluded-exception-types` combine
  with the always-on self-exclusion
  and the `NO_ALERT` per-event opt-out (Logback and Log4j2).
- [mdc-context.md](mdc-context.md) — the `include-mdc-keys` allow-list: which MDC values are
  attached to the alerts that do get through, and the guards on them.
- [alert-behavior.md](alert-behavior.md) — how the rate-limiting, digest, priority, and tag settings
  combine at runtime.
