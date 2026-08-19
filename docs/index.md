# ntfy-logging

--8<-- "README.md:tagline"

--8<-- "README.md:engine"

## Pick your stack

Each library has its own guide with the dependency snippet, the Maven Central link, and the base
config to get error alerts publishing:

| Guide | Artifact | The 30-second version |
|---|---|---|
| **[Spring Boot](spring-boot.md)** | `ntfy-spring-boot-starter` | Add the starter, set `ntfy.url`/`ntfy.topic` in `application.yml` — error logs auto-publish; `@Autowired NtfyClient` for manual ones. |
| **[Quarkus](quarkus.md)** | `ntfy-quarkus-runtime` | Add the extension, set `quarkus.ntfy.url`/`.topic` — error logs auto-publish (native-ready); `@Inject NtfyClient` for manual ones. |
| **[Micronaut](micronaut.md)** | `ntfy-micronaut` | Add the module, set `ntfy.url`/`ntfy.topic` in `application.yml` — error logs auto-publish on the Logback root logger; `@Inject NtfyClient` for manual ones. |
| **[Plain Logback](logback.md)** | `ntfy-logback` | Add the appender, set `NTFY_URL`/`NTFY_TOPIC` — zero-code auto-install, or wire it explicitly in `logback.xml`. |
| **[Plain Log4j2](log4j2.md)** | `ntfy-log4j2` | Add the appender, set `NTFY_URL`/`NTFY_TOPIC` — declare `<Ntfy .../>` in `log4j2.xml`, or `NtfyLog4j2Installer.install()` in `main`. |
| **[java.util.logging](jul.md)** | `ntfy-jul` | Add the handler, set `NTFY_URL`/`NTFY_TOPIC` — declare it in `logging.properties`, or `NtfyJulInstaller.install()` in `main`. |
| **[Core](core.md)** | `ntfy-core` | Build a `NtfyConfig`, drive `NtfyClient` yourself from any JVM app. |

Each module pulls `ntfy-core` transitively — you only ever declare the one that matches your stack.
Everything beyond the base config — the [full key reference](configuration.md),
[authentication](authentication.md), [alert behavior](alert-behavior.md),
[level routing](level-routing.md), [filtering](filtering.md), [MDC context](mdc-context.md),
[observability](observability.md), [troubleshooting](troubleshooting.md) and
[compatibility](compatibility.md) — is shared across all seven and listed in the sidebar.

## API documentation

The aggregated Javadoc for every published module is part of this site, versioned alongside it:

[Browse the Javadoc :material-arrow-right:](apidocs/index.html){ .md-button }

## Runnable examples

Every adapter has a small, realistic example application, and the guides above embed their real
configuration files directly. They are not illustrations: their integration tests boot the example
apps against a loopback ntfy stand-in in CI — covering the base alert path, the synchronous-vs-
asynchronous delivery difference, and the clean-shutdown digest flush — so the examples on this site
can never drift from the code.
