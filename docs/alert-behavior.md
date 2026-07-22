# Alert Behavior

This page explains *why* the ntfy engine behaves the way it does once your application starts logging
ERRORs — what gets published immediately, what gets suppressed, and how suppressed alerts are never
silently lost. The behavior is identical across every adapter (Logback, Spring Boot, Quarkus, or the
programmatic client), because they all drive the same `AlertEngine`. For the settings referenced
below, see [configuration.md](configuration.md); for the diagnostics the engine emits, see
[troubleshooting.md](troubleshooting.md).

## An isolated error is published immediately

The very first error seen in a suppression window is published right away, as an individual ntfy
notification, at `error-priority` (default `high`) with `error-tags` (default `rotating_light`,
rendered by ntfy as a 🚨). There is no batching delay for a lone error — you want to know about it as
soon as it happens.

## Storm rate-limiting

If errors keep arriving, the engine does not publish one notification per error indefinitely — that
would flood both ntfy and whoever receives the push notifications during an actual incident, which is
exactly when you can least afford to be flooded. Instead, a token-bucket-style limiter allows at most
`max-alerts-per-window` individual alerts (default **3**) per `suppression-window` (default **3
minutes**). The first few errors in a window get through as full individual notifications with real
error content; any error beyond the allowance is counted rather than published.

Rate limiting is **always on** by default — configuring only `url` and `topic` still protects you
against a runaway error loop out of the box. Setting `max-alerts-per-window` to `0` (or negative)
disables the limiter and reverts to publish-everything behavior.

The limiter's scope is global to the engine instance: a single shared counter, not one per logger.
When a new window opens the allowance fully refills, so a sustained storm still lets a few fresh
individual alerts through every window — you keep seeing real error content as the incident evolves,
rather than only ever digests.

## The aggregated digest

When a suppression window closes, if one or more errors were suppressed during that window, exactly
**one** aggregated digest notification is emitted: a single "N errors suppressed" notification,
published at `digest-priority` (default `urgent`) with `digest-tags` (default `fire`, rendered as
🔥). The digest title follows `<title|app-name> — N errors suppressed`, and the body includes a
per-logger tally (e.g. `9× com.example.MyService`) so you can see which component is the source
without opening a log file.

If nothing was suppressed in a window, no digest is sent — silence during a quiet window is expected.

The suppressed count is never silently dropped. Three mechanisms guarantee this:

- **Failed publishes count too.** If an individual publish attempt fails (HTTP error, network down,
  ntfy rate-limiting the client itself with a 429), that failure is folded into the suppression
  counter rather than discarded — the next digest reports it honestly.
- **A failed digest publish re-folds its count back in.** If the digest publish itself fails, the
  drained count (per-logger breakdown included) is restored into the limiter instead of being lost,
  so it survives into the next window's digest.
- **Shutdown flushes the digest synchronously.** When the engine is stopped (JVM shutdown, Logback
  context reset, Quarkus shutdown), if there is a non-zero pending suppression count, a best-effort
  synchronous digest flush is attempted before resources are released, bounded by the existing HTTP
  timeouts (`connect-timeout`/`request-timeout`) so shutdown is never blocked indefinitely.

## Priority and tags for visual triage

`error-priority`/`digest-priority` and `error-tags`/`digest-tags` are independently configurable,
letting a lone error and a storm digest look visually distinct in the ntfy client at a glance: a lone
error arrives as `high`/🚨 while a digest arrives as `urgent`/🔥, so the recipient can immediately
tell "one error happened" from "this service is having a bad time" without opening the notification.
Both are pass-through values (no local validation), so future ntfy priority or tag values work
automatically without a library update.

## Content truncation

ntfy enforces a hard 4096-byte body limit. Before any alert (individual or digest) is published, its
body is truncated to fit, sacrificing whole trailing lines first (typically the tail of a stack
trace) so the message, logger name, and cause chain stay intact as long as possible. Truncation is
measured in UTF-8 bytes, not string length, so multi-byte characters are never split mid-character
and the published body never exceeds the byte budget.

## Delivery mode: synchronous (default) or asynchronous

By default the engine publishes on the calling (logging) thread: `HttpClient.send()` blocks for up to
`connect-timeout + request-timeout` per event.

Set `async = true` to offload delivery instead. Individual error alerts are then handed to a
**bounded work queue** drained by a single daemon worker thread (`ntfy-alert-delivery`), so a slow or
unreachable ntfy server never back-pressures your application threads during an error storm. This is
the first-class alternative to hand-wrapping `LogbackAlertAppender` in an `AsyncAppender`.

Rate-limit gating still runs on the submitting thread, so the queue only ever holds events that have
already won a publish slot. The queue holds up to `async-queue-capacity` pending alerts (default
`1024`). When it is full, an alert is **dropped but folded into the storm digest's suppressed count**
(and a throttled overflow warning is emitted) — it is never lost silently, consistent with the
"suppressed count is never silently dropped" guarantee above. On shutdown, `stop()` drains any
queued-but-unsent alerts into that same digest count before the final synchronous flush.

Async is opt-in: with `async = false` (the default) delivery is inline and behaves exactly as it did
before the flag existed. See [configuration.md](configuration.md) for the `async` /
`async-queue-capacity` keys and each adapter's spelling.

## See also

- [configuration.md](configuration.md) — the settings (`max-alerts-per-window`, `suppression-window`,
  `error-priority`, `digest-priority`, `error-tags`, `digest-tags`) that control this behavior.
- [troubleshooting.md](troubleshooting.md) — what each diagnostic the engine emits means.
