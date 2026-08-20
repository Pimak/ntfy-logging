#!/usr/bin/env bash
#
# release.sh — one reviewed operation that prepares a release commit + tag, then STOPS.
#
# It bumps the pom version, stamps the CHANGELOG date, folds both into a single reviewed
# commit, and creates an annotated tag — but it NEVER pushes. Pushing the tag is the
# operator's deliberate point of no return (D-03): autoPublish=true means CI publishes to
# an immutable Central coordinate the moment the tag lands upstream.
#
# Before it touches anything it refuses a non-major version when the CHANGELOG's `## [Unreleased]`
# section announces a breaking change — see step 0.
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

# Highest semver tag (vMAJOR.MINOR.PATCH) reachable on the current branch, or empty when the
# branch carries none. Empty is a legitimate answer — the very first release has nothing behind it.
previous_version() {
  local tags
  # No `| head -n1`: under `set -o pipefail`, head closing the pipe early can leave git killed by
  # SIGPIPE, and the pipeline's 141 would abort the whole script through `set -e` — turning this
  # deliberately non-fatal lookup fatal. It takes thousands of tags to reach that, so it has never
  # fired here, which is exactly why it would surface on someone else's repo instead of ours.
  # Trimming the first line in the shell keeps a genuine git failure fatal, which `|| true` would
  # have swallowed into a silent "no previous tag" — and that answer skips the SemVer guard.
  tags="$(git tag --merged HEAD --list 'v[0-9]*.[0-9]*.[0-9]*' --sort=-v:refname)"
  local tag="${tags%%$'\n'*}"
  echo "${tag#v}"
}

# Same, but a missing tag is fatal: the three bump modes have no base to increment.
last_version() {
  local version
  version="$(previous_version)"
  if [[ -z "${version}" ]]; then
    echo "ERROR: no vMAJOR.MINOR.PATCH tag found on the current branch to bump from." >&2
    exit 1
  fi
  echo "${version}"
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
  -p|--patch) PREV_VERSION="$(last_version)"; VERSION="$(bump "${PREV_VERSION}" patch)" ;;
  -m|--minor) PREV_VERSION="$(last_version)"; VERSION="$(bump "${PREV_VERSION}" minor)" ;;
  -M|--major) PREV_VERSION="$(last_version)"; VERSION="$(bump "${PREV_VERSION}" major)" ;;
  -*)         usage ;;
  *)
    VERSION="${1#v}"
    [[ "${VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
      || { echo "ERROR: '$1' is not a MAJOR.MINOR.PATCH version." >&2; exit 1; }
    # An explicit version needs no tag to bump from, so the non-fatal lookup: on a first release
    # PREV_VERSION is legitimately empty and the SemVer guard below reads that as "nothing has
    # shipped yet, so there is no published contract to break".
    PREV_VERSION="$(previous_version)"
    ;;
esac

TAG="v${VERSION}"
# ---------------------------------------------------------------------------------------

TODAY="$(date +%F)"

echo "==> Preparing release ${VERSION} (tag ${TAG})"

# 0) SemVer guard, deliberately the FIRST thing after the version is resolved and before ANY file
#    is touched: a refusal that fires once the pom is bumped and the CHANGELOG stamped leaves the
#    operator a half-released tree to unpick by hand.
#    A breaking change announced under `## [Unreleased]` may only ship as a MAJOR bump. The promise
#    reaches far past the pom: roughly thirty "since 2.0" notes across README.md and docs/ tell
#    readers which release a behavior arrived in, and each of them is only true while the major
#    line and the breaking changes stay in step. Slip a breaking change out under a patch and every
#    one of those notes quietly becomes a lie no test can catch.
echo "==> Checking the CHANGELOG's Unreleased section against the bump"
#    The guard is only meaningful while the anchor heading exists — without it the section scan
#    below finds nothing and waves everything through — so assert it here rather than at the stamp
#    in step 2, which runs after the reactor has already been bumped.
grep -qxF '## [Unreleased]' CHANGELOG.md \
  || { echo "ERROR: '## [Unreleased]' heading not found in CHANGELOG.md." >&2; exit 1; }

#    Scope is the Unreleased section ALONE — its heading down to the next `## [` — because the
#    breaking notes already recorded under [2.0.0] are history and must never block a 2.0.1. Note
#    step 2 inserts the new dated section *directly beneath* the permanent heading, so on the next
#    run these same boundaries correctly see an empty Unreleased.
#    `grep -w` on the uppercase token matches how this project actually writes the notes
#    (`**BREAKING: …**`, and Conventional Commits' `BREAKING CHANGE`) while ignoring ordinary
#    lowercase prose — "breaking the connection" in an entry describing a bugfix must not veto a
#    patch release, or the guard trains the operator to work around it.
if [[ -n "${PREV_VERSION}" ]] && (( "${VERSION%%.*}" <= "${PREV_VERSION%%.*}" )); then
  BREAKING_NOTES="$(
    awk '/^## \[Unreleased\]$/ { unreleased = 1; next }
         unreleased && /^## \[/ { exit }
         unreleased' CHANGELOG.md | grep -w 'BREAKING' || true
  )"
  if [[ -n "${BREAKING_NOTES}" ]]; then
    cat >&2 <<EOF
ERROR: '## [Unreleased]' announces a breaking change, but ${VERSION} is not a major bump over ${PREV_VERSION}.

${BREAKING_NOTES}

Release this as $(( ${PREV_VERSION%%.*} + 1 )).0.0 instead (./release.sh --major), or — if the entry is not
actually breaking — reword it and move it into a non-breaking section of the CHANGELOG.
EOF
    exit 1
  fi
fi

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
#    accumulated under Unreleased for ${VERSION}. The anchor heading was already asserted by the
#    SemVer guard in step 0, back when failing was still free.
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
#      - every `io.github.pimak:ntfy-<artifact>:<ver>` coordinate in prose (ntfy-core, ntfy-jul,
#        ntfy-logback, ntfy-log4j2, ntfy-spring-boot-starter, ntfy-quarkus-runtime).
#    The artifact character class MUST keep the digits: ntfy-log4j2 is the first artifact whose
#    name is not purely [a-z-], and a class that omits 0-9 silently skips it — leaving the log4j2
#    install snippets frozen at the previous version while every other artifact is stamped.
#    Scope is deliberately the <version> tag and the `groupId:artifact:` coordinate only, so
#    illustrative version-like strings in prose or code samples (e.g. a `v1.2.3` example
#    message, or the Logback `1.5.38` line in compatibility.md) are never rewritten.
DOC_FILES=(README.md)
while IFS= read -r -d '' doc; do DOC_FILES+=("${doc}"); done < <(find docs -maxdepth 1 -name '*.md' -print0 | sort -z)
echo "==> Stamping version ${VERSION} across documentation: ${DOC_FILES[*]}"
for doc in "${DOC_FILES[@]}"; do
  sed -i "s#<version>[0-9][0-9.]*</version>#<version>${VERSION}</version>#g" "${doc}"
  sed -i "s#\(io.github.pimak:ntfy-[a-z0-9-]*:\)[0-9][0-9.]*#\1${VERSION}#g" "${doc}"
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
