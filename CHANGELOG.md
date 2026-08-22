# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- **The build is reproducible.** `project.build.outputTimestamp` is set at the parent pom, so every
  archiver in the reactor writes that instant into its zip entries instead of the wall clock and a
  rebuild of a tag from clean sources is byte-identical to the jars published from it — which is
  what lets a third party check that what is on Central really came from the tag it names. It
  generalises to the whole reactor what `<notimestamp>` already did for the javadoc alone. The stamp
  is the release date and `release.sh` now rewrites it from the same `TODAY` that dates the
  CHANGELOG section, in the same commit as the version bump, because a stamp nothing moves keeps
  reproducing perfectly while claiming the previous release's date. A new `reproducible` CI job runs
  `artifact:check-buildplan` on every push and PR, which fails both if the property goes missing and
  if a plugin is bumped to a version whose output is not reproducible; `ReproducibleBuildGuardTest`
  additionally holds the stamp against the CHANGELOG's newest dated heading, since Maven silently
  ignores a value it cannot parse and would otherwise drop reproducibility without failing anything.

### Changed
- **The GraalVM section says what the jars actually carry.** `docs/compatibility.md` described the
  hand-rolled (non-Quarkus) native path as `--enable-url-protocols=https` plus a resource
  registration for `ntfy.properties`, and stopped there — omitting the `bundles` block that
  registers `io.github.pimak.ntfy.core.AlertMessages`, without which a localized alert body comes
  out as its key in a native image, and omitting `ntfy-jul`'s `reflect-config.json` entirely. A
  reader following the page would have concluded both were missing and written them again by hand.
  The section now lists every shipped metadata file and what each registers, and says which modules
  ship none. It also states, rather than leaves to inference, the two native paths this project does
  **not** measure — Spring AOT and Micronaut native — and why they are recorded as a gap rather than
  folded into the Quarkus row's green tick. `NativeImageMetadataGuardTest` now fails the build when
  any metadata file, resource, bundle, locale, reflectively registered type or image-builder flag is
  shipped without appearing on the page — the file-set check included, so the page's claim to
  inventory them in full cannot be quietly falsified by adding one. Two of its checks read no
  documentation at all and compare build inputs to each other, which is what makes them survive a
  registration being *deleted* rather than changed: a translation added as a `_xx.properties` file
  but left out of the `locales` array, and any classpath resource the code reads or the jar ships
  that no include pattern matches. Both resolve correctly on the JVM and silently fail in a native
  image, which is the class of bug neither the suite nor the `native-smoke` job can see.
- **One Maven line for the whole project.** Every build — local, CI, the compatibility sweep, the
  docs site — ran Maven 4.0.0-rc-5 through `./mvnw`, while the release job downloaded its own Maven
  3.9.9 to publish: `central-publishing-maven-plugin` does not upload under Maven 4, where `deploy`
  stages the bundle to the local repository and the session upload hook never fires, so the build
  reports success and publishes nothing (this bit 0.1.0 and 0.1.1). Nothing was therefore ever
  tested on the toolchain that actually ships a release. The wrapper now pins Maven 3.9.16 and the
  release job runs `./mvnw` like every other job — one pin to bump instead of two, and no second
  distribution download with an inline checksum to keep current. Maven 4 buys the project nothing
  in the meantime: it was still not GA when this landed (the line was on 4.0.0-rc-6), no pom uses
  anything beyond model `4.0.0`, and its javadoc already needed a workaround here. Because the failure mode
  it replaces is silent — a green release that published nothing — the publish job now asserts it is
  running under 3.9.x and fails outright if the wrapper is ever moved to the 4.x line.
- **Micronaut 5 is supported, and the compatibility page now says so.** `docs/compatibility.md`
  reported Micronaut 5 as **Cannot be built here**, reasoning from the fact that
  `micronaut-inject` and the `micronaut-inject-java` processor ship Java 25 bytecode. The bytecode
  is real, but the conclusion was too strong: it describes a build running on JDK 21, not a limit of
  this project. On JDK 25 the module compiles against Micronaut 5 and its whole suite passes with no
  source change, and the jar published from the 4.x line is read by Micronaut 5 as-is — the bean
  definitions the Micronaut 4 annotation processor generates load unchanged under
  `micronaut-inject` 5.x, so an application on Micronaut 5 can use the artifact as released rather
  than waiting for a rebuild. 5.1.1 is now a **Verified** row, `micronaut.version` stays pinned on
  4.10.x so the JDK 21 leg keeps proving the project's own baseline, and the pin is documented as a
  default rather than a compatibility ceiling.
- **The compatibility sweep picks its JDK per row.** `.github/compat-versions.tsv` gains a fourth
  column and every row states its build JDK, because the right JDK belongs to the version under test
  rather than to the sweep: Micronaut 5 can only be built on 25, while every 4.x row is run on 21 on
  purpose. A default would have hidden that distinction behind an omission, so
  `CompatibilityMatrixGuardTest` requires the column and rejects a JDK below
  `maven.compiler.release`, which could never have compiled at all.
- **A sweep job watches the published jar, not just the sources.** Every other sweep leg recompiles,
  so a green there says "our sources build against this version" — a weaker claim than the one the
  compatibility page now makes about the released artifact. The new `forward-compat` job rebuilds
  `ntfy-micronaut` against Micronaut 4, then runs its suite with only the classpath moved to
  whatever 5.x is current, and fails if the generated definitions were rebuilt, if the version
  override missed the classpath, or if no test ran. It tracks the latest release rather than a pin
  because the risk being watched is a future 5.x dropping support for what the 4.x processor emits —
  against a pinned version that job would stay green long after the claim stopped being true.

## [2.0.0] - 2026-08-20

### Added
- **Startup self-test, via the new opt-in `startup-ping` flag.** Alerting is silent by design — it
  only speaks when something breaks — which is exactly what makes a broken *alerting* configuration
  invisible: a revoked token, a renamed topic, a typo'd base URL and a blocked egress route all
  behave identically at boot (nothing happens) and were only discovered when the first real
  production error failed to deliver and nobody got paged. The engine's start-up validation was
  entirely local (URL syntax, topic charset, auth-pair completeness, transport policy, timeouts) and
  never touched the network, so it could not tell a working setup apart from one whose token was
  revoked last week. `startup-ping` closes that gap by making one real round-trip when the engine
  starts: `probe` issues a read-only `GET {url}/{topic}/json?poll=1&since=none` that validates DNS,
  reachability, TLS, that the endpoint really is an ntfy server, and read access — while publishing
  nothing of its own, so subscribers see no noise; `publish` sends one `low`-priority test notification through
  the production publish path, exercising the exact code, headers and credentials that carry real
  alerts, and is therefore the only mode that proves alerts are genuinely deliverable (ntfy ACLs
  grant read and write separately, so a read-only token passes a probe and still fails every alert,
  and on an open server anonymous reads make a probe prove nothing about credentials at all — a
  caveat the passing-probe diagnostic states in its own text rather than burying in the docs). The
  outcome is one diagnostic line that names the probable cause and the fix instead of leaving the
  operator to look up a status code: 401/403 reports that the token may be revoked, expired, or
  lack access to the topic; 404 points at the single most common ntfy mistake, appending the topic
  to `url`; 429 and 5xx explicitly absolve the configuration and blame the server; a connect/DNS/
  timeout failure names the transport and points at firewall and proxy rules. No diagnostic ever
  echoes a token, username, password or raw exception message, and failures are classified by
  exception type only, matching the publisher's existing no-leak rule. The self-test runs on a
  one-shot daemon thread (`ntfy-alert-selftest`) and never delays startup; the companion
  `startup-ping-fail-fast` turns a failure into a failed startup (`NtfyStartupSelfTestException`)
  for CI and staging, which necessarily also makes the check inline — a start that already returned
  cannot be aborted — and releases every resource the engine had acquired before throwing, so an
  aborted start leaks no HTTP pool and no ticking digest timer. It is deliberately excluded from
  `PipelineCounters` and never routes through the delivery path that folds failures into the storm
  digest, so it can neither inflate observability dashboards nor manufacture a phantom suppressed
  error. Available on every surface like any other key (Logback XML `<startupPing>`, Log4j2
  `<Ntfy startupPing="probe">`, Spring and Micronaut `ntfy.startup-ping`, Quarkus
  `quarkus.ntfy.startup-ping`, env `NTFY_STARTUP_PING`, sysprop `ntfy.startup-ping`, classpath
  `ntfy.properties`), bound as a string on every framework surface so one typo in a config file
  warns (`startup-ping is not a recognized mode …`) instead of failing a context refresh or bean
  creation. Default `off` is a true no-op: no request, and no extra diagnostic line, so the default
  path's output stays byte-identical to before this feature existed. See
  [docs/configuration.md](docs/configuration.md#startup-self-test),
  [docs/troubleshooting.md](docs/troubleshooting.md) and
  [docs/authentication.md](docs/authentication.md).
- **The startup self-test now covers the WARN route, and reports failures through ntfy itself.**
  `startup-ping` verified the primary topic only, which left the exact blind spot the feature exists
  to close: ntfy grants permissions **per topic**, so a token that publishes fine to the ERROR topic
  may have no write access to the WARN one, and nothing would have said so until the first real
  warning failed to deliver. The WARN route now has its own `startup-ping-warn`, with the same
  `off`/`probe`/`publish` vocabulary and its own `off` default. The two flags are deliberately
  independent rather than one switch covering both: enabling a self-test is a cost decision as much
  as a coverage one — in `publish` mode each route costs its own notification on every boot — so
  neither route can opt the other in. The WARN self-test is skipped when the engine has *withdrawn*
  the warn route (an invalid `warn-topic`, or a classpath-only one without
  `allow-classpath-endpoint`), since verifying a route that will never carry an alert reports on
  nothing. Both routes share one worker and one vocabulary — the WARN line is the primary route's
  own text wrapped with its route and topic, so the two can never drift apart — and `fail-fast` now
  covers **every route whose self-test was enabled**, on the grounds that a route explicitly asked
  to be verified is one whose failure should gate the deploy. Alongside it, the new
  `startup-ping-notify-failures` (**on by default — the one opt-out in this feature**) publishes the
  diagnosis as a notification on a route that PASSED: a diagnostic line lands on a status manager or
  stderr nobody reads until they are already investigating, whereas the whole point of alerting is
  to reach people, and when one route is broken the other can carry that news. It is gated on a
  route having *passed*, never merely being configured, and that single rule is what makes it
  impossible to publish to a topic the self-test has not just verified — with the WARN self-test
  off, nothing about that route is tested, nothing fails, and the ERROR topic is never pinged. If no
  route passed there is no channel worth trying and the diagnostics are the only report, which the
  engine says out loud rather than staying silent. One honest consequence, documented rather than
  hidden: with failure notifications on, a self-test configured purely as `probe` can still publish
  exactly one notification — the failure report — so `probe`'s "publishes nothing" promise is now
  stated as "publishes nothing of its own", and `startup-ping-notify-failures=false` restores it
  absolutely. Available on every surface like the other keys, and the failure notification is
  excluded from `PipelineCounters` for the same reason the self-test itself is. See
  [docs/configuration.md](docs/configuration.md#startup-self-test).
- **An opt-in WARN route, via the new `warn-topic` / `warn-priority` / `warn-tags` settings.**
  Alerting has been ERROR-only, enforced as a hard-coded floor repeated in every adapter's gate;
  the common request it could not serve was "tell me about warnings too, but not the way you tell me
  about errors." Setting `warn-topic` adds a second, independent route: WARN-level events
  (`WARNING` on JUL/Quarkus) publish to that topic at `warn-priority`/`warn-tags`, defaulting to a
  deliberately quieter `default`/`warning` ⚠️ against the error route's `high`/`rotating_light` 🚨.
  **The presence of a non-blank `warn-topic` is the entire opt-in** — there is no `alert-on-warn`
  boolean beside it, because a route needs a destination and naming it is the only thing the engine
  has to be told, which also makes the feature impossible to half-enable. Point it at your main
  `topic` to alert on warnings through one channel at a different priority. Unset, **every path is
  byte-identical to before**: same gates, same bodies, same startup diagnostics, same request count.
  **The two routes never share a storm budget.** Each holds its own rate limiter, both sized by the
  same `max-alerts-per-window`/`suppression-window`, and each digests separately on its own topic and
  in its own words ("N warnings suppressed" vs "N errors suppressed"), driven by the one existing
  timer. That separation is the reason this is a second route rather than a lowered threshold: with a
  shared budget a service logging warnings in a loop would spend the allowance and push genuine
  errors into a digest — precisely the failure the ERROR-only floor existed to prevent — so opening
  the WARN gate must not make error alerting worse. The warn digest deliberately keeps
  `warn-priority`/`warn-tags` rather than escalating to `digest-priority`/`digest-tags`: escalating
  would undo the point of a quiet channel at the worst moment, when a burst of warnings is the last
  thing that should page anyone. **Nothing below WARN can alert on any configuration**, and that is
  structural rather than a validation rule — the engine's new `AlertLevel` has exactly two constants,
  so "route INFO" is not expressible by any adapter, present or future. Two conditions withdraw the
  route while **leaving ERROR alerting fully active**, since a mistyped optional extra must never
  cost an operator their error alerts: a `warn-topic` that is not a valid ntfy topic name, and one
  that reached the engine solely through a classpath `ntfy.properties` without
  `allow-classpath-endpoint`. That second guard puts `warn-topic` on the same footing as `url`,
  following the rule the loader already applied — `include-mdc-keys` is read from all three
  configuration layers precisely because it cannot redirect where alerts go, and `warn-topic` names a
  destination and widens how much log content leaves the host. When the route is active the engine
  announces it once at startup, naming the topic. On Log4j2 the attach level is lowered to `WARN`
  alongside the appender's own gate — an appender attached at `ERROR` is never handed a warning at
  all — with both floors derived from a single accessor so a reconfiguration cannot re-attach at a
  different one. Available on every surface with the same names and defaults:
  `-Dntfy.warn-topic` / `NTFY_WARN_TOPIC` / `ntfy.properties` `ntfy.warn-topic`, Logback XML
  `<warnTopic>`, the Log4j2 `<Ntfy>` element's `warnTopic` attribute, Spring `ntfy.warn-topic`,
  Micronaut `ntfy.warn-topic`, Quarkus `quarkus.ntfy.warn-topic`, and
  `NtfyConfig.Builder.warnTopic(String)` for programmatic core users. `AlertEvent` gained a `level`
  component; both previous constructor signatures and the `of(...)` factory remain as explicit
  delegating forms meaning `ERROR`, so adapters compiled against an earlier release stay source- and
  binary-compatible. See the new [docs/level-routing.md](docs/level-routing.md).
- **MDC context in alert bodies, via a new opt-in `include-mdc-keys` allow-list.** Until now an alert
  said WHAT broke — message, logger, cause chain, root-cause frames — but never for WHOM or in WHICH
  request, so acting on one still meant going back to centralized logs, exactly the trip the alert
  exists to spare you. Naming MDC keys in `include-mdc-keys` renders their values into the body as
  one `key: value` line each, in the order configured, inserted immediately after the `Logger:` line;
  a key absent from the MDC, or holding a null/blank value, produces no line at all. The setting is
  an **explicit allow-list with no wildcard and no "include everything" mode** — that is the security
  design, not a limitation: an MDC is a free-form, application-populated map that routinely holds
  session identifiers and user names nobody chose to publish, so no MDC value is ever sent off-host
  unless an operator wrote its key in the configuration. Rendering is bounded by hard guards in
  `MdcProjector`, none of them configurable: at most 16 keys, every value (and every key, since keys
  can come from an env var) scrubbed of control characters, ANSI escapes, Unicode line/paragraph
  separators and bidi/format characters, each truncated to 256 characters, and a 1024-character
  aggregate budget for the whole block — so a value like `acme\nCaused by: java.lang.Forged` cannot
  forge a body line. One consequence is worth knowing: `PayloadTruncator` keeps whole lines from the
  start of the body and drops from the end, so under the 4096-byte ntfy limit the context block
  survives while the tail is sacrificed — `Time:` first, then stack frames bottom-up, then
  `Caused by:` lines — meaning a large context block can reduce the number of frames actually
  visible even though `max-stack-frames` is unchanged. That inversion is deliberate (a
  correlation-id beats the 5th frame) and the 1024-character budget is what bounds it. Storm digests
  deliberately carry no MDC, since per-event context across N aggregated events would be meaningless.
  Available on every surface: `-Dntfy.include-mdc-keys` / `NTFY_INCLUDE_MDC_KEYS` /
  `ntfy.properties` `ntfy.include-mdc-keys`, Logback XML `<includeMdcKeys>`, the Log4j2 `<Ntfy>`
  element's `includeMdcKeys` attribute, Spring `ntfy.include-mdc-keys`, Micronaut
  `ntfy.include-mdc-keys`, Quarkus `quarkus.ntfy.include-mdc-keys`, and
  `NtfyConfig.Builder.includeMdcKeys(List)` / `.includeMdcKeysCsv(String)` for programmatic core
  users (the `List` form is also the only way to name a key containing a comma). Full support on
  Logback and therefore Spring Boot and Micronaut (read from `ILoggingEvent.getMDCPropertyMap()`,
  captured at event construction, so it is correct even behind an `AsyncAppender`), on Log4j2 —
  standalone or as a Log4j2-backed Spring Boot application (read from `LogEvent.getContextData()`,
  since log4j2's `ThreadContext` is its MDC; again the event's own snapshot, so it stays correct
  behind an `<Async>` appender) — and on JBoss LogManager and therefore Quarkus (read per key from
  `org.jboss.logmanager.ExtLogRecord`); on plain
  `java.util.logging` the block is always empty, since JUL has no MDC concept — the same situation as
  SLF4J markers, which the JUL path already does not carry. When the allow-list is non-empty the
  engine emits one extra startup INFO line next to its `ACTIVE` and excluded-loggers lines —
  `ntfy alert engine: MDC keys included in alert bodies: correlation-id, tenant` — listing key names
  only, never a value. Unset (the default) leaves alert bodies byte-for-byte identical to before. See
  [docs/mdc-context.md](docs/mdc-context.md), the feature's own page, and
  [docs/alert-behavior.md](docs/alert-behavior.md).
- **New `ntfy-jul` module: a framework-neutral `java.util.logging` adapter.** The JUL handler,
  event mapper, and stderr diagnostics sink previously private to the Quarkus runtime now live in
  their own artifact (`io.github.pimak:ntfy-jul`, package `io.github.pimak.ntfy.jul`), usable from
  any JUL-based application (plain JUL, Tomcat, Helidon, zero-dependency tools). Beyond the moved
  handler, the module adds two zero-code entry points, both fed by the same ambient `ConfigLoader`
  chain (sysprop > env > classpath `ntfy.properties`) and both protected by the same
  classpath-endpoint supply-chain guard as the Logback auto-install: `NtfyJulAutoHandler` for
  declarative installation via a `logging.properties` `handlers =` line (never throws — an
  unconfigured environment degrades to a no-op), and `NtfyJulInstaller.install()` /
  `install(Logger)` for a programmatic one-liner scoped to the root logger or a subtree. A
  `reflect-config.json` under `META-INF/native-image/` covers the auto-handler's reflective
  instantiation in hand-rolled native builds. See [docs/jul.md](docs/jul.md).
- **New `ntfy-log4j2` module: a Log4j2 adapter.** The Log4j2 sibling of `ntfy-logback`
  (`io.github.pimak:ntfy-log4j2`, package `io.github.pimak.ntfy.log4j2`) maps Log4j2 `LogEvent`s
  onto the same framework-neutral `AlertEngine`, so rate limiting, storm digests, priorities/tags,
  truncation, sync-vs-async delivery, `excluded-loggers`, auth modes and `locale` behave exactly as
  on every other adapter; it gates at `ERROR` and above (`Level.isMoreSpecificThan(Level.ERROR)`),
  matching Logback's ERROR floor and JUL's `SEVERE`. Two entry points: an `<Ntfy .../>` appender
  plugin for `log4j2.xml`, whose attributes are the camelCase form of the same setting roster as the
  Logback appender (`url`/`topic` required, everything else optional and defaulted); and
  `NtfyLog4j2Installer.install()` / `install(LoggerContext)` — a programmatic one-liner reading the
  ambient `ConfigLoader` chain (sysprop > env > classpath `ntfy.properties`) exactly like
  `NtfyJulInstaller.install()`, returning `Optional<NtfyLog4j2Appender>`, never throwing, protected
  by the same classpath-endpoint supply-chain guard as the Logback and JUL auto-installs, and
  re-attaching itself across a Log4j2 reconfiguration (`uninstall(LoggerContext, appender)` releases
  it). There is deliberately **no** fully zero-code auto-install: Log4j2 has no equivalent of
  Logback's `Configurator` SPI for augmenting an existing configuration, and the only mechanism that
  could — a delegating `ConfigurationFactory` — is fragile because exactly one factory can win.
  The `NO_ALERT` marker works here too (including markers that reach it via `Marker.getParents()`),
  diagnostics go to Log4j2's `StatusLogger` rather than an application logger, and native-image
  reachability metadata for the plugin is generated at build time by log4j-core's own annotation
  processor and ships inside the jar. Log4j2 is a `provided`-scope dependency (compiled against
  2.26.1), so the host application's own version is what runs; Java 21+ as everywhere else. See
  [docs/log4j2.md](docs/log4j2.md).
- **New `ntfy-micronaut` module: a Micronaut integration.** `io.github.pimak:ntfy-micronaut`
  (package `io.github.pimak.ntfy.micronaut`) binds the `ntfy.*` keys from a Micronaut application's
  own configuration (`application.yml` / `application.properties`, environment, config clients) via
  a `@ConfigurationProperties("ntfy")` class whose key roster, types and defaults are deliberately
  identical to the Spring starter's — the same `ntfy.*` block moves between the two frameworks
  unchanged, with Micronaut's relaxed binding accepting `ntfy.app-name` / `ntfy.appName` /
  `NTFY_APP_NAME` alike and converting the duration keys natively (`5s`, `3m`, `500ms`, `PT10S`).
  Two things are contributed to the context: an injectable `NtfyClient` `@Singleton` for manual
  notifications, closed by the container on shutdown so the async delivery worker never outlives
  the application; and `NtfyLogbackInstaller`, which on `StartupEvent` attaches the shared
  `LogbackAlertAppender` under the usual name `ntfy-auto` to the root Logback logger — replacing
  idempotently, so an appender the zero-code `ntfy-logback` `Configurator` SPI already installed
  from env/sysprops before the context existed is superseded by the Micronaut-bound values rather
  than duplicated — and detaches and stops it again on context shutdown. **Logback only:** Micronaut
  binds Logback through `micronaut-logging`, and there is deliberately no Log4j2 path here, so only
  the *appender* is backend-specific — the `NtfyClient` bean is contributed whatever SLF4J backend
  is bound, and when Logback is on the classpath but is not the bound backend the installer logs a
  warning and skips the install instead of failing startup (a Micronaut application on Log4j2 wires
  `ntfy-log4j2`'s `<Ntfy>` element itself). There is no Micrometer binding yet; the pipeline
  counters remain reachable programmatically off the installed appender. **Micronaut 4.10.x only**
  (compiled and tested against `micronaut-platform` 4.10.17 / core 4.10.26): Micronaut 5's
  `micronaut-inject` and its `micronaut-inject-java` annotation processor ship Java 25 bytecode
  (class file version 69) and therefore cannot be compiled or run against this project's Java 21
  baseline, so Micronaut 5 is not supported yet. Logback is a `provided`-scope dependency, so the
  version the host application brings is the one that runs. See
  [docs/micronaut.md](docs/micronaut.md).
- **Runnable example applications that double as functional tests.** New (unpublished) reactor
  modules under `examples/` — `examples/spring-boot`, `examples/logback`, `examples/log4j2`,
  `examples/jul`, `examples/core`, plus a shared pure-JDK loopback ntfy stand-in in
  `examples/testkit` — each a small, realistic app using one adapter exactly the way a consumer
  would (`application.yml` `ntfy.*`, an explicit `logback.xml`, an `<Ntfy>` element in
  `log4j2.xml`, the `NtfyJulInstaller` one-liner with `-Dntfy.*`/env config, programmatic
  `NtfyClient`/`AlertEngine`). Their integration tests run under plain
  `./mvnw verify`, so the examples can never drift from the code, and each proves three behaviors
  end-to-end: the base alert/notify path; the synchronous-vs-asynchronous delivery difference,
  deterministically (the stand-in holds its HTTP response open, showing a sync error log blocked in
  the calling thread while an async one has already returned); and the clean-shutdown contract
  (context close / `LoggerContext` stop / `AlertEngine.stop()` flushes the pending storm digest,
  folds queued-or-in-flight async alerts into it — count-never-lost — and leaves no engine thread
  alive). The existing Quarkus integration-tests app plays the same role for the Quarkus extension
  and gained an async-delivery smoke test (`quarkus.ntfy.async=true` keeps `/boom` non-blocking
  while the ntfy response is held). See [examples/README.md](examples/README.md).
- **Micrometer metrics on Quarkus, at parity with the Spring Boot starter.** The pipeline counters
  were tracked on every adapter but only ever *exposed* as metrics by the starter — an odd gap on
  Quarkus, where Micrometer is the standard metrics stack. `ntfy-quarkus` now exports the same three
  meters under the same names: `ntfy.pipeline.published`, `ntfy.pipeline.suppressed` and
  `ntfy.pipeline.failed`. Nothing to enable, configure or inject — the binding is a CDI `MeterBinder`
  that the Quarkus Micrometer extension discovers and applies to whichever registry the application
  configured, so with `quarkus-micrometer-registry-prometheus` the meters simply appear under
  `/q/metrics` as `ntfy_pipeline_*_total`. It is **extension-conditional**, the counterpart of the
  starter's classpath condition: the build step registers the bean only when the application provides
  the `io.quarkus.metrics` capability *and* Micrometer's `MeterRegistry` is on the runtime classpath,
  and it names the bean class by string so on a Micrometer-free build the binder is never loaded and
  the deployment module needs no Micrometer dependency at all. `micrometer-core` is `optional` in
  `ntfy-quarkus-runtime`, so an application that does not want metrics pulls in nothing. The meters
  read the installed handler's counters **lazily at scrape time**, which is what makes them work
  across Quarkus's boot order — the log handler is created at `RUNTIME_INIT`, long before the CDI
  container the binding lives in — and what makes a scrape safe when no handler is installed at all
  (an inactive config, or a scrape before logging is configured): the meters report `0` rather than
  failing or disappearing. Enabling this also gave `NtfyJulHandler` a public `counters()` accessor,
  the JUL counterpart of the Logback/Log4j2 appenders' `getCounters()`, so plain `java.util.logging`
  applications can now reach the tallies programmatically too. See
  [docs/observability.md](docs/observability.md) and [docs/quarkus.md](docs/quarkus.md).

### Changed
- **The Spring Boot starter is no longer Logback-only.** Its auto-configuration used to carry a
  class-level `@ConditionalOnClass` on `ch.qos.logback.classic.LoggerContext`, so on a
  `spring-boot-starter-log4j2` classpath the **entire** auto-configuration was skipped — no
  appender, no `NtfyClient` bean, no Micrometer metrics. That gate is gone: the starter now installs
  the `ntfy-auto` appender onto whichever backend is actually on the classpath and bound, Logback or
  Log4j2, and the `NtfyClient` bean and the `ntfy.pipeline.*` meters are exposed either way. The
  observable change for a Log4j2 application is that all three now appear where previously nothing
  did. Logback applications are unaffected: `ntfy-logback` remains a normal transitive dependency of
  the starter and behaves exactly as before; Log4j2 applications additionally declare
  `io.github.pimak:ntfy-log4j2` alongside the starter. See
  [docs/spring-boot.md](docs/spring-boot.md).
- **The public record `AlertEvent` gained a seventh component, `mdcValues`.** It carries the
  event's MDC snapshot (normalized to an empty map when null, like the existing collection
  components) so adapters can hand per-event context to the engine for `include-mdc-keys` rendering.
  A delegating six-argument constructor with the previous canonical signature is retained, so code
  that constructs an `AlertEvent` the old way keeps compiling **and** keeps linking without
  recompilation — the change is both source- and binary-compatible for callers. Two honest caveats
  for anyone doing more than constructing one: `equals`, `hashCode` and `toString` now take the new
  component into account, so two events that differ only in their MDC are no longer equal and
  `toString` output has changed; and a Java 21 **record deconstruction pattern** written with six
  sub-patterns (`case AlertEvent(var logger, var msg, var ts, var causes, var frames, var markers)`)
  no longer compiles, because record patterns must match the canonical arity — add a seventh
  sub-pattern (or bind the whole event) to fix it. The retained constructor cannot help here: record
  patterns are matched against the canonical component list, not against constructors.
- **`ntfy-quarkus-runtime` now depends on `ntfy-jul` instead of carrying its own JUL classes.**
  The extension installs the shared `NtfyJulHandler` (via the new `NtfyJulHandler.forConfig`
  factory) and keeps only the Quarkus glue: `@ConfigMapping`, the `RUNTIME_INIT` recorder, and the
  CDI producer. No behavior or configuration change for Quarkus users; `ntfy-core` still arrives
  transitively.
- **BREAKING: `require-https-for-credentials` now defaults to `true`.** When credentials — a
  configured `token`, a `username`/`password` pair, or userinfo embedded in the URL itself
  (`http://user:pass@host`) — would traverse a cleartext `http://` endpoint, the engine now
  **refuses activation** with a fixed, credential-safe diagnostic instead of warning and
  proceeding. Secrets never leave the process unencrypted unless explicitly allowed.
  **Migration:** self-hosted plain-HTTP setups that relied on the old warn-and-activate behavior
  must either switch the endpoint to `https://` (recommended) or explicitly set
  `require-https-for-credentials=false` (env `NTFY_REQUIRE_HTTPS_FOR_CREDENTIALS=false`, sysprop
  `ntfy.require-https-for-credentials=false`, Logback XML
  `<requireHttpsForCredentials>false</requireHttpsForCredentials>`, Spring
  `ntfy.require-https-for-credentials=false`, Quarkus
  `quarkus.ntfy.require-https-for-credentials=false`, or
  `NtfyConfig.Builder.requireHttpsForCredentials(false)`). Credential-free `http://` endpoints are
  unaffected.
- **BREAKING: `async` delivery now defaults to `true`.** Individual error alerts are handed to the
  engine's bounded work queue (default capacity `1024`) and published by a single daemon worker
  thread (`ntfy-alert-delivery`), so a slow or unreachable ntfy server can never back-pressure
  application threads during an error storm. Queue overflow folds dropped alerts into the storm
  digest's suppressed count — never a silent loss. **Migration:** delivery is no longer guaranteed
  to have completed when the logging call returns; short-lived processes (batch jobs, CLIs) that
  log an error and exit immediately should either ensure an orderly logging-framework shutdown
  (Logback shutdown hook / context stop, which flushes the queue accounting into the digest) or
  restore the pre-2.0 synchronous, inline delivery with `async=false` (env `NTFY_ASYNC=false`,
  sysprop `ntfy.async=false`, Logback XML `<async>false</async>`, Spring `ntfy.async=false`,
  Quarkus `quarkus.ntfy.async=false`, or `NtfyConfig.Builder.asyncEnabled(false)`). Setups that
  wrap the appender in a Logback `AsyncAppender` should also set `<async>false</async>` to avoid
  queuing delivery twice.
- **Corrected what the Micrometer meters do across an appender re-install.** The documentation and
  the `NtfyMetricsBinder` javadoc claimed `FunctionCounter` "ignores decreases in its source, so the
  exported metric never goes backwards". It does not: `CumulativeFunctionCounter` reports whatever
  its source function currently returns, so when a Spring re-install swaps in a fresh appender with
  fresh counters, the exported value restarts from zero along with them. No behavior changed — only
  the description of it. This is an ordinary counter reset, which is what a monitoring system already
  sees when the process restarts and handles the same way (`rate()`/`increase()`), so no query needs
  revisiting; the claim was simply wrong and would have misled anyone reasoning about a dashboard
  gap. The Quarkus binding above documents and tests the accurate behavior from the start.
- **The compatibility matrix now reports measured version ranges instead of reasoned ones.** Every
  "expected to work" row was an inference from the API surface the adapters use; none had ever been
  run. Each affected module's full suite was executed against the versions now listed, by overriding
  the version property the root `pom.xml` pins — Logback 1.3.14/1.4.14/1.5.0/1.5.18/1.5.38, Log4j2
  2.17.2/2.20.0/2.24.3/2.25.2, Spring Boot 3.3.13/3.4.10/3.5.6, Micronaut 4.6.3/4.9.4, and Quarkus
  3.15.7/3.20.6/3.24.5/3.34.7. All pass. The page now distinguishes **Tested (CI)** from **Verified**
  (run once, locally, not re-run by CI) from **Expected to work** (reasoned, not run), because
  collapsing those into one word is what let the errors below hide.
  **One row was factually wrong:** Logback 1.3.x was listed with 1.2.x as unsupported "because the
  adapter targets the modern JavaBean/Joran and `Configurator` SPIs", yet 1.3.14 compiles and passes
  all 37 tests — the `Configurator` SPI cited as the reason is present and working there, and
  `NtfyLogbackConfiguratorResetSurvivalTest` proves it. 1.3.x is now recorded as known-to-work but
  unsupported (no CI leg), which is a different and honest claim. 1.2.x really is out, and now says
  why: `Configurator.ExecutionStatus` and `ILoggingEvent.getMarkerList()` do not exist there.
  **Two claims are newly stated rather than corrected:** GraalVM had no tested-version row at all
  (CI native-compiles on CE 25, and CE 21 cannot be used because that line is frozen at 21.0.2,
  older than Quarkus 3.38+ requires), and Micrometer had no entry despite backing a binding on two
  surfaces. The Micrometer row is explicitly marked weaker evidence — **API-checked only** — since
  both BOMs pin it literally and the suites could not be run against older versions; what was checked
  is that all four signatures the bindings call exist from 1.9.17 through 1.17.0.
  **One claim remains unverified and is labelled as such:** JDK 22–24. Only 21 and 25 were available
  to test with, and those are the two CI legs already.
- **The compatibility matrix is now enforced rather than trusted.** Measuring the ranges once only
  moves the problem: a measurement decays silently, and the wrong 1.3.x row above is what that looks
  like after a while. Two mechanisms now hold the page to its claims. `CompatibilityMatrixGuardTest`
  (in `ntfy-core`, so every module's `-am` reactor runs it) asserts that each **Tested (CI)** row
  names the version the root `pom.xml` actually pins — the Dependabot-bump failure mode, and by far
  the likeliest, since those pins move on their own schedule — that the documented JDK floor matches
  `maven.compiler.release`, and that the **Verified** rows and the sweep's version list are the same
  set in both directions, so neither can drift ahead of the other. A new weekly
  `Compatibility sweep` workflow then re-runs each module's suite against all 18 verified versions
  and opens (or comments on) a single tracking issue when one stops passing; it is scheduled and
  non-blocking on purpose, since it can go red because of an upstream republish rather than anything
  in the pull request at hand, and a blocking check that behaves that way just teaches people to
  ignore it. Both sides read one file, `.github/compat-versions.tsv`, so a version cannot be claimed
  in the docs without being swept, nor swept without being documented.

- **The documentation is now a published site, and the Javadoc is hosted with it.** The sixteen
  pages under `docs/` were only readable as raw Markdown on GitHub: no search, no sidebar, and no
  URL that could be cited from an issue or a badge — while the API was delegated to javadoc.io, one
  badge per module, seven of them by the time Log4j2 and Micronaut landed. Both now live at
  <https://pimak.github.io/ntfy-logging/>, built with MkDocs Material and versioned so a reader on
  an older release still finds the documentation that matches it: one entry per **minor** version,
  overwritten by each of its patches, with `dev` tracking `main` and `latest` following the newest
  release. The per-module javadoc jar is unchanged and still published — Maven Central requires it,
  and javadoc.io serves itself from Central regardless — but it is no longer the address the project
  advertises. The guides additionally **embed the real example files** rather than linking to them:
  the Logback and Log4j2 XML, the Spring and Micronaut `application.yml`, and the `main` of the JUL
  and core apps are inlined from `examples/`, the same sources CI compiles and integration-tests, and
  a moved or renamed example fails the documentation build. That closes the last gap where a page
  could quietly describe something the code no longer did — the guarantee the examples already had
  as test-beds, now extended to the prose about them.

### Fixed
- **Micronaut: `ntfy.include-mdc-keys` never reached the injectable `NtfyClient` bean.**
  `NtfyClientFactory` forwarded every other bound property onto the `NtfyConfig` builder and silently
  skipped this one, so an operator's allow-list was honoured by the appender that
  `NtfyLogbackInstaller` installs and dropped by the client — one configuration, two apply sites, and
  nothing anywhere to announce that they disagreed. Micronaut was the only adapter with the gap: the
  Spring starter and the Quarkus extension have always wired the key into both their appender/handler
  and their client. No alert body was ever affected, because the ad-hoc `NtfyClient.notify(...)` path
  renders no MDC today; what was wrong is the config the bean carries, which would have become a real
  loss the moment that path learned to. The property was bound and documented all along — only the
  forwarding was missing — so no configuration needs changing to pick the fix up. Also closes the
  test gap that hid it: the binding test asserted all two dozen other getters and skipped this one,
  and there was no equivalent of the starter's `includeMdcKeys_reachesTheNtfyClientBean`.
- **A shutdown race could publish a phantom storm digest — or lose the alert entirely.** With async
  delivery on (the default since 2.0), `AlertEngine.stop()` called `shutdownNow()` on the delivery
  worker, interrupting whatever send was in flight. `NtfyPublisher` maps that `InterruptedException`
  to a failed publish, `deliver()` folds a failed publish into the suppression count, and `stop()`
  then flushes a digest for it — so a single ERROR logged just before shutdown could produce a
  bogus `1 errors suppressed in the last 3 minutes` notification, and `PipelineCounters` counted a
  delivered alert as `failed`. Two shapes were observed, depending on where the interrupt lands
  relative to the server accepting the request: the alert **plus** a contradicting digest, or —
  worse — the digest **instead of** the alert, which is a silently lost notification. This hit
  every ordinary shutdown that races an in-flight publish: Spring context close, a Logback
  `LoggerContext` reset, Quarkus shutdown, plain JVM exit. `stop()` now drains the never-started
  backlog off the queue first, then shuts the worker down gracefully and awaits it (bounded at
  500ms) before force-cancelling — exactly the treatment the digest scheduler immediately above it
  has always had, and whose comment already described this precise hazard: *"Interrupting it
  mid-send manufactures a spurious failure for a request the server may have already accepted."*
  The delivery executor, added later for 2.0 async delivery, never received it. Draining before the
  graceful shutdown is what preserves the existing contract that queued-but-unsent events fold into
  the digest rather than being published on the way out; only the already-running send completes.
  The defect is in `ntfy-core`, so **every adapter was affected**. Found by the compatibility sweep,
  which surfaced it as an intermittent `ntfy-logback` failure.
- **`LogbackAlertAppenderDoubleResetTest` now identifies that failure instead of merely counting.**
  Both cases asserted `verify(1, ...)` on the publish count, which is satisfied just as well by
  "the alert was lost and a digest went out in its place" — so the test could pass while the
  behaviour it exists to protect was broken, and that is precisely the shape the bug above took most
  often. They now assert the single publish IS the alert, by priority (`high` for an alert,
  `urgent` for a digest), with a failure message that names the mechanism and dumps the published
  bodies. The three reachable failures read distinctly: `[high, urgent]` is a duplicate, `[urgent]`
  is a lost alert, `[]` is nothing published.

## [1.2.0] - 2026-07-23

### Added
- **Translatable notification language, selectable via a new `locale` setting.** Every visible
  string the engine produces — body labels, the storm-digest title/body, and the self-diagnostic
  status/warning lines — is now backed by a JDK `ResourceBundle` + `MessageFormat` catalog and can be
  rendered in a language other than English. Set it with `ntfy.locale` / `NTFY_LOCALE` /
  `ntfy.properties` `ntfy.locale` (core/Logback), the Logback `<locale>` XML setter, Spring
  `ntfy.locale`, Quarkus `quarkus.ntfy.locale`, or `NtfyConfig.Builder.locale(Locale)` /
  `.locale(String)` for programmatic core users. The default is English and is **deterministic** — it
  never follows the host JVM's `Locale.getDefault()`. A locale with no shipped bundle falls back
  wholesale to English; a partial translation falls back per missing key; an unknown/garbage tag
  keeps English. Shipped languages: English (base) and French (`fr`); adding a language is a pure
  additive drop of an `AlertMessages_<lang>.properties` file (plus one native-image bundle entry).
  Default-locale output is byte-for-byte identical to before. Credential safety is preserved
  structurally and locked by a new invariant test: the fixed status strings take no arguments (so no
  translation can interpolate a token/username/password), and the composed lines keep their
  URL/credential scrubbing in Java — a translation can only reorder or drop the already-safe
  arguments, never widen the set. `ntfy-core` stays pure-JDK: `ResourceBundle`/`MessageFormat` live
  in `java.base`, so the maven-enforcer dependency allowlist is unchanged. See
  [docs/configuration.md](docs/configuration.md#notification-language-translations).

## [1.1.1] - 2026-07-23
### Security
- **The Logback zero-code auto-install now refuses a classpath-only endpoint URL unless explicitly
  opted in.** Previously, a `ntfy.properties` shipped inside ANY jar on the classpath (e.g. a
  malicious or compromised transitive dependency) could point `ntfy.url` at attacker infrastructure
  and silently activate alerting — exfiltrating every ERROR log (messages and stack traces) with
  zero code change by the victim; the only guard was a WARN status line. Now, when the endpoint URL
  comes only from a classpath `ntfy.properties` (no `ntfy.url` system property or `NTFY_URL` env
  var), `NtfyLogbackConfigurator` does not install the appender and emits a WARN status explaining
  why and how to opt in. Operators who intentionally configure via a classpath file can restore the
  old behavior with the new `allow-classpath-endpoint` flag (default `false`;
  `-Dntfy.allow-classpath-endpoint=true` or `NTFY_ALLOW_CLASSPATH_ENDPOINT=true`) — which is
  deliberately NOT readable from `ntfy.properties` itself, so a classpath file can never grant
  itself trust — or simply set the URL via env/sysprop. Explicit configuration (Logback XML setters,
  Spring, Quarkus, programmatic `NtfyConfig`) is unaffected.
- **Broadened cleartext-credential detection at startup.** The existing warning for credentials
  over a plain `http://` URL now also fires when the URL itself embeds userinfo
  (`http://user:pass@host`), even when no separate `token` or `username`/`password` is configured —
  previously this case transmitted a secret in the request target with no warning at all.
- **New opt-in `require-https-for-credentials` flag** (default `false`), wired through every
  configuration surface: env `NTFY_REQUIRE_HTTPS_FOR_CREDENTIALS`, sysprop
  `ntfy.require-https-for-credentials`, `NtfyConfig.Builder.requireHttpsForCredentials(...)`,
  Logback XML `<requireHttpsForCredentials>`, Spring `ntfy.require-https-for-credentials`, and
  Quarkus `quarkus.ntfy.require-https-for-credentials`. When enabled and credentials would traverse
  a cleartext `http://` endpoint (configured token/basic pair, or userinfo embedded in the URL), the
  engine refuses activation with a fixed, credential-safe diagnostic instead of merely warning.
  Default behavior is unchanged: with the flag off, the engine warns loudly and still activates.
- **Startup warning for a half-configured basic-auth pair.** When exactly one of
  `username`/`password` is set (and no `token` supersedes the pair), the engine used to silently
  fall back to `None` auth mode — every publish went out with no `Authorization` header while the
  operator believed auth was in effect (a common misconfig, e.g. a `${VAR}` that resolves empty).
  `start()` now emits a one-time, credential-safe warning: `username or password set but not both — basic auth is incomplete; publishing WITHOUT an Authorization header`. Activation still proceeds
  (same policy as the token-plus-basic overlap warning), and `AuthMode` derivation is unchanged.

## [1.1.0] - 2026-07-22
### Added
- **Opt-in asynchronous delivery mode.** A new `async` flag (default `false`) offloads individual
  error-alert publishing to a bounded work queue drained by a single daemon worker thread, so a slow
  or unreachable ntfy server no longer back-pressures your application/logging threads during an
  error storm — the first-class alternative to hand-wrapping the appender in an `AsyncAppender`.
  When the queue is full (capacity is tunable via `async-queue-capacity`, default `1024`), an alert
  is dropped but folded into the storm digest's suppressed count rather than lost silently, and
  shutdown drains any queued-but-unsent alerts into that same count. The flag is available on every
  surface: Logback `setAsync`/`setAsyncQueueCapacity` (XML `<async>`/`<asyncQueueCapacity>`), Spring
  `ntfy.async`/`ntfy.async-queue-capacity`, Quarkus `quarkus.ntfy.async`/
  `quarkus.ntfy.async-queue-capacity`, `NtfyConfig.Builder.asyncEnabled(...)`/`asyncQueueCapacity(...)`
  for programmatic core users, and `ConfigLoader` (`NTFY_ASYNC` / `NTFY_ASYNC_QUEUE_CAPACITY`).
  Default behavior is unchanged: with `async` off, delivery is synchronous and identical to before.
- **Pipeline observability counters** for the alert pipeline: the engine now tracks read-only,
  monotonic `published`, `suppressed`, and `failed` tallies so you can "alert on the alerter" (e.g.
  a spike in `failed` signalling a revoked token or a topic ACL change). Read them programmatically
  via `AlertEngine.counters()` (`ntfy-core`) or `LogbackAlertAppender.getCounters()`
  (`ntfy-logback`); the Logback appender keeps them monotonic across `stop()`/`start()` cycles and
  context resets. Counters are pulled, never logged, so reading them cannot re-enter the logging
  pipeline. The `suppressed` and `failed` counters are disjoint (a failed publish is never also
  counted suppressed). See [docs/observability.md](docs/observability.md).
- **Micrometer metrics in the Spring Boot starter**: when `micrometer-core` is on the classpath and
  a `MeterRegistry` bean is present, the starter exports `ntfy.pipeline.published`,
  `ntfy.pipeline.suppressed`, and `ntfy.pipeline.failed` as monotonic `FunctionCounter`s. The
  binding is classpath-conditional — consumers without Micrometer get no new dependency and
  `ntfy-core` stays dependency-free.

### Changed
- `NtfyClient` now implements `AutoCloseable`, so it can be used in a try-with-resources block and
  IDEs/linters can flag a leaked client. The existing `close()` method is unchanged; this is a
  source- and binary-compatible addition and makes the try-with-resources example in `docs/core.md`
  compile. The Javadoc notes that `AutoCloseable` does not mandate closing, so a long-lived,
  container-managed instance (e.g. a Spring/Quarkus bean) may be kept open for the application's
  lifetime without an IDE resource-leak warning being a real defect.

### Fixed
- Hardened the Logback appender's shutdown thread-leak regression coverage so it also watches for a
  leaked `ntfy-alert-delivery` async worker after `stop()`, keeping the no-leak-on-shutdown contract
  enforced for the async delivery path as well as the synchronous one.

## [1.0.2] - 2026-07-22
### Added
- **Regression test coverage** for `ntfy-core` config resolution: unit tests now pin the
  `ConfigLoader` precedence chain, `DurationParser` parsing rules, and `AuthMode` header
  construction. Behavior is unchanged.

### Fixed
- **Invalid HTTP timeouts no longer break engine startup or silently kill alerting.** A zero,
  negative, or unset `connectTimeout`/`requestTimeout` (e.g. from `NTFY_CONNECT_TIMEOUT=0` or a
  framework setter passing `null`) previously threw out of engine startup — leaking the
  `ntfy-alert-http` thread pool — or made every publish fail silently. The engine now validates both
  timeouts before acquiring any resource, falls back to the built-in defaults (5s connect, 10s
  request), and emits a one-time startup warning instead.
- **Endpoint URL validation at startup**: the engine now refuses to activate when the configured
  `url` is not a structurally valid http(s) endpoint (no scheme like `ntfy.sh`, a non-http(s)
  scheme like `ftp://…`, unparseable syntax, whitespace-padded, or missing a host/authority),
  emitting one specific startup diagnostic instead of failing every publish with an opaque generic
  error. Valid forms — reverse-proxy paths, non-standard ports, trailing slashes, `user:pass@host`
  basic-auth URLs, underscore hostnames, and IPv6 literals — continue to activate as before.

## [1.0.1] - 2026-07-21
### Added
- **Ergonomic factories** for `ntfy-core` types: `AlertEvent.of(loggerName, formattedMessage,
  timestampMillis)` builds the common "no throwable, no markers" event, and new
  `NtfyAction.http(...)` / `NtfyAction.broadcast(...)` overloads take an explicit `clear` flag
  (previously reachable only via the raw canonical constructor). The `AlertEvent` compact
  constructor now validates its mandatory fields and normalizes optional collections to empty for
  every construction path. The existing constructors and factories are unchanged, so these
  additions are backward-compatible.
- **Full ntfy action-button coverage** in `ntfy-core`: the typed `NtfyAction` model now supports all
  three ntfy action types — added `NtfyAction.broadcast(...)` (Android broadcast intents, with typed
  `BroadcastExtra` extras) and typed `HttpHeader`s on `http` actions (e.g. `Authorization`),
  alongside the existing `view` and `http` types.

### Changed
- The `NtfyAction.Http` record gained a trailing `List<HttpHeader> headers` component. Code using the
  `NtfyAction.http(...)` static factories is unaffected; direct `new NtfyAction.Http(...)` callers
  must add the new argument.

### Security
- **Logback adapter now gates at ERROR** (mirroring the Quarkus adapter's `SEVERE` gate): the
  appender submits only ERROR-and-above events, so a root-logger install can no longer publish
  INFO/WARN log content off-host.
- **Topic validation**: the publisher and engine reject topics outside ntfy's
  `[-_A-Za-z0-9]{1,64}` rule, closing a request-path injection through a `/`, `?`, `#`, or
  `..`-bearing topic value.
- **`Priority`/`Tags` header sanitization**: non-printable-ASCII configured values are omitted
  (with a startup warning) instead of forwarded, so CRLF safety no longer depends solely on the
  JDK client and one bad value can no longer abort every publish.
- **Cleartext-credentials warning**: configuring a token/password with an `http://` URL now warns
  loudly at startup.
- **Classpath-activation warning**: the Logback zero-code auto-install warns (naming the
  destination host) when the endpoint URL comes only from a classpath `ntfy.properties`, since any
  jar can ship one.
- **Bounded suppression tally**: the per-logger suppression map is capped at 100 distinct loggers
  (overflow folds into `(other loggers)`), so a long ntfy outage plus dynamically-named loggers
  can no longer grow it without bound.
- **Cycle-guarded JUL cause-chain walk** (Quarkus adapter): a circular exception cause chain can
  no longer loop the logging thread until OOM.
- **Credential-redaction fix**: the `ACTIVE` diagnostic's userinfo stripping now handles an
  unencoded `@` inside the password without leaking a password fragment.
- **Race-proof lifecycle**: `AlertEngine.start()`/`stop()` are now synchronized, so concurrent
  starts can no longer orphan live thread pools and duplicate digest timers.
- **Context-stop leak fix** (Logback): `LoggerContext.stop()` fires a reset before `onStop`, which
  resurrected the auto-installed appender mid-stop and leaked its engine threads and `HttpClient`
  forever; the reattach listener now tears the appender down on `onStop`.
- **No duplicate digest at shutdown**: `stop()` now shuts the digest scheduler down gracefully
  before force-cancelling, so an in-flight digest publish is no longer interrupted mid-send —
  which manufactured a spurious failure for a request the server had already accepted and made
  the stop-flush re-send the same digest.
- **Hardened config parsing**: malformed numeric/duration values from env/sysprops fall back to
  defaults instead of throwing during logging-framework initialization.
- **Supply-chain hardening**: GitHub Actions pinned to commit SHAs, `permissions: contents: read`
  on CI, release job resolves dependencies cold (no shared cache), Maven wrapper distribution
  pinned by SHA-256, Maven 3.9.9 release download pinned by inline SHA-512, strict Maven checksum
  policy, Dependabot enabled, and the Quarkus (3.15.7) / Spring Boot (3.4.13) BOMs moved to their
  latest security patch levels. CodeQL (Java + workflow files) and a PR dependency-review SCA
  gate run in CI, and the Central publish job is gated behind a reviewer-protected `release`
  environment.

## [1.0.0] - 2026-07-19
### Changed
- **Project split into a multi-module reactor and renamed** from `logback-ntfy` to the
  `ntfy-logging` family. The single `io.github.pimak:logback-ntfy` artifact is superseded by
  five focused, independently-versioned artifacts under `io.github.pimak` at `1.0.0`. The old
  `logback-ntfy` 0.1.x line is retired.

### Added
- **`ntfy-core`** — a framework-neutral, zero-dependency ntfy engine and client. The storm
  suppression, loop-safety, digest, truncation and auth logic that used to live in the Logback
  appender now lives here as `AlertEngine` + `NtfyClient`, driven by an immutable `NtfyConfig`
  and, when no framework config is present, a `ConfigLoader` that resolves settings from JVM
  system properties (`-Dntfy.*`), environment variables (`NTFY_*`), and a classpath
  `ntfy.properties`, in that precedence.
- **`ntfy-logback`** — the Logback appender (`LogbackAlertAppender`) plus **zero-code
  auto-install**: a `ch.qos.logback.classic.spi.Configurator` SPI that attaches an `ntfy-auto`
  appender to the root logger when `ntfy.url`/`ntfy.topic` are present, with a reset-resistant
  re-attach listener so it survives a Logback context reset. Classic XML `<appender>` wiring
  still works.
- **`ntfy-spring-boot-starter`** — Spring Boot auto-configuration that binds `ntfy.*` from the
  Spring `Environment` (relaxed binding, native `Duration` support), idempotently installs the
  `ntfy-auto` Logback appender, and exposes an injectable `NtfyClient` bean.
- **`ntfy-quarkus`** — a Quarkus 3.15 extension (runtime + deployment) that installs the alert
  handler via a `LogHandlerBuildItem` recorded at `RUNTIME_INIT`, binds `quarkus.ntfy.*` via
  `@ConfigMapping`, exposes an injectable `NtfyClient`, and is **GraalVM native-image ready**.
- **GraalVM native metadata** for `ntfy-core` (URL-protocol and `ntfy.properties` resource
  registration) for hand-rolled native builds of the core/Logback path, and a Quarkus
  `integration-tests` module that smoke-tests the extension end-to-end in both JVM and native.
- **Flexible duration syntax** everywhere via `DurationParser`: a bare integer is milliseconds
  (`500`), a suffixed integer uses its unit (`5s`, `3m`, `2h`, `1d`), and ISO-8601 (`PT5S`) is
  accepted too.

## [0.1.1] - 2026-07-18
### Added
- Initial release: a zero-dependency, storm-resistant, loop-safe Logback appender
  (`NtfyAlertAppender`) that publishes `ERROR`-level log events as [ntfy](https://ntfy.sh)
  push notifications.
- Rate-limited alert storms with a count-never-lost suppressed-count digest, so a burst of
  errors collapses into a single follow-up notification instead of flooding the ntfy topic.
- Three authentication modes (`BearerToken`, `BasicAuth`, `None`) with a "token wins"
  precedence rule when both are configured.
- Logger exclusion filtering (`excludedLoggers`, prefix-matched) plus a built-in `NO_ALERT`
  marker and an always-on self-exclusion to prevent feedback loops.
- Structured, truncated (4096-byte) payloads carrying the root-cause exception, and
  `StatusManager`-only diagnostics so the appender itself never re-enters the logging
  pipeline it publishes from.

[Unreleased]: https://github.com/Pimak/ntfy-logging/compare/v2.0.0...HEAD
[2.0.0]: https://github.com/Pimak/ntfy-logging/compare/v1.2.0...v2.0.0
[1.2.0]: https://github.com/Pimak/ntfy-logging/compare/v1.1.1...v1.2.0
[1.1.1]: https://github.com/Pimak/ntfy-logging/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/Pimak/ntfy-logging/compare/v1.0.2...v1.1.0
[1.0.2]: https://github.com/Pimak/ntfy-logging/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/Pimak/ntfy-logging/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/Pimak/ntfy-logging/compare/v0.1.1...v1.0.0
[0.1.1]: https://github.com/Pimak/ntfy-logging/releases/tag/v0.1.1
