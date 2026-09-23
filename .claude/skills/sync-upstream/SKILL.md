---
name: sync-upstream
description: Bring a new Nuvio Desktop release (upstream NuvioMedia/NuvioDesktop) into StreamCut. Merges the release tag on a sync branch, re-applies StreamCut's branding and fork decisions, resolves conflicts by fixed rules, asks the user only about new upstream features, verifies, and opens a PR. Use when the user says "sync upstream", "traer los cambios de Nuvio", "merge Nuvio", "hay una versión nueva de Nuvio", or asks what upstream has released since the last sync.
---

# Sync upstream Nuvio into StreamCut

StreamCut is a GPL fork of Nuvio Desktop turned into a clip tool. Upstream keeps
shipping fixes worth having; this skill brings a whole upstream **release** in by
**merge** (never cherry-pick, so git remembers what is integrated) and then puts
StreamCut's decisions back on top.

Tooling lives in `scripts/upstream/`:

| File | What it does |
| --- | --- |
| `sync-upstream.sh status` | Fetches upstream, shows the last synced and newest release, commits by area |
| `sync-upstream.sh start [TAG]` | Branches `sync/upstream-TAG` off main, merges, resets owned paths, re-deletes deleted paths, rewrites `UpstreamVersion.properties`, rebrands strings, lists what is left |
| `sync-upstream.sh verify` | Conflict markers, rebrand, fork invariants, JS parse, desktop compile + tests |
| `ours-paths.txt` | Files StreamCut owns outright; always reset to ours |
| `deleted-paths.txt` | Paths StreamCut deleted on purpose; always re-deleted |
| `rebrand-strings.py` | Nuvio → StreamCut in string resources, except `branding-allowlist.txt` |
| `check-fork-invariants.sh` | Fails when a merge undid a fork decision (updater, data dir, MSI code, gating, branding...) |

## Steps

1. **Check the tree.** Other Claude sessions may be editing this repo: run `git status`
   and ListAgents. Do not start with uncommitted work in the tree, and never stash
   someone else's changes. `start` refuses unless main is clean and equal to origin/main.
2. **Status.** `scripts/upstream/sync-upstream.sh status`. Tell the user in two or three
   lines what is coming (version, commit count, main areas). If it says up to date, stop.
3. **Triage before merging.** Read the upstream commit list
   (`git log --no-merges --format='%h %s' <UPSTREAM_COMMIT>..refs/upstream-tags/<TAG>`)
   and sort every user-facing change into one of:
   - **take**: bug fixes, performance, playback, subtitles, sources, sync. The default.
   - **gate**: new UI for watching rather than clipping (see rules below). Taken, but
     hidden on desktop behind `AppFeaturePolicy.viewingChromeEnabled`.
   - **drop**: changes to things StreamCut owns or deleted (branding, updater,
     profiles, upstream's release CI). Handled by the scripts; nothing to do.
   - **ask**: a new feature where take/gate is a product call.

   Put every **ask** item to the user in **one** AskUserQuestion batch before resolving
   anything, with a one-line description of each. Do not ask about take or drop items.
4. **Merge.** `scripts/upstream/sync-upstream.sh start <TAG>`.
5. **Resolve** the listed conflicts with the rules below. rerere replays resolutions it
   has seen before: still read those files, since the surrounding code may have moved.
6. **Rebrand and verify.** `python3 scripts/upstream/rebrand-strings.py`, then
   `scripts/upstream/sync-upstream.sh verify` until it passes. Compile errors after a
   clean merge are normal: upstream code calling things StreamCut deleted or renamed.
   Fix them in the direction of StreamCut's decision, never by restoring what was deleted.
7. **Commit** the merge (`git commit`, keeping git's merge message and adding a short
   body: upstream version, commit count, what was gated, what was asked and the answer).
   Stage explicit paths if any file outside the merge is dirty; never `git add -A`.
8. **Push the branch and build Windows** from it:
   `git push -u origin sync/upstream-<TAG>` then
   `gh workflow run windows-build.yml --ref sync/upstream-<TAG>`.
9. **Open a PR** into main with `gh pr create`: the triage table (take / gate / ask with
   answers), the conflicts and how each group was resolved, and a **manual test list**
   for the user covering what the merge touched (always: open a film, play, mark IN/OUT,
   export a clip, subtitle search; plus anything in the areas upstream changed most).
10. **Stop there.** The user tests the app (Mac build + the Windows MSI artifact) and
    merges the PR. Never merge it, release, or push tags yourself.

## Conflict rules

Default: **take upstream's change, then re-apply StreamCut's intent on top.** Throwing
away upstream's side loses fixes silently; only owned paths get that treatment.

- **Owned and deleted paths** (`ours-paths.txt`, `deleted-paths.txt`): already handled
  by `start`. If upstream moved a file StreamCut owns, add the new path to the list.
- **String resources** (`composeResources/values*/strings.xml`): keep **both** sides'
  entries. Same key changed on both sides: take upstream's text, the rebrand script
  puts the name back. Never edit `clip_strings.xml` to resolve an upstream conflict;
  it is StreamCut's own file and upstream does not have it.
- **Profiles** (deleted 2026-09-04): upstream code that calls `ProfileRepository`,
  avatars or the profile picker is removed or rewritten against the fixed scope.
  Storage keys keep the `_1` suffix (`ProfileScopedKey.ScopeId = 1`): never drop it,
  it would strand every install's library and settings.
- **Viewing chrome**: new upstream UI that serves watching (next-episode, skip intro,
  continue watching rows, trailers, ratings chrome, pause overlays, hero banners)
  goes behind `AppFeaturePolicy.viewingChromeEnabled` on desktop. Gate, don't delete:
  deleting makes every later sync conflict.
- **`AppFeaturePolicy`**: upstream adding an `expect val` means adding the `actual` in
  **all six** source sets (`androidFull`, `androidPlaystore`, `desktopMain`, `iosFull`,
  `iosAppStore`, plus the common `expect`). Desktop's value follows the triage.
- **Player chrome** (`desktopMain/resources/player-ui/`): take upstream's fixes in
  `controls.{html,js,css}` but keep StreamCut's hooks into `clip-controls.js` and
  `clip-search.js`. Clip code stays in those files; never move it into `controls.js`.
  Frame counts are never shown (stepping is `,`/`.` only). Test the chrome standalone
  with the harness: `python3 -m http.server 8731` in that directory, open
  `controls.html`, push state with `window.playerControls({...})`.
- **Look** (`core/ui/Theme*.kt`, riso material): StreamCut's riso world wins on colors,
  type and surfaces; take upstream's structural and behavioral changes.
- **Updater** (`AppUpdaterPlatform.desktop.kt`): keep `releaseSource` on
  julianbou/streamcut and `isSupported = true`; take upstream's download and install fixes.
- **`build.gradle.kts`**: keep both. StreamCut-only blocks stay: ffmpeg bundling
  (`nuvio.windows.ffmpeg.dir`), `windowsMsiUpgradeUuid`, `upstreamVersionProps`, the
  packaging gates (duplicated resources, external dylibs), `fork.*` properties.
- **Storage** (`DesktopStorage.kt`): the data directory stays `StreamCut/`.
- **iOS and Android files**: take upstream unless it touches something deleted. They
  cannot be compiled on this machine (no Xcode); say so in the PR.
- **Binary files** not in the owned list (fonts, dylibs, DLLs): take upstream's.
  They arrive as LFS pointers (`GIT_LFS_SKIP_SMUDGE=1`); run `git lfs pull` before a
  local build that needs them.

## Keeping the rules current

This skill is only as good as its lists. Whenever StreamCut makes a decision a merge
could undo, or a sync hits a conflict these rules did not predict, update in the same PR:

- a file StreamCut now owns entirely: `ours-paths.txt`
- something deleted on purpose: `deleted-paths.txt`
- a string that must keep "Nuvio": `branding-allowlist.txt`, with the reason
- a fork decision that lives inside a shared file: a check in `check-fork-invariants.sh`
- a new resolution rule: this file

## Gotchas

- Upstream tags are fetched into `refs/upstream-tags/*`. Both projects tag
  `X.Y.Z-alpha`, and some names already collide. Never fetch upstream tags into
  `refs/tags`, and never `git push --tags`: it would publish upstream's tags on StreamCut.
- The merge base is recorded in `composeApp/Configuration/UpstreamVersion.properties`,
  which also drives the "Based on Nuvio Desktop <version>" credit in About.
  `DesktopVersion.properties` is StreamCut's own version and is always kept.
- Package releases only with Temurin (`~/.nuvio/jdks/temurin-17-arm64`), never
  Homebrew's JDK. Compiling with either is fine.
- A sync is not a release. Releasing is a separate step after the PR is merged.
