<!--
The checklist below carries the three requirements CONTRIBUTING.md states under "Pull requests".
Nothing else is asked of you.
-->

## What changes, and why

<!-- The behavior before and after, and the reason for the change. Link the issue it closes, if there is one. -->

## Checklist

- [ ] **One logical change.** Unrelated fixes belong in their own PR: a mixed diff cannot be
      reviewed against the behavior it claims to change, nor reverted without taking the rest
      of the PR with it.
- [ ] **Tests added or updated** for the behavior this changes, so the next refactor cannot
      undo it silently. Docs-only PRs excepted.
- [ ] **`./mvnw verify` passes locally.** It runs the same gates CI does — unit tests, the
      WireMock integration tests, the dependency-allowlist enforcer, and the PMD dead-code
      guard. A new direct or transitive dependency also needs the allowlist in `pom.xml`
      updated, or this fails; see
      [CONTRIBUTING.md](https://github.com/Pimak/ntfy-logging/blob/main/CONTRIBUTING.md#dependency-policy).
