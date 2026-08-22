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

// --- trim draft state ---
let clipDraft = { inMs: null, outMs: null };
let clipDraftDurationMs = 0;
let clipDragTarget = null;
let clipPreviewActive = false;
let clipPreviewLoopSeekAt = 0;

const clipHasRange = () =>
  clipDraft.inMs != null && clipDraft.outMs != null && clipDraft.outMs > clipDraft.inMs;

/** Clip times show tenths of a second — precision the plain time labels don't need. */
const formatClipTime = ms => {
  const safe = Math.max(0, Number(ms) || 0);
  return `${formatTime(safe)}.${Math.floor((safe % 1000) / 100)}`;
};

const renderClipUi = () => {
  const show = Boolean(state.showClip);
  const ready = show && clipDraftDurationMs > 0 && clipHasRange();
  setVisible(clipRow, show);
  clipRange.hidden = !ready;
  clipHandleIn.hidden = !ready;
  clipHandleOut.hidden = !ready;
  if (!show) return;
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
  clipExportButton.disabled = running || !clipHasRange();
  clipExportButton.textContent = running ? "Exporting..." : "Export clip";
  clipPreviewButton.disabled = running || !clipHasRange();
  clipPreviewButton.textContent = clipPreviewActive ? "Stop preview" : "Preview";
  clipCancelButton.hidden = !running;
  clipRevealButton.hidden = state.clipStatusKind !== "done";
  clipDismissButton.hidden = running || !state.clipStatusKind;
  clipNudgeRows.forEach(row => {
    const target = row.dataset.clipTarget;
    const value = target === "in" ? clipDraft.inMs : clipDraft.outMs;
    row.querySelectorAll("button").forEach(button => {
      button.disabled = running || value == null;
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

let clipDragLastSeekAt = 0;

[clipHandleIn, clipHandleOut].forEach(handle => {
  const target = handle.dataset.clipHandle;
  handle.addEventListener("pointerdown", event => {
    if (state.clipRunning || clipDraftDurationMs <= 0) return;
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
    const ms = clipApplyPoint(target, clipTrackMsFromEvent(event));
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
clipCancelButton.addEventListener("click", event => {
  event.stopPropagation();
  if (state.clipRunning) send("clipCancel", 0);
});
clipRevealButton.addEventListener("click", event => {
  event.stopPropagation();
  send("clipReveal", 0);
});
clipDismissButton.addEventListener("click", event => {
  event.stopPropagation();
  send("clipDismiss", 0);
});

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
  }
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
window.clipUi = { render: renderClipUi, syncPlayback: clipSyncPlayback };

// controls.js runs its initial render() before this file loads, so the clip row
// needs one render of its own to show up on first paint.
renderClipUi();
