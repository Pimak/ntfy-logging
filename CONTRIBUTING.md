# Contributing

## Build

```bash
./mvnw verify
```

This runs unit tests, integration tests (WireMock-based), the dependency-allowlist
enforcer check, and the dead-code static-analysis guard (PMD) — all gates CI runs too.

## Dependency policy

`ntfy-logging` is zero-dependency beyond Logback + the JDK. Any new dependency (direct
or transitive) must be added to the `maven-enforcer-plugin` allowlist in `pom.xml`
(`enforce-dependency-allowlist` execution) — see the comment block above that rule for
the exact process. A PR that adds a dependency without updating the allowlist will fail
`mvn verify` locally and in CI.

## Pull requests

- Keep changes focused; one logical change per PR.
- Add or update tests for behavior changes.
- `./mvnw verify` must pass before requesting review.

## Commit messages

[Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/):
`type(scope): summary`, the summary in the imperative, lower case, no trailing period, on
one line. Nothing parses the log — the CHANGELOG is written by hand and the GitHub Release
notes are extracted from it, not from commit subjects. The convention is there so that
`git log --oneline` reads as a change history, and so the scope says which module a commit
landed in without opening the diff.

The types in use:

| Type | For |
|---|---|
| `feat` | behavior a user can observe or configure that did not exist before |
| `fix` | a defect in behavior that already shipped |
| `docs` | README, `docs/`, Javadoc, code comments |
| `test` | tests only — no production code in the diff |
| `refactor` | production code reshaped with no behavior change |
| `build` | the Maven build: poms, plugins, profiles, dependency versions |
| `ci` | `.github/workflows/`, `.github/actions/`, `.github/scripts/` |
| `chore` | the rest, which is neither the product nor the build |

The scope is optional and names what the change actually touches:

- a module, by its short name — `core`, `logback`, `log4j2`, `jul`, `spring`, `micronaut`,
  `quarkus` (the short name, not the artifactId: `spring`, not `spring-boot-starter`);
- several of them, comma-separated, when one change lands across modules:
  `feat(spring, micronaut, quarkus): bind the three warn keys`;
- `deps` for dependency version bumps (`build(deps):` — what Dependabot opens);
- a documentation area for docs-only changes — `readme`, `compatibility`, `examples`;
- `release`, which is reserved: `chore(release): <version>` is written by `release.sh` and
  by nothing else.

A breaking change is marked with `!` before the colon (`feat!: …`). Its body has to say what
the previous behavior was and how to get it back, because that is what the CHANGELOG's
breaking entry and the migration notes are written from.

Use a body whenever the *why* does not fit in the subject — that is where the reasoning
belongs, not in the code.
