#!/usr/bin/env python3
"""Create a StreamCut trial account with hidden, preloaded addons.

The account is a normal email/password user (already confirmed, so no mail is
sent) with a row in public.trial_accounts and its addons stored as hidden:
the app uses them but never lists them. Needs supabase/patch-trial-accounts.sql
applied first.

Usage:
    SUPABASE_SERVICE_ROLE_KEY=... python3 scripts/trials/create_trial.py \
        --label "Lucas Baini" --email trial-baini@streamcut.app --days 30 \
        --addon https://aiostreams.example/stremio/<uuid>/<pass>/manifest.json

The service role key is in Supabase -> Project Settings -> API. It bypasses all
security, so keep it in your shell, never in the repo. The project URL is read
from local.properties (NUVIO_SUPABASE_URL) unless SUPABASE_URL is set.

Use one AIOStreams config per tester: if a URL leaks, you can kill that one
config without touching the others.
"""

import argparse
import datetime as dt
import json
import os
import secrets
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

# Installed on every trial account after the ones passed with --addon.
DEFAULT_ADDONS = [
    "https://opensubtitles-v3.strem.io/manifest.json",  # OpenSubtitles v3: subtitles, needed for phrase search
]


def project_url() -> str:
    url = os.environ.get("SUPABASE_URL", "").strip()
    if not url:
        props = REPO / "local.properties"
        for line in props.read_text().splitlines() if props.exists() else []:
            if line.startswith("NUVIO_SUPABASE_URL="):
                url = line.split("=", 1)[1].strip()
    if not url:
        sys.exit("Set SUPABASE_URL or NUVIO_SUPABASE_URL in local.properties")
    return url.rstrip("/")


def request(method: str, url: str, key: str, body=None, prefer=None):
    headers = {
        "apikey": key,
        "Authorization": f"Bearer {key}",
        "Content-Type": "application/json",
    }
    if prefer:
        headers["Prefer"] = prefer
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req) as resp:
            text = resp.read().decode()
            return json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {url} -> {e.code}: {e.read().decode()}")


def manifest_url(raw: str) -> str:
    url = raw.strip()
    if url.startswith("stremio://"):
        url = "https://" + url[len("stremio://"):]
    path, _, query = url.partition("?")
    path = path.rstrip("/")
    if not path.endswith("/manifest.json"):
        path += "/manifest.json"
    return f"{path}?{query}" if query else path


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--label", required=True, help="Who it is for, shown in the dashboard")
    ap.add_argument("--email", required=True, help="Login email (does not need to be a real inbox)")
    ap.add_argument("--password", help="Defaults to a random one")
    ap.add_argument("--days", type=int, default=30, help="Days until it expires; 0 = never")
    ap.add_argument("--addon", action="append", required=True, help="Manifest URL; repeat for several")
    ap.add_argument("--notes", default="")
    args = ap.parse_args()

    key = os.environ.get("SUPABASE_SERVICE_ROLE_KEY", "").strip()
    if not key:
        sys.exit("Set SUPABASE_SERVICE_ROLE_KEY")
    base = project_url()
    password = args.password or secrets.token_urlsafe(12)

    user = request("POST", f"{base}/auth/v1/admin/users", key, {
        "email": args.email,
        "password": password,
        "email_confirm": True,
    })
    user_id = user["id"]

    expires_at = None
    if args.days > 0:
        expires_at = (dt.datetime.now(dt.timezone.utc) + dt.timedelta(days=args.days)).isoformat()

    request("POST", f"{base}/rest/v1/trial_accounts", key, {
        "user_id": user_id,
        "label": args.label,
        "email": args.email,
        "expires_at": expires_at,
        "notes": args.notes,
    }, prefer="return=minimal")

    addons = list(dict.fromkeys(manifest_url(u) for u in args.addon + DEFAULT_ADDONS))
    request("POST", f"{base}/rest/v1/addons", key, [
        {
            "user_id": user_id,
            "profile_id": 1,
            "url": url,
            "enabled": True,
            "sort_order": i,
            "hidden": True,
        }
        for i, url in enumerate(addons)
    ], prefer="return=minimal")

    print(f"Trial account for {args.label}")
    print(f"  email:    {args.email}")
    print(f"  password: {password}")
    print(f"  expires:  {expires_at or 'never'}")
    print(f"  addons:   {len(addons)} hidden")


if __name__ == "__main__":
    main()
