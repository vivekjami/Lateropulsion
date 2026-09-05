#!/usr/bin/env python3
"""Verifies every dependency in gradle/libs.versions.toml has a SOUP entry (IEC 62304 §8.1.2).

The SOUP list at docs/traceability/soup.md is a Markdown table whose first column is the
Maven coordinate `group:name` (or the plugin id for plugins). This script fails if any
catalog library or plugin is missing from that table.
"""
import re
import sys
import os

ROOT = sys.argv[1] if len(sys.argv) > 1 else "."
CATALOG = os.path.join(ROOT, "gradle", "libs.versions.toml")
SOUP = os.path.join(ROOT, "docs", "traceability", "soup.md")

lib_re = re.compile(r'^\s*[\w\-]+\s*=\s*\{\s*group\s*=\s*"([^"]+)"\s*,\s*name\s*=\s*"([^"]+)"')
plugin_re = re.compile(r'^\s*[\w\-]+\s*=\s*\{\s*id\s*=\s*"([^"]+)"')


def main():
    coords = set()
    section = None
    with open(CATALOG, encoding="utf-8") as fh:
        for line in fh:
            s = line.strip()
            if s.startswith("["):
                section = s
                continue
            if section == "[libraries]":
                m = lib_re.match(line)
                if m:
                    coords.add(f"{m.group(1)}:{m.group(2)}")
            elif section == "[plugins]":
                m = plugin_re.match(line)
                if m:
                    coords.add(m.group(1))
    listed = set()
    with open(SOUP, encoding="utf-8") as fh:
        for line in fh:
            if line.startswith("|"):
                cells = [c.strip().strip("`") for c in line.strip().strip("|").split("|")]
                if cells:
                    listed.add(cells[0])
    missing = sorted(c for c in coords if c not in listed)
    if missing:
        print("SOUP check FAILED. Missing entries in docs/traceability/soup.md:")
        for m in missing:
            print("  -", m)
        return 1
    print(f"SOUP check passed ({len(coords)} catalog entries all listed).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
