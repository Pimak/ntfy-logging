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
# Usage: ./release.sh <VERSION>   (explicit version, e.g. ./release.sh 1.2.0)
#        ./release.sh --patch | -p   (bump the patch component of the last tag)
#        ./release.sh --minor | -m   (bump the minor component, reset patch)
#        ./release.sh --major | -M   (bump the major component, reset minor+patch)
#
# For the three bump modes the next version is derived from the highest semver tag reachable
# on the CURRENT branch (git tag --merged HEAD), incremented per semver. Run from the repo
# root, on a clean main working tree.

set -euo pipefail

usage() {
  cat >&2 <<EOF
Usage: $0 <VERSION>       explicit version, e.g. $0 1.2.0
       $0 --patch | -p    bump patch of the last tag  (1.2.3 -> 1.2.4)
       $0 --minor | -m    bump minor of the last tag  (1.2.3 -> 1.3.0)
       $0 --major | -M    bump major of the last tag  (1.2.3 -> 2.0.0)
EOF
  exit 2
}

# Highest semver tag (vMAJOR.MINOR.PATCH) reachable on the current branch.
last_version() {
  local tag
  tag="$(git tag --merged HEAD --list 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname | head -n1)"
  if [[ -z "${tag}" ]]; then
    echo "ERROR: no vMAJOR.MINOR.PATCH tag found on the current branch to bump from." >&2
    exit 1
  fi
  echo "${tag#v}"
}

# Increment BASE_VERSION ($1) by the requested component ($2: major|minor|patch).
bump() {
  local base="$1" part="$2"
  if [[ ! "${base}" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    echo "ERROR: last tag version '${base}' is not a MAJOR.MINOR.PATCH semver." >&2
    exit 1
  fi
  local major="${BASH_REMATCH[1]}" minor="${BASH_REMATCH[2]}" patch="${BASH_REMATCH[3]}"
  case "${part}" in
    major) echo "$((major + 1)).0.0" ;;
    minor) echo "${major}.$((minor + 1)).0" ;;
    patch) echo "${major}.${minor}.$((patch + 1))" ;;
  esac
}

# --- resolve the target VERSION from exactly one CLI argument ---------------------------
[[ $# -eq 1 ]] || usage

case "$1" in
  -p|--patch) VERSION="$(bump "$(last_version)" patch)" ;;
  -m|--minor) VERSION="$(bump "$(last_version)" minor)" ;;
  -M|--major) VERSION="$(bump "$(last_version)" major)" ;;
  -*)         usage ;;
  *)
    VERSION="${1#v}"
    [[ "${VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
      || { echo "ERROR: '$1' is not a MAJOR.MINOR.PATCH version." >&2; exit 1; }
    ;;
esac

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
#    The permanent `## [Unreleased]` heading is NEVER removed — it stays at the top as the
#    landing zone for the next cycle's entries. Instead we insert a new dated release section
#    immediately below it (separated by one blank line), which claims everything currently
#    accumulated under Unreleased for ${VERSION}. Fail loudly if the anchor heading is gone.
grep -qxF '## [Unreleased]' CHANGELOG.md \
  || { echo "ERROR: '## [Unreleased]' heading not found in CHANGELOG.md." >&2; exit 1; }
sed -i "s/^## \[Unreleased\]$/## [Unreleased]\n\n## [${VERSION}] - ${TODAY}/" CHANGELOG.md

#    Regenerate the reference-link block at the very bottom of the CHANGELOG from the version
#    headings now present in the file (the new ${VERSION} section included). This rewrites the
#    whole block in one pass, so it both refreshes the [Unreleased] link (now anchored at the
#    freshly released version) and repairs any stale/missing per-tag links, rather than only
#    touching the new entry. Links are derived, never hand-maintained:
#      - [Unreleased] -> compare/v<newest>...HEAD
#      - [X.Y.Z]      -> compare/v<previous>...vX.Y.Z
#      - the oldest   -> releases/tag/v<oldest>
#    The repo base URL is derived from the existing [Unreleased] link so nothing is hardcoded.
echo "==> Regenerating CHANGELOG compare links"
CL_BASE="$(sed -n 's#^\[Unreleased\]: \(https\{0,1\}://[^ ]*\)/compare/.*#\1#p' CHANGELOG.md | head -n1)"
[[ -n "${CL_BASE}" ]] \
  || { echo "ERROR: could not derive the repo base URL from the [Unreleased] link in CHANGELOG.md." >&2; exit 1; }
mapfile -t CL_VERSIONS < <(grep -oE '^## \[[0-9]+\.[0-9]+\.[0-9]+\]' CHANGELOG.md | sed -E 's/^## \[(.*)\]/\1/')
[[ ${#CL_VERSIONS[@]} -gt 0 ]] \
  || { echo "ERROR: no versioned section headings found in CHANGELOG.md." >&2; exit 1; }
CL_LINKS="$(
  printf '[Unreleased]: %s/compare/v%s...HEAD\n' "${CL_BASE}" "${CL_VERSIONS[0]}"
  n=${#CL_VERSIONS[@]}
  for ((i = 0; i < n; i++)); do
    if (( i + 1 < n )); then
      printf '[%s]: %s/compare/v%s...v%s\n' "${CL_VERSIONS[i]}" "${CL_BASE}" "${CL_VERSIONS[i+1]}" "${CL_VERSIONS[i]}"
    else
      printf '[%s]: %s/releases/tag/v%s\n' "${CL_VERSIONS[i]}" "${CL_BASE}" "${CL_VERSIONS[i]}"
    fi
  done
)"
#    Drop the old link definitions, then re-append the fresh block separated by one blank line.
#    The command substitution strips the file's trailing blank lines, so spacing stays exact.
sed -i -E '/^\[[^]]+\]: https?:\/\//d' CHANGELOG.md
{ printf '%s\n\n' "$(< CHANGELOG.md)"; printf '%s\n' "${CL_LINKS}"; } > CHANGELOG.md.tmp
mv CHANGELOG.md.tmp CHANGELOG.md

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

# 3) Single reviewed commit folding the bump + docs pass. versions:set touches every
#    module's pom.xml (root + each child), so stage them all by name, not just the root
#    pom.xml — a partial stage here silently ships a reactor with mismatched module
#    versions, which Central then rejects (or worse, half-publishes).
echo "==> Committing release ${VERSION}"
git add $(find . -name pom.xml -not -path '*/target/*') CHANGELOG.md README.md docs
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
