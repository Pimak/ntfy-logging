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

## Logback (`ntfy-logback`, and Spring Boot via it)

| Version | Status |
|---------|--------|
| 1.5.38  | Tested — the exact version pinned in this repo's `pom.xml` and CI |
| 1.5.x (other) | Expected to work — no API surface used beyond what's stable across the 1.5 line, including the `ch.qos.logback.classic.spi.Configurator` SPI used for zero-code auto-install |
| 1.2.x / 1.3.x | Not tested, not supported — the adapter targets the modern JavaBean/Joran and `Configurator` SPIs |

## Spring Boot (`ntfy-spring-boot-starter`)

| Version | Status |
|---------|--------|
| 3.4.x   | Tested — the version pinned in this repo (`spring-boot.version` in `pom.xml`) |
| 3.x (other, on Logback) | Expected to work — the starter uses stable `@AutoConfiguration` + `@ConfigurationProperties` binding and installs the Logback `ntfy-auto` appender |

Spring Boot must be running on Logback (the default) — the starter installs a Logback appender.

## Quarkus (`ntfy-quarkus-runtime` / `-deployment`)

| Version | Status |
|---------|--------|
| 3.15.x (LTS) | Tested — the version pinned in this repo (`quarkus.version`), exercised JVM + native by the `ntfy-quarkus/integration-tests` module |
| 3.x (other) | Expected to work within the stable extension-API surface used (`LogHandlerBuildItem`, `@ConfigMapping @ConfigRoot(RUN_TIME)`, `@Recorder`) |

## GraalVM native image

Supported through the **Quarkus extension**: the alert engine's `HttpClient`, threads, and digest
scheduler are all created at `RUNTIME_INIT` (never build-time / static-init), which is what keeps the
extension native-safe. The `ntfy-quarkus/integration-tests` module native-compiles a real app and
runs its `@QuarkusIntegrationTest` against the native binary in the CI `native-smoke` job.

For a **hand-rolled** (non-Quarkus) native build of `ntfy-core` / `ntfy-logback`, `ntfy-core` ships
native-image metadata under
`META-INF/native-image/io.github.pimak/ntfy-core/` — `--enable-url-protocols=https` (so the JDK
`HttpClient` TLS handler survives image build) and a resource registration for `ntfy.properties` (so
`ConfigLoader` can read it at image run time). GraalVM 21+ picks these up automatically.

## ntfy server

Verified against [ntfy.sh](https://ntfy.sh) (the public hosted instance) and a self-hosted ntfy
instance. The engine targets ntfy's plain publish-with-headers HTTP API (`POST` to the topic URL,
`Title`/`Priority`/`Tags` as headers, body as the message text) — any ntfy server implementing that
stable API surface should work.

## See also

- [configuration.md](configuration.md) — the unified key reference and per-framework mapping.
- [troubleshooting.md](troubleshooting.md) — diagnostics and where each framework surfaces them.
