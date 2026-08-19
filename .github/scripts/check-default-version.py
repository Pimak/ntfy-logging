#!/usr/bin/env python3
"""Refuse a `mike set-default` that would send the site root backwards.

mike accepts any existing identifier without comment, including a superseded one — a realistic slip
right after a backport. Reads `mike list -j` on stdin. Exit 0 when the target is safe, 1 otherwise.
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
        if len(carriers) > 1:
            # mike moves an alias rather than duplicating it, so versions.json has been damaged;
            # picking a carrier would make the root depend on JSON ordering.
            print(f"check-default-version: '{args.target}' is an alias on more than one version "
                  f"({', '.join(sorted(carriers))}), so what it points at is ambiguous. Repair "
                  f"versions.json on gh-pages before setting the root.", file=sys.stderr)
            return 1
        resolved = carriers[0]

    releases = sorted((v for v in versions if RELEASE.match(v)), key=sort_key)
    newest = releases[-1] if releases else None

    if newest is None:
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
