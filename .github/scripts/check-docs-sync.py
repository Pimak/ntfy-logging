#!/usr/bin/env python3
"""Fail if README.md and docs/index.md disagree on the set of per-library guides.

Both files carry a "pick your stack" table, and they cannot share one: the README links
``docs/spring-boot.md`` while the site's landing page, already inside ``docs/``, links
``spring-boot.md``. That single unavoidable duplication is what this script guards — adding a
module to one table and forgetting the other is the realistic drift, and it has happened before
(``ntfy-log4j2`` and ``ntfy-micronaut`` arrived after the first documentation pass).

Run from the repository root. Invoked twice on purpose:

* ``.github/workflows/docs.yml`` on every pull request — blocks the PR while fixing it is one line;
* ``.github/workflows/release.yml`` before the Central deploy — the last net, since a publish
  cannot be taken back.

Exit code 0 when the two agree, 1 when they drift (or a table cannot be found at all).
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# Guides are the pages under docs/ named after a stack. Reference pages (configuration, filtering,
# …) are deliberately out of scope: they are listed only in the MkDocs sidebar, never duplicated.
README = Path("README.md")
INDEX = Path("docs/index.md")

# README: [Spring Boot](docs/spring-boot.md) — the "Install & configure" table.
README_GUIDE = re.compile(r"\]\(docs/([a-z0-9-]+)\.md\)")
# docs/index.md: [Spring Boot](spring-boot.md) — same table, site-relative.
INDEX_GUIDE = re.compile(r"\]\(([a-z0-9-]+)\.md\)")

# Pages both files may legitimately link outside the stack table.
NOT_A_GUIDE = {
    "configuration", "alert-behavior", "authentication", "filtering", "level-routing",
    "mdc-context", "observability", "troubleshooting", "compatibility", "index",
}


def guides(path: Path, pattern: re.Pattern[str]) -> set[str]:
    if not path.is_file():
        sys.exit(f"check-docs-sync: {path} not found — run from the repository root.")
    found = set(pattern.findall(path.read_text(encoding="utf-8"))) - NOT_A_GUIDE
    if not found:
        sys.exit(f"check-docs-sync: no guide links found in {path}; has its table been restructured?")
    return found


def main() -> int:
    in_readme = guides(README, README_GUIDE)
    in_index = guides(INDEX, INDEX_GUIDE)
    if in_readme == in_index:
        print(f"check-docs-sync: OK — {len(in_readme)} guides listed in both "
              f"{README} and {INDEX}: {', '.join(sorted(in_readme))}")
        return 0

    print(f"check-docs-sync: FAILED — {README} and {INDEX} list different guides.", file=sys.stderr)
    for missing, where, other in (
        (in_readme - in_index, INDEX, README),
        (in_index - in_readme, README, INDEX),
    ):
        for guide in sorted(missing):
            print(f"  {guide}.md is linked from {other} but not from {where}", file=sys.stderr)
    print(f"\nAdd the missing row(s) so both tables cover the same set of modules.", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
