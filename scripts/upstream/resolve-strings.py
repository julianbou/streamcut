#!/usr/bin/env python3
"""Resolve merge conflicts in Compose string resources (values*/strings.xml).

Run during a conflicted upstream merge. For every conflict hunk it keeps the
entries of both sides, in upstream's order, and for a key both sides carry with
different text it keeps the side that changed it relative to the merge base
(upstream's when both did). Branding is compared through the rebrand, so a
"Nuvio" upstream line never beats StreamCut's own wording on that alone.

A hunk holding anything other than <string> entries is left untouched and
reported, for a human to resolve. Files with no hunk left are `git add`ed.

    resolve-strings.py            resolve every conflicted strings.xml
"""

import re
import subprocess
import sys

ENTRY_RE = re.compile(r'[ \t]*<string name="([^"]+)"[^>]*>.*?</string>[ \t]*\n?', re.S)
HUNK_RE = re.compile(
    r"^<<<<<<< [^\n]*\n(.*?)^=======\n(.*?)^>>>>>>> [^\n]*\n", re.S | re.M
)
WORD_RE = re.compile(r"\bNuvio\b")


def git(*args: str) -> str:
    return subprocess.run(["git", *args], check=True, capture_output=True, text=True).stdout


def entries(text: str) -> list[tuple[str, str]] | None:
    """Ordered (key, full entry text); None if the text holds anything else."""
    found, pos = [], 0
    for match in ENTRY_RE.finditer(text):
        if text[pos:match.start()].strip():
            return None
        found.append((match.group(1), match.group(0) if match.group(0).endswith("\n") else match.group(0) + "\n"))
        pos = match.end()
    if text[pos:].strip():
        return None
    return found


def value(entry: str) -> str:
    return WORD_RE.sub("StreamCut", entry.strip())


def resolve_file(path: str) -> int:
    base_text = git("show", f":1:{path}")
    base = {key: value(entry) for key, entry in (entries_loose(base_text))}
    text = open(path, encoding="utf-8").read()
    unresolved = 0

    def resolve(match: re.Match) -> str:
        nonlocal unresolved
        ours, theirs = entries(match.group(1)), entries(match.group(2))
        if ours is None or theirs is None:
            unresolved += 1
            return match.group(0)
        ours_map = dict(ours)
        out, seen = [], set()
        for key, their_entry in theirs:
            seen.add(key)
            our_entry = ours_map.get(key)
            if our_entry is None or value(our_entry) == value(their_entry):
                out.append(their_entry)
            elif value(their_entry) == base.get(key):
                out.append(our_entry)  # only StreamCut changed it
            else:
                out.append(their_entry)  # upstream changed it (or both did)
        out += [entry for key, entry in ours if key not in seen]
        return "".join(out)

    text = HUNK_RE.sub(resolve, text)
    # An entry StreamCut kept may now also exist where upstream moved it.
    lines, keys = [], set()
    for chunk in re.split(r"(?=^[ \t]*<string name=)", text, flags=re.M):
        match = re.match(r'[ \t]*<string name="([^"]+)"', chunk)
        if match and match.group(1) in keys:
            chunk = ENTRY_RE.sub("", chunk, count=1)
        elif match:
            keys.add(match.group(1))
        lines.append(chunk)
    open(path, "w", encoding="utf-8").write("".join(lines))
    if unresolved == 0:
        git("add", path)
    return unresolved


def entries_loose(text: str) -> list[tuple[str, str]]:
    return [(m.group(1), m.group(0)) for m in ENTRY_RE.finditer(text)]


def main() -> int:
    conflicted = [
        path for path in git("diff", "--name-only", "--diff-filter=U").splitlines()
        if re.search(r"composeResources/values[^/]*/strings\.xml$", path)
    ]
    left = 0
    for path in conflicted:
        count = resolve_file(path)
        left += count
        print(f"{'resolved' if count == 0 else f'{count} hunk(s) left in'} {path}")
    return 1 if left else 0


if __name__ == "__main__":
    sys.exit(main())
