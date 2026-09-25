// Clip trim + export UI for the desktop player.
//
// Split out of controls.js so upstream Nuvio merges stay clean: everything the
// clipper adds to the player chrome lives here, and controls.js keeps only two
// call sites, both null-safe through the window.clipUi namespace below.
//
// Loaded after controls.js, so the shared helpers used here -- state, send,
// setVisible, formatTime -- are already bound in the global lexical scope by
// the time any of these functions run.

// The clip fields are deliberately absent from the state defaults in
// controls.js -- every read below coerces (Boolean/Number/|| ""), so an
// undefined field behaves exactly like the old default, and controls.js keeps
// zero clipper lines in the object literal upstream edits most often.

// --- element handles ---
const scrubWrap = document.getElementById("scrubWrap");
const clipRow = document.getElementById("clipRow");
const clipRange = document.getElementById("clipRange");
const clipHandleIn = document.getElementById("clipHandleIn");
const clipHandleOut = document.getElementById("clipHandleOut");
const clipMainPlayhead = document.getElementById("clipMainPlayhead");
const clipInReadout = document.getElementById("clipInReadout");
const clipOutReadout = document.getElementById("clipOutReadout");
const clipLenReadout = document.getElementById("clipLenReadout");
const clipProgressTrack = document.getElementById("clipProgressTrack");
const clipProgressBar = document.getElementById("clipProgressBar");
const clipStatus = document.getElementById("clipStatus");
const clipCancelButton = document.getElementById("clipCancelButton");
const clipRevealButton = document.getElementById("clipRevealButton");
const clipDismissButton = document.getElementById("clipDismissButton");
const clipSubsButton = document.getElementById("clipSubsButton");
const clipPreviewButton = document.getElementById("clipPreviewButton");
const clipAddRangeButton = document.getElementById("clipAddRangeButton");
const clipRangeList = document.getElementById("clipRangeList");
const clipZoomRangeList = document.getElementById("clipZoomRangeList");
const clipRangeChips = document.getElementById("clipRangeChips");
const clipExportButton = document.getElementById("clipExportButton");
const clipZoomWrap = document.getElementById("clipZoomWrap");
const clipZoomTrack = document.getElementById("clipZoomTrack");
const clipZoomRange = document.getElementById("clipZoomRange");
const clipZoomPlayhead = document.getElementById("clipZoomPlayhead");
const clipZoomNow = document.getElementById("clipZoomNow");
const clipZoomHandleIn = document.getElementById("clipZoomHandleIn");
const clipZoomHandleOut = document.getElementById("clipZoomHandleOut");
const clipZoomStartLabel = document.getElementById("clipZoomStartLabel");
const clipZoomEndLabel = document.getElementById("clipZoomEndLabel");
const clipZoomScale = document.getElementById("clipZoomScale");
const clipJobsButton = document.getElementById("clipJobsButton");
const clipJobsPanel = document.getElementById("clipJobsPanel");
const clipJobsList = document.getElementById("clipJobsList");
const clipLibraryButton = document.getElementById("clipLibraryButton");
const clipLibraryPanel = document.getElementById("clipLibraryPanel");
const clipLibraryList = document.getElementById("clipLibraryList");
const clipLibraryPath = document.getElementById("clipLibraryPath");
const clipStripButton = document.getElementById("clipStripButton");
const clipKeysButton = document.getElementById("clipKeysButton");
const clipRowStatus = clipRow.querySelector(".clip-row-status");
const clipUndoButton = document.getElementById("clipUndoButton");

// --- clipper mode ---
//
// `viewingChromeEnabled` comes from AppFeaturePolicy, not from playback state, so
// it never changes for the life of the window. Undefined means the field never
// arrived (an older host, or the standalone browser harness) -- fall back to
// upstream behaviour and leave the viewing chrome alone.
const isClipperMode = () => state.viewingChromeEnabled === false;

/**
 * Keep the controls up while a trim is in progress.
 *
 * Marking in/out means staring at the timeline without moving the mouse, which
 * is exactly what the idle timer reads as "user is watching, hide everything".
 */
const clipShouldPinChrome = () =>
  clipStripVisible || (Boolean(state.showClip) && clipTrimStarted());

/** The three editable timecodes, paired with the draft point each one names. */
const clipTimeFields = [
  ["in", clipInReadout],
  ["out", clipOutReadout],
  ["len", clipLenReadout],
];

// --- trim draft state ---
//
// One title, several clips. `clipDraft` is the pair being built right now;
// `clipRanges` holds the ones already set aside. Scrubbing a film for the good
// bits finds more than one of them, and the old single-draft model made you
// export, wait, and re-find your place before you could mark the next.
let clipDraft = { inMs: null, outMs: null };
let clipRanges = [];
let clipDraftDurationMs = 0;
let clipDragTarget = null;
let clipPreviewActive = false;
let clipPreviewLoopSeekAt = 0;
let clipLibraryOpen = false;
let clipJobsOpen = false;
// Window of the timeline the zoom track spans, or null when zoom is off.
let clipZoomView = null;
// The range a chip's remove button just took out, kept long enough to undo.
let clipRemovedRange = null;
let clipRemovedTimer = 0;
const CLIP_UNDO_WINDOW_MS = 6000;
// When a first Escape was swallowed to protect the draft; a second one inside
// the window is taken as meaning it.
let clipEscArmedAt = 0;
let clipEscTimer = 0;
const CLIP_ESC_WINDOW_MS = 2500;

const clipHasRange = () =>
  clipDraft.inMs != null && clipDraft.outMs != null && clipDraft.outMs > clipDraft.inMs;

/** True once either point is marked, i.e. a trim is under way. */
const clipTrimStarted = () =>
  clipDraft.inMs != null || clipDraft.outMs != null || clipRanges.length > 0;

/** Everything that would be exported right now, set-aside ranges plus the draft. */
const clipExportableRanges = () => {
  const ranges = clipRanges.slice();
  if (clipHasRange()) ranges.push({ inMs: clipDraft.inMs, outMs: clipDraft.outMs });
  return ranges;
};

/** Clip times show tenths of a second -- precision the plain time labels don't need. */
const formatClipTime = ms => {
  const safe = Math.max(0, Number(ms) || 0);
  return `${formatTime(safe)}.${Math.floor((safe % 1000) / 100)}`;
};

/**
 * Microseconds in one source frame, probed off the container by Kotlin, or 0
 * while the probe is out (or failed). Microseconds rather than a rate so the
 * step stays an exact integer -- a rounded 23.976 drifts a frame within a
 * couple of dozen presses.
 */
const clipFrameDurationUs = () => Number(state.clipFrameDurationUs) || 0;

/** Step used while the source rate is unknown. */
const CLIP_FALLBACK_STEP_MS = 100;

/**
 * Where `frames` frames from `fromMs` lands. Snapped to the frame grid rather
 * than added to the current value, so a playhead sitting mid-frame lands on a
 * boundary at the first press instead of carrying the offset forever.
 */
const clipFrameStepMs = (fromMs, frames) => {
  const frameUs = clipFrameDurationUs();
  if (frameUs <= 0) return fromMs + frames * CLIP_FALLBACK_STEP_MS;
  const index = Math.round((fromMs * 1000) / frameUs) + frames;
  return Math.max(0, Math.round((index * frameUs) / 1000));
};

// --- zoomed trim window ---------------------------------------------------
//
// On a 2h movie the main scrub bar packs ~7 seconds into every pixel, so a
// 20-second selection is a sliver and the mouse cannot place a cut within a
// second. Once the handles are that close together, a second track appears
// beneath the scrub bar spanning only a narrow window around the selection,
// which buys back two orders of magnitude of pointer precision.

/** Never show a window narrower than this, or the handles have nowhere to go. */
const CLIP_ZOOM_MIN_WINDOW_MS = 4000;
/** Selection-to-window ratio: the selection fills a third of the zoom track. */
const CLIP_ZOOM_CONTEXT_FACTOR = 3;
/** Window width before anything is marked, so the track still has a scale. */
const CLIP_ZOOM_IDLE_WINDOW_MS = 30000;

// The track used to appear only once the selection was already under a tenth of
// the runtime, which had it backwards: placing a cut to the second on the main
// scrub bar is the very thing that needs magnifying, so the tool only showed up
// after the hard part was over. It is up whenever the clip row is.
const clipZoomShouldShow = () => Boolean(state.showClip) && clipDraftDurationMs > 0;

/**
 * The stretch of runtime the zoom track spans, clamped to the film.
 *
 * Anchored on the selection once there is one, and on the playhead until then --
 * with nothing marked, the part worth magnifying is wherever you are looking.
 */
const clipZoomComputeView = () => {
  const selection = clipHasRange() ? clipDraft.outMs - clipDraft.inMs : 0;
  const floorMs = selection > 0 ? CLIP_ZOOM_MIN_WINDOW_MS : CLIP_ZOOM_IDLE_WINDOW_MS;
  const width = Math.min(
    clipDraftDurationMs,
    Math.max(selection * CLIP_ZOOM_CONTEXT_FACTOR, floorMs),
  );
  const centre = clipHasRange()
    ? (clipDraft.inMs + clipDraft.outMs) / 2
    : Number(state.positionMs) || 0;
  const start = Math.max(0, Math.min(Math.max(0, clipDraftDurationMs - width), centre - width / 2));
  return { startMs: start, endMs: start + width };
};

/**
 * Slide the window (keeping its width) so `ms` stays off the edges.
 * Used while dragging: recentring mid-drag would yank the track out from under
 * the pointer, whereas panning only kicks in near the ends.
 */
const clipZoomPanTo = ms => {
  if (!clipZoomView) return;
  const width = clipZoomView.endMs - clipZoomView.startMs;
  const pad = width * 0.1;
  let start = clipZoomView.startMs;
  if (ms < start + pad) start = ms - pad;
  else if (ms > start + width - pad) start = ms - width + pad;
  start = Math.max(0, Math.min(Math.max(0, clipDraftDurationMs - width), start));
  clipZoomView = { startMs: start, endMs: start + width };
};

const clipZoomPercentFor = ms => {
  if (!clipZoomView) return 0;
  const width = clipZoomView.endMs - clipZoomView.startMs;
  if (width <= 0) return 0;
  return Math.max(0, Math.min(100, ((ms - clipZoomView.startMs) / width) * 100));
};

/**
 * Paints one set-aside range per child of `list`, positioned by `percentFor`.
 *
 * Rebuilt from scratch on every render rather than diffed: there are a handful
 * of these at most, and a stale bar pointing at a range that was removed is a
 * worse bug than the cost of recreating them.
 */
const clipPaintRangeList = (list, percentFor) => {
  list.textContent = "";
  clipRanges.forEach(range => {
    const start = percentFor(range.inMs);
    const end = percentFor(range.outMs);
    if (end <= start) return;
    const bar = document.createElement("div");
    bar.className = "clip-range-item";
    bar.style.left = `${start}%`;
    bar.style.width = `${end - start}%`;
    list.appendChild(bar);
  });
};

/**
 * Draws the zoom track from `clipZoomView`. Each point is drawn on its own, so
 * the half-made state after marking only In renders as a lone marker.
 */
const clipZoomPaint = () => {
  if (!clipZoomView) return;
  const inPct = clipDraft.inMs == null ? null : clipZoomPercentFor(clipDraft.inMs);
  const outPct = clipDraft.outMs == null ? null : clipZoomPercentFor(clipDraft.outMs);
  clipZoomHandleIn.hidden = inPct == null;
  clipZoomHandleOut.hidden = outPct == null;
  if (inPct != null) clipZoomHandleIn.style.left = `${inPct}%`;
  if (outPct != null) clipZoomHandleOut.style.left = `${outPct}%`;
  const hasSpan = inPct != null && outPct != null && outPct > inPct;
  clipZoomRange.hidden = !hasSpan;
  if (hasSpan) {
    clipZoomRange.style.left = `${inPct}%`;
    clipZoomRange.style.width = `${outPct - inPct}%`;
  }
  clipPaintRangeList(clipZoomRangeList, clipZoomPercentFor);
  clipZoomSetPlayhead(Number(state.positionMs) || 0);
  clipZoomStartLabel.textContent = formatClipTime(clipZoomView.startMs);
  clipZoomEndLabel.textContent = formatClipTime(clipZoomView.endMs);
  const windowSec = (clipZoomView.endMs - clipZoomView.startMs) / 1000;
  clipZoomScale.textContent = `${windowSec < 10 ? windowSec.toFixed(1) : Math.round(windowSec)}s view`;
};

/**
 * Moves the zoom playhead and the timecode riding under it.
 *
 * The only "where am I" readout next to the bars: the runtime label sits at the
 * far bottom right, away from where the eye is while trimming. Near either end
 * the label flips to hang inward rather than spill over the edge timecodes.
 */
const clipZoomSetPlayhead = positionMs => {
  const pct = clipZoomPercentFor(positionMs);
  clipZoomPlayhead.style.left = `${pct}%`;
  clipZoomNow.textContent = formatClipTime(positionMs);
  clipZoomNow.dataset.edge = pct < 6 ? "start" : pct > 94 ? "end" : "";
};

const clipZoomUpdatePlayhead = positionMs => {
  if (!clipZoomView || clipZoomWrap.hidden) return;
  // With nothing marked the window trails the playhead, panning only as it nears
  // an edge -- a window that recentred every tick would slide under the eye
  // continuously and never give a stable scale to read.
  if (!clipHasRange() && clipDragTarget == null) {
    clipZoomPanTo(positionMs);
    clipZoomPaint();
    clipZoomSetPlayhead(positionMs);
    return;
  }
  clipZoomSetPlayhead(positionMs);
};

const renderClipZoom = () => {
  const show = clipZoomShouldShow();
  clipZoomWrap.hidden = !show;
  if (!show) {
    clipZoomView = null;
    return;
  }
  // Recentre on the selection only at rest; while a handle is down the window
  // may only pan, so the pixel under the pointer keeps meaning the same instant.
  // A playhead-anchored window is left alone here -- updatePlayhead pans it, and
  // recomputing would snap it back on every unrelated render.
  if (!clipZoomView) clipZoomView = clipZoomComputeView();
  else if (clipDragTarget == null && clipHasRange()) clipZoomView = clipZoomComputeView();
  clipZoomPaint();
};

// --- exports in flight ----------------------------------------------------
//
// Marking a clip never waits on the previous export, so this title can have
// several jobs at once. The row above shows only the summary; every job gets a
// row here with its own progress and its own cancel.

const clipJobItems = () => (Array.isArray(state.clipJobs) ? state.clipJobs : []);

/** Index of the first job matching `predicate`, or -1. Addresses one row for Kotlin. */
const clipJobIndex = predicate => clipJobItems().findIndex(predicate);

const renderClipJobs = () => {
  const items = clipJobItems();
  const active = items.filter(item => item.isActive).length;
  clipJobsButton.hidden = items.length === 0;
  clipJobsButton.textContent = active > 0 ? `Exports (${active})` : "Exports";
  const open = clipJobsOpen && items.length > 0;
  clipJobsPanel.hidden = !open;
  if (!open) return;

  clipJobsList.textContent = "";
  items.forEach((item, index) => {
    const row = document.createElement("div");
    row.className = "clip-job-item";

    const range = document.createElement("span");
    range.className = "clip-job-range";
    range.textContent = item.rangeLabel || "";
    row.appendChild(range);

    const duration = document.createElement("span");
    duration.className = "clip-job-duration";
    duration.textContent = item.durationLabel || "";
    row.appendChild(duration);

    const status = document.createElement("span");
    status.className = "clip-job-status";
    status.dataset.kind = item.statusKind || "";
    status.textContent = item.statusMessage || "";
    status.title = item.statusMessage || "";
    row.appendChild(status);

    if (item.statusKind === "running") {
      const track = document.createElement("div");
      track.className = "clip-progress-track";
      const bar = document.createElement("div");
      bar.className = "clip-progress-bar";
      bar.style.transform = `scaleX(${Math.max(0, Math.min(1, Number(item.progress) || 0))})`;
      track.appendChild(bar);
      row.appendChild(track);
    }

    const actions = item.isActive
      ? [["Cancel", "clipCancel", ""]]
      : [
          ...(item.canReveal ? [["Show file", "clipReveal", ""]] : []),
          ["Dismiss", "clipDismiss", ""],
        ];
    actions.forEach(([label, event, extraClass]) => {
      const button = document.createElement("button");
      button.type = "button";
      button.className = `clip-action${extraClass ? ` ${extraClass}` : ""}`;
      button.textContent = label;
      button.addEventListener("click", clickEvent => {
        clickEvent.stopPropagation();
        send(event, index);
      });
      row.appendChild(button);
    });

    clipJobsList.appendChild(row);
  });
};

// --- saved clips for this title ------------------------------------------

const clipLibraryItems = () => (Array.isArray(state.clipLibrary) ? state.clipLibrary : []);

const renderClipLibrary = () => {
  const items = clipLibraryItems();
  clipLibraryButton.hidden = items.length === 0;
  clipLibraryButton.textContent = `Clips (${items.length})`;
  const open = clipLibraryOpen && items.length > 0;
  clipLibraryPanel.hidden = !open;
  if (!open) return;

  clipLibraryPath.textContent = clipShortPath(state.clipOutputDir);
  clipLibraryPath.title = state.clipOutputDir || "";
  clipLibraryList.textContent = "";
  items.forEach((item, index) => {
    const row = document.createElement("div");
    row.className = "clip-library-item";

    const name = document.createElement("span");
    name.className = "clip-library-item-name";
    name.textContent = item.fileName || "clip.mp4";
    name.title = item.fileName || "";
    row.appendChild(name);

    const meta = document.createElement("span");
    meta.className = "clip-library-item-meta";
    meta.textContent = [item.rangeLabel, item.durationLabel].filter(Boolean).join(" \u00b7 ");
    row.appendChild(meta);

    // The index is the address: Kotlin resolves it against the same
    // per-title, newest-first list it just sent, so ids stay out of the DOM.
    [
      ["Play", "clipLibraryOpen", ""],
      ["Show", "clipLibraryReveal", ""],
      ["Delete", "clipLibraryDelete", "danger"],
    ].forEach(([label, event, extraClass]) => {
      const button = document.createElement("button");
      button.type = "button";
      button.className = `clip-action${extraClass ? ` ${extraClass}` : ""}`;
      button.textContent = label;
      button.addEventListener("click", clickEvent => {
        clickEvent.stopPropagation();
        send(event, index);
      });
      row.appendChild(button);
    });

    clipLibraryList.appendChild(row);
  });
};

/**
 * One chip per set-aside range, numbered in film order and named by where it
 * sits -- a length alone stops telling ranges apart after the third. The chip
 * itself jumps to its IN; only the small x takes it out, and even that can be
 * undone for a few seconds, because a range can take minutes to find.
 */
const renderClipRangeChips = () => {
  clipRangeChips.textContent = "";
  clipRanges.forEach((range, index) => {
    const label = `#${index + 1} ${formatClipTime(range.inMs)}\u2013${formatClipTime(range.outMs)}`;
    const chip = document.createElement("span");
    chip.className = "clip-range-chip";

    const jump = document.createElement("button");
    jump.type = "button";
    jump.className = "clip-range-chip-jump";
    jump.textContent = label;
    jump.title = `Go to range ${index + 1} (${formatClipTime(range.outMs - range.inMs)} long)`;
    jump.addEventListener("click", event => {
      event.stopPropagation();
      clipPauseForInspection();
      clipSeekTo(range.inMs);
      noteChromeActivity();
    });

    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "clip-range-chip-remove";
    remove.setAttribute("aria-label", `Remove range ${index + 1}, ${label}`);
    remove.title = "Remove this range";
    remove.innerHTML = `<svg aria-hidden="true"><use href="#icon-close"></use></svg>`;
    remove.addEventListener("click", event => {
      event.stopPropagation();
      clipRemoveRange(index);
    });

    chip.append(jump, remove);
    clipRangeChips.appendChild(chip);
  });
};

/** Keeps set-aside ranges in film order, so chip numbers read left to right. */
const clipSortRanges = () => clipRanges.sort((a, b) => a.inMs - b.inMs);

const clipClearRemoved = () => {
  clipRemovedRange = null;
  if (clipRemovedTimer) clearTimeout(clipRemovedTimer);
  clipRemovedTimer = 0;
};

const clipRemoveRange = index => {
  const [range] = clipRanges.splice(index, 1);
  if (!range) return;
  clipClearRemoved();
  clipRemovedRange = range;
  clipRemovedTimer = setTimeout(() => {
    clipClearRemoved();
    renderClipUi();
  }, CLIP_UNDO_WINDOW_MS);
  renderClipUi();
  noteChromeActivity();
};

const clipUndoRemove = () => {
  if (!clipRemovedRange) return;
  clipRanges.push(clipRemovedRange);
  clipSortRanges();
  clipClearRemoved();
  renderClipUi();
  noteChromeActivity();
};

/** `/Users/name/Movies/StreamCut` reads as `~/Movies/StreamCut`. */
const clipShortPath = path => String(path || "").replace(/^\/(Users|home)\/[^/]+(?=\/|$)/, "~");

/**
 * mpv draws subtitles on the video layer under this page, so with the clip
 * chrome up a line of dialogue landed on the clip row and timeline and neither
 * could be read. While the chrome shows, tell the native side how much of the
 * window it covers (percent from the bottom) so the subtitle sits above it; 0
 * puts it back. Only the clipper build lifts -- the viewing chrome is unchanged.
 */
let clipSubtitleLiftSent = -1;
let clipSubtitleLiftFrame = 0;
const clipSyncSubtitleLift = () => {
  if (clipSubtitleLiftFrame) return;
  clipSubtitleLiftFrame = requestAnimationFrame(() => {
    clipSubtitleLiftFrame = 0;
    let lift = 0;
    const chromeUp = isClipperMode() && state.controlsVisible && !root.classList.contains("chrome-hidden");
    if (chromeUp && !clipRow.hidden && window.innerHeight > 0) {
      const top = clipRow.getBoundingClientRect().top;
      // A little air over the row, so the line never touches the buttons.
      lift = Math.round((window.innerHeight - top) / window.innerHeight * 100) + 2;
      lift = Math.max(0, Math.min(60, lift));
    }
    if (lift === clipSubtitleLiftSent) return;
    clipSubtitleLiftSent = lift;
    send("subtitleLift", lift);
  });
};
window.addEventListener("resize", clipSyncSubtitleLift);
if (typeof ResizeObserver === "function") new ResizeObserver(clipSyncSubtitleLift).observe(clipRow);

const renderClipUi = () => {
  clipSyncSubtitleLift();
  document.body.classList.toggle("clipper-mode", isClipperMode());
  // Declared later in the file; safe because every render happens after load.
  clipStripRender();
  const show = Boolean(state.showClip);
  const hasDuration = show && clipDraftDurationMs > 0;
  // Each point is drawn on its own. Marking In leaves a single marker and no
  // range, and that half-made state has to show or there is no confirmation the
  // mark landed where you meant it.
  const hasIn = hasDuration && clipDraft.inMs != null;
  const hasOut = hasDuration && clipDraft.outMs != null;
  const ready = hasDuration && clipHasRange();
  setVisible(clipRow, show);
  clipRange.hidden = !ready;
  clipHandleIn.hidden = !hasIn;
  clipHandleOut.hidden = !hasOut;
  if (clipStripButton) clipStripButton.hidden = !hasDuration;
  clipMainPlayhead.hidden = !hasDuration;
  if (!show) {
    clipStripHide();
    clipZoomWrap.hidden = true;
    clipJobsPanel.hidden = true;
    clipJobsButton.hidden = true;
    clipLibraryPanel.hidden = true;
    clipLibraryButton.hidden = true;
    return;
  }
  const running = Boolean(state.clipRunning);
  const clipTrackPercent = ms => Math.max(0, Math.min(100, ms / clipDraftDurationMs * 100));
  if (hasDuration) clipPaintRangeList(clipRangeList, clipTrackPercent);
  else clipRangeList.textContent = "";
  if (hasIn) clipHandleIn.style.left = `${clipTrackPercent(clipDraft.inMs)}%`;
  if (hasOut) clipHandleOut.style.left = `${clipTrackPercent(clipDraft.outMs)}%`;
  if (ready) {
    const inPct = clipTrackPercent(clipDraft.inMs);
    const outPct = clipTrackPercent(clipDraft.outMs);
    clipRange.style.left = `${inPct}%`;
    clipRange.style.width = `${Math.max(0, outPct - inPct)}%`;
  }
  // Never overwrite a field mid-edit; the blur handler is what reconciles it.
  clipTimeFields.forEach(([target, input]) => {
    if (document.activeElement !== input) input.value = clipFieldText(target);
  });
  // A length is only meaningful measured from somewhere.
  clipLenReadout.disabled = clipDraft.inMs == null;
  renderClipRangeChips();
  clipAddRangeButton.disabled = !clipHasRange();
  if (clipKeysButton) clipKeysButton.hidden = !hasDuration;
  // Exports run in the background, so nothing below is gated on one: the trim
  // controls stay live while clips encode, and so does the export button.
  const exportable = clipExportableRanges().length;
  clipExportButton.disabled = exportable === 0;
  clipExportButton.textContent = exportable > 1 ? `Export ${exportable} clips` : "Export clip";
  // Preview loops the draft, so it needs the draft specifically, not a count.
  clipPreviewButton.disabled = !clipHasRange();
  // Only offered when something is actually on screen to burn in; the export
  // itself decides nothing, it just carries whatever this says.
  const burnSubtitles = Boolean(state.clipBurnSubtitles);
  clipSubsButton.hidden = !state.clipSubtitlesAvailable;
  clipSubsButton.classList.toggle("toggled-on", burnSubtitles);
  clipSubsButton.textContent = burnSubtitles ? "Subtitles: burn in" : "Subtitles: none";
  clipSubsButton.title = burnSubtitles
    ? "The subtitle on screen is rendered into the clip. Click to export without it."
    : "The clip is exported without subtitles. Click to burn in the one on screen.";
  clipPreviewButton.textContent = clipPreviewActive ? "Stop preview" : "Preview";
  // The row's buttons act on a single job, so they only appear when there is no
  // ambiguity about which one; the rest is per-row in the exports panel.
  const soleActiveIndex = clipJobItems().filter(item => item.isActive).length === 1
    ? clipJobIndex(item => item.isActive)
    : -1;
  const revealIndex = state.clipStatusKind === "done" ? clipJobIndex(item => item.canReveal) : -1;
  const dismissIndex = running ? -1 : clipJobIndex(item => !item.isActive);
  clipCancelButton.hidden = soleActiveIndex < 0;
  clipCancelButton.dataset.clipJobIndex = soleActiveIndex;
  clipRevealButton.hidden = revealIndex < 0;
  clipRevealButton.dataset.clipJobIndex = revealIndex;
  clipDismissButton.hidden = dismissIndex < 0 || !state.clipStatusKind;
  clipDismissButton.dataset.clipJobIndex = dismissIndex;
  if (running) {
    clipProgressTrack.hidden = false;
    clipProgressBar.style.transform = `scaleX(${Math.max(0, Math.min(1, Number(state.clipProgress) || 0))})`;
  } else {
    clipProgressTrack.hidden = true;
    clipProgressBar.style.transform = "scaleX(0)";
  }
  // Local notices outrank the export status for the few seconds they live:
  // they answer the key the user just pressed.
  const escArmed = clipEscArmedAt > 0;
  const pending = clipExportableRanges().length;
  let statusText = state.clipStatusMessage || "";
  let statusKind = state.clipStatusKind || "";
  if (escArmed) {
    statusText = `Press Esc again to leave \u2013 ${pending} unexported ${pending === 1 ? "range" : "ranges"} will be lost`;
    statusKind = "warn";
  } else if (clipRemovedRange) {
    statusText = `Removed ${formatClipTime(clipRemovedRange.inMs)}\u2013${formatClipTime(clipRemovedRange.outMs)}`;
    statusKind = "";
  }
  clipUndoButton.hidden = !clipRemovedRange || escArmed;
  clipStatus.textContent = statusText;
  // Failure text can run long and the row truncates it; hovering shows all of it.
  clipStatus.title = statusText;
  clipStatus.dataset.kind = statusKind;
  renderClipZoom();
  renderClipJobs();
  renderClipLibrary();
  // The line collapses when it has nothing to say, so an idle row is one line.
  clipRowStatus.hidden = !statusText && !running && [
    clipUndoButton, clipCancelButton, clipRevealButton, clipDismissButton, clipJobsButton,
  ].every(button => button.hidden);
  clipSyncHandleAria();
};

const clipSeekTo = ms => {
  const clamped = Math.max(0, Math.min(Number(state.durationMs) || ms, ms));
  state.positionMs = clamped;
  send("scrubFinish", clamped);
  return clamped;
};

const CLIP_MIN_GAP_MS = 200;

/**
 * Reads a typed timecode into milliseconds, or null if it cannot.
 *
 * Accepts what the readouts print back -- `44:09.5`, `1:30:00` -- and also bare
 * seconds, because typing `90` for a minute and a half is the obvious thing to
 * try. A comma is taken as a decimal point: this app is used in locales that
 * write it that way, and it is never ambiguous in a timecode.
 */
const parseClipTime = text => {
  const trimmed = String(text).trim().replace(",", ".");
  if (!/^\d{1,3}(:\d{1,2}){0,2}(\.\d{1,3})?$/.test(trimmed)) return null;
  const [whole, fraction] = trimmed.split(".");
  let seconds = 0;
  for (const part of whole.split(":")) seconds = seconds * 60 + Number(part);
  return Math.round(seconds * 1000 + Number(`0.${fraction || 0}`) * 1000);
};

/** What a timecode field should read right now; empty shows its placeholder. */
const clipFieldText = target => {
  if (target === "len") {
    return clipHasRange() ? formatClipTime(clipDraft.outMs - clipDraft.inMs) : "";
  }
  const ms = target === "in" ? clipDraft.inMs : clipDraft.outMs;
  return ms == null ? "" : formatClipTime(ms);
};

const clipApplyPoint = (target, ms) => {
  if (target === "in") {
    const limit = clipDraft.outMs != null ? clipDraft.outMs - CLIP_MIN_GAP_MS : clipDraftDurationMs;
    clipDraft.inMs = Math.max(0, Math.min(limit, ms));
    return clipDraft.inMs;
  }
  const floor = clipDraft.inMs != null ? clipDraft.inMs + CLIP_MIN_GAP_MS : 0;
  clipDraft.outMs = Math.min(clipDraftDurationMs, Math.max(floor, ms));
  return clipDraft.outMs;
};

/**
 * Holds the picture still so the frame being landed on can be read.
 *
 * Used when a typed timecode jumps the playhead: the point of the jump is to see
 * the frame it names, which a running film carries away before the eye gets
 * there. Quiet, because this is not the user asking to stop watching; it is the
 * trim UI needing a still to work against.
 */
const clipPauseForInspection = () => {
  if (!state.isPlaying) return;
  // Optimistic, like controls.js's own toggle: the next native update carries
  // the real value a beat later.
  state.isPlaying = false;
  send("setPlaybackStateQuiet", 0);
};

/**
 * Sets the current range aside and clears the draft for the next one.
 *
 * Nothing is exported here -- the ranges pile up until Export, which starts one
 * job per range. Keeping them as drafts rather than firing each off immediately
 * is what lets you go back and remove one you changed your mind about.
 */
const clipAddRange = () => {
  if (!clipHasRange()) return;
  clipRanges.push({ inMs: clipDraft.inMs, outMs: clipDraft.outMs });
  clipSortRanges();
  clipDraft = { inMs: null, outMs: null };
  clipPreviewActive = false;
  clipZoomView = null;
  renderClipUi();
  noteChromeActivity();
};

/**
 * Undo, one step at a time: the half-made draft first, then the set-aside
 * ranges newest-first. Clearing everything at once would throw away work that
 * took minutes to find, on a single keypress.
 */
const clipClearDraft = () => {
  if (clipTrimStarted() && clipDraft.inMs == null && clipDraft.outMs == null) {
    clipRanges.pop();
  } else {
    clipDraft = { inMs: null, outMs: null };
  }
  clipPreviewActive = false;
  clipZoomView = null;
  renderClipUi();
  noteChromeActivity();
};

/**
 * Sets In or Out to the frame already on screen.
 *
 * What matters here is what it does *not* do: it never seeks. Dragging a handle
 * live-seeks the player (see bindClipHandle below), so trimming by drag costs
 * you the frame you just spent minutes hunting for. Marking brings the point to
 * the playhead instead of dragging the playhead to the point, and the picture
 * never moves.
 *
 * Marking past the opposite point clears that opposite instead of clamping
 * against it: someone who marks In an hour after the current Out is starting a
 * fresh selection there, not asking for a 200ms clip.
 */
const clipMarkAtPlayhead = target => {
  if (clipDraftDurationMs <= 0) return;
  const ms = Math.max(0, Math.min(clipDraftDurationMs, Number(state.positionMs) || 0));
  if (target === "in") {
    if (clipDraft.outMs != null && ms >= clipDraft.outMs - CLIP_MIN_GAP_MS) clipDraft.outMs = null;
    clipDraft.inMs = ms;
  } else {
    if (clipDraft.inMs != null && ms <= clipDraft.inMs + CLIP_MIN_GAP_MS) clipDraft.inMs = null;
    clipDraft.outMs = ms;
  }
  clipPreviewActive = false;
  // The one moment the zoom window should jump: the selection just moved.
  clipZoomView = null;
  renderClipUi();
  noteChromeActivity();
};

/**
 * Makes [inMs, outMs] the draft, as if both had been typed: the search panel's
 * "Clip" lands here. Pauses on the IN frame so what was just set is on screen.
 */
const clipSetRange = (inMs, outMs) => {
  if (clipDraftDurationMs <= 0) return;
  const start = Math.max(0, Math.min(clipDraftDurationMs - CLIP_MIN_GAP_MS, inMs));
  clipDraft = {
    inMs: start,
    outMs: Math.min(clipDraftDurationMs, Math.max(start + CLIP_MIN_GAP_MS, outMs)),
  };
  clipPreviewActive = false;
  clipZoomView = null;
  clipPauseForInspection();
  clipSeekTo(start);
  renderClipUi();
  noteChromeActivity();
};

/**
 * Moves the playhead one frame. Nothing about the draft changes -- step to the
 * frame you want with `,` / `.`, then press I or O to pin it. Pauses first: a
 * frame you cannot hold still is a frame you cannot judge.
 */
const clipStepPlayhead = frames => {
  if (clipDraftDurationMs <= 0) return;
  clipPauseForInspection();
  const from = Math.max(0, Number(state.positionMs) || 0);
  // Not clipSeekTo: its scrubFinish seek snaps to a keyframe, so a one-frame
  // target lands back on the same picture. clipFrameSeek asks for the exact one.
  const target = Math.max(0, Math.min(Number(state.durationMs) || Infinity, clipFrameStepMs(from, frames)));
  state.positionMs = target;
  send("clipFrameSeek", target);
  noteChromeActivity();
};

/**
 * The clipper's keyboard map. controls.js offers every keydown here before its
 * own shortcut table, so these win; a truthy return means the key was consumed.
 *
 *   I O        mark In / Out at the frame on screen
 *   , .        step the playhead one frame back / forward
 *   ?          the shortcut sheet
 *   A          set this range aside and start another
 *   X          export every range
 *   R          review the selection on a loop
 *   Backspace  undo -- the draft, then the set-aside ranges newest-first
 *
 * Deliberately not on the letters upstream already spends (S/T/C/E/P for the
 * panels, K/J/L and the arrows for transport) -- a clipper build still has to
 * reach the subtitle and audio pickers, since a clip inherits both.
 */
const clipHandleKey = event => {
  // Before the showClip guard: the strip covers the screen, so Escape has to
  // reach it whatever the chrome underneath is doing.
  if (clipStripVisible) {
    if (event.code === "Escape" || event.code === "KeyB") {
      clipStripHide();
      event.preventDefault();
      return true;
    }
    return false;
  }
  if (clipKeysVisible) {
    if (event.code === "Escape" || event.key === "?") {
      clipKeysHide();
      event.preventDefault();
      return true;
    }
    return false;
  }
  if (!state.showClip || clipDraftDurationMs <= 0) return false;
  if (event.key === "?" && !event.metaKey && !event.ctrlKey && !event.altKey) {
    clipKeysShow();
    event.preventDefault();
    return true;
  }
  if (event.metaKey || event.ctrlKey || event.altKey || event.shiftKey) return false;
  switch (event.code) {
    case "Comma":
    case "Period":
      clipStepPlayhead(event.code === "Comma" ? -1 : 1);
      break;
    case "KeyI":
    case "KeyO":
      clipMarkAtPlayhead(event.code === "KeyI" ? "in" : "out");
      break;
    case "KeyA":
      clipAddRange();
      break;
    case "KeyB":
      clipStripShow();
      break;
    case "KeyX":
      if (!clipExportButton.disabled) clipExportButton.click();
      break;
    case "KeyR":
      if (!clipPreviewButton.disabled) clipPreviewButton.click();
      break;
    case "Backspace":
    case "Delete":
      clipClearDraft();
      break;
    default:
      return false;
  }
  event.preventDefault();
  return true;
};

/**
 * Applies a typed timecode.
 *
 * A typed length moves Out, never In: In is the point that was chosen against a
 * frame, so "make this 30 seconds" means extend the end, not shift the start.
 */
const clipCommitTime = (target, ms) => {
  clipPreviewActive = false;
  if (target === "len") {
    if (clipDraft.inMs == null) return;
    clipDraft.outMs = Math.min(
      clipDraftDurationMs,
      clipDraft.inMs + Math.max(CLIP_MIN_GAP_MS, ms),
    );
  } else {
    const clamped = Math.max(0, Math.min(clipDraftDurationMs, ms));
    // Same rule as marking: a point typed past its opposite starts a new
    // selection rather than being clamped into a sliver against it.
    if (target === "in") {
      if (clipDraft.outMs != null && clamped >= clipDraft.outMs - CLIP_MIN_GAP_MS) clipDraft.outMs = null;
      clipDraft.inMs = clamped;
    } else {
      if (clipDraft.inMs != null && clamped <= clipDraft.inMs + CLIP_MIN_GAP_MS) clipDraft.inMs = null;
      clipDraft.outMs = clamped;
    }
    // Typing a point is a deliberate jump -- show the frame it names.
    clipPauseForInspection();
    clipSeekTo(clamped);
  }
  clipZoomView = null;
  renderClipUi();
};

/**
 * Wires one timecode field.
 *
 * Every keydown is stopped from reaching controls.js: its handler treats Escape
 * as "leave the player" before it ever checks whether a text field has focus,
 * and Space would toggle playback mid-timecode.
 */
const clipBindTimeField = (input, target) => {
  const revert = () => { input.value = clipFieldText(target); };
  input.addEventListener("focus", () => input.select());
  input.addEventListener("keydown", event => {
    event.stopPropagation();
    if (event.code === "Enter" || event.code === "NumpadEnter") {
      input.blur();
    } else if (event.code === "Escape") {
      event.preventDefault();
      revert();
      input.blur();
    }
  });
  input.addEventListener("blur", () => {
    const ms = parseClipTime(input.value);
    if (ms != null) clipCommitTime(target, ms);
    // Rewrite either way. A commit normalises what was typed (`1:29:59,5`
    // becomes `1:29:59.5`, `90` becomes `01:30.0`), and a rejected entry has to
    // go back to what the draft actually holds rather than sitting there
    // looking accepted.
    revert();
  });
};

clipBindTimeField(clipInReadout, "in");
clipBindTimeField(clipOutReadout, "out");
clipBindTimeField(clipLenReadout, "len");

document.querySelectorAll("[data-clip-mark]").forEach(button => {
  button.addEventListener("click", event => {
    event.stopPropagation();
    clipMarkAtPlayhead(button.dataset.clipMark);
  });
});

const clipTrackMsFromEvent = event => {
  const rect = scrubWrap.getBoundingClientRect();
  const ratio = rect.width > 0 ? (event.clientX - rect.left) / rect.width : 0;
  return Math.max(0, Math.min(1, ratio)) * clipDraftDurationMs;
};

const clipZoomMsFromEvent = event => {
  if (!clipZoomView) return clipTrackMsFromEvent(event);
  const rect = clipZoomTrack.getBoundingClientRect();
  const ratio = rect.width > 0 ? (event.clientX - rect.left) / rect.width : 0;
  const width = clipZoomView.endMs - clipZoomView.startMs;
  return clipZoomView.startMs + Math.max(0, Math.min(1, ratio)) * width;
};

let clipDragLastSeekAt = 0;

/**
 * Wires one In/Out handle for pointer dragging.
 *
 * `msFromEvent` is what makes the same code serve both tracks: the main scrub
 * maps the pointer across the whole runtime, the zoom track across its window.
 * `onDrag` lets the zoom track pan itself as a handle nears the edge.
 */
const bindClipHandle = (handle, msFromEvent, onDrag) => {
  const target = handle.dataset.clipHandle;
  handle.addEventListener("pointerdown", event => {
    if (clipDraftDurationMs <= 0) return;
    event.stopPropagation();
    event.preventDefault();
    clipDragTarget = target;
    clipPreviewActive = false;
    handle.setPointerCapture(event.pointerId);
    isScrubbing = true;
    noteChromeActivity();
  });
  handle.addEventListener("pointermove", event => {
    if (clipDragTarget !== target) return;
    event.stopPropagation();
    const ms = clipApplyPoint(target, msFromEvent(event));
    if (onDrag) onDrag(ms);
    scrubPositionMs = ms;
    setProgress(ms, state.durationMs);
    // Live-seek (throttled) so the frame under the handle shows while dragging.
    const now = Date.now();
    if (now - clipDragLastSeekAt > 200) {
      clipDragLastSeekAt = now;
      send("scrubFinish", ms);
    }
    renderClipUi();
    noteChromeActivity();
  });
  const finishDrag = event => {
    if (clipDragTarget !== target) return;
    event.stopPropagation();
    clipDragTarget = null;
    isScrubbing = false;
    const ms = target === "in" ? clipDraft.inMs : clipDraft.outMs;
    if (ms != null) clipSeekTo(ms);
    renderClipUi();
  };
  handle.addEventListener("pointerup", finishDrag);
  handle.addEventListener("pointercancel", finishDrag);
};

/**
 * Arrow keys on a focused handle move its point: one frame, or a second with
 * Shift. Stopped here so the transport's own arrow seek never fires under it.
 */
const bindClipHandleKeys = handle => {
  const target = handle.dataset.clipHandle;
  handle.addEventListener("keydown", event => {
    if (event.code !== "ArrowLeft" && event.code !== "ArrowRight") return;
    event.preventDefault();
    event.stopPropagation();
    const current = target === "in" ? clipDraft.inMs : clipDraft.outMs;
    if (current == null) return;
    const direction = event.code === "ArrowLeft" ? -1 : 1;
    const next = event.shiftKey ? current + direction * 1000 : clipFrameStepMs(current, direction);
    clipPauseForInspection();
    clipSeekTo(clipApplyPoint(target, next));
    clipZoomView = null;
    renderClipUi();
    noteChromeActivity();
  });
};
[clipHandleIn, clipHandleOut, clipZoomHandleIn, clipZoomHandleOut].forEach(bindClipHandleKeys);

/** Screen readers get the point's time, not a bare percentage. */
const clipSyncHandleAria = () => {
  [[clipHandleIn, clipZoomHandleIn, clipDraft.inMs], [clipHandleOut, clipZoomHandleOut, clipDraft.outMs]]
    .forEach(([main, zoom, ms]) => {
      [main, zoom].forEach(handle => {
        handle.setAttribute("aria-valuemin", "0");
        handle.setAttribute("aria-valuemax", String(Math.round(clipDraftDurationMs)));
        handle.setAttribute("aria-valuenow", String(Math.round(ms || 0)));
        handle.setAttribute("aria-valuetext", ms == null ? "not set" : formatClipTime(ms));
      });
    });
};

// The main-bar playhead follows the seek input's own --progress, which
// controls.js sets on every path that moves it -- playback ticks, scrubbing,
// keyboard seeks. Watching it means no call site in controls.js has to know.
const clipSeekInput = document.getElementById("seek");
const clipSyncMainPlayhead = () => {
  clipMainPlayhead.style.left = clipSeekInput.style.getPropertyValue("--progress") || "0%";
};
new MutationObserver(clipSyncMainPlayhead).observe(clipSeekInput, { attributes: true, attributeFilter: ["style"] });
clipSyncMainPlayhead();

bindClipHandle(clipHandleIn, clipTrackMsFromEvent, null);
bindClipHandle(clipHandleOut, clipTrackMsFromEvent, null);
bindClipHandle(clipZoomHandleIn, clipZoomMsFromEvent, clipZoomPanTo);
bindClipHandle(clipZoomHandleOut, clipZoomMsFromEvent, clipZoomPanTo);

// Clicking the zoom track scrubs within the window without moving the handles.
clipZoomTrack.addEventListener("pointerdown", event => {
  if (clipDragTarget != null) return;
  event.stopPropagation();
  clipPreviewActive = false;
  clipSeekTo(clipZoomMsFromEvent(event));
  renderClipUi();
  noteChromeActivity();
});

clipJobsButton.addEventListener("click", event => {
  event.stopPropagation();
  clipJobsOpen = !clipJobsOpen;
  renderClipUi();
});

clipLibraryButton.addEventListener("click", event => {
  event.stopPropagation();
  clipLibraryOpen = !clipLibraryOpen;
  renderClipUi();
});

clipPreviewButton.addEventListener("click", event => {
  event.stopPropagation();
  if (clipPreviewActive) {
    clipPreviewActive = false;
    renderClipUi();
    return;
  }
  if (!clipHasRange()) return;
  clipPreviewActive = true;
  clipPreviewLoopSeekAt = Date.now();
  clipSeekTo(clipDraft.inMs);
  if (!state.isPlaying) send("toggle", 0);
  renderClipUi();
});

clipSubsButton.addEventListener("click", event => {
  event.stopPropagation();
  send("clipBurnSubtitles", state.clipBurnSubtitles ? 0 : 1);
});

clipAddRangeButton.addEventListener("click", event => {
  event.stopPropagation();
  clipAddRange();
});

/**
 * Hands every range to Kotlin through `sendRange`, then clears them. Shared by
 * Export and Save as (clip-save.js), which differ only in what follows each
 * In/Out pair on the wire.
 */
const clipQueueExports = (ranges, sendRange) => {
  if (ranges.length === 0) return;
  clipPreviewActive = false;
  ranges.forEach((range, index) => {
    send("clipStart", range.inMs / 1000);
    send("clipEnd", range.outMs / 1000);
    sendRange(range, index);
  });
  // The ranges live in the Exports panel now; leaving them here would re-queue
  // every one of them on the next press.
  clipRanges = [];
  clipDraft = { inMs: null, outMs: null };
  clipZoomView = null;
  renderClipUi();
};

clipExportButton.addEventListener("click", event => {
  event.stopPropagation();
  // Kotlin holds no state between these: clipExport starts a job from whatever
  // clipStart/clipEnd last said, so one triple per range queues one job per
  // range, and they encode concurrently.
  clipQueueExports(clipExportableRanges(), () => send("clipExport", 0));
});
/** Row buttons carry the index of the job they act on, set during render. */
const bindClipRowJobAction = (button, event) => {
  button.addEventListener("click", clickEvent => {
    clickEvent.stopPropagation();
    const index = Number(button.dataset.clipJobIndex);
    if (!Number.isInteger(index) || index < 0) return;
    send(event, index);
  });
};
bindClipRowJobAction(clipCancelButton, "clipCancel");
bindClipRowJobAction(clipRevealButton, "clipReveal");
bindClipRowJobAction(clipDismissButton, "clipDismiss");
clipUndoButton.addEventListener("click", event => {
  event.stopPropagation();
  clipUndoRemove();
});

// --- Escape guard ----------------------------------------------------------
//
// controls.js reads a bare Escape as "leave the player", and the ranges being
// built live only in this page -- so one reflexive press threw away minutes of
// marking. While anything is unexported, the first Escape only warns; a second
// inside the window leaves as before. A capture listener, because controls.js
// handles Escape on document before any other key logic runs.
const clipEscDisarm = () => {
  clipEscArmedAt = 0;
  if (clipEscTimer) clearTimeout(clipEscTimer);
  clipEscTimer = 0;
};

window.addEventListener("keydown", event => {
  if (event.key !== "Escape") return;
  // A timecode field's own Escape reverts the field; that is not leaving.
  if (isTextEntryTarget(event.target)) return;
  if (!isClipperMode() || !state.showClip || state.isFullscreen) return;
  // Anything open closes first, through its own handler.
  if (activeModal || clipStripVisible || clipKeysVisible) return;
  const search = document.getElementById("clipSearchPanel");
  if (search && !search.hidden) return;
  if (clipExportableRanges().length === 0) return;
  if (clipEscArmedAt && Date.now() - clipEscArmedAt < CLIP_ESC_WINDOW_MS) {
    clipEscDisarm();
    return;
  }
  event.preventDefault();
  event.stopPropagation();
  clipEscDisarm();
  clipEscArmedAt = Date.now();
  clipEscTimer = setTimeout(() => {
    clipEscDisarm();
    renderClipUi();
  }, CLIP_ESC_WINDOW_MS);
  renderClipUi();
  noteChromeActivity();
}, true);

// --- shortcut sheet ----------------------------------------------------------
//
// The one place every key is written down, including the ones with no button
// (frame stepping, search, undo). Opened with ? or the keyboard icon.
let clipKeysVisible = false;

const CLIP_KEY_GROUPS = [
  ["Mark", [
    [["I"], "Mark IN at the frame on screen"],
    [["O"], "Mark OUT at the frame on screen"],
    [[",", "."], "Step one frame back / forward"],
    [["\u2190", "\u2192"], "On a focused handle: move it one frame (Shift: one second)"],
  ]],
  ["Ranges", [
    [["A"], "Set this range aside and start another"],
    [["R"], "Loop the range"],
    [["Backspace"], "Undo: the draft, then ranges newest first"],
  ]],
  ["Export and find", [
    [["X"], "Export every range"],
    [["\u21e7X"], "Save as: name the clips, pick the folder"],
    [["/"], "Find a line of dialogue"],
    [["B"], "Browse the film as stills"],
    [["Space"], "Play / pause"],
  ]],
];

const clipKeysOverlay = document.createElement("div");
clipKeysOverlay.className = "clip-keys";
clipKeysOverlay.hidden = true;
clipKeysOverlay.setAttribute("role", "dialog");
clipKeysOverlay.setAttribute("aria-modal", "true");
clipKeysOverlay.setAttribute("aria-labelledby", "clipKeysTitle");
clipKeysOverlay.innerHTML = `
  <div class="clip-keys-card">
    <div class="clip-keys-head">
      <h2 class="clip-keys-title" id="clipKeysTitle">Keyboard shortcuts</h2>
      <button class="clip-action compact" type="button" data-clip-keys-close>Close</button>
    </div>
    <div class="clip-keys-groups">${CLIP_KEY_GROUPS.map(([title, rows]) => `
      <section class="clip-keys-group">
        <h3 class="clip-keys-group-title">${title}</h3>
        <dl class="clip-keys-list">${rows.map(([keys, what]) => `
          <dt>${keys.map(key => `<kbd>${key}</kbd>`).join("")}</dt><dd>${what}</dd>`).join("")}
        </dl>
      </section>`).join("")}
    </div>
  </div>
`;
document.body.appendChild(clipKeysOverlay);

const clipKeysShow = () => {
  if (clipKeysVisible) return;
  clipKeysVisible = true;
  clipKeysOverlay.hidden = false;
  clipKeysOverlay.querySelector("[data-clip-keys-close]").focus();
  noteChromeActivity();
};

const clipKeysHide = () => {
  if (!clipKeysVisible) return;
  clipKeysVisible = false;
  clipKeysOverlay.hidden = true;
  if (clipKeysButton) clipKeysButton.focus();
  noteChromeActivity();
};

clipKeysOverlay.addEventListener("click", event => {
  if (event.target === clipKeysOverlay || event.target.closest("[data-clip-keys-close]")) {
    event.stopPropagation();
    clipKeysHide();
  }
});
// Escape here must not reach controls.js, which would leave the player.
clipKeysOverlay.addEventListener("keydown", event => {
  if (event.key === "Escape" || event.key === "?") {
    event.preventDefault();
    event.stopPropagation();
    clipKeysHide();
  }
});
if (clipKeysButton) {
  clipKeysButton.addEventListener("click", event => {
    event.stopPropagation();
    clipKeysShow();
  });
}

// --- hover preview on the scrub bar -------------------------------------
//
// The same frames the strip is made of, shown one at a time where the cursor
// is. It costs nothing extra: whatever the strip has already fetched for this
// title is on disk and published, so hovering is a file read. Which also means
// it only shows what exists -- before a strip has been built for a title there
// is nothing to show, and hovering will not start a download to fix that.

const clipHoverPreview = document.createElement("div");
clipHoverPreview.className = "clip-hover-preview";
clipHoverPreview.hidden = true;
clipHoverPreview.innerHTML = `<span class="clip-hover-shot"></span><span class="clip-hover-time"></span>`;
document.body.appendChild(clipHoverPreview);

const clipHoverShot = clipHoverPreview.querySelector(".clip-hover-shot");
const clipHoverTime = clipHoverPreview.querySelector(".clip-hover-time");

/**
 * Which frames of the open session exist, learned by whoever asks first.
 *
 * Shared between the grid and the hover preview: browsing the strip teaches the
 * scrub bar what it can show, and vice versa. Cleared when the session changes,
 * since the numbers mean nothing across a different strip.
 */
const clipFramePresent = new Set();
const clipFrameMissing = new Set();

/** The session the two sets above describe. */
let clipFrameSession = 0;

/**
 * The last complete strip description seen for the title on screen.
 *
 * All three numbers, not just the session: closing the Scenes view resets the
 * state, and a session that survives a zeroed spacing is no use because the
 * spacing is what turns a cursor position into a frame number. The frames are
 * files on disk and stay valid for as long as the film does, so the page keeps
 * the last good triple and clears it when the source changes.
 */
let clipHoverStrip = { session: 0, count: 0, spacingMs: 0 };

/**
 * Forgets what was known when the session changes.
 *
 * Called from the hover path as well as the grid, because a title can be opened
 * and hovered without the strip ever being shown -- and frame 12 of one film
 * says nothing about frame 12 of the next.
 */
const clipFrameKnowledgeFor = session => {
  if (session === clipFrameSession) return;
  clipFrameSession = session;
  clipFramePresent.clear();
  clipFrameMissing.clear();
  clipFrameMissingReady = -1;
};

/** The ready count [clipFrameMissing] was learned against. */
let clipFrameMissingReady = -1;

/**
 * Forgets the misses once Kotlin reports more frames written.
 *
 * A miss only means "not written yet" while the strip is building. Kept
 * forever, the frames the hover asked for early stayed refused after they
 * existed, so the preview showed a handful of stills until Scenes loaded every
 * cell and cleared them one by one. Clearing on each new ready count costs at
 * most one failed file load per hovered frame per step of the build.
 */
const clipFrameMissingRefresh = ready => {
  if (ready === clipFrameMissingReady) return;
  clipFrameMissingReady = ready;
  clipFrameMissing.clear();
};

/** Used only for the first hover, before the element has ever been laid out. */
const CLIP_HOVER_FALLBACK_WIDTH = 186;
const CLIP_HOVER_FALLBACK_HEIGHT = 125;

/**
 * How far either side of the hovered moment to accept a substitute frame.
 *
 * A half-built strip has gaps, and hiding the preview whenever the exact frame
 * is missing made it blink in and out as the cursor moved -- which read as "the
 * hover does not work". Two steps is close enough to still be the same scene.
 */
const CLIP_HOVER_NEIGHBOURS = 2;

/** The frame currently in the preview, so an unchanged one is not reloaded. */
let clipHoverIndex = -1;

const clipHoverHide = () => {
  clipHoverPreview.hidden = true;
  clipHoverIndex = -1;
};

/**
 * [index] itself unless it is known to be missing, then the nearest neighbour
 * known to exist.
 *
 * The exact frame first, even untried: the attempt is what fills in
 * [clipFramePresent]. Preferring a known neighbour over an untried exact frame
 * meant every loaded still captured the two either side of it, which were then
 * never asked for -- a 15-frame strip hovered as about five steps until Scenes
 * had loaded every cell.
 */
const clipHoverNearest = index => {
  if (!clipFrameMissing.has(index)) return index;
  for (let step = 1; step <= CLIP_HOVER_NEIGHBOURS; step += 1) {
    if (clipFramePresent.has(index - step)) return index - step;
    if (clipFramePresent.has(index + step)) return index + step;
  }
  return -1;
};

/** The last move over the bar, so a failed frame can fall back without another. */
let clipHoverLastEvent = null;

const clipHoverMove = event => {
  const live = {
    session: Number(state.clipStripSession) || 0,
    count: Number(state.clipStripCount) || 0,
    spacingMs: Number(state.clipStripSpacingMs) || 0,
  };
  if (live.session > 0 && live.count > 0 && live.spacingMs > 0) clipHoverStrip = live;
  const { session, count, spacingMs } = live.session > 0 && live.spacingMs > 0 ? live : clipHoverStrip;
  if (session <= 0 || spacingMs <= 0 || clipDraftDurationMs <= 0) return clipHoverHide();
  clipFrameKnowledgeFor(session);
  if (session === live.session) clipFrameMissingRefresh(Number(state.clipStripReady) || 0);

  clipHoverLastEvent = event;
  const ms = clipTrackMsFromEvent(event);
  // -1 because frame i sits at (i+1) spacings, as above.
  const target = Math.max(0, Math.min(count - 1, Math.round(ms / spacingMs) - 1));
  const index = clipHoverNearest(target);
  clipHoverTime.textContent = formatTime(ms);

  // Anchored to the cursor but kept inside the window: sideways so it does not
  // hang off the edge when you inspect the very start or end of a film, and
  // vertically because the controls are not always near the bottom -- above the
  // bar by default, below it when there is no room above.
  const rect = scrubWrap.getBoundingClientRect();
  const half = clipHoverPreview.offsetWidth / 2 || CLIP_HOVER_FALLBACK_WIDTH / 2;
  const height = clipHoverPreview.offsetHeight || CLIP_HOVER_FALLBACK_HEIGHT;
  const above = rect.top - height - 12;
  clipHoverPreview.style.left =
    `${Math.max(half + 8, Math.min(window.innerWidth - half - 8, event.clientX))}px`;
  clipHoverPreview.style.top = `${above >= 8 ? above : rect.bottom + 12}px`;

  if (index < 0) return clipHoverHide();
  if (index === clipHoverIndex) return;
  clipHoverIndex = index;
  const img = new Image();
  img.className = "clip-hover-img";
  img.addEventListener("load", () => {
    clipFramePresent.add(index);
    // Discarded if the cursor has already moved on: frames load out of order
    // and a late arrival must not replace a newer one.
    if (clipHoverIndex !== index) return;
    clipHoverShot.textContent = "";
    clipHoverShot.appendChild(img);
    clipHoverPreview.hidden = false;
  });
  img.addEventListener("error", () => {
    clipFrameMissing.add(index);
    // Also forgotten as present, or the retry below could pick it again forever.
    clipFramePresent.delete(index);
    if (clipHoverIndex !== index) return;
    // Still under the cursor: go again, which now settles on a neighbour.
    clipHoverIndex = -1;
    if (clipHoverLastEvent) clipHoverMove(clipHoverLastEvent);
  });
  img.src = `strip/${session}/${index}.jpg`;
};

// Bound to the document, and hit-tested by geometry rather than by which
// element the event names.
//
// Two reasons, both learned the hard way. Pointer events, not mouse events:
// everything else in this chrome is written against them because that is what
// the native player delivers, and `mousemove` never fires in the app at all --
// a browser synthesises it, which is why a harness cannot catch that. And
// document-level, because an event landing on the scrub bar can still be
// retargeted or swallowed by whatever sits over it; controls.js already tracks
// activity this way, which is proof the document sees the moves.
const CLIP_HOVER_MARGIN = 10;

document.addEventListener("pointermove", event => {
  const rect = scrubWrap.getBoundingClientRect();
  const inside =
    rect.width > 0 &&
    event.clientX >= rect.left - CLIP_HOVER_MARGIN &&
    event.clientX <= rect.right + CLIP_HOVER_MARGIN &&
    event.clientY >= rect.top - CLIP_HOVER_MARGIN &&
    event.clientY <= rect.bottom + CLIP_HOVER_MARGIN;
  if (inside) {
    clipHoverMove(event);
  } else {
    clipHoverHide();
  }
});
document.addEventListener("pointercancel", clipHoverHide);

// --- filmstrip: finding a scene without scrubbing for it ------------------
//
// A two-hour film scrubbed for one moment means seek, watch, seek again. The
// strip answers the same question by looking: one still every few seconds
// across the whole runtime, scrolled and clicked.
//
// Frames arrive as files written by Kotlin into a folder beside this page,
// coarse-first rather than left to right, so the strip is complete-but-sparse
// almost immediately and sharpens while you read it. Nothing about them crosses
// the bridge except four numbers -- which session folder, how many frames, how
// many exist, how far apart -- and every URL and timestamp is derived from
// those. That is why the session is a number: the bridge carries no strings.

const CLIP_STRIP_RETRY_MS = 900;
/** How far outside the viewport to keep frames loaded, in pixels. */
const CLIP_STRIP_OVERSCAN = 500;

let clipStripVisible = false;
let clipStripCells = [];
let clipStripSession = 0;
let clipStripRetryTimer = 0;
/** Last index reported to Kotlin, so scrolling does not spam the bridge. */
let clipStripFocusSent = -1;

const clipStripOverlay = document.createElement("div");
clipStripOverlay.className = "clip-strip";
clipStripOverlay.hidden = true;
clipStripOverlay.innerHTML = `
  <div class="clip-strip-bar">
    <span class="clip-strip-title">Find a scene</span>
    <span class="clip-strip-count"></span>
    <div class="clip-row-spacer"></div>
    <button class="clip-action" type="button" data-clip-strip-close>Close</button>
  </div>
  <div class="clip-strip-grid"></div>
`;
document.body.appendChild(clipStripOverlay);

const clipStripGrid = clipStripOverlay.querySelector(".clip-strip-grid");
const clipStripCountLabel = clipStripOverlay.querySelector(".clip-strip-count");

/** Lays out one cell per frame. Pictures arrive later; the grid does not wait. */
const clipStripBuild = (session, count, spacingMs) => {
  clipStripSession = session;
  clipStripFocusSent = -1;
  clipFrameKnowledgeFor(session);
  clipStripGrid.textContent = "";
  clipStripCells = [];
  for (let index = 0; index < count; index += 1) {
    // One spacing in, matching where Kotlin actually seeks: a film's first
    // frame is usually black and tells you nothing.
    const ms = (index + 1) * spacingMs;
    const cell = document.createElement("button");
    cell.type = "button";
    cell.className = "clip-strip-cell";
    const shot = document.createElement("span");
    shot.className = "clip-strip-shot";
    const label = document.createElement("span");
    label.className = "clip-strip-time";
    label.textContent = formatTime(ms);
    cell.append(shot, label);
    // One tab stop for the whole grid; arrows move within it. A thousand
    // stills as a thousand tab stops made the grid unreachable by keyboard.
    cell.tabIndex = index === 0 ? 0 : -1;
    cell.setAttribute("aria-label", `Still at ${formatTime(ms)}`);
    cell.addEventListener("click", () => {
      send("clipStripSeek", ms);
      clipStripHide();
    });
    clipStripGrid.appendChild(cell);
    clipStripCells.push({ index, cell, shot, loaded: false, pending: false });
  }
};

/**
 * Tries to show one frame.
 *
 * A fresh <img> every attempt on purpose: the file may simply not have been
 * written yet, and a `file:` URL that failed once stays failed on the element
 * that asked for it.
 */
const clipStripAttempt = entry => {
  if (entry.loaded || entry.pending) return;
  entry.pending = true;
  const img = new Image();
  img.className = "clip-strip-img";
  img.decoding = "async";
  img.addEventListener("load", () => {
    entry.pending = false;
    entry.loaded = true;
    clipFramePresent.add(entry.index);
    clipFrameMissing.delete(entry.index);
    entry.shot.textContent = "";
    entry.shot.appendChild(img);
  });
  img.addEventListener("error", () => {
    entry.pending = false;
    clipFrameMissing.add(entry.index);
  });
  img.src = `strip/${clipStripSession}/${entry.index}.jpg`;
};

/**
 * Loads what is on screen, and a little either side of it -- and tells Kotlin
 * where to fetch next.
 *
 * Without that second part the builder works through the film in its own order
 * while you sit staring at an empty patch of grid. The frames cost the same
 * either way; which ones arrive first is the difference.
 */
const clipStripRefresh = () => {
  if (!clipStripVisible) return;
  const top = clipStripGrid.scrollTop - CLIP_STRIP_OVERSCAN;
  const bottom = clipStripGrid.scrollTop + clipStripGrid.clientHeight + CLIP_STRIP_OVERSCAN;
  let firstVisible = -1;
  let lastVisible = -1;
  clipStripCells.forEach(entry => {
    const cellTop = entry.cell.offsetTop;
    if (cellTop + entry.cell.offsetHeight < top || cellTop > bottom) return;
    if (firstVisible < 0) firstVisible = entry.index;
    lastVisible = entry.index;
    if (!entry.loaded) clipStripAttempt(entry);
  });
  if (firstVisible < 0) return;
  const middle = Math.round((firstVisible + lastVisible) / 2);
  if (middle !== clipStripFocusSent) {
    clipStripFocusSent = middle;
    send("clipStripFocus", middle);
  }
};

/** Keeps retrying while frames are still being written, then stops. */
const clipStripTick = () => {
  clipStripRetryTimer = 0;
  clipStripRefresh();
  if (!clipStripVisible) return;
  if (clipStripCells.every(entry => entry.loaded)) return;
  clipStripRetryTimer = setTimeout(clipStripTick, CLIP_STRIP_RETRY_MS);
};

clipStripGrid.addEventListener("scroll", clipStripRefresh, { passive: true });

/** Columns in the grid as laid out now -- it reflows with the window. */
const clipStripColumns = () => {
  const cells = clipStripCells;
  if (cells.length < 2) return 1;
  const top = cells[0].cell.offsetTop;
  const next = cells.findIndex(entry => entry.cell.offsetTop !== top);
  return next > 0 ? next : cells.length;
};

clipStripGrid.addEventListener("keydown", event => {
  const current = clipStripCells.findIndex(entry => entry.cell === document.activeElement);
  if (current < 0) return;
  const columns = clipStripColumns();
  const moves = { ArrowLeft: -1, ArrowRight: 1, ArrowUp: -columns, ArrowDown: columns, Home: -current,
    End: clipStripCells.length - 1 - current };
  if (!(event.code in moves)) return;
  event.preventDefault();
  event.stopPropagation();
  const target = Math.max(0, Math.min(clipStripCells.length - 1, current + moves[event.code]));
  clipStripCells[current].cell.tabIndex = -1;
  const next = clipStripCells[target].cell;
  next.tabIndex = 0;
  next.focus();
});

const clipStripShow = () => {
  if (clipStripVisible || clipDraftDurationMs <= 0) return;
  clipStripVisible = true;
  clipStripOverlay.hidden = false;
  send("clipStripOpen", 0);
  // Kotlin answers with the session numbers on its next state push, but a
  // reopened strip already has them -- so render now rather than showing an
  // empty grid until the next playback tick happens to arrive.
  renderClipUi();
  noteChromeActivity();
};

const clipStripHide = () => {
  if (!clipStripVisible) return;
  clipStripVisible = false;
  clipStripOverlay.hidden = true;
  if (clipStripRetryTimer) {
    clearTimeout(clipStripRetryTimer);
    clipStripRetryTimer = 0;
  }
  // Deliberately tells Kotlin nothing. Closing the view is not closing the
  // strip: its frames are what the scrub-bar preview reads, and tearing them
  // down here is what made the hover stop working the moment Scenes was opened
  // and shut again. The strip belongs to the title, and goes when it does.
  noteChromeActivity();
};

/** Called from renderClipUi, so the strip follows the same state as everything else. */
const clipStripRender = () => {
  if (!clipStripVisible) return;
  const session = Number(state.clipStripSession) || 0;
  const count = Number(state.clipStripCount) || 0;
  const ready = Number(state.clipStripReady) || 0;
  const spacingMs = Number(state.clipStripSpacingMs) || 0;
  if (session > 0 && count > 0 && spacingMs > 0 &&
      (session !== clipStripSession || count !== clipStripCells.length)) {
    clipStripBuild(session, count, spacingMs);
  }
  clipStripCountLabel.textContent = count > 0 && ready >= count
    ? `${count.toLocaleString()} stills`
    : `Building stills \u2013 ${ready.toLocaleString()} of ${count.toLocaleString()}`;
  if (!clipStripRetryTimer) clipStripTick();
};

clipStripOverlay.querySelector("[data-clip-strip-close]").addEventListener("click", clipStripHide);
if (clipStripButton) clipStripButton.addEventListener("click", clipStripShow);

/**
 * Keeps the trim draft in step with playback. Called from controls.js on every
 * native playback update.
 */
const clipSyncPlayback = (durationMs, positionMs) => {
  // Reset the trim draft when a source's duration first becomes known or the
  // source changes; small duration jitter is ignored.
  if (durationMs > 0 && Math.abs(durationMs - clipDraftDurationMs) > 1500) {
    clipDraftDurationMs = durationMs;
    // Nothing is marked until you mark it. Defaulting to the whole runtime armed
    // Export with a two-hour "selection" nobody had chosen, and parked both
    // handles at the far ends of the bar -- as far as reachable from wherever
    // you actually were.
    clipDraft = { inMs: null, outMs: null };
    clipRanges = [];
    clipClearRemoved();
    clipEscDisarm();
    clipPreviewActive = false;
    clipZoomView = null;
    // A new source means a different title, so a stale open panel would be
    // showing the previous one's clips until the next full render.
    clipLibraryOpen = false;
    clipJobsOpen = false;
    // A different film: the previous title's frame numbers mean nothing now.
    clipHoverStrip = { session: 0, count: 0, spacingMs: 0 };
  }
  clipZoomUpdatePlayhead(positionMs);
  if (
    clipPreviewActive &&
    clipDraft.inMs != null &&
    clipDraft.outMs != null &&
    positionMs >= clipDraft.outMs &&
    Date.now() - clipPreviewLoopSeekAt > 500
  ) {
    // Loop the preview back to the In point.
    clipPreviewLoopSeekAt = Date.now();
    clipSeekTo(clipDraft.inMs);
  }
};

// controls.js reaches the clipper only through this namespace, so its call
// sites stay safe regardless of script load order.
window.clipUi = {
  render: renderClipUi,
  syncPlayback: clipSyncPlayback,
  shouldPinChrome: clipShouldPinChrome,
  handleKey: clipHandleKey,
  setRange: clipSetRange,
};

// controls.js runs its initial render() before this file loads, so the clip row
// needs one render of its own to show up on first paint.
renderClipUi();
