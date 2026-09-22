# Contributing

StreamCut is a personal fork of [Nuvio Desktop](https://github.com/NuvioMedia/NuvioDesktop),
maintained by one person for their own use. It is alpha software and its direction changes
whenever the author wants it to.

You are welcome to open issues and pull requests, but please read this first — it is short,
and it sets expectations honestly rather than promising a review process that does not exist.

## What to expect

- **There is no maintenance commitment.** Issues and PRs may sit untouched, or be closed
  because the change doesn't fit where the app is going. That is not a judgment of the work.
- **No support for the packaged builds.** Releases are unsigned, macOS-only and built by hand.
  If a build doesn't launch on your machine, an issue is fine, but a fix isn't guaranteed.
- **Fork it instead, if you want control.** GPL-3.0 — take it and go. That is what this repo
  is, after all.

## Bugs

Useful reports include:

- StreamCut version (from Settings → About, or the commit hash)
- OS and hardware, and whether you used a release DMG or built from source
- Your ffmpeg build — `ffmpeg -version`, and whether it has libass and libzimg (see the
  README's Requirements section). A large share of export failures are this.
- Exact steps, expected vs actual behavior, and how often it happens
- For crashes and export failures: the terminal output. Run the app from the command line
  (`/Applications/StreamCut.app/Contents/MacOS/StreamCut`) and paste what it prints.

## Pull requests

No template, no checklist. Just:

- Keep it to one problem, and say in the description what you tested.
- Include a before/after screenshot or clip for anything visual.
- **Don't delete upstream's viewing features to "clean up".** They are gated behind
  `AppFeaturePolicy.viewingChromeEnabled` on purpose so merges from NuvioMedia/NuvioDesktop
  keep applying. Gate, don't delete.
- Keep clipper code in its own files where it already is (`clip-controls.{js,css}`,
  `clip-search.{js,css}`, `clip_strings.xml`) for the same reason.
- Touching an `expect` declaration means finding **all six** `actual`s. `AppFeaturePolicy` is
  the reference case: something that compiles on desktop can break iOS or Android silently.
- Translations are welcome. Clipper strings live in
  `composeApp/src/commonMain/composeResources/values/clip_strings.xml`; only the default
  locale is filled in and the other 25 fall back to it.

Build and test instructions are in the [README](README.md#development).

## Upstream

Bugs in browsing, addons, catalogs, stream resolution, sync or playback are most likely
inherited and better reported to [Nuvio Desktop](https://github.com/NuvioMedia/NuvioDesktop)
— they will reach more users and, if fixed there, land here on the next merge. Anything about
clipping, trimming, export, the filmstrip or subtitle search belongs here.
