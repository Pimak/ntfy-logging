# Compatibility

The versions the `ntfy-logging` family is tested against, plus the reasoned range expected to work
beyond what CI directly exercises.

## Java

| JDK | Status |
|-----|--------|
| 21  | Tested — CI matrix leg, every push/PR |
| 25  | Tested — CI matrix leg, every push/PR |
| 22–24 | Expected to work (not CI-tested) — the compiler `<release>` floor is 21 |

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
| 1.6.1   | Tested — the exact version pinned in this repo's `pom.xml` and CI |
| 1.6.x / 1.5.x (other) | Expected to work — no API surface used beyond what's stable across those lines, including the `ch.qos.logback.classic.spi.Configurator` SPI used for zero-code auto-install |
| 1.2.x / 1.3.x | Not tested, not supported — the adapter targets the modern JavaBean/Joran and `Configurator` SPIs |

## Log4j2 (`ntfy-log4j2`, and Spring Boot on `spring-boot-starter-log4j2`)

| Version | Status |
|---------|--------|
| 2.26.1  | Tested — the exact version pinned in this repo's `pom.xml` and CI, and the version the module compiles against |
| 2.x (other) | Expected to work — the only API surface used is the stable `@Plugin`/`@PluginFactory` appender contract, `AbstractAppender`, `LogEvent`, `Marker`, and `LoggerContext`/`Configuration` reattachment |

Log4j2 is a **`provided`-scope** dependency of `ntfy-log4j2`: the module never drags a Log4j2 of its
own onto your classpath, so the version your application declares is the one that runs. Native-image
reachability metadata for the `Ntfy` plugin is generated at build time by log4j-core's own annotation
processor and ships inside the jar.

## Spring Boot (`ntfy-spring-boot-starter`)

| Version | Status |
|---------|--------|
| 4.1.x   | Tested — the version pinned in this repo (`spring-boot.version` in `pom.xml`) |
| 3.x / 4.x (other) | Expected to work — the starter uses stable `@AutoConfiguration` + `@ConfigurationProperties` binding and installs onto whichever logging backend is bound |

The starter is **backend-agnostic**: it installs onto whichever backend Spring Boot is actually
running on — Logback (the default) or Log4j2 (`spring-boot-starter-log4j2`). `ntfy-logback` arrives
transitively with the starter; Log4j2 applications additionally declare `io.github.pimak:ntfy-log4j2`
alongside it. The `NtfyClient` bean and the Micrometer meters are available either way. See
[spring-boot.md](spring-boot.md).

## Micronaut (`ntfy-micronaut`)

| Version | Status |
|---------|--------|
| 4.10.17 | Tested — the `micronaut-platform` version pinned in this repo (`micronaut.version` in `pom.xml`), resolving Micronaut core 4.10.26; the version the module compiles and runs its tests against |
| 4.10.x / 4.x (other) | Expected to work — the only API surface used is the stable `@ConfigurationProperties`, `@Factory`/`@Singleton`, `@Requires(classes = …)` and `ApplicationEventListener<StartupEvent>` contract |
| 5.x | **Not supported (yet)** — see below |

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
| 3.38.x | Tested — the version pinned in this repo (`quarkus.version`), exercised JVM + native by the `ntfy-quarkus/integration-tests` module |
| 3.x (other) | Expected to work within the stable extension-API surface used (`LogHandlerBuildItem`, `@ConfigMapping @ConfigRoot(RUN_TIME)`, `@Recorder`) |

The extension's Micrometer binding is **optional and conditional** — the meters appear only if the
application brings `quarkus-micrometer` itself. See the Micrometer section below.

## Micrometer (optional — Spring Boot and Quarkus)

| Version | Status |
|---------|--------|
| Whatever your application's BOM resolves | Supported — this project pins nothing |
| 1.17.0 | Tested — what CI actually exercises on both surfaces; `spring-boot-dependencies` 4.1.0 and `quarkus-bom` 3.38.1 happen to resolve the same version |
| 1.x (other) | Expected to work — the only API surface used is `MeterRegistry`, `FunctionCounter.builder(…)`, and (on Quarkus) the `MeterBinder` interface |

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
| GraalVM CE **25** (`graalvm-community`, JDK 25) | Tested — the CI `native-smoke` job, every push/PR |
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
