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
const clipInReadout = document.getElementById("clipInReadout");
const clipOutReadout = document.getElementById("clipOutReadout");
const clipLenReadout = document.getElementById("clipLenReadout");
const clipProgressTrack = document.getElementById("clipProgressTrack");
const clipProgressBar = document.getElementById("clipProgressBar");
const clipStatus = document.getElementById("clipStatus");
const clipCancelButton = document.getElementById("clipCancelButton");
const clipRevealButton = document.getElementById("clipRevealButton");
const clipDismissButton = document.getElementById("clipDismissButton");
const clipPreviewButton = document.getElementById("clipPreviewButton");
const clipExportButton = document.getElementById("clipExportButton");
const clipNudgeRows = Array.from(document.querySelectorAll(".clip-nudge-row"));
const clipZoomWrap = document.getElementById("clipZoomWrap");
const clipZoomTrack = document.getElementById("clipZoomTrack");
const clipZoomRange = document.getElementById("clipZoomRange");
const clipZoomPlayhead = document.getElementById("clipZoomPlayhead");
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
const clipShouldPinChrome = () => Boolean(state.showClip) && clipHasRange();

// --- trim draft state ---
let clipDraft = { inMs: null, outMs: null };
let clipDraftDurationMs = 0;
let clipDragTarget = null;
let clipPreviewActive = false;
let clipPreviewLoopSeekAt = 0;
let clipLibraryOpen = false;
let clipJobsOpen = false;
// Window of the timeline the zoom track spans, or null when zoom is off.
let clipZoomView = null;

const clipHasRange = () =>
  clipDraft.inMs != null && clipDraft.outMs != null && clipDraft.outMs > clipDraft.inMs;

/** Clip times show tenths of a second -- precision the plain time labels don't need. */
const formatClipTime = ms => {
  const safe = Math.max(0, Number(ms) || 0);
  return `${formatTime(safe)}.${Math.floor((safe % 1000) / 100)}`;
};

// --- zoomed trim window ---------------------------------------------------
//
// On a 2h movie the main scrub bar packs ~7 seconds into every pixel, so a
// 20-second selection is a sliver and the mouse cannot place a cut within a
// second. Once the handles are that close together, a second track appears
// beneath the scrub bar spanning only a narrow window around the selection,
// which buys back two orders of magnitude of pointer precision.

/** Zoom in once the selection covers less than this share of the runtime. */
const CLIP_ZOOM_TRIGGER_RATIO = 0.1;
/** Never show a window narrower than this, or the handles have nowhere to go. */
const CLIP_ZOOM_MIN_WINDOW_MS = 4000;
/** Selection-to-window ratio: the selection fills a third of the zoom track. */
const CLIP_ZOOM_CONTEXT_FACTOR = 3;

const clipZoomShouldShow = () =>
  clipHasRange() &&
  clipDraftDurationMs > 0 &&
  clipDraft.outMs - clipDraft.inMs <= clipDraftDurationMs * CLIP_ZOOM_TRIGGER_RATIO;

/** A window centred on the selection, clamped to the runtime. */
const clipZoomComputeView = () => {
  const selection = clipDraft.outMs - clipDraft.inMs;
  const width = Math.min(
    clipDraftDurationMs,
    Math.max(selection * CLIP_ZOOM_CONTEXT_FACTOR, CLIP_ZOOM_MIN_WINDOW_MS),
  );
  const centre = (clipDraft.inMs + clipDraft.outMs) / 2;
  const start = Math.max(0, Math.min(clipDraftDurationMs - width, centre - width / 2));
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

const clipZoomUpdatePlayhead = positionMs => {
  if (!clipZoomView || clipZoomWrap.hidden) return;
  clipZoomPlayhead.style.left = `${clipZoomPercentFor(positionMs)}%`;
};

const renderClipZoom = () => {
  const show = clipZoomShouldShow();
  clipZoomWrap.hidden = !show;
  if (!show) {
    clipZoomView = null;
    return;
  }
  // Recentre only at rest; while a handle is down the window may only pan, so
  // the pixel under the pointer keeps meaning the same instant.
  if (!clipZoomView || clipDragTarget == null) clipZoomView = clipZoomComputeView();

  const inPct = clipZoomPercentFor(clipDraft.inMs);
  const outPct = clipZoomPercentFor(clipDraft.outMs);
  clipZoomRange.style.left = `${inPct}%`;
  clipZoomRange.style.width = `${Math.max(0, outPct - inPct)}%`;
  clipZoomHandleIn.style.left = `${inPct}%`;
  clipZoomHandleOut.style.left = `${outPct}%`;
  clipZoomUpdatePlayhead(Number(state.positionMs) || 0);
  clipZoomStartLabel.textContent = formatClipTime(clipZoomView.startMs);
  clipZoomEndLabel.textContent = formatClipTime(clipZoomView.endMs);
  const windowSec = (clipZoomView.endMs - clipZoomView.startMs) / 1000;
  clipZoomScale.textContent = `${windowSec < 10 ? windowSec.toFixed(1) : Math.round(windowSec)}s view`;
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
      bar.style.width = `${Math.round((Number(item.progress) || 0) * 100)}%`;
      track.appendChild(bar);
      row.appendChild(track);
    }

    const actions = item.isActive
      ? [["Cancel", "clipCancel", ""]]
      : [
          ...(item.canReveal ? [["Show file", "clipReveal", ""]] : []),
          ["\u2715", "clipDismiss", ""],
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

  clipLibraryPath.textContent = state.clipOutputDir || "";
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
    meta.textContent = `${item.rangeLabel || ""} - ${item.durationLabel || ""}`;
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

const renderClipUi = () => {
  document.body.classList.toggle("clipper-mode", isClipperMode());
  const show = Boolean(state.showClip);
  const ready = show && clipDraftDurationMs > 0 && clipHasRange();
  setVisible(clipRow, show);
  clipRange.hidden = !ready;
  clipHandleIn.hidden = !ready;
  clipHandleOut.hidden = !ready;
  if (!show) {
    clipZoomWrap.hidden = true;
    clipJobsPanel.hidden = true;
    clipJobsButton.hidden = true;
    clipLibraryPanel.hidden = true;
    clipLibraryButton.hidden = true;
    return;
  }
  const running = Boolean(state.clipRunning);
  if (ready) {
    const inPct = Math.max(0, Math.min(100, clipDraft.inMs / clipDraftDurationMs * 100));
    const outPct = Math.max(0, Math.min(100, clipDraft.outMs / clipDraftDurationMs * 100));
    clipRange.style.left = `${inPct}%`;
    clipRange.style.width = `${Math.max(0, outPct - inPct)}%`;
    clipHandleIn.style.left = `${inPct}%`;
    clipHandleOut.style.left = `${outPct}%`;
  }
  clipInReadout.textContent = clipDraft.inMs == null ? "--:--" : formatClipTime(clipDraft.inMs);
  clipOutReadout.textContent = clipDraft.outMs == null ? "--:--" : formatClipTime(clipDraft.outMs);
  clipLenReadout.textContent = clipHasRange()
    ? `(${formatClipTime(clipDraft.outMs - clipDraft.inMs)})`
    : "";
  // Exports run in the background, so nothing below is gated on one: the trim
  // controls stay live while clips encode, and so does the export button.
  clipExportButton.disabled = !clipHasRange();
  clipPreviewButton.disabled = !clipHasRange();
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
  clipNudgeRows.forEach(row => {
    const target = row.dataset.clipTarget;
    const value = target === "in" ? clipDraft.inMs : clipDraft.outMs;
    row.querySelectorAll("button").forEach(button => {
      button.disabled = value == null;
    });
  });
  if (running) {
    clipProgressTrack.hidden = false;
    clipProgressBar.style.width = `${Math.round((Number(state.clipProgress) || 0) * 100)}%`;
  } else {
    clipProgressTrack.hidden = true;
    clipProgressBar.style.width = "0%";
  }
  clipStatus.textContent = state.clipStatusMessage || "";
  clipStatus.dataset.kind = state.clipStatusKind || "";
  renderClipZoom();
  renderClipJobs();
  renderClipLibrary();
};

const clipSeekTo = ms => {
  const clamped = Math.max(0, Math.min(Number(state.durationMs) || ms, ms));
  state.positionMs = clamped;
  send("scrubFinish", clamped);
  return clamped;
};

const CLIP_MIN_GAP_MS = 200;

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

clipNudgeRows.forEach(row => {
  const target = row.dataset.clipTarget;
  row.querySelectorAll("button").forEach(button => {
    button.addEventListener("click", event => {
      event.stopPropagation();
      const delta = Number(button.dataset.clipNudge) || 0;
      const current = target === "in" ? clipDraft.inMs : clipDraft.outMs;
      if (current == null) return;
      clipPreviewActive = false;
      // Seek the player to the adjusted point so the exact frame is on screen.
      clipSeekTo(clipApplyPoint(target, current + delta));
      renderClipUi();
    });
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

clipExportButton.addEventListener("click", event => {
  event.stopPropagation();
  if (!clipHasRange()) return;
  clipPreviewActive = false;
  send("clipStart", clipDraft.inMs / 1000);
  send("clipEnd", clipDraft.outMs / 1000);
  send("clipExport", 0);
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

/**
 * Keeps the trim draft in step with playback. Called from controls.js on every
 * native playback update.
 */
const clipSyncPlayback = (durationMs, positionMs) => {
  // (Re)initialize the trim handles to the full range when a source's duration
  // first becomes known or the source changes; small duration jitter is ignored.
  if (durationMs > 0 && Math.abs(durationMs - clipDraftDurationMs) > 1500) {
    clipDraftDurationMs = durationMs;
    clipDraft = { inMs: 0, outMs: durationMs };
    clipPreviewActive = false;
    clipZoomView = null;
    // A new source means a different title, so a stale open panel would be
    // showing the previous one's clips until the next full render.
    clipLibraryOpen = false;
    clipJobsOpen = false;
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
};

// controls.js runs its initial render() before this file loads, so the clip row
// needs one render of its own to show up on first paint.
renderClipUi();
