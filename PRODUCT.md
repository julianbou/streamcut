# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

Recorded as `web` because the design language is desktop pointer-and-keyboard, not native mobile. StreamCut actually ships as a desktop app: Kotlin/Compose Multiplatform shell with an HTML/CSS/JS player chrome (`player-ui/`) over native mpv. Released for macOS (Apple Silicon, `.dmg`) and Windows x64 (`.msi`, ffmpeg bundled); Linux packaging is inherited but unbuilt and untested.

## Users

Clip-makers who cut moments out of movies and series to use as **raw material for their own edits**: compilations, fan edits, video essays, reaction and analysis pieces. They come in knowing the scene they want, find the exact frame, and leave with a file that goes into an editor. Future decisions serve these people, not only the author, even though the product is currently an alpha built by one person.

## Product Purpose

StreamCut turns a streaming client into a clip-export tool: find the moment, mark in and out, export a frame-exact MP4. The clip is the subject; the player is the instrument. Success is a user getting from "I know the line" to "clip on disk, cut on the right frame, right audio track" without leaving playback or fighting the timeline.

## Positioning

Clips are cut from the user's own resolved streams inside a native mpv player, re-encoded frame-exact rather than keyframe-snapped, and the player itself is the trimming surface. There is no separate trim screen or modal. Marking never seeks: `I`/`O` bring the point to the playhead, so the frame you found is never lost. Clips inherit the playing audio track and, optionally, burned-in subtitles; HDR sources are tonemapped. Subtitle phrase search finds a spoken line and jumps to it.

## Operating Context

- Desktop, keyboard-heavy sessions: `I`/`O` mark, `,`/`.` frame-step (keys only, listed in the `?` shortcut sheet), `A` add range, `X` export, `R` loop preview, `B` filmstrip, `Backspace` clear, `Cmd/Ctrl+F` phrase search.
- Sources come from user-installed addons and user-provided/debrid streams, often remote over HTTP, so seeking and thumbnail extraction have real latency.
- Exports land on disk and are dragged out to Finder into an editing tool.
- A typed timecode or a frame step pauses playback first so the frame can be judged. Readouts stay at tenths of a second on purpose: the user does not want frame counts shown.

## Capabilities and Constraints

- Export: H.264/AAC MP4, yuv420p, stereo, via hardware encoders (VideoToolbox, NVENC, QSV, AMF) with libx264 fallback. Multi-range drafts export as a queue. Every export keeps the source shape with no size cap (the crop/size-cap Format popover was removed in `5120bdfb`; frame stepping came back 2026-09-21 as keys only, with no buttons in the clip row).
- Clips-first library, grouped by film, with real thumbnails, hover scrubbing, drag-out, and a 7-second undo on delete. Filmstrip scrubbing with cached, on-demand thumbnails.
- Upstream Nuvio viewing features are **gated, never deleted**, behind `AppFeaturePolicy.viewingChromeEnabled` (false on desktop) so upstream merges keep applying. Profiles are the one exception: removed outright.
- The webview bridge carries numbers only; enum-like values travel as ordinals.
- Releases are built locally, ad-hoc signed, not notarized; the desktop auto-updater is disabled.
- **Undecided:** the output was designed as a "shareable" MP4, but the confirmed primary use is edit source. Whether to add an edit-grade export (higher bitrate, intermediate codec, preserved audio channels) is open.

## Brand Commitments

- Name: **StreamCut**. One logo (scissors over a film-strip play button, on navy) drives icons, launch screen and wordmark via `branding/make_brand.py` from `branding/logo-source.jpg`.
- Attribution stays: "Based on Nuvio Desktop" in About, licence credit, and Nuvio's name in third-party device-auth texts where those services show the registered app name.
- Voice in existing copy: plain, direct, technical; honest about alpha status.

## Evidence on Hand

- `README.md` feature list and shortcut table; brand mark at `composeApp/src/commonMain/composeResources/drawable/app_brand_mark.png`.
- No users besides the author, no testimonials, no usage data, no press. Do not invent any.

## Product Principles

1. **The found frame is sacred.** Nothing may move the playhead or lose a mark the user did not ask to move.
2. **The player is the tool.** Clipping lives on the playback surface; no detours into separate screens.
3. **Fidelity over convenience.** A clip is source material for someone else's edit; exactness and quality beat speed of sharing.
4. **Keyboard first, pointer complete.** Every core action has a key; everything is still reachable by mouse.
5. **Gate, don't delete.** Stay mergeable with upstream.
