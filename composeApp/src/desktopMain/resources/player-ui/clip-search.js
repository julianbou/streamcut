// Subtitle phrase search for the desktop player.
//
// Type a line the way you remember it, jump to the moment, or turn it straight
// into a clip range.
//
// Loaded after clip-controls.js, so `state`, `send`, `setVisible` and
// `formatTime` from controls.js are already bound, and window.clipUi already
// exists to wrap. Kept in its own file for the same reason the clip UI is:
// upstream Nuvio merges never touch it.
//
// The matching runs HERE rather than in Kotlin because the control bridge
// carries only {type, value:Double} -- the typed query has no way across it.
// Kotlin downloads and parses the subtitle, and ships the cues; everything from
// normalization onward happens on this side. The algorithm below was settled in
// scripts/subtitle-search-prototype, which has the match-quality checks.

// --- element handles ---
const clipSearchButton = document.getElementById("clipSearchButton");
const clipSearchPanel = document.getElementById("clipSearchPanel");
const clipSearchInput = document.getElementById("clipSearchInput");
const clipSearchSource = document.getElementById("clipSearchSource");
const clipSearchStatus = document.getElementById("clipSearchStatus");
const clipSearchList = document.getElementById("clipSearchList");

// --- tuning (see the prototype README for why each number is what it is) ---

/** Blended score a fuzzy window must beat. */
const CLIP_SEARCH_FUZZY_THRESHOLD = 0.6;
/** Share of the query's weight a hit must carry, checked separately. */
const CLIP_SEARCH_MIN_RECALL = 0.65;
/** A window may run this many tokens past the query: spoken lines are wordier. */
const CLIP_SEARCH_WINDOW_SLACK = 3;
/** Further apart than this and a window spans a scene change, not a phrase. */
const CLIP_SEARCH_MAX_CUE_SPAN = 2;
/** Breathing room either side of a matched line when it becomes a clip. */
const CLIP_SEARCH_PAD_MS = 250;
const CLIP_SEARCH_RESULT_LIMIT = 40;

// --- normalization ---

const CLIP_SEARCH_APOSTROPHES = /[’ʼ'`´]/g;
const CLIP_SEARCH_COMBINING = /[̀-ͯ]/g;
const CLIP_SEARCH_NON_WORD = /[^\p{L}\p{N}]+/gu;

/**
 * Apostrophes are dropped rather than kept, so "can't" and "cant" collapse to one
 * token and it stops mattering which the user typed. Letters of any script
 * survive; only Latin accents fold.
 */
const clipSearchNormalize = text =>
  String(text || "")
    .normalize("NFD")
    .replace(CLIP_SEARCH_COMBINING, "")
    .toLowerCase()
    .replace(CLIP_SEARCH_APOSTROPHES, "")
    .replace(CLIP_SEARCH_NON_WORD, " ")
    .replace(/\s+/g, " ")
    .trim();

// --- index ---

let clipSearchIndex = null;
let clipSearchIndexSignature = "";
let clipSearchResults = [];
let clipSearchQuery = "";

/**
 * One continuous normalized string with an offset -> cue map, so a phrase broken
 * across a cue boundary is found like any other. Subtitlers split long sentences,
 * and long sentences are what people search for.
 */
const clipSearchBuildIndex = cues => {
  const spans = [];
  const tokens = [];
  const frequency = new Map();
  let haystack = "";

  cues.forEach((cue, cueIndex) => {
    const normalized = clipSearchNormalize(cue.text);
    if (!normalized) {
      spans.push({ cueIndex, start: haystack.length });
      return;
    }
    if (haystack.length > 0) haystack += " ";
    spans.push({ cueIndex, start: haystack.length });
    haystack += normalized;

    const seen = new Set();
    for (const token of normalized.split(" ")) {
      if (!token) continue;
      tokens.push({ text: token, cueIndex });
      seen.add(token);
    }
    for (const token of seen) frequency.set(token, (frequency.get(token) || 0) + 1);
  });

  return { cues, haystack, spans, tokens, frequency, documentCount: Math.max(1, cues.length) };
};

/**
 * Inverse document frequency, so "the" cannot carry a match on its own -- scoring
 * by raw token count let a query of ordinary words match any busy line.
 */
const clipSearchWeight = (index, token) =>
  Math.log(1 + index.documentCount / (1 + (index.frequency.get(token) || 0)));

const clipSearchCueAtOffset = (index, offset) => {
  const spans = index.spans;
  let low = 0;
  let high = spans.length - 1;
  let best = 0;
  while (low <= high) {
    const mid = (low + high) >> 1;
    if (spans[mid].start <= offset) {
      best = mid;
      low = mid + 1;
    } else {
      high = mid - 1;
    }
  }
  return spans[best].cueIndex;
};

// --- token similarity ---

const clipSearchLevenshteinWithin = (a, b, max) => {
  if (Math.abs(a.length - b.length) > max) return max + 1;
  let previous = new Array(b.length + 1);
  let current = new Array(b.length + 1);
  for (let j = 0; j <= b.length; j += 1) previous[j] = j;

  for (let i = 1; i <= a.length; i += 1) {
    current[0] = i;
    let rowMin = current[0];
    for (let j = 1; j <= b.length; j += 1) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      current[j] = Math.min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost);
      if (current[j] < rowMin) rowMin = current[j];
    }
    if (rowMin > max) return max + 1;
    const swap = previous;
    previous = current;
    current = swap;
  }
  return previous[b.length];
};

/** 0 when the tokens are unrelated, otherwise how much the match is worth. */
const clipSearchTokenQuality = (a, b) => {
  if (a === b) return 1;
  const shorter = Math.min(a.length, b.length);
  if (shorter >= 5 && (a.startsWith(b) || b.startsWith(a))) return 0.85;
  // Six, not four: "rain" and "train" are one edit apart and unrelated.
  if (shorter >= 6 && clipSearchLevenshteinWithin(a, b, 1) <= 1) return 0.85;
  return 0;
};

const clipSearchScoreWindow = (index, window, query) => {
  const used = new Array(window.length).fill(false);
  let matchedWeight = 0;
  let anyMatch = false;

  for (const queryToken of query) {
    let bestIndex = -1;
    let bestQuality = 0;
    for (let i = 0; i < window.length; i += 1) {
      if (used[i]) continue;
      const quality = clipSearchTokenQuality(queryToken, window[i]);
      if (quality > bestQuality) {
        bestQuality = quality;
        bestIndex = i;
      }
    }
    if (bestIndex < 0) continue;
    used[bestIndex] = true;
    anyMatch = true;
    matchedWeight += bestQuality * clipSearchWeight(index, queryToken);
  }
  if (!anyMatch) return null;

  let queryWeight = 0;
  for (const token of query) queryWeight += clipSearchWeight(index, token);
  let windowWeight = 0;
  for (const token of window) windowWeight += clipSearchWeight(index, token);
  if (queryWeight <= 0 || windowWeight <= 0) return null;

  const recall = matchedWeight / queryWeight;
  return { score: 0.8 * recall + 0.2 * (matchedWeight / windowWeight), recall };
};

// --- search ---

const clipSearchRun = (index, rawQuery) => {
  if (!index || !index.cues.length) return [];
  const query = clipSearchNormalize(rawQuery);
  if (!query) return [];
  const queryTokens = query.split(" ").filter(Boolean);
  if (!queryTokens.length) return [];

  const hits = new Map();
  const record = (firstCue, lastCue, score, isExact) => {
    const key = firstCue + ":" + lastCue;
    const existing = hits.get(key);
    if (existing && existing.score >= score) return;
    hits.set(key, { firstCue, lastCue, score, isExact });
  };

  // Pass A -- exact substring over the whole haystack, which is what finds a
  // phrase that straddles a cue break.
  let at = index.haystack.indexOf(query);
  while (at >= 0) {
    record(
      clipSearchCueAtOffset(index, at),
      clipSearchCueAtOffset(index, at + query.length - 1),
      1,
      true,
    );
    at = index.haystack.indexOf(query, at + 1);
  }

  // Pass B -- fuzzy token windows, for a line remembered imperfectly.
  const tokens = index.tokens;
  if (tokens.length) {
    // A window with no exact query token cannot clear the recall gate; skipping
    // those is what keeps a full-length film cheap per keystroke.
    const querySet = new Set(queryTokens);
    const prefix = new Int32Array(tokens.length + 1);
    for (let i = 0; i < tokens.length; i += 1) {
      prefix[i + 1] = prefix[i] + (querySet.has(tokens[i].text) ? 1 : 0);
    }

    const smallest = Math.max(1, queryTokens.length - 1);
    for (let size = smallest; size <= queryTokens.length + CLIP_SEARCH_WINDOW_SLACK; size += 1) {
      if (size > tokens.length) break;
      for (let start = 0; start + size <= tokens.length; start += 1) {
        if (prefix[start + size] - prefix[start] === 0) continue;
        const firstCue = tokens[start].cueIndex;
        const lastCue = tokens[start + size - 1].cueIndex;
        if (lastCue - firstCue > CLIP_SEARCH_MAX_CUE_SPAN) continue;

        const window = [];
        for (let i = start; i < start + size; i += 1) window.push(tokens[i].text);
        const scored = clipSearchScoreWindow(index, window, queryTokens);
        if (!scored) continue;
        if (scored.recall < CLIP_SEARCH_MIN_RECALL) continue;
        if (scored.score < CLIP_SEARCH_FUZZY_THRESHOLD) continue;
        record(firstCue, lastCue, scored.score, false);
      }
    }
  }

  const ordered = Array.from(hits.values()).sort(
    (a, b) => b.score - a.score || index.cues[a.firstCue].timeMs - index.cues[b.firstCue].timeMs,
  );

  // The sliding window reports the same moment once per offset it fits at, and a
  // fuzzy window often overlaps a range an exact hit already claimed. Best-first
  // order means keeping any range that touches none of the kept ones leaves
  // exactly one entry per moment.
  const kept = [];
  for (const hit of ordered) {
    if (kept.length >= CLIP_SEARCH_RESULT_LIMIT) break;
    if (kept.some(k => hit.firstCue <= k.lastCue && k.firstCue <= hit.lastCue)) continue;
    const cues = index.cues.slice(hit.firstCue, hit.lastCue + 1);
    kept.push({
      firstCue: hit.firstCue,
      lastCue: hit.lastCue,
      score: hit.score,
      isExact: hit.isExact,
      startMs: Number(cues[0].timeMs) || 0,
      endMs: Number(cues[cues.length - 1].endMs) || 0,
      text: cues.map(cue => cue.text).join(" "),
    });
  }
  return kept;
};

// --- actions ---

/**
 * Offset between the cue's own time and the picture. Kotlin sends 0 unless the
 * searched file is the one on screen: a delay only describes the file it was set on.
 */
const clipSearchDelayMs = () => Number(state.subtitleSearchDelayMs) || 0;

const clipSearchGoTo = result => {
  // scrubFinish is in MILLISECONDS -- clipStart/clipEnd below are in seconds.
  send("scrubFinish", Math.max(0, result.startMs + clipSearchDelayMs()));
};

const clipSearchClipFrom = result => {
  const delay = clipSearchDelayMs();
  const inMs = Math.max(0, result.startMs + delay - CLIP_SEARCH_PAD_MS);
  const outMs = Math.max(inMs + 100, result.endMs + delay + CLIP_SEARCH_PAD_MS);
  // These two are in SECONDS; the Kotlin side multiplies back up.
  send("clipStart", inMs / 1000);
  send("clipEnd", outMs / 1000);
  // Land on the in-point so the range that was just set is the thing on screen.
  send("scrubFinish", inMs);
};

// --- rendering ---

const clipSearchSources = () =>
  Array.isArray(state.addonSubtitleItems) ? state.addonSubtitleItems : [];

const clipSearchStatusText = () => {
  if (!clipSearchSources().length) {
    return state.isLoadingAddonSubtitles ? "Loading subtitles..." : "No addon subtitles for this title";
  }
  if (state.subtitleSearchErrorMessage) return state.subtitleSearchErrorMessage;
  // Nothing loaded and nothing wrong means a download is on its way -- including the
  // moment between picking a source and Kotlin starting to fetch it.
  if (state.subtitleSearchIsLoading || !clipSearchIndex || !clipSearchIndex.cues.length) {
    return state.loadingSubtitleLinesLabel || "Loading subtitle lines...";
  }
  if (!clipSearchQuery.trim()) {
    return `${clipSearchIndex.cues.length} lines — type a phrase`;
  }
  if (!clipSearchResults.length) return "No match";
  return `${clipSearchResults.length} result${clipSearchResults.length === 1 ? "" : "s"}`;
};

const clipSearchRenderResults = () => {
  clipSearchList.textContent = "";
  const delay = clipSearchDelayMs();

  for (const result of clipSearchResults) {
    const row = document.createElement("div");
    row.className = "clip-search-row";

    const meta = document.createElement("div");
    meta.className = "clip-search-meta";

    const time = document.createElement("span");
    time.className = "clip-search-time";
    time.textContent = formatTime(Math.max(0, result.startMs + delay));
    meta.appendChild(time);

    const tag = document.createElement("span");
    tag.className = "clip-search-tag" + (result.isExact ? " exact" : "");
    tag.textContent = result.isExact ? "exact" : result.score.toFixed(2);
    meta.appendChild(tag);

    const spacer = document.createElement("span");
    spacer.className = "clip-search-spacer";
    meta.appendChild(spacer);

    const goButton = document.createElement("button");
    goButton.className = "clip-search-action";
    goButton.type = "button";
    goButton.textContent = "Go";
    goButton.addEventListener("click", () => clipSearchGoTo(result));
    meta.appendChild(goButton);

    const clipButton = document.createElement("button");
    clipButton.className = "clip-search-action primary";
    clipButton.type = "button";
    clipButton.textContent = "Clip";
    clipButton.title = "Set IN and OUT around this line";
    clipButton.addEventListener("click", () => clipSearchClipFrom(result));
    meta.appendChild(clipButton);

    row.appendChild(meta);

    const text = document.createElement("div");
    text.className = "clip-search-text";
    text.textContent = result.text;
    row.appendChild(text);

    // Double-clicking the line is the same as Go -- the obvious gesture.
    row.addEventListener("dblclick", () => clipSearchGoTo(result));
    clipSearchList.appendChild(row);
  }
};

const clipSearchRecompute = () => {
  clipSearchResults = clipSearchQuery.trim() ? clipSearchRun(clipSearchIndex, clipSearchQuery) : [];
  clipSearchRenderResults();
  clipSearchStatus.textContent = clipSearchStatusText();
};

/**
 * State arrives as a fresh object on every push, so the cue array is a new
 * reference each time even when nothing changed. Rebuild the index only when the
 * cues themselves differ, or every control-state update would re-index the film.
 */
const clipSearchSyncIndex = () => {
  const cues = Array.isArray(state.subtitleSearchCues) ? state.subtitleSearchCues : [];
  const signature = cues.length
    ? `${state.subtitleSearchSourceIndex}:${cues.length}:${cues[0].timeMs}:${cues[cues.length - 1].timeMs}`
    : "";
  if (signature === clipSearchIndexSignature) return false;
  clipSearchIndexSignature = signature;
  clipSearchIndex = cues.length ? clipSearchBuildIndex(cues) : null;
  return true;
};

let clipSearchSourceSignature = "";

/**
 * The subtitle picker. Rebuilt only when the list of subtitles changes, not on every
 * state push, or an open dropdown would be torn down under the pointer.
 */
const clipSearchRenderSources = () => {
  if (!clipSearchSource) return;
  const sources = clipSearchSources();
  setVisible(clipSearchSource, sources.length > 0);
  const signature = sources.map(item => item.id).join("\n");
  if (signature !== clipSearchSourceSignature) {
    clipSearchSourceSignature = signature;
    clipSearchSource.textContent = "";
    // Two files in the same language from the same addon would read identically.
    const seen = new Map();
    sources.forEach((item, position) => {
      const base = item.display || item.languageLabel || item.language || `Subtitle ${position + 1}`;
      const count = (seen.get(base) || 0) + 1;
      seen.set(base, count);
      const option = document.createElement("option");
      option.value = String(Number.isFinite(item.index) ? item.index : position);
      option.textContent = count > 1 ? `${base} (${count})` : base;
      clipSearchSource.appendChild(option);
    });
  }
  // Not while the picker has focus: a push that lands between a pick and Kotlin's
  // echo of it would otherwise flick the selection back for a frame.
  const selected = Number(state.subtitleSearchSourceIndex);
  if (document.activeElement !== clipSearchSource && Number.isFinite(selected) && selected >= 0) {
    clipSearchSource.value = String(selected);
  }
};

const clipSearchRender = () => {
  if (!clipSearchPanel) return;
  const isOpen = Boolean(state.subtitleSearchOpen);
  setVisible(clipSearchPanel, isOpen);
  if (clipSearchButton) {
    clipSearchButton.classList.toggle("active", isOpen);
    // Shown with the rest of the clip tools. Whether a subtitle is actually
    // searchable is reported in the panel's status line instead, so the button
    // does not silently vanish when no addon subtitle happens to be selected.
    setVisible(clipSearchButton, Boolean(state.showClip));
  }
  if (!isOpen) return;

  clipSearchRenderSources();
  if (clipSearchSyncIndex()) clipSearchRecompute();
  else clipSearchStatus.textContent = clipSearchStatusText();
};

// --- open / close ---

const clipSearchOpen = () => {
  send("subtitleSearchOpen", 1);
  setVisible(clipSearchPanel, true);
  clipSearchStatus.textContent = clipSearchStatusText();
  window.requestAnimationFrame(() => clipSearchInput && clipSearchInput.focus());
};

const clipSearchClose = () => {
  send("subtitleSearchOpen", 0);
  setVisible(clipSearchPanel, false);
};

const clipSearchToggle = () => {
  if (state.subtitleSearchOpen) clipSearchClose();
  else clipSearchOpen();
};

if (clipSearchButton) clipSearchButton.addEventListener("click", clipSearchToggle);

// #playerRoot turns every wheel event into a volume change and preventDefaults it,
// which over a scrollable list means the list cannot scroll AND the volume jumps.
// Stopping propagation inside the panel leaves the browser's own scrolling intact;
// no preventDefault here, or the scroll would be cancelled again.
if (clipSearchPanel) {
  clipSearchPanel.addEventListener("wheel", event => event.stopPropagation(), { passive: true });
}

if (clipSearchInput) {
  clipSearchInput.addEventListener("input", () => {
    clipSearchQuery = clipSearchInput.value;
    clipSearchRecompute();
  });
  clipSearchInput.addEventListener("keydown", event => {
    if (event.key === "Enter" && clipSearchResults.length) {
      event.preventDefault();
      clipSearchGoTo(clipSearchResults[0]);
    }
  });
}

if (clipSearchSource) {
  clipSearchSource.addEventListener("change", () => {
    const index = Number(clipSearchSource.value);
    if (!Number.isFinite(index) || index < 0) return;
    // Drop results from the previous file now, rather than showing them until the
    // new one has downloaded.
    clipSearchResults = [];
    clipSearchRenderResults();
    send("subtitleSearchSource", index);
  });
}

// controls.js answers Escape before anything else in its keydown listener -- it
// leaves fullscreen or leaves the player -- so an open panel never got a say, and
// Escape in Find line or Scenes threw away the whole video. Capturing on window
// runs first. The key is consumed only when there is a panel to close; everywhere
// else Escape keeps its usual meaning.
window.addEventListener("keydown", event => {
  if (event.key !== "Escape") return;
  // An open modal is closed by controls.js's own branch; leave that alone.
  if (typeof activeModal !== "undefined" && activeModal) return;
  // The Scenes strip covers the screen, so when both are open it is the one on top.
  if (typeof clipStripVisible !== "undefined" && clipStripVisible && typeof clipStripHide === "function") {
    clipStripHide();
  } else if (clipSearchPanel && !clipSearchPanel.hidden) {
    clipSearchClose();
  } else {
    return;
  }
  event.preventDefault();
  event.stopPropagation();
  if (typeof focusShortcutRoot === "function") focusShortcutRoot();
}, true);

// --- hook into the clip UI without editing its file ---
//
// clip-controls.js and controls.js are where every clipper feature collides, so
// this wraps the three entry points it needs instead of adding lines to them.

const clipSearchPreviousUi = window.clipUi || {};
const clipSearchPreviousRender = clipSearchPreviousUi.render;
const clipSearchPreviousPin = clipSearchPreviousUi.shouldPinChrome;
const clipSearchPreviousKey = clipSearchPreviousUi.handleKey;

window.clipUi = Object.assign({}, clipSearchPreviousUi, {
  render() {
    if (clipSearchPreviousRender) clipSearchPreviousRender();
    clipSearchRender();
  },
  shouldPinChrome() {
    // Hiding the chrome out from under an open search box would take the box
    // with it, mid-query.
    if (state.subtitleSearchOpen) return true;
    return clipSearchPreviousPin ? clipSearchPreviousPin() : false;
  },
  handleKey(event) {
    if (!event.metaKey && !event.ctrlKey && !event.altKey) {
      if (event.code === "Slash") {
        event.preventDefault();
        clipSearchToggle();
        return true;
      }
    }
    return clipSearchPreviousKey ? clipSearchPreviousKey(event) : false;
  },
});

clipSearchRender();
