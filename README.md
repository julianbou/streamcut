<div align="center">

  <img src="composeApp/src/commonMain/composeResources/drawable/app_brand_mark.png" alt="StreamCut" width="160" />

  # StreamCut

  [![Stargazers][stars-shield]][stars-url]
  [![Issues][issues-shield]][issues-url]
  [![License][license-shield]][license-url]

  <p>
    A desktop clipping tool for movies and series.
    <br />
    Find the moment, mark in and out, export a shareable MP4.
  </p>

</div>

## ⚠️ Alpha — personal project, expect breakage

StreamCut is in alpha and built for its author. Releases are unsigned, macOS-only so far,
and breaking changes land without notice — stored clips, settings and compatibility can all
change between builds. Don't rely on it for anything you can't redo.

## About

StreamCut is a fork of [Nuvio Desktop](https://github.com/NuvioMedia/NuvioDesktop) turned
into a clip-export tool. Upstream is a media client that happens to play video; StreamCut
makes the clip the subject and the player the instrument.

What it does differently:

- **The player *is* the clipping UI.** No separate trim screen and no modal — in/out markers,
  timecode readouts, a zoomed timeline and the export controls live on the playback chrome.
- **Marking never seeks.** `I` and `O` bring the in/out point to the playhead instead of
  moving the playhead to a handle, so you never lose the frame you just found. Fine
  adjustments pause first.
- **Frame-exact cuts.** Clips are re-encoded rather than keyframe-snapped: H.264/AAC MP4
  (yuv420p, stereo) via the platform hardware encoder — VideoToolbox, NVENC, QSV or AMF —
  with an automatic libx264 fallback.
- **Clips inherit what you were watching.** The audio track that was playing carries over,
  and the active subtitle can be burned in. HDR sources are tonemapped to SDR instead of
  coming out grey.
- **Subtitle phrase search.** Type a line, get the timestamps where it's said, jump there,
  and clip around it. Matching is fuzzy and spans consecutive cues.
- **Filmstrip scrubbing.** Thumbnails are extracted on demand with tuned seeks and cached
  per title, so building a strip over a remote stream costs a fraction of the file.
- **Multi-range drafts.** Mark several ranges in one pass with `A` and export them as a queue.
- **A clips-first library** with real thumbnails, drag-out to Finder, and a 7-second undo
  window on delete.

Upstream's viewing features (next-episode cards, intro skip, parental guides, the app-icon
picker and so on) are **gated, not deleted** — they sit behind
`AppFeaturePolicy.viewingChromeEnabled`, which is `false` on desktop, so merges from
NuvioMedia/NuvioDesktop keep applying cleanly.

Browsing, catalogs and stream resolution still come from user-installed addons and
user-provided sources, exactly as upstream.

### Keyboard shortcuts

| Key | Action |
| --- | --- |
| `I` / `O` | Mark in / out at the playhead |
| `,` / `.` | Step one frame back / forward (pauses first) |
| `←` / `→` on a focused handle | Move that point one frame (`Shift`: one second) |
| `A` | Add the current range to the draft |
| `X` | Export |
| `R` | Loop-preview the range |
| `B` | Show / hide the filmstrip |
| `Backspace` | Undo: the current draft first, then set-aside ranges newest-first |
| `/` or `Cmd`/`Ctrl` + `F` | Subtitle phrase search |
| `?` | Show every shortcut |

With unexported ranges, the first `Esc` only warns; press it again to leave the player.

## Installation

Download the latest build from [Releases](https://github.com/julianbou/streamcut/releases/latest).

Currently published: **macOS Apple Silicon DMG only**. The DMG is ad-hoc signed and *not*
notarized, so Gatekeeper will block it on first launch. Either approve it under
System Settings → Privacy & Security → "Open Anyway", or strip the quarantine flag:

```bash
xattr -dr com.apple.quarantine /Applications/StreamCut.app
```

Windows and Linux packaging tasks are inherited from upstream and should still work, but no
build for either has been produced or tested here. Build from source if you need one.

The desktop in-app updater is disabled on purpose — it pointed at upstream's releases.

## Requirements

- **JDK 17** to build.
- **ffmpeg and ffprobe** on `PATH` for clip export. The build must include **libass**
  (subtitle burn-in) and **libzimg** (`zscale`, for HDR tonemapping). Homebrew's
  `ffmpeg` has neither; jellyfin-ffmpeg does. StreamCut probes each candidate binary for
  those filters and picks the first that has both; `NUVIO_FFMPEG_PATH` overrides the search.
  No ffmpeg is bundled yet.

## Development

```bash
git clone https://github.com/julianbou/streamcut.git
cd streamcut
```

`local.properties` is gitignored, and Gradle fails configuration without it. Blank values
are fine — only sign-in and cross-device sync depend on them:

```properties
NUVIO_SUPABASE_URL=
NUVIO_SUPABASE_ANON_KEY=
```

To run the backend yourself, point those at your own Supabase project and apply
[`supabase/schema.sql`](supabase/schema.sql) (tables, RPCs, RLS and triggers) in its SQL
editor, then enable email auth. Clipping, playback and the local library all work without it.

Run from source:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew :composeApp:run
```

On Windows PowerShell:

```powershell
.\gradlew.bat :composeApp:run
```

Compile-only check (~2 min cold):

```bash
./gradlew :composeApp:compileKotlinDesktop
```

Tests:

```bash
./gradlew :composeApp:desktopTest
```

Package a release for the current host:

```bash
./gradlew :composeApp:packageReleaseDmg -Pcompose.desktop.packaging.checkJdkVendor=false \
  --no-configuration-cache --no-daemon
```

The DMG lands in `composeApp/build/compose/release-dmgs/`. Other hosts:

```bash
# Windows
./gradlew :composeApp:packageReleaseMsi --rerun-tasks

# Linux
./gradlew :composeApp:packageReleaseDeb

# macOS, both architectures (needs an x86_64 JDK for the Intel slice)
./scripts/build-macos-release-dmgs.sh --package-only
```

`.github/workflows/desktop-release.yml` is upstream's and **cannot run in this repo** — it
requires Apple signing certificates, notarytool credentials, a Sentry DSN and a base64
`local.properties` with third-party API keys. Releases are built locally.

## Project structure

- `composeApp/src/commonMain/` — shared UI, features and repositories, including
  `features/clip/` (the clip library, models, repository and filename templating).
- `composeApp/src/desktopMain/kotlin/.../features/clip/` — the export path:
  `ClipExtractor.desktop.kt` (ffmpeg graph: tonemap → crop → subtitles) and
  `ClipStrip.desktop.kt` (filmstrip extraction and caching).
- `composeApp/src/desktopMain/resources/player-ui/` — the player chrome, rendered in a
  webview. Clipper code is kept in its own `clip-controls.{js,css}` and
  `clip-search.{js,css}` to stay off upstream's merge surface.
- `composeApp/src/*/kotlin/com/nuvio/app/core/build/AppFeaturePolicy*` — one `expect` and six
  per-target `actual`s; this is what gates viewing chrome.
- `composeApp/src/commonMain/composeResources/values/clip_strings.xml` — clipper strings,
  separate from upstream's `strings.xml` for the same reason.
- `branding/make_brand.py` — regenerates every icon, launch mark and wordmark from
  `branding/logo-source.jpg`.
- `composeApp/Configuration/DesktopVersion.properties` — release version and build code.
- Fork identity (app name, vendor, bundle id, URL scheme) lives in the `fork.*` properties in
  `gradle.properties`.

Android and iOS source sets are still present, but clipping is desktop-only and neither mobile
target is built or verified here.

## Versioning

```properties
VERSION_NAME=0.1.0-alpha
VERSION_CODE=1
```

Use the helper, and keep the bump as the last commit before the tag. Tags carry no `v`
prefix — the tag is the version verbatim.

```bash
./scripts/set-version.sh --desktop 0.1.1-alpha --desktop-code 2
./scripts/set-version.sh --show
```

## Legal & DMCA

StreamCut is a client-side interface for browsing metadata and playing media provided by
user-installed addons and/or user-provided sources. It does not host, store, or distribute
any media, and it is not affiliated with any addon, catalog, source, or content provider.

Clipping is intended for content you own or are otherwise authorized to use, and for uses
permitted by applicable law. What you export is your responsibility.

Upstream's [Legal & Disclaimer page](https://nuvioapp.space/legal) covers the inherited
addon and DMCA policy.

## Built with

- Kotlin Multiplatform / Compose Multiplatform
- mpv via a native player bridge
- ffmpeg / ffprobe for extraction, tonemapping and subtitle burn-in
- Supabase (optional, for auth and sync)
- Compose Desktop packaging (jpackage)

## License

GPL-3.0, inherited from [Nuvio Desktop](https://github.com/NuvioMedia/NuvioDesktop).
See [LICENSE](LICENSE).

<!-- MARKDOWN LINKS & IMAGES -->
[stars-shield]: https://img.shields.io/github/stars/julianbou/streamcut.svg?style=for-the-badge
[stars-url]: https://github.com/julianbou/streamcut/stargazers
[issues-shield]: https://img.shields.io/github/issues/julianbou/streamcut.svg?style=for-the-badge
[issues-url]: https://github.com/julianbou/streamcut/issues
[license-shield]: https://img.shields.io/github/license/julianbou/streamcut.svg?style=for-the-badge
[license-url]: https://github.com/julianbou/streamcut/blob/main/LICENSE
