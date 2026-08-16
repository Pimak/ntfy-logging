# Compatibility

The versions the `ntfy-logging` family is tested against. Most rows below are **measured**, not
reasoned: the affected module's full test suite was run against that exact version, by overriding
the version property the root `pom.xml` pins (`./mvnw -pl <module> -am clean test
-Dlogback.version=1.4.14`, and so on). These three status words carry most of the page, and they
mean different things:

| Status | Means |
|--------|-------|
| **Tested (CI)** | The pinned version. Exercised on every push and PR. |
| **Verified** | The module's suite was run against this version and passed — once, locally, on JDK 21. Not re-run by CI, so it can regress without anything going red. |
| **Expected to work** | Reasoned from the API surface used. Not run. |

A few rows carry a more specific status (**API-checked only**, **Does not work**, **Cannot be built
here**); each is explained where it appears.

A verified row is evidence, not a guarantee: a single green run on one JDK. Where a range is given,
the listed versions are the ones actually run — the range between them is interpolation.

**What keeps this page from going stale.** A measurement taken once decays silently, so two checks
hold it in place:

- `CompatibilityMatrixGuardTest` (in `ntfy-core`, so it runs in every build) asserts that each
  **Tested (CI)** row names the version the root `pom.xml` actually pins — the Dependabot-bump
  failure mode — and that the **Verified** rows and the sweep's version list are the same set, in
  both directions. Neither side can drift ahead of the other.
- The weekly `Compatibility sweep` workflow re-runs each module's suite against every **Verified**
  version, and files an issue when one stops passing. It is scheduled rather than blocking, because
  it can fail for reasons unrelated to the change in front of you.

Both read `.github/compat-versions.tsv`, which is the single source of truth for the swept versions.
To add or drop one, edit that file and the matching row here; the guard fails until they agree.

## Java

| JDK | Status |
|-----|--------|
| 21  | **Tested (CI)** — matrix leg, every push/PR |
| 25  | **Tested (CI)** — matrix leg, every push/PR |
| 22–24 | **Expected to work** — the compiler `<release>` floor is 21 and nothing here uses a later API |

The 22–24 row is the one claim on this page nobody has run. It is also the least interesting: those
are non-LTS releases, and the two LTS versions on either side of them are both CI legs.

Minimum supported JDK: **21** (`<maven.compiler.release>21</maven.compiler.release>`; required for
`HttpClient.shutdownNow()`, used to release the client deterministically on stop/close).

## java.util.logging (`ntfy-jul`, and Quarkus via it)

JUL ships in `java.base`, so `ntfy-jul` has no framework dependency to version at all: it is
supported on every JDK the family supports (21+, table above). The declarative
`logging.properties` entry point (`NtfyJulAutoHandler`) relies only on the stable
`LogManager`/`Handler` contract that Tomcat's `conf/logging.properties` also uses. The Quarkus
extension installs this module's `NtfyJulHandler` and is exercised by the Quarkus CI legs below.

## Logback (`ntfy-logback`, and Spring Boot / Micronaut via it)

| Version | Status |
|---------|--------|
| 1.6.1 | **Tested (CI)** — the exact version pinned in this repo's `pom.xml` |
| 1.5.0, 1.5.18, 1.5.38 | **Verified** — full suite green, including the `Configurator` SPI tests |
| 1.4.14 | **Verified** — full suite green |
| 1.3.14 | **Verified** — full suite green (37 tests), *but see below* |
| 1.2.13 | **Does not work** — hard compile failure, see below |

**1.2.x is genuinely out.** It is not a policy choice: `ntfy-logback` does not compile against it.
`ch.qos.logback.classic.spi.Configurator.ExecutionStatus` (which `NtfyLogbackConfigurator` returns)
and `ILoggingEvent.getMarkerList()` (which `LogbackEventMapper` calls) do not exist in that line.

**1.3.x is a different case, and this page used to get it wrong.** It was listed alongside 1.2.x as
unsupported "because the adapter targets the modern JavaBean/Joran and `Configurator` SPIs" — but
1.3.14 compiles and passes all 37 tests, `NtfyLogbackConfiguratorResetSurvivalTest` and
`NtfyLogbackConfiguratorClasspathEndpointTest` included, so the very SPI cited as the reason is
present and working there.

That makes 1.3.x *known to work* rather than *supported*: no CI leg covers it, the project pins
1.6.x, and 1.3.x is an older line receiving no attention here. Run it if you must and it will work
today; nothing will tell you if that stops being true.

## Log4j2 (`ntfy-log4j2`, and Spring Boot on `spring-boot-starter-log4j2`)

| Version | Status |
|---------|--------|
| 2.26.1 | **Tested (CI)** — the exact version pinned in this repo's `pom.xml`, and the version the module compiles against |
| 2.17.2, 2.20.0, 2.24.3, 2.25.2 | **Verified** — full suite green on each |
| 2.x (other, ≥ 2.17) | **Expected to work** — the only API surface used is the stable `@Plugin`/`@PluginFactory` appender contract, `AbstractAppender`, `LogEvent`, `Marker`, and `LoggerContext`/`Configuration` reattachment |

The verified span is deliberately wide: 2.17.2 is the first post-Log4Shell release, so anything an
application can responsibly still be running is covered. Nothing below 2.17 was tried, and nothing
below it should be used.

Log4j2 is a **`provided`-scope** dependency of `ntfy-log4j2`: the module never drags a Log4j2 of its
own onto your classpath, so the version your application declares is the one that runs. Native-image
reachability metadata for the `Ntfy` plugin is generated at build time by log4j-core's own annotation
processor and ships inside the jar.

## Spring Boot (`ntfy-spring-boot-starter`)

| Version | Status |
|---------|--------|
| 4.1.0 | **Tested (CI)** — the version pinned in this repo (`spring-boot.version` in `pom.xml`) |
| 3.3.13, 3.4.10, 3.5.6 | **Verified** — full suite green on each |
| 3.x / 4.x (other) | **Expected to work** — the starter uses stable `@AutoConfiguration` + `@ConfigurationProperties` binding and installs onto whichever logging backend is bound |

Spring Boot 3 and 4 are both covered by measurement, which matters because the starter is the module
most exposed to a framework major: the whole 3.3 → 4.1 span was run, and the auto-configuration,
conditional wiring and Micrometer binding pass unchanged across it.

The starter is **backend-agnostic**: it installs onto whichever backend Spring Boot is actually
running on — Logback (the default) or Log4j2 (`spring-boot-starter-log4j2`). `ntfy-logback` arrives
transitively with the starter; Log4j2 applications additionally declare `io.github.pimak:ntfy-log4j2`
alongside it. The `NtfyClient` bean and the Micrometer meters are available either way. See
[spring-boot.md](spring-boot.md).

## Micronaut (`ntfy-micronaut`)

| Version | Status |
|---------|--------|
| 4.10.17 | **Tested (CI)** — the `micronaut-platform` version pinned in this repo (`micronaut.version` in `pom.xml`), resolving Micronaut core 4.10.26 |
| 4.6.3, 4.9.4 | **Verified** — full suite green on each |
| 4.x (other, ≥ 4.6) | **Expected to work** — the only API surface used is the stable `@ConfigurationProperties`, `@Factory`/`@Singleton`, `@Requires(classes = …)` and `ApplicationEventListener<StartupEvent>` contract |
| 5.x | **Cannot be built here** — see below |

Micronaut 5 is not merely untested, it cannot be built here: `micronaut-inject` **and** the
`micronaut-inject-java` annotation processor ship Java 25 bytecode (class file version 69), so
compiling or running them under this project's Java 21 baseline fails outright (`… has been compiled
by a more recent version of the Java Runtime`). Micronaut 4.10.x is Java 17 bytecode and runs on both
JDKs CI builds with (21 and 25). Support for Micronaut 5 can only be revisited together with the
project's `maven.compiler.release` floor.

The module is **Logback-only**: it installs the `ntfy-auto` appender on the root Logback logger —
Logback being what `micronaut-logging` binds by default — and skips the install with a warning if
Logback is not the bound SLF4J backend. The injectable `NtfyClient` bean is contributed regardless of
backend. Logback is a `provided`-scope dependency of the module, so the version your Micronaut
application brings is the one that runs. There is no Log4j2 path in this module; a Micronaut
application on Log4j2 wires `ntfy-log4j2`'s `<Ntfy>` element itself. See
[micronaut.md](micronaut.md).

## Quarkus (`ntfy-quarkus-runtime` / `-deployment`)

| Version | Status |
|---------|--------|
| 3.38.1 | **Tested (CI)** — the version pinned in this repo (`quarkus.version`), exercised JVM + native by the `ntfy-quarkus/integration-tests` module |
| 3.15.7, 3.20.6, 3.24.5, 3.34.7 | **Verified** — full suite green on each, including the QuarkusUnitTests that boot a real application |
| 3.x (other, ≥ 3.15) | **Expected to work** within the stable extension-API surface used (`LogHandlerBuildItem`, `@ConfigMapping @ConfigRoot(RUN_TIME)`, `@Recorder`, `Capability.METRICS`) |

The verified rows cover both 3.15 and 3.20 — the two LTS lines — down to five minor releases below
the pin. They are stronger than a compile check: `NtfyMetricsBindingTest` boots a real in-JVM
application on each version and asserts the meter reads the installed handler's counters, so the
Micrometer binding is confirmed *working*, not merely linking, across the whole span.

The extension's Micrometer binding is **optional and conditional** — the meters appear only if the
application brings `quarkus-micrometer` itself. See the Micrometer section below.

## Micrometer (optional — Spring Boot and Quarkus)

| Version | Status |
|---------|--------|
| Whatever your application's BOM resolves | Supported — this project pins nothing |
| 1.17.0 | **Tested (CI)** — what CI exercises on both surfaces; `spring-boot-dependencies` 4.1.0 and `quarkus-bom` 3.38.1 resolve the same version |
| 1.9.17 → 1.17.0 | **API-checked only** — every signature the bindings call is present across that span, confirmed by inspecting the jars. The suites were *not* run against those versions. |

The Micrometer row is weaker evidence than the others on this page, and deliberately marked as such.
Both BOMs pin the version literally rather than through an overridable property, so there is no clean
way to run the suites against an older Micrometer without editing a POM. What was checked instead is
that `FunctionCounter.builder(String, T, ToDoubleFunction)`, `Builder.description(String)`,
`Builder.register(MeterRegistry)` and `MeterBinder.bindTo(MeterRegistry)` — the entire surface both
bindings touch — exist unchanged from 1.9.17 through 1.17.0. That is a strong signal for a binding
this small, but it is not a test run.

Micrometer is an **`optional`** dependency of both the Spring Boot starter and `ntfy-quarkus-runtime`,
so neither ever drags it onto a consumer's classpath and there is no version for this project to pin
— the one your application's BOM resolves is the one that runs. Each binding activates only when
Micrometer is genuinely there: the starter guards on `@ConditionalOnClass(MeterRegistry)` plus a
`MeterRegistry` bean, and the Quarkus extension on the `io.quarkus.metrics` capability (provided by
`quarkus-micrometer`) plus `MeterRegistry` on the runtime classpath. Applications without Micrometer
resolve and run exactly as they did before either binding existed. See
[observability.md](observability.md).

## GraalVM native image

| Version | Status |
|---------|--------|
| GraalVM CE **25** (`graalvm-community`, JDK 25) | **Tested (CI)** — the `native-smoke` job, every push/PR |
| GraalVM CE 21 | **Not usable for the Quarkus path** — the CE 21 line is frozen at 21.0.2, which is older than Quarkus 3.38+ requires. This is why `native-smoke` runs on 25 only rather than the full JDK matrix. |

Supported through the **Quarkus extension**: the alert engine's `HttpClient`, threads, and digest
scheduler are all created at `RUNTIME_INIT` (never build-time / static-init), which is what keeps the
extension native-safe. The `ntfy-quarkus/integration-tests` module native-compiles a real app and
runs its `@QuarkusIntegrationTest` against the native binary in the CI `native-smoke` job.

Note that the native leg exercises the alert path, not the Micrometer binding: `quarkus-micrometer` is
deliberately kept out of the integration-tests app so the native build stays fast. The binder adds no
reflection and no build-time initialization of its own, so nothing about it is native-specific.

For a **hand-rolled** (non-Quarkus) native build of `ntfy-core` / `ntfy-logback`, `ntfy-core` ships
native-image metadata under
`META-INF/native-image/io.github.pimak/ntfy-core/` — `--enable-url-protocols=https` (so the JDK
`HttpClient` TLS handler survives image build) and a resource registration for `ntfy.properties` (so
`ConfigLoader` can read it at image run time). Any GraalVM 21+ picks these up automatically — that
floor is about the metadata format, and applies to this non-Quarkus path only; it does not walk back
the table above, where the Quarkus path needs 25.

## ntfy server

Verified against [ntfy.sh](https://ntfy.sh) (the public hosted instance) and a self-hosted ntfy
instance. The engine targets ntfy's plain publish-with-headers HTTP API (`POST` to the topic URL,
`Title`/`Priority`/`Tags` as headers, body as the message text) — any ntfy server implementing that
stable API surface should work.

## See also

- [configuration.md](configuration.md) — the unified key reference and per-framework mapping.
- [troubleshooting.md](troubleshooting.md) — diagnostics and where each framework surfaces them.
