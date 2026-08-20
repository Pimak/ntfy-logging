"""MkDocs hook: resolves ``{{ pin.<property> }}`` from the root ``pom.xml``.

``docs/compatibility.md`` claims which versions the build pins. Written as literals, those numbers
went stale the moment Dependabot bumped a property — the page quietly said something false, and
``CompatibilityMatrixGuardTest`` turned every dependency PR red until someone edited the row by
hand. The page now names the property instead of the number, and this hook substitutes whatever
``pom.xml`` pins at build time. There is no longer anything to keep in sync.

Lives outside ``docs_dir`` on purpose: MkDocs copies unknown files from the docs tree into the
published site, and a hook is build machinery, not a page.
"""

import re
from pathlib import Path

from mkdocs.exceptions import PluginError

#: ``{{ pin.logback.version }}`` -> the ``<logback.version>`` property. Whitespace inside the
#: braces is tolerated so the markdown can be wrapped the way the rest of the page is.
_PLACEHOLDER = re.compile(r"\{\{\s*pin\.([A-Za-z0-9._-]+)\s*\}\}")

_pom = ""


def on_config(config):
    """Read the POM once per build — re-run on every rebuild, so ``mkdocs serve`` stays live."""
    global _pom
    pom_path = Path(config.config_file_path).parent / "pom.xml"
    try:
        _pom = pom_path.read_text(encoding="utf-8")
    except OSError as exc:
        raise PluginError(f"compat_pins: cannot read {pom_path}: {exc}") from exc
    return config


def on_page_markdown(markdown, page, **_):
    if "{{" not in markdown:
        return markdown
    return _PLACEHOLDER.sub(lambda m: _pin(m.group(1), page), markdown)


def _pin(prop, page):
    """The value ``pom.xml`` gives ``prop``. An unknown property fails the build rather than
    rendering a half-substituted sentence, which is the failure mode this hook exists to prevent."""
    quoted = re.escape(prop)
    found = re.search(rf"<{quoted}>\s*([^<]+?)\s*</{quoted}>", _pom)
    if not found:
        raise PluginError(
            f"compat_pins: {page.file.src_path} references '{{{{ pin.{prop} }}}}', "
            f"but pom.xml has no <{prop}> property"
        )
    return found.group(1)
