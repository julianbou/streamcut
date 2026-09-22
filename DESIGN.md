---
name: StreamCut
description: A clip-export tool dressed as the user's own riso-printed repertory programme.
colors:
  stock: "#141019"
  stock-raised: "#1D1724"
  ink-pink: "#FF48B0"
  ink-blue: "#3D5AFE"
  ink-sun: "#FFD84A"
  ink-red: "#F15060"
  paper: "#F2ECE4"
  paper-dim: "rgba(242, 236, 228, 0.66)"
  paper-faint: "rgba(242, 236, 228, 0.14)"
typography:
  display:
    fontFamily: "Big Shoulders Display, sans-serif"
    fontSize: "124px"
    fontWeight: 800
    lineHeight: "120px"
    letterSpacing: "0em"
  headline:
    fontFamily: "Big Shoulders Display, sans-serif"
    fontSize: "34px"
    fontWeight: 800
    lineHeight: "36px"
    letterSpacing: "0.01em"
  title:
    fontFamily: "JetBrains Sans, sans-serif"
    fontSize: "14px"
    fontWeight: 600
    lineHeight: "18px"
  body:
    fontFamily: "JetBrains Sans, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: "21px"
  body-small:
    fontFamily: "JetBrains Sans, sans-serif"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: "19px"
  label:
    fontFamily: "JetBrains Sans, sans-serif"
    fontSize: "12px"
    fontWeight: 400
    lineHeight: "16px"
rounded:
  listing: "14px"
  mark: "9999px"
spacing:
  hairline: "2px"
  meta-gap: "5px"
  stack-sm: "10px"
  stack-md: "16px"
  listing-gap: "22px"
  row-gap: "28px"
  section-top: "28px"
  masthead-bottom: "36px"
  browse-top: "56px"
  masthead-top: "96px"
components:
  search-masthead:
    textColor: "{colors.paper}"
    typography: "{typography.display}"
    padding: "96px 0 36px"
  listing:
    backgroundColor: "{colors.stock-raised}"
    textColor: "{colors.paper}"
    typography: "{typography.title}"
    rounded: "{rounded.listing}"
    width: "156px"
  listing-meta:
    textColor: "{colors.paper-dim}"
    typography: "{typography.label}"
  position-mark:
    backgroundColor: "{colors.ink-blue}"
    rounded: "{rounded.mark}"
    height: "3px"
  position-mark-track:
    backgroundColor: "{colors.paper-faint}"
    rounded: "{rounded.mark}"
    height: "3px"
  browse-toggle:
    textColor: "{colors.paper-dim}"
    typography: "{typography.headline}"
  browse-toggle-hover:
    textColor: "{colors.paper}"
---

# Design System: StreamCut

## Overview

**Creative North Star: "Riso Repertory Programme"**

StreamCut is printed, not rendered. Every surface is a sheet of near-black plum card stock overprinted with translucent spot inks, and the whole sheet carries one stochastic paper grain, content included. The inks land as soft, organic blooms: dense at the centre, breaking into specks at their thin edges, the lead ink printed twice a hair off register. They drift very slowly and swell when the user acts. The feel is warm, grainy and quiet; the user's own films and their posters are the only full-colour objects on the page.

Colour is state, not decoration. Each ink means one thing wherever it appears, so a user who has learned "blue is where I was" on the home reads it the same way everywhere. Hierarchy comes from type scale and from where ink pools, not from boxes, panels or shadows. Sections are divided by space and by bloom edges; there are no printed lines at rest: pink ink runs under the search field only while it is in use.

Status: this is the app-wide system for the clipper build. The home is built natively in it; every other Compose screen inherits palette, display face, shapes and root grain through `NuvioTheme` (`core/ui/Theme.kt`, `withRisoInks`), with per-surface ink on Library clips, details, streams, sign-in and standard pages (corner bloom on `NuvioScreen`). The HTML player chrome takes its colours from the same tokens and adds grain on its panels in `clip-controls.css` (`body.clipper-mode`). Upstream builds are untouched. New and migrated surfaces take their material from `core/ui/RisoMaterial.kt`; they do not reinvent it.

**Key Characteristics:**
- Dark plum stock, paper-white type, three spot inks with fixed meanings.
- One grain over the whole print, and a coarser grain eroding every bloom.
- Organic, squashed, eased-falloff blooms that drift over 16 seconds and swell on focus.
- Posters shown in their own colour, never tinted or duotoned.
- Two faces: Big Shoulders Display (condensed poster lettering) for what a programme sets big, JetBrains Sans for everything you read.
- No shadows, no borders, no cards-in-cards.

## Colors

A three-ink riso palette on dark stock: fluorescent pink, riso blue and sunflower, over plum-black, with warm paper-white for type.

### Primary
- **Fluorescent Pink** (`ink-pink`): acting. The search masthead's bloom, the caret, the ink line that runs under the field while you type, the hover bloom behind a search result. If the user is about to do something, the ink is pink.

### Secondary
- **Riso Red** (`ink-red`): danger only. Errors and destructive actions (delete, failed export); never decoration, never 'important'.
- **Riso Blue** (`ink-blue`): where you were. The bloom behind recently opened films, the position mark under each of them, the hover bloom behind a recent listing.

### Tertiary
- **Sunflower** (`ink-sun`): set aside. Defined in `Riso.Sun` and reserved for ranges and items put aside for later; the player's set-aside ranges and the clips page print it.

### Neutral
- **Plum Stock** (`stock`): the sheet. Full-bleed page background, under the blooms.
- **Raised Stock** (`stock-raised`): reserved. A poster that has not arrived prints as a grained ink tile of the listing's own ink (`risoInkTile`), never a flat box.
- **Paper White** (`paper`): primary text, masthead input text, hovered browse toggle.
- **Dim Paper** (`paper-dim`): hints, meta lines, "stopped at" labels, status messages, the browse toggle at rest.
- **Faint Paper** (`paper-faint`): unfilled tracks only: the position-mark track.

### Named Rules
**The One Meaning Per Ink Rule.** Pink is acting, blue is where you were, sun is set aside. An ink is never used for decoration or for a meaning another ink owns; a surface with nothing to act on, return to or set aside prints no ink at all.

**The Own Colour Rule.** Posters and frame art keep their own colour. Ink blooms sit behind them, never over them.

**The Ink Pairing Rule.** The player chrome runs the same inks: paper white = playhead and played fill, pink = the range being marked, sun `#FFD84A` = set-aside ranges. The video itself is never tinted or grained.

## Typography

**Display Font:** Big Shoulders Display (variable, used at 800), bundled as `composeResources/font/big_shoulders_display.ttf`, SIL OFL in `third_party/licenses/`; exposed as `RisoDisplay` in `RisoMaterial.kt`
**Body Font:** JetBrains Sans (regular 400, semibold 600)

**Character:** One humanist-technical sans doing all the work; hierarchy comes from a steep jump in size and weight, with display tightened to -0.03em so the masthead reads as printed headline type, not a form field. Only regular, semibold and bold ship in `composeResources/font`.

### Hierarchy
- **Display** (Big Shoulders 800, 124px / 120px, 0em): the search masthead's own input text and placeholder. It is the largest type in the app.
- **Headline** (Big Shoulders 800, 34px / 36px, 0.01em): section titles ("Recently opened", result group names) and the browse toggle.
- **Title** (600, 14px / 18px): listing titles, max two lines, ellipsised.
- **Body** (400, 14px / 21px): running text such as empty states, held to about 60% of the content width.
- **Body Small** (400, 13px / 19px): keyboard hints and the browse hint, always in dim paper.
- **Label** (400, 12px / 16px): meta lines under listings (year, episode), addon names beside a section title, "stopped at" positions.

### Named Rules
**The Loudest Line Rule.** The thing the user types is the biggest type on the page. No title, hero or wordmark outranks the search masthead on the home.

**The One Display Face Rule.** Big Shoulders Display is the programme's only display face: mastheads and section titles, never running text, labels or data. Do not introduce another face per surface.

**The No Eyebrow Rule.** No small tracked all-caps labels above headings. A section is named by a headline in the display face (`NuvioSectionLabel` renders this way in the riso world, sentence-casing upstream all-caps strings); a label that only repeats the page title is dropped. Numbers and data stay in the UI face.

**The No Outline Rule (enforced).** In the riso world `outline`, `outlineVariant`, `borderSubtle` and `borderDefault` are transparent: fields and groups separate by raised stock and space, focus draws pink ink, selection is a bloom plus a check mark.

## Layout

A single scrolling programme sheet. Horizontal inset is the inherited home section padding (`homeSectionHorizontalPaddingForWidth`, width-responsive); the ink and grain run full-bleed beyond it.

- **Masthead:** at least 300px tall, content bottom-aligned, 96px above the field and 36px below the hint; 22px between the field and its hint.
- **Sections:** 28px above a section, 16px from title to content (14px for result groups).
- **Listings:** fixed 156px columns, 22px apart horizontally. Recent films wrap in a flowing grid with 28px between rows (at most 12). Search results scroll horizontally, one row per result group.
- **Listing interior:** 10px poster to title, 2px title to meta, 8px to the position mark, 5px mark to its label.
- **Browse toggle:** 56px of air above it, sitting last, folded by default; the hint sits 6px below.
- **Density:** generous. One job per viewport region; nothing else above the fold beyond the masthead and the first programme section.

## Elevation & Depth

Flat stock, no shadows anywhere. Depth is ink and paper: blooms pool light behind the content they belong to, raised stock gives a poster its well, and grain unifies the layers into one print. The page is composed strictly as stock, then ink, then grain over everything including text and posters (`background(Riso.Stock)` → `risoBlooms` → `risoGrain`).

### Ink material (from `RisoMaterial.kt`)
- **Grain:** a 192px stochastic tile (seeded, generated in code, no asset), about 16% light and 20% dark specks, tiled at 7% opacity in overlay blend over the whole surface.
- **Blooms:** radial gradients with an eased falloff (strength at 0, 70% at 0.35, 32% at 0.6, 10% at 0.8, 2.5% at 0.92, zero at the edge), squashed vertically into lobes, screen-blended onto an offscreen layer, then eroded by the grain at 55% with destination-out so the edges break into specks.
- **Misregistration:** the first bloom is printed a second time at 25% strength, offset 3px right and 2px up.
- **Placement:** blooms are positioned in page space as fractions of the viewport and lift with scroll, so a bloom leaves with the section it sits behind.
- **Motion:** each bloom drifts up to 36px, alternating direction, on a 16-second linear back-and-forth. Every ink is printed twice, the second pass 5dp off register at 40% strength, so overlapping inks overprint into a third colour. Focusing search swells every bloom to 1.14x over 900ms.

### Named Rules
**The One Print Rule.** Stock, ink, grain, in that order, on the full surface. Grain goes over content, never under it; a surface without grain is not in this world.

**The Never Boxed Rule.** Blooms go on a surface at least as large as they are. A bloom clipped by an element edge reads as a box, which ink never is.

**The Eased Edge Rule.** Ink never fades on a linear tail; a linear falloff leaves a visible seam where the ink stops.

## Shapes

Soft rectangles for pictures, pills for marks, nothing else. Posters are clipped to a 2:3 frame with gently rounded corners (14px). Progress marks are 3px pills. Blooms are squashed ellipses (vertical squash 0.7 to 0.8). There are no borders, outlines or divider lines at rest; the only line is the 3px pink ink stroke under the search field while it is in use.

## Components

### Search Masthead
The page headline is the search field itself: display type, no box.
- **Rest:** paper-white text on bare stock, placeholder in paper at 62% opacity, nothing under the text.
- **Focus / typing:** a 3px pink ink line, fading out to the right, runs in under the text over 420ms; Down arrow moves focus to the first listing; the caret is pink, and the page's blooms swell. Home focuses the field on arrival.
- **Keys:** Escape clears a non-empty query. Results replace the recents in place as you type.

### Programme Listing
One film as a listing: poster, title, meta, and for an opened film where you stopped.
- **Shape:** 156px wide, 2:3 poster clipped at 14px corners on raised stock; the title is centred in dim paper when there is no poster.
- **Text:** title in paper, meta in dim paper.
- **Hover:** a soft bloom of the listing's ink (blue for recents, pink for results) rises behind the poster over 360ms; hand cursor; no ripple, lift or scale.

### Position Mark
Where you stopped, in blue ink: a 3px pill track in faint paper filled with blue to the resume fraction, with "Stopped at h:mm:ss" in dim label type 5px below. Shown only when a resume position exists.

### Browse Toggle
Catalog browsing folded to the bottom as a headline-size text button with a 26px chevron. Dim paper at rest, paper on hover; the chevron turns 180° over 320ms when open, and the one-line hint fades out.

## Do's and Don'ts

### Do:
- **Do** take stock, ink, grain and the palette from `RisoMaterial.kt` (`Riso`, `Modifier.risoBlooms`, `Modifier.risoGrain`) when bringing a screen into the world.
- **Do** place ink where its meaning lives: pink behind the thing the user acts on, blue behind what they returned to, sun behind what is set aside.
- **Do** let a hovered or keyboard-focused item answer with a bloom of its own ink rather than an outline or lift: focus and hover are one affordance.
- **Do** keep posters and frames in their own colour at 2:3, 14px corners.
- **Do** carry hierarchy with the type ramp: display 124px for the one thing the user types, headline 21px for sections, 12px to 14px for everything in a listing.
- **Do** keep running text to about 60% of the content width.

### Don't:
- **Don't** use an ink for a meaning it does not own, or as decoration.
- **Don't** add drop shadows, borders or cards-in-cards; depth is ink and raised stock.
- **Don't** fade a bloom linearly, or clip a bloom to a component's edge.
- **Don't** tint, duotone or overprint ink onto poster art.
- **Don't** introduce a second typeface on one surface while the display-face question is open.
- **Don't** grain or tint the video picture; grain belongs to chrome panels only.
