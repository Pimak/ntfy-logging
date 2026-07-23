# Authentication

The ntfy engine supports three first-class authentication modes for the outbound publish request,
modeled by `AuthMode`, a sealed type with three variants: `BearerToken`, `BasicAuth`, and `None`.
Which variant is active is derived automatically from which of `token` / `username` / `password` you
configure — there is no separate "auth mode" switch. This is engine-wide: it applies identically
whether you configure via env/sysprop/`ntfy.properties`, Logback XML, Spring `ntfy.*`, or Quarkus
`quarkus.ntfy.*`. (For the full list of where each key lives, see
[configuration.md](configuration.md).)

## The three modes

### `BearerToken`

Set `token`. The request is sent with an `Authorization: Bearer <token>` header. This is the
recommended mode for a private ntfy topic protected by an access token.

```yaml
# Spring application.yml
ntfy:
  url: https://ntfy.example.com
  topic: my-app-alerts
  token: tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

```properties
# Quarkus application.properties
quarkus.ntfy.url=https://ntfy.example.com
quarkus.ntfy.topic=my-app-alerts
quarkus.ntfy.token=tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

```bash
# Core / plain Logback via environment
export NTFY_URL=https://ntfy.example.com NTFY_TOPIC=my-app-alerts
export NTFY_TOKEN=tk_xxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

### `BasicAuth`

Set both `username` and `password`. The request uses HTTP Basic authentication. Use this when the
ntfy server (or a reverse proxy in front of it) is gated by a username/password pair rather than a
token.

Both `username` and `password` must be non-blank. **If only one of the two is set, the pair is
ignored and the engine falls back to `None` mode**: every publish goes out with no
`Authorization` header. The engine emits a one-time startup warning for this half-configured state
(`username or password set but not both — basic auth is incomplete; publishing WITHOUT an Authorization header`) —
unless a `token` is also configured, in which case the token supersedes
basic auth entirely and only the token-plus-basic overlap warning below can apply. Activation
still proceeds. Against a protected server the observable
symptom is `ntfy publish failed for topic '<topic>' (HTTP 401)` (or `403`) diagnostics — see
[troubleshooting.md](troubleshooting.md). Against a permissive server the publishes may even
succeed, unauthenticated, while you believe auth is in effect. If Basic Auth appears not to apply,
first check that both values are actually set and non-blank (a `${VAR}` that resolves to an empty
string counts as blank).

### `None`

Set neither `token` nor `username`/`password`. Publishes go out with no `Authorization` header at
all. `None` is a valid, first-class configuration — not a misconfiguration — and is the correct mode
for a public `ntfy.sh` topic or a self-hosted server whose publish endpoint is deliberately open.

## Precedence: token wins over Basic Auth

If you configure **both** a `token` and a `username`/`password` pair, the engine does not fail and
does not refuse to start. Instead:

- `BearerToken` takes precedence — every request uses the token, and the configured
  `username`/`password` are ignored for the `Authorization` header.
- A **one-time** warning is emitted at startup:
  `both token and username/password configured — token takes precedence`.
- Startup still proceeds and alerting still activates normally. Authentication configuration is
  never a reason to block activation.

This lets you leave stale `username`/`password` values in place (for example while migrating from
Basic Auth to a token) without breaking anything — the token simply wins, and the one-time warning
tells you the overlap exists so you can clean it up.

## The token is never surfaced in diagnostics

The engine's self-diagnostics (its `ACTIVE` line, publish-failure warnings, error messages) never
include the `token`, `username`, or `password` value. The only credential-adjacent information that
can appear is the `url` and `topic` — and even then, if you supply a URL with embedded userinfo
(`https://user:pass@host/…`), the userinfo is stripped before the URL is logged. No diagnostic
output ever echoes a secret. Where those diagnostics appear differs per framework (Logback
`StatusManager`, or `System.err` for the Quarkus/Spring paths) — see
[troubleshooting.md](troubleshooting.md).

## See also

- [configuration.md](configuration.md) — where `url`, `topic`, `token`, `username`, and `password`
  live in each framework.
- [troubleshooting.md](troubleshooting.md) — the exact wording of the token-wins warning and every
  other diagnostic the engine can produce.
