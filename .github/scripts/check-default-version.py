#!/usr/bin/env python3
"""Refuse a `mike set-default` that would send the site root backwards.

`mike set-default` accepts any existing identifier without comment, including an older minor:
setting it to 2.0 while 2.1 is published silently points https://pimak.github.io/ntfy-logging/ at
the superseded docs, and nothing on the site says so. That is a realistic slip right after a
backport — you republish the 2.0 pages, then set the default out of the same habit.

This is the guard for that. It reads `mike list -j` on stdin and decides whether TARGET is a safe
default:

* an alias is resolved to the version carrying it, so `latest` is judged on what it points at;
* a release version is safe only when it is the newest published one;
* a non-release version (`dev`) is safe only while nothing has been released yet, i.e. during the
  initial bootstrap — once a release exists, defaulting to `dev` would land every visitor on
  unreleased documentation.

--force overrides the refusal for the rare deliberate case (say, pulling the root back off a
release that turned out to be broken). It is a separate, explicit input so it cannot happen by
accident.

Exit code 0 when the default is safe to set, 1 otherwise.
"""

from __future__ import annotations

import argparse
import json
import re
import sys

RELEASE = re.compile(r"^([0-9]+)\.([0-9]+)$")


def sort_key(version: str) -> tuple[int, int]:
    major, minor = RELEASE.match(version).groups()
    return int(major), int(minor)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", help="the version or alias to make the site root's default")
    parser.add_argument("--force", action="store_true",
                        help="set it anyway, even if it points at superseded documentation")
    args = parser.parse_args()

    try:
        deployed = json.load(sys.stdin)
    except json.JSONDecodeError as exc:
        print(f"check-default-version: could not parse the `mike list -j` output: {exc}",
              file=sys.stderr)
        return 1

    versions = {entry["version"]: entry.get("aliases", []) for entry in deployed}
    if not versions:
        print("check-default-version: nothing is deployed on gh-pages yet — deploy a version "
              "before choosing which one the site root points at.", file=sys.stderr)
        return 1

    # Resolve an alias to the version carrying it, so `latest` is judged on its target.
    resolved = args.target
    if args.target not in versions:
        carriers = [v for v, aliases in versions.items() if args.target in aliases]
        if not carriers:
            known = ", ".join(sorted(versions) + sorted(a for al in versions.values() for a in al))
            print(f"check-default-version: '{args.target}' is neither a deployed version nor an "
                  f"alias. Deployed: {known}", file=sys.stderr)
            return 1
        resolved = carriers[0]

    releases = sorted((v for v in versions if RELEASE.match(v)), key=sort_key)
    newest = releases[-1] if releases else None

    if newest is None:
        # Bootstrap: only dev-like versions exist, so any of them is the only sensible root.
        verdict = f"nothing is released yet, so '{args.target}' is the only sensible default"
        problem = None
    elif not RELEASE.match(resolved):
        problem = (f"'{args.target}' resolves to '{resolved}', which is not a release, while "
                   f"{newest} is published. Visitors landing on the site root would get unreleased "
                   f"documentation.")
        verdict = None
    elif resolved == newest:
        verdict = f"'{args.target}' resolves to {resolved}, the newest published release"
        problem = None
    else:
        problem = (f"'{args.target}' resolves to {resolved}, but {newest} is published. The site "
                   f"root would point at superseded documentation.")
        verdict = None

    if problem is None:
        print(f"check-default-version: OK — {verdict}.")
        return 0

    if args.force:
        print(f"check-default-version: WARNING — {problem}\nProceeding anyway because --force was "
              f"given.")
        return 0

    hint = "latest" if newest else "dev"
    print(f"check-default-version: REFUSED — {problem}", file=sys.stderr)
    print(f"\nUse '{hint}' instead, which follows the newest release automatically. If pointing the "
          f"root at '{args.target}' really is intended, re-run with the force option.",
          file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
