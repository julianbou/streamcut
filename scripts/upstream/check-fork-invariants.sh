#!/usr/bin/env bash
# Checks the StreamCut-specific decisions an upstream Nuvio merge can silently undo.
#
#   scripts/upstream/check-fork-invariants.sh [REF]
#
# REF (default: main) is the StreamCut commit the branding assets must still match;
# on a sync branch that is the main the branch started from. Exits 1 on any failure.
# Each check names the file to fix. Add a check here whenever a merge breaks a fork
# decision that nothing caught.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
REF="${1:-main}"
failures=0

pass() { printf '  ok    %s\n' "$1"; }
fail() { printf '  FAIL  %s\n        -> %s\n' "$1" "$2"; failures=$((failures + 1)); }

# check DESCRIPTION FILE PATTERN [FIX HINT]: FILE must contain the extended regex PATTERN.
check() {
  local description="$1" file="$2" pattern="$3" hint="${4:-}"
  if [[ -f "$file" ]] && grep -Eq -- "$pattern" "$file"; then
    pass "$description"
  else
    fail "$description" "${hint:-$file must match /$pattern/}"
  fi
}

# refute DESCRIPTION FILE PATTERN [FIX HINT]: FILE must NOT contain PATTERN.
refute() {
  local description="$1" file="$2" pattern="$3" hint="${4:-}"
  if [[ -f "$file" ]] && ! grep -Eq -- "$pattern" "$file"; then
    pass "$description"
  else
    fail "$description" "${hint:-$file must not match /$pattern/}"
  fi
}

echo "Identity"
check "app name is StreamCut" gradle.properties '^fork\.appName=StreamCut$'
check "bundle id is StreamCut's" gradle.properties '^fork\.bundleId=com\.streamcut\.media\.desktop$'
refute "MSI upgrade code is not Nuvio's" composeApp/build.gradle.kts '395990ee-9b8a-3548-922c-e7a23a495b8d"' \
  "windowsMsiUpgradeUuid in composeApp/build.gradle.kts must stay StreamCut's own: sharing Nuvio's makes Windows uninstall one app when installing the other"
check "data directory is StreamCut/" \
  composeApp/src/desktopMain/kotlin/com/nuvio/app/core/storage/DesktopStorage.kt 'APP_DIR_NAME = "StreamCut"'

echo "Updates and links"
check "updater reads julianbou/streamcut" \
  composeApp/src/desktopMain/kotlin/com/nuvio/app/features/updater/AppUpdaterPlatform.desktop.kt 'owner = "julianbou"'
refute "updater never reads NuvioMedia" \
  composeApp/src/desktopMain/kotlin/com/nuvio/app/features/updater/AppUpdaterPlatform.desktop.kt 'owner = "NuvioMedia"'
check "privacy policy is StreamCut's" \
  composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/SettingsRootPage.kt 'PRIVACY_POLICY_URL = "https://github.com/julianbou/streamcut/'

echo "Clipper build"
check "viewing chrome stays gated off on desktop" \
  composeApp/src/desktopMain/kotlin/com/nuvio/app/core/build/AppFeaturePolicy.desktop.kt 'viewingChromeEnabled: Boolean = false'
check "picture-in-picture cannot open in the clipper build" \
  composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/DesktopPlayerPictureInPicture.kt 'AppFeaturePolicy\.viewingChromeEnabled &&' \
  "isSupportedHost() in DesktopPlayerPictureInPicture.kt must require AppFeaturePolicy.viewingChromeEnabled"
check "picture-in-picture button stays hidden in the clipper build" \
  composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/NativePlayerController.kt 'AppFeaturePolicy\.viewingChromeEnabled &&' \
  "the pipLabel field in NativePlayerController.kt must be blank unless AppFeaturePolicy.viewingChromeEnabled"
check "mouse wheel never changes volume in the clipper build" \
  composeApp/src/desktopMain/resources/player-ui/controls.js 'if \(state\.viewingChromeEnabled === false\) return;' \
  "the root wheel handler in controls.js must return before sendKeyboardVolume when viewingChromeEnabled is false"
if [[ -d composeApp/src/commonMain/kotlin/com/nuvio/app/features/profiles ]]; then
  fail "profiles feature stays deleted" "git rm -r composeApp/src/commonMain/kotlin/com/nuvio/app/features/profiles (see the sync-upstream skill)"
else
  pass "profiles feature stays deleted"
fi
check "player loads the clip controls" composeApp/src/desktopMain/resources/player-ui/controls.html 'clip-controls\.js'
check "player loads the subtitle search" composeApp/src/desktopMain/resources/player-ui/controls.html 'clip-search\.js'
check "player loads Save as" composeApp/src/desktopMain/resources/player-ui/controls.html 'clip-save\.js'
check "Save as is extracted with the player page" \
  composeApp/src/desktopMain/kotlin/com/nuvio/app/features/player/desktop/NativePlayerBridge.kt '"clip-save\.js" to' \
  "exportControlsPageAssets() in NativePlayerBridge.kt must list clip-save.js and clip-save.css, or the running app 404s them"
check "clipper build reuses the last stream link by default" \
  composeApp/src/commonMain/kotlin/com/nuvio/app/features/player/PlayerSettingsRepository.kt 'loadStreamReuseLastLinkEnabled\(\) \?: DefaultStreamReuseLastLink' \
  "PlayerSettingsRepository.kt: the saved-setting fallback must be DefaultStreamReuseLastLink (on in the clipper build)"
check "clipper build defaults to the top bar" \
  composeApp/src/commonMain/kotlin/com/nuvio/app/features/settings/DesktopNavigationLayout.kt 'viewingChromeEnabled\) Sidebar else TopBar'
check "clip keys run before upstream's single-key shortcuts" \
  composeApp/src/desktopMain/resources/player-ui/controls.js '!activeModal && window\.clipUi\?\.handleKey'
check "Clips tab wears scissors in the clipper build" \
  composeApp/src/commonMain/kotlin/com/nuvio/app/AppShellComponents.kt 'Icons\.Rounded\.ContentCut'
check "Clips tab wears scissors in the clipper top bar" \
  composeApp/src/commonMain/kotlin/com/nuvio/app/MainTabsDestination.kt 'Icons\.Rounded\.ContentCut'
check "Windows build bundles ffmpeg" .github/workflows/windows-build.yml 'nuvio\.windows\.ffmpeg\.dir'
check "macOS app bundles ffmpeg" composeApp/build.gradle.kts 'from\(prepareMacosFfmpeg\)' \
  "prepareMacosPlayerAppResources in composeApp/build.gradle.kts must copy prepareMacosFfmpeg into ffmpeg/: macOS has no usable ffmpeg"

if git check-ignore -q scripts/upstream/new-file-probe.sh; then
  fail "new files under scripts/ are not ignored" ".gitignore: upstream's scripts/* needs StreamCut's !scripts/upstream/ and !scripts/*.sh exceptions"
else
  pass "new files under scripts/ are not ignored"
fi

echo "Branding"
if git rev-parse --verify --quiet "$REF^{commit}" >/dev/null; then
  brand_paths=(
    composeApp/src/desktopMain/resources/icons
    composeApp/src/commonMain/composeResources/drawable/app_brand_mark.png
  )
  while IFS= read -r icon; do brand_paths+=("$icon"); done < <(
    git ls-files 'composeApp/src/commonMain/composeResources/drawable/app_icon_*' \
      'composeApp/src/commonMain/composeResources/drawable/app_logo_wordmark*'
  )
  if git diff --quiet "$REF" -- "${brand_paths[@]}"; then
    pass "icons and logos match $REF"
  else
    fail "icons and logos match $REF" \
      "git checkout $REF -- the paths listed by: git diff --stat $REF -- ${brand_paths[*]}"
  fi
else
  fail "icons and logos match $REF" "$REF is not a commit"
fi
if python3 scripts/upstream/rebrand-strings.py --check >/dev/null; then
  pass "strings name StreamCut"
else
  fail "strings name StreamCut" "scripts/upstream/rebrand-strings.py (details: --check)"
fi

echo
if (( failures > 0 )); then
  echo "$failures fork invariant(s) broken."
  exit 1
fi
echo "All fork invariants hold."
