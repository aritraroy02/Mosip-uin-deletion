#!/usr/bin/env python3
"""
Render the two static-page charts for local development.

The pages in collab-ui/delete-uin and collab-ui/landing-page are Helm templates: the
config they run on arrives as {{ .Values.x.y }} placeholders that Helm fills in
at install time. Opened straight from the repo, nothing is substituted, so the
delete-uin page detects the un-rendered placeholders, forces demo mode, and
never actually redirects to eSignet -- which is exactly the "stuck on
Redirecting to eSignet" symptom.

This script does the substitution Helm would do, using values-local.json, and
writes the result to dist/. No Helm, no chart dependencies, stdlib only.

    python render.py

Production deployment is unaffected: each chart's own values.yaml is never read
or written here.
"""

import json
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
CHARTS = os.path.dirname(HERE)
DIST = os.path.join(HERE, "dist")

# (source template, output subdirectory)
PAGES = [
    (os.path.join(CHARTS, "landing-page", "collab-index.html"), "landing-page"),
    (os.path.join(CHARTS, "delete-uin", "delete-uin-index.html"), "delete-uin"),
]

# {{ .Values.a.b }} with any amount of internal whitespace, and optional Helm
# pipes such as `| quote` which we ignore for local rendering.
PLACEHOLDER = re.compile(r"\{\{-?\s*\.Values\.([A-Za-z0-9_.]+)\s*(?:\|[^}]*)?-?\}\}")


def lookup(values, dotted):
    node = values
    for part in dotted.split("."):
        if not isinstance(node, dict) or part not in node:
            return None
        node = node[part]
    return node


def render(path, values, missing):
    with open(path, encoding="utf-8") as fh:
        text = fh.read()

    def sub(match):
        key = match.group(1)
        val = lookup(values, key)
        if val is None:
            missing.add(key)
            return match.group(0)
        return str(val)

    return PLACEHOLDER.sub(sub, text)


def main():
    with open(os.path.join(HERE, "values-local.json"), encoding="utf-8") as fh:
        values = json.load(fh)
    values.pop("_comment", None)

    if os.path.isdir(DIST):
        shutil.rmtree(DIST)

    missing = set()
    for src, outdir in PAGES:
        if not os.path.exists(src):
            sys.exit("missing template: %s" % src)
        target_dir = os.path.join(DIST, outdir)
        os.makedirs(target_dir, exist_ok=True)
        # Served as index.html so the redirect_uri can be a bare origin with a
        # trailing slash, which is what is registered on the eSignet client.
        target = os.path.join(target_dir, "index.html")
        rendered = render(src, values, missing)
        with open(target, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(rendered)
        left = len(PLACEHOLDER.findall(rendered))
        print("  %-46s -> %s%s" % (
            os.path.relpath(src, CHARTS),
            os.path.relpath(target, HERE),
            "" if left == 0 else "   (%d placeholder(s) left)" % left,
        ))

    if missing:
        print("\n  WARNING: no local value for: %s" % ", ".join(sorted(missing)))
        print("  Those placeholders were left as-is; the delete-uin page will")
        print("  fall back to demo mode if esignet.authorizeUrl is among them.")
        return 1

    print("\n  rendered into %s" % DIST)
    return 0


if __name__ == "__main__":
    sys.exit(main())
