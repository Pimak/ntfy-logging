# Runnable examples

One small, realistic application per adapter. Each doubles as that adapter's functional test-bed:
the app code and configuration files are exactly what you would write in a real project, and the
modules' integration tests boot them against a loopback ntfy stand-in under plain `./mvnw verify` —
so the examples can never drift from the library.

| Example | Library | The example app |
|---|---|---|
| [`spring-boot/`](spring-boot/) | `ntfy-spring-boot-starter` | A Spring Boot app configured via `application.yml` `ntfy.*`: a service whose ERROR log alerts, plus a `NtfyClient`-injecting service for manual notifications. |
| [`micronaut/`](micronaut/) | `ntfy-micronaut` | A Micronaut app configured via `application.yml` `ntfy.*`: a service whose ERROR log alerts through the appender installed on the root Logback logger at startup, plus a `NtfyClient`-injecting service for manual notifications. |
| [`logback/`](logback/) | `ntfy-logback` | A plain SLF4J app whose [`logback.xml`](logback/src/main/resources/logback.xml) wires the `LogbackAlertAppender` (env-substituted settings, shutdown hook). |
| [`log4j2/`](log4j2/) | `ntfy-log4j2` | A plain Log4j2 app whose [`log4j2.xml`](log4j2/src/main/resources/log4j2.xml) declares the `<Ntfy>` appender (env-substituted settings) and references it from the root logger. |
| [`jul/`](jul/) | `ntfy-jul` | A plain `java.util.logging` app installing the ntfy handler with the `NtfyJulInstaller.install()` one-liner from ambient config (`NTFY_URL`/`-Dntfy.*`). |
| [`core/`](core/) | `ntfy-core` | A plain-JVM app driving `NtfyClient` (manual notifications) and `AlertEngine` (error alerting) programmatically. |
| [`../ntfy-quarkus/integration-tests/`](../ntfy-quarkus/integration-tests/) | `ntfy-quarkus-runtime` | A real Quarkus app (REST endpoint whose ERROR log alerts), run in JVM, fast-jar, and native mode. |

Every example's integration tests cover the same three behaviors:

- **Base alert path** — an ordinary ERROR log (or a manual `notify(..)`) lands on the configured
  topic with title/priority/body intact.
- **Synchronous vs asynchronous delivery** — the loopback stand-in holds its HTTP response open, so
  the tests prove *deterministically* that with `async` (the default since 2.0) an error log
  returns immediately and a daemon worker (`ntfy-alert-delivery`) does the blocking send in the
  background, while with the explicit `async=false` opt-out the very same call blocks the calling
  thread until the ntfy server responds.
- **Clean shutdown** — stopping the app the normal way (Spring context close, Logback context
  stop/shutdown hook, Log4j2 `LoggerContext` stop, JUL handler uninstall/close, `AlertEngine.stop()`)
  flushes the pending storm digest, folds queued-or-in-flight async alerts into it
  (count-never-lost), and leaves no engine thread alive.

The [`testkit/`](testkit/) module is the shared loopback ntfy stand-in (pure JDK) that makes the
hold-the-response technique possible.

## Notes

- The example modules are **never published to Central** — they build as part of the reactor only
  (`./mvnw verify` from the repository root), which is also why their poms inherit the reactor
  parent. For the dependency snippet to copy into your own project, use the per-library guides in
  [`docs/`](../docs).
- The app code deliberately lives in `com.example.*`: the engine's always-on self-exclusion drops
  any log from the library's own `io.github.pimak.ntfy` package root, so an app logging from that
  package would never alert.
