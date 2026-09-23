#!/usr/bin/env python3
"""Re-apply the StreamCut name to Compose string resources after an upstream merge.

Upstream Nuvio adds and edits strings that say "Nuvio". This replaces the whole
word "Nuvio" with "StreamCut" in every values*/strings.xml entry, except the keys
listed in branding-allowlist.txt (third-party device-auth texts, attribution, ...).

    rebrand-strings.py          rewrite the files in place, print what changed
    rebrand-strings.py --check  change nothing; exit 1 if any entry still needs it

Only <string> values are touched, never keys, so ids like action_support_nuvio
survive. "NuvioMedia", "NuvioDesktop" and URLs are not whole-word matches and are
left alone.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
RESOURCES = ROOT / "composeApp/src/commonMain/composeResources"
ALLOWLIST = Path(__file__).with_name("branding-allowlist.txt")

STRING_RE = re.compile(r'(<string name="([^"]+)"[^>]*>)(.*?)(</string>)', re.S)
WORD_RE = re.compile(r"\bNuvio\b")


def allowed_keys() -> set[str]:
    keys = set()
    for line in ALLOWLIST.read_text().splitlines():
        key = line.split("#", 1)[0].strip()
        if key:
            keys.add(key)
    return keys


def main() -> int:
    check = "--check" in sys.argv[1:]
    allowed = allowed_keys()
    offenders = []

    for path in sorted(RESOURCES.glob("values*/strings.xml")):
        text = path.read_text(encoding="utf-8")

        def replace(match: re.Match) -> str:
            key, value = match.group(2), match.group(3)
            if key in allowed or not WORD_RE.search(value):
                return match.group(0)
            offenders.append(f"{path.relative_to(ROOT)}: {key}")
            return match.group(1) + WORD_RE.sub("StreamCut", value) + match.group(4)

        updated = STRING_RE.sub(replace, text)
        if not check and updated != text:
            path.write_text(updated, encoding="utf-8")

    if offenders:
        verb = "still name Nuvio" if check else "rebranded"
        print(f"{len(offenders)} string(s) {verb}:")
        for line in offenders:
            print(f"  {line}")
        if check:
            print("Run scripts/upstream/rebrand-strings.py, or add the key to branding-allowlist.txt.")
            return 1
    else:
        print("Strings: no Nuvio mentions outside the allowlist.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
