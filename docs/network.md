# Network: proxies and TLS trust

How the engine reaches your ntfy server: what the JVM already does for you, the four settings that
scope proxy and TLS trust to ntfy alone, the programmatic escape hatch for everything else, and the
two things this library deliberately does **not** offer.

Start here if you self-host ntfy behind a corporate proxy or a private CA — the two situations where
a default JVM cannot reach the server and the failure looks like a bug in the alerting.

## What already works, with no configuration at all

This is worth knowing before you change anything, because the answer is often "nothing to do".

The engine publishes with the JDK's `java.net.http.HttpClient`, and a client built with no explicit
proxy or SSL context **already** picks up the JVM-wide switches:

| You set | Effect on ntfy traffic | Why |
|---|---|---|
| `-Dhttps.proxyHost` / `-Dhttps.proxyPort` | Publishes go through that proxy | An `HttpClient` with no explicit proxy uses `ProxySelector.getDefault()`, which reads these |
| `-Dhttp.proxyHost` / `-Dhttp.proxyPort` | Same, for a plain `http://` endpoint | idem |
| `-Dhttp.nonProxyHosts` | Matching hosts bypass the proxy | idem — `nonProxyHosts` applies to both schemes |
| `-Djavax.net.ssl.trustStore` (+ `…Password`, `…Type`) | The ntfy server's certificate is verified against that store | The client's default SSL context **is** `SSLContext.getDefault()` |

So `proxy: system` (the default) is a genuine no-op, not a synonym for "no proxy". If your operators
already set `JAVA_TOOL_OPTIONS` or `JAVA_OPTS` globally, ntfy inherits it and there is nothing to
configure here.

> **Gotcha for anyone debugging this:** `HttpClient.proxy()` returns an **empty** `Optional` even
> while the client is actively proxying, because the default selector is not exposed. It is not a
> usable probe. To confirm a proxy is in play, watch the proxy's own access log, or point
> `https.proxyHost` at a deliberately bogus host and check that publishing starts failing.

## When the JVM-wide switches are not enough

Two reasons, and both are common enough to be the point of this page.

**1. `javax.net.ssl.trustStore` replaces `cacerts` wholesale.** It does not add your corporate CA to
the JDK's built-in roots — it swaps the whole set. Point it at a store containing only your internal
CA and every *other* TLS connection your application makes (a payment API, S3, an OIDC provider) now
fails, because the public roots are gone. Building a merged store is possible but has to be rebuilt
on every JDK upgrade. `ntfy.truststore-path` sidesteps this entirely: it applies to the ntfy client
and nothing else.

**2. You may not control the JVM flags.** On many platforms an operator owns `application.yml` but
not the container entrypoint. These settings live wherever the rest of your ntfy configuration does.

## The settings

All four are available on every configuration surface, like any other key — see
[configuration.md](configuration.md) for the full per-framework table.

### `proxy`

| Value | Meaning |
|---|---|
| *(unset)* or `system` | Inherit the JVM's default proxy selector, honoring `https.proxyHost` / `http.nonProxyHosts`. The default. |
| `none` | Connect directly, ignoring any JVM-wide proxy. |
| `host:port` | Route ntfy publishes through that proxy. IPv6 literals are bracketed: `[2001:db8::1]:3128`. |

`none` matters more than it looks: it is the escape when a JVM-wide proxy is set for outbound
internet access but cannot reach your *internal* ntfy server.

There is deliberately no "non-proxy hosts" companion — the engine talks to exactly one host, so the
answer to "don't proxy this one" is `none`.

A malformed value is **not** fatal. The engine warns
(`proxy must be 'system', 'none', or 'host:port' — falling back to the JVM default proxy selector`)
and keeps alerting: a typo in a routing hint must not silence your alerts, especially when the
default selector may reach the server anyway.

### `truststore-path`, `truststore-password`, `truststore-type`

Verify the ntfy server's certificate against your own CA instead of the JDK's public roots.

`truststore-type` accepts:

| Type | Use for |
|---|---|
| `PKCS12` | The default when unset. A `.p12` / `.pfx` store. |
| `JKS` | A legacy Java keystore. |
| `PEM` | A plain text certificate file — one or more `-----BEGIN CERTIFICATE-----` blocks. |

**`PEM` is usually the one you want on Kubernetes.** A corporate CA is almost always mounted as a
`ca.crt` from a ConfigMap or projected by cert-manager, and reading it directly saves you a
`keytool -import` step and a second artifact in your image. No password applies.

```yaml
# Spring Boot / Micronaut — application.yml
ntfy:
  url: https://ntfy.internal.corp
  topic: my-app-alerts
  truststore-path: /etc/ssl/corp/ca.crt
  truststore-type: PEM
```

```properties
# Quarkus — application.properties
quarkus.ntfy.truststore-path=/etc/ssl/corp/ca.p12
quarkus.ntfy.truststore-password=${TRUSTSTORE_PASSWORD}
```

```xml
<!-- Logback logback.xml -->
<appender name="NTFY" class="io.github.pimak.ntfy.logback.LogbackAlertAppender">
  <url>https://ntfy.internal.corp</url>
  <topic>my-app-alerts</topic>
  <truststorePath>/etc/ssl/corp/ca.crt</truststorePath>
  <truststoreType>PEM</truststoreType>
</appender>
```

```bash
# Core / plain Logback / plain Log4j2 / JUL, via environment
export NTFY_TRUSTSTORE_PATH=/etc/ssl/corp/ca.crt NTFY_TRUSTSTORE_TYPE=PEM
```

**The store replaces the default anchors, it does not extend them** — the same semantics as
`javax.net.ssl.trustStore`, and the reason the setting is useful. Trust exactly the CA that signed
your ntfy server and nothing else, without touching what the rest of the application trusts. If your
ntfy server uses a *public* CA, do not set this at all.

If the store cannot be loaded, the engine **refuses to activate** and says so
(`truststore could not be loaded (unreadable path, wrong password, or wrong truststore-type) — engine disabled`).
It never quietly falls back to the default trust material: that would publish over a CA you did not
pin, without telling you. The diagnostic never echoes the path or the password. The injectable
`NtfyClient` bean fails its construction for the same reason — see
[troubleshooting.md](troubleshooting.md).

## The escape hatch: `httpClientCustomizer`

Anything the four settings cannot express is reachable programmatically. `NtfyConfig.Builder`
accepts a `UnaryOperator<HttpClient.Builder>` applied **last**, after every declarative setting, so
it can override any of them:

```java
NtfyConfig config = NtfyConfig.builder()
    .url("https://ntfy.internal.corp")
    .topic("my-app-alerts")
    .httpClientCustomizer(b -> b
        .sslContext(myMutualTlsContext)          // client certificates
        .authenticator(myProxyAuthenticator)     // see the caveat below
        .proxy(myPacBackedProxySelector))        // dynamic / PAC-driven routing
    .build();
```

This is what covers mutual TLS, a hand-built `SSLContext`, a dynamic `ProxySelector`, or any future
`HttpClient` knob, without the configuration surface having to grow a key for each. It is
builder-only and has no XML/YAML spelling on purpose: exposing it as a class name to instantiate
reflectively would break GraalVM native image.

In Spring Boot, Micronaut and Quarkus the injectable `NtfyClient` bean is built from the framework's
own properties, so use the customizer by defining your own `NtfyClient` bean — the starters all back
off when one is already present.

## What this library does not offer, and why

### No proxy authentication settings

There is no `proxy-username` / `proxy-password`, and this is a JDK constraint rather than an
omission. `$JAVA_HOME/conf/net.properties` ships with

```
jdk.http.auth.tunneling.disabledSchemes=Basic
```

active, which disables Basic authentication on the `CONNECT` used to tunnel HTTPS through a proxy.
With it in force the client's `Authenticator` is **never consulted** for the proxy, no
`Proxy-Authorization` header is sent, and the failure is silent. Undoing it means setting
`-Djdk.http.auth.tunneling.disabledSchemes=""`, which is JVM-global — it also re-enables Basic proxy
auth for `HttpsURLConnection` everywhere else in the process — and must be applied before the HTTP
machinery initializes. That is a decision for whoever owns the JVM, not something a logging library
should make on their behalf.

Setting `Proxy-Authorization` on the request instead is not a workaround: for an HTTPS endpoint that
header travels *inside* the tunnel, so it would never reach the proxy and **would leak your proxy
credentials to the ntfy server**.

If you need authenticated proxying: set the JVM property yourself, then supply an `Authenticator`
through `httpClientCustomizer`.

### No `insecure-skip-tls-verify`

There is no trust-all switch, and there will not be one.

Alert traffic is not low-value: it carries stack traces, allow-listed MDC values, and your
`Authorization` header. Disabling certificate verification turns anyone on the network path into a
credential harvester with a complete view of your errors. In practice these flags get set
"temporarily" during an incident and are never removed. It would also directly contradict
`require-https-for-credentials`, which exists to stop exactly this class of exposure.

`truststore-path` solves the legitimate case — a self-signed or privately-signed server certificate
— at the same effort, without the failure mode. Point it at the CA (or at the self-signed
certificate itself, in `PEM` form) and verification succeeds properly.

## See also

- [configuration.md](configuration.md) — the unified key reference, including these four keys on
  every surface.
- [authentication.md](authentication.md) — credentials, and `require-https-for-credentials`.
- [troubleshooting.md](troubleshooting.md) — `PKIX path building failed` and the transport
  diagnostics.
- [compatibility.md](compatibility.md) — GraalVM native image notes.
