#!/usr/bin/env bash
#
# release.sh — one reviewed operation that prepares a release commit + tag, then STOPS.
#
# It bumps the pom version, stamps the CHANGELOG date, folds both into a single reviewed
# commit, and creates an annotated tag — but it NEVER pushes. Pushing the tag is the
# operator's deliberate point of no return (D-03): autoPublish=true means CI publishes to
# an immutable Central coordinate the moment the tag lands upstream.
#
# This script handles NO credentials. It echoes no secret, sets no secret, reads no secret.
#
# Usage: ./release.sh   (run from the repo root, on a clean main working tree)

set -euo pipefail

# --- single source of truth: change these two lines for a future release ---------------
VERSION="1.0.1"
TAG="v${VERSION}"
# ---------------------------------------------------------------------------------------

TODAY="$(date +%F)"

echo "==> Preparing release ${VERSION} (tag ${TAG})"

# 1) Version bump across the whole reactor (parent + every child module) as an explicit,
#    reviewed change (D-04). versions:set updates the parent and all child <version>/parent
#    references in one pass. A lingering -SNAPSHOT silently routes the deploy to the snapshot
#    repo (RESEARCH Pitfall 4).
echo "==> Bumping reactor version to ${VERSION}"
./mvnw -q versions:set -DnewVersion="${VERSION}" -DprocessAllModules=true -DgenerateBackupPoms=false

# 2) Release-time docs pass (D-06), folded into the SAME commit as the bump so the tagged
#    tree is fully consistent and nothing commits back after the tag.
echo "==> Stamping CHANGELOG [${VERSION}] date"
#    Stamp the CHANGELOG heading date (the real action for v0.1.0).
sed -i "s/^## \[${VERSION}\] - Unreleased/## [${VERSION}] - ${TODAY}/" CHANGELOG.md

#    Coordinate stamping across EVERY documentation file — the README plus each per-library
#    guide under docs/ — so no install snippet is ever left pointing at the previous release.
#    The README and each docs/*.md guide carry install snippets, so both seds are global:
#      - every <version>…</version> line in the Maven dependency snippets, and
#      - every `io.github.pimak:ntfy-<artifact>:<ver>` coordinate in prose (ntfy-core,
#        ntfy-logback, ntfy-spring-boot-starter, ntfy-quarkus-runtime).
#    Scope is deliberately the <version> tag and the `groupId:artifact:` coordinate only, so
#    illustrative version-like strings in prose or code samples (e.g. a `v1.2.3` example
#    message, or the Logback `1.5.38` line in compatibility.md) are never rewritten.
DOC_FILES=(README.md)
while IFS= read -r -d '' doc; do DOC_FILES+=("${doc}"); done < <(find docs -maxdepth 1 -name '*.md' -print0 | sort -z)
echo "==> Stamping version ${VERSION} across documentation: ${DOC_FILES[*]}"
for doc in "${DOC_FILES[@]}"; do
  sed -i "s#<version>[0-9][0-9.]*</version>#<version>${VERSION}</version>#g" "${doc}"
  sed -i "s#\(io.github.pimak:ntfy-[a-z-]*:\)[0-9][0-9.]*#\1${VERSION}#g" "${doc}"
done

# 3) Single reviewed commit folding the bump + docs pass.
echo "==> Committing release ${VERSION}"
git add pom.xml CHANGELOG.md README.md docs
git commit -m "chore(release): ${VERSION}"

# 4) Annotated tag.
echo "==> Tagging ${TAG}"
git tag -a "${TAG}" -m "Release ${VERSION}"

# 5) STOP before the push — the point of no return is the operator's deliberate act.
cat <<EOF

──────────────────────────────────────────────────────────────────────────────
  Release ${VERSION} is committed and tagged locally — NOTHING has been pushed.

  Review the commit and tag now:
      git show --stat HEAD
      git show ${TAG}

  When you are satisfied, push to trigger publication (POINT OF NO RETURN — D-03):
      git push origin main --follow-tags

  Because autoPublish=true, a validation-passing bundle goes live on the immutable
  Central coordinate as soon as CI runs on the pushed tag. There is no undo.
──────────────────────────────────────────────────────────────────────────────
EOF
