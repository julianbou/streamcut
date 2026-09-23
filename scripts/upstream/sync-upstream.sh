#!/usr/bin/env bash
# Brings an upstream Nuvio Desktop release into StreamCut. The sync-upstream skill
# (.claude/skills/sync-upstream) drives this; it also works by hand.
#
#   sync-upstream.sh status          what upstream released since the last sync
#   sync-upstream.sh start [TAG]     branch sync/upstream-TAG off main and merge TAG
#                                    (default: the newest upstream release)
#   sync-upstream.sh verify          checks to run once every conflict is resolved
#
# Upstream release tags are fetched into refs/upstream-tags/*, never refs/tags/*:
# both projects tag releases as X.Y.Z-alpha, so the names collide, and a stray
# `git push --tags` would publish upstream's tags on StreamCut.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
HERE="scripts/upstream"
UPSTREAM_URL="https://github.com/NuvioMedia/NuvioDesktop.git"
UPSTREAM_FILE="composeApp/Configuration/UpstreamVersion.properties"
JAVA_HOME_DEFAULT="$HOME/.nuvio/jdks/temurin-17-arm64/Contents/Home"

die() { echo "error: $*" >&2; exit 1; }

# Pathspec lines from a list file, comments and blanks dropped.
list_paths() { sed -e 's/#.*//' -e 's/[[:space:]]*$//' -e '/^$/d' "$HERE/$1"; }

prop() { sed -n "s/^$1=//p" "$UPSTREAM_FILE" | tr -d '\r'; }

ensure_upstream() {
  if ! git remote get-url upstream >/dev/null 2>&1; then
    git remote add upstream "$UPSTREAM_URL"
  fi
  git config remote.upstream.tagOpt --no-tags
  if ! git config --get-all remote.upstream.fetch | grep -qx '+refs/tags/\*:refs/upstream-tags/\*'; then
    git config --add remote.upstream.fetch '+refs/tags/*:refs/upstream-tags/*'
  fi
  # Upstream's LFS binaries stay pointers until needed: pulling them all is slow
  # and has failed on this machine with stale credentials.
  GIT_LFS_SKIP_SMUDGE=1 git fetch --quiet upstream
}

latest_tag() {
  git for-each-ref --format='%(refname:lstrip=2)' refs/upstream-tags \
    | grep -E '^[0-9]+\.[0-9]+\.[0-9]+(-[a-z]+)?$' | sort -V | tail -1
}

# One line per upstream commit in RANGE, grouped by the area it touches most.
summarize() {
  local range="$1"
  echo "By area (a commit counts once, under the first area it touches):"
  git log --no-merges --format='%H' "$range" | while read -r sha; do
    git diff-tree --no-commit-id --name-only -r "$sha" \
      | sed -nE \
          -e 's#^composeApp/src/[^/]+/kotlin/com/nuvio/app/features/([^/]+)/.*#\1#p' \
          -e 's#^composeApp/src/[^/]+/kotlin/com/nuvio/app/core/([^/]+)/.*#core/\1#p' \
          -e 's#^composeApp/src/desktopMain/resources/player-ui/.*#player-ui#p' \
          -e 's#^composeApp/src/commonMain/composeResources/.*#strings+resources#p' \
          -e 's#^(iosApp|androidApp)/.*#\1#p' \
          -e 's#^(\.github)/.*#\1#p' \
      | head -1
  done | sort | uniq -c | sort -rn | sed 's/^/  /'
  echo
  echo "Commits touching paths StreamCut owns (expect them to be reset):"
  local owned=()
  while IFS= read -r p; do owned+=(":(glob)$p"); done < <(list_paths ours-paths.txt)
  git log --no-merges --format='  %h %s' "$range" -- "${owned[@]}" | head -20
}

cmd_status() {
  ensure_upstream
  local base tag
  base="$(prop UPSTREAM_COMMIT)"
  tag="$(latest_tag)"
  [[ -n "$tag" ]] || die "no upstream release tags found"
  echo "Last synced:     Nuvio Desktop $(prop UPSTREAM_VERSION_NAME) (${base:0:8})"
  echo "Newest upstream: Nuvio Desktop $tag ($(git rev-parse --short "refs/upstream-tags/$tag^{commit}"))"
  if git merge-base --is-ancestor "refs/upstream-tags/$tag" HEAD; then
    echo "Up to date."
    return
  fi
  echo "Commits to bring in: $(git rev-list --count --no-merges "$base..refs/upstream-tags/$tag")"
  echo
  summarize "$base..refs/upstream-tags/$tag"
}

cmd_start() {
  ensure_upstream
  local tag="${1:-$(latest_tag)}"
  local ref="refs/upstream-tags/$tag"
  git rev-parse --verify --quiet "$ref^{commit}" >/dev/null || die "unknown upstream tag: $tag"
  [[ "$(git branch --show-current)" == "main" ]] || die "start from main"
  git diff --quiet && git diff --cached --quiet || die "working tree has uncommitted changes"
  git fetch --quiet origin main
  [[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || die "main is not in sync with origin/main"

  # rerere replays how each conflict was resolved last time; most conflicts
  # recur on every sync (strings, App.kt, the player chrome).
  git config rerere.enabled true
  git config rerere.autoupdate true

  local branch="sync/upstream-$tag"
  git switch --quiet -c "$branch"
  echo "On $branch. Merging Nuvio Desktop $tag..."
  GIT_LFS_SKIP_SMUDGE=1 git merge --no-ff --no-commit "$ref" >/dev/null 2>&1 || true

  echo "Resetting paths StreamCut owns:"
  local patterns=() owned=() file pattern
  while IFS= read -r pattern; do patterns+=("$pattern"); done < <(list_paths ours-paths.txt)
  # In [[ == ]] a * also matches "/", so "icons/**" covers the whole tree.
  while IFS= read -r file; do
    for pattern in "${patterns[@]}"; do
      # shellcheck disable=SC2053
      if [[ "$file" == $pattern ]]; then owned+=("$file"); break; fi
    done
  done < <(git ls-tree -r --name-only HEAD)
  if (( ${#owned[@]} > 0 )); then
    git checkout HEAD -- "${owned[@]}"
    git diff --name-only ORIG_HEAD MERGE_HEAD -- "${owned[@]}" 2>/dev/null | sed 's/^/  /' || true
  fi

  echo "Removing paths StreamCut deleted:"
  while IFS= read -r p; do
    if git ls-files -- "$p" | grep -q .; then
      git rm -rq --force -- "$p"
      rm -rf -- "$p"
      echo "  $p"
    fi
  done < <(list_paths deleted-paths.txt)

  local sha
  sha="$(git rev-parse "$ref^{commit}")"
  sed -i.bak -e "s/^UPSTREAM_VERSION_NAME=.*/UPSTREAM_VERSION_NAME=$tag/" \
    -e "s/^UPSTREAM_COMMIT=.*/UPSTREAM_COMMIT=$sha/" "$UPSTREAM_FILE"
  rm -f "$UPSTREAM_FILE.bak"
  git add "$UPSTREAM_FILE"

  python3 "$HERE/rebrand-strings.py" >/dev/null || true

  echo
  local conflicts
  conflicts="$(git diff --name-only --diff-filter=U)"
  if [[ -z "$conflicts" ]]; then
    echo "No conflicts left. Run: $0 verify"
  else
    echo "$(wc -l <<<"$conflicts" | tr -d ' ') conflict(s) left to resolve:"
    sed 's/^/  /' <<<"$conflicts"
    echo
    echo "rerere already replayed any resolution it remembered; check those too."
  fi
}

cmd_verify() {
  local failed=0
  echo "== Conflict markers"
  if git diff --name-only --diff-filter=U | grep -q .; then
    git diff --name-only --diff-filter=U | sed 's/^/  unresolved: /'
    failed=1
  fi
  if git grep -nE '^(<<<<<<<|>>>>>>>)( |$)' -- ':!*.md' >/dev/null 2>&1; then
    git grep -nE '^(<<<<<<<|>>>>>>>)( |$)' -- ':!*.md' | head -20 | sed 's/^/  /'
    failed=1
  fi
  (( failed == 0 )) && echo "  none"

  echo "== Rebrand strings"
  python3 "$HERE/rebrand-strings.py" || failed=1

  echo "== Fork invariants"
  "$HERE/check-fork-invariants.sh" main || failed=1

  echo "== Player chrome scripts parse"
  for js in composeApp/src/desktopMain/resources/player-ui/*.js; do
    node --check "$js" || { echo "  $js does not parse"; failed=1; }
  done

  echo "== Desktop compile and tests"
  export JAVA_HOME="${JAVA_HOME:-$JAVA_HOME_DEFAULT}"
  local log
  log="$(mktemp)"
  if ./gradlew :composeApp:compileKotlinDesktop :composeApp:desktopTest -q --console=plain >"$log" 2>&1; then
    echo "  passed"
  else
    grep -vE "are in Beta|^w: " "$log" | tail -40 | sed 's/^/  /'
    echo "  desktop compile or tests failed (full log: $log)"
    failed=1
  fi

  echo
  if (( failed )); then
    echo "Verification failed."
    exit 1
  fi
  echo "Verification passed. Commit the merge, then build and try the app."
}

case "${1:-}" in
  status) cmd_status ;;
  start) shift; cmd_start "$@" ;;
  verify) cmd_verify ;;
  *) sed -n '2,12p' "$0"; exit 2 ;;
esac
