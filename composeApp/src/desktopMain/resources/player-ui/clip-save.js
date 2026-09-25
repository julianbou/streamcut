// Save as: export the marked ranges under a name and into a folder you choose.
//
// Export (X) stays the one-press path -- clips folder, filename template. Save as
// (Shift+X, or the caret joined to Export) opens a sheet over the clip row: one
// name for the batch, numbered in film order, any row renamable on its own, and
// the folders you saved to recently as one-click choices.
//
// Kept in its own file for the same reason as clip-search.js: clip-controls.js
// is where every clipper feature collides. It uses that file's globals (state,
// send, clipExportableRanges, clipQueueExports, formatClipTime, clipShortPath)
// and wraps window.clipUi rather than editing it.
//
// The bridge carries numbers only, so a file name crosses it one code point at
// a time (clipSaveNameChar) and the folder by its index in the list Kotlin sent
// (clipSaveFolder, once per batch).

const CLIP_SAVE_MAX_STEM = 150;
// Mirrors ClipFilenameTemplate.sanitize, so the name shown is the name written.
const CLIP_SAVE_ALLOWED = new Set([".", "_", "-", " ", "(", ")", "[", "]", "'", "&", "+", ","]);

let clipSaveOpen = false;
let clipSaveBase = "";
// Per-row names the user typed over the numbered default, keyed by range
// identity so they survive a redraw but not a different set of ranges.
let clipSaveOverrides = new Map();
let clipSaveFolderIndex = 0;
let clipSaveRenaming = null;
let clipSaveSeenPickToken = 0;
// The last name typed per title, so a second Save as on the same film starts
// where the first left off instead of back at the title.
const clipSaveBaseByTitle = new Map();

const clipSaveSanitize = name => Array.from(String(name || ""))
  .map(ch => (/[\p{L}\p{N}]/u.test(ch) || CLIP_SAVE_ALLOWED.has(ch) ? ch : " "))
  .join("")
  .replace(/ {2,}/g, " ")
  .trim()
  .replace(/^[. ]+/, "")
  .slice(0, CLIP_SAVE_MAX_STEM)
  .trim();

const clipSaveRangeKey = range => `${Math.round(range.inMs)}-${Math.round(range.outMs)}`;

/** Every range that would be saved, in film order -- the order they are numbered in. */
const clipSaveRanges = () =>
  clipExportableRanges().slice().sort((a, b) => a.inMs - b.inMs || a.outMs - b.outMs);

const clipSavePad = (n, count) => String(n).padStart(Math.max(2, String(count).length), "0");

/** The stem each range will be written as, before `.mp4`. Blank means the template. */
const clipSaveStems = ranges => {
  const base = clipSaveSanitize(clipSaveBase);
  return ranges.map((range, index) => {
    const override = clipSaveOverrides.get(clipSaveRangeKey(range));
    if (override) return override;
    if (!base) return "";
    return ranges.length > 1 ? `${base} ${clipSavePad(index + 1, ranges.length)}` : base;
  });
};

/** `4.6s`, or `1:12.4` past a minute. A length, not a position. */
const clipSaveLength = ms => {
  const tenths = Math.max(0, Math.round((Number(ms) || 0) / 100));
  const seconds = Math.floor(tenths / 10);
  if (seconds < 60) return `${seconds}.${tenths % 10}s`;
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}.${tenths % 10}`;
};

const clipSaveFolders = () => (Array.isArray(state.clipSaveFolders) ? state.clipSaveFolders : []);

const clipSaveSplitPath = path => {
  const trimmed = String(path || "").replace(/[\\/]+$/, "");
  const cut = Math.max(trimmed.lastIndexOf("/"), trimmed.lastIndexOf("\\"));
  return {
    leaf: cut >= 0 ? trimmed.slice(cut + 1) || trimmed : trimmed,
    parent: cut > 0 ? clipShortPath(trimmed.slice(0, cut)) : "",
  };
};

// --- elements ---------------------------------------------------------------

// The caret half of a split Export button. It opens the sheet directly: a menu
// holding one item would only add a click between the user and the sheet.
const clipSaveCaret = document.createElement("button");
clipSaveCaret.type = "button";
clipSaveCaret.className = "clip-action primary clip-save-caret";
clipSaveCaret.id = "clipSaveAsButton";
clipSaveCaret.title = "Save as… (⇧X)";
clipSaveCaret.setAttribute("aria-label", "Save as");
clipSaveCaret.setAttribute("aria-haspopup", "dialog");
clipSaveCaret.setAttribute("aria-expanded", "false");
clipSaveCaret.innerHTML =
  '<svg viewBox="0 0 12 12" aria-hidden="true"><path d="M3 4.5 6 7.5 9 4.5" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>';
clipExportButton.classList.add("clip-save-main");
clipExportButton.insertAdjacentElement("afterend", clipSaveCaret);
clipExportButton.parentElement.classList.add("clip-save-split-host");

const clipSavePanel = document.createElement("section");
clipSavePanel.className = "clip-save";
clipSavePanel.id = "clipSavePanel";
clipSavePanel.hidden = true;
clipSavePanel.setAttribute("role", "dialog");
clipSavePanel.setAttribute("aria-labelledby", "clipSaveTitle");
clipSavePanel.innerHTML = `
  <header class="clip-save-head">
    <h2 class="clip-save-title" id="clipSaveTitle">Save clip as</h2>
    <button class="clip-save-close" type="button" data-clip-save-cancel aria-label="Close" title="Close (Esc)">
      <svg aria-hidden="true"><use href="#icon-close"></use></svg>
    </button>
  </header>
  <div class="clip-save-body">
  <div class="clip-save-name">
    <label class="clip-save-label" for="clipSaveBaseInput">Name</label>
    <div class="clip-save-field">
      <input class="clip-save-input" id="clipSaveBaseInput" type="text" spellcheck="false" autocomplete="off" placeholder="Use the filename template">
      <span class="clip-save-suffix" id="clipSaveSuffix" aria-hidden="true">.mp4</span>
    </div>
  </div>
  <ol class="clip-save-files" id="clipSaveFiles" aria-label="Files"></ol>
  <fieldset class="clip-save-dest">
    <legend class="clip-save-label">Save to</legend>
    <div class="clip-save-folders" id="clipSaveFolders"></div>
    <button class="clip-save-choose" id="clipSaveChoose" type="button">Choose folder…</button>
  </fieldset>
  </div>
  <footer class="clip-save-foot">
    <span class="clip-save-summary" id="clipSaveSummary"></span>
    <button class="clip-action" type="button" data-clip-save-cancel>Cancel</button>
    <button class="clip-action primary" id="clipSaveConfirm" type="button">Save</button>
  </footer>
`;
clipRow.parentElement.insertBefore(clipSavePanel, clipRow);

const clipSaveTitle = clipSavePanel.querySelector("#clipSaveTitle");
const clipSaveBaseInput = clipSavePanel.querySelector("#clipSaveBaseInput");
const clipSaveSuffix = clipSavePanel.querySelector("#clipSaveSuffix");
const clipSaveFiles = clipSavePanel.querySelector("#clipSaveFiles");
const clipSaveFolderList = clipSavePanel.querySelector("#clipSaveFolders");
const clipSaveChoose = clipSavePanel.querySelector("#clipSaveChoose");
const clipSaveSummary = clipSavePanel.querySelector("#clipSaveSummary");
const clipSaveConfirm = clipSavePanel.querySelector("#clipSaveConfirm");

// The numbering suffix follows the typed name instead of waiting at the far
// end of the field, so the input is sized to its text. Measured on a canvas
// rather than with field-sizing, which WKWebView does not have.
const clipSaveMeasure = document.createElement("canvas").getContext("2d");
const clipSaveFitInput = () => {
  const style = getComputedStyle(clipSaveBaseInput);
  clipSaveMeasure.font = `${style.fontWeight} ${style.fontSize} ${style.fontFamily}`;
  const text = clipSaveBaseInput.value || clipSaveBaseInput.placeholder;
  clipSaveBaseInput.style.width = `${Math.ceil(clipSaveMeasure.measureText(text).width) + 3}px`;
};

// --- render -----------------------------------------------------------------

// The player re-renders the chrome on every state push -- playback ticks
// included -- and the lists below are rebuilt from scratch. Rebuilding them
// when nothing they show has changed replaced a half-typed rename with a fresh
// input holding the old name, so each list keeps the signature it last drew
// and skips an identical redraw.
let clipSaveFilesDrawn = null;
let clipSaveFoldersDrawn = null;

const clipSaveRenderFiles = (ranges, stems) => {
  const signature = JSON.stringify([
    ranges.map(clipSaveRangeKey),
    stems,
    clipSaveRenaming,
    [...clipSaveOverrides.keys()],
  ]);
  if (signature === clipSaveFilesDrawn) return;
  clipSaveFilesDrawn = signature;
  const single = ranges.length <= 1;
  clipSaveFiles.hidden = single;
  clipSaveFiles.textContent = "";
  if (single) return;

  // Two rows landing on the same name would still both be written -- the
  // exporter keeps both -- but the second as "name (1)", so say so here.
  const seen = new Map();
  stems.forEach((stem, index) => {
    const key = stem.toLowerCase();
    if (stem && !seen.has(key)) seen.set(key, index);
  });

  let renameInput = null;
  ranges.forEach((range, index) => {
    const key = clipSaveRangeKey(range);
    const stem = stems[index];
    const renamed = clipSaveOverrides.has(key);
    const item = document.createElement("li");
    item.className = "clip-save-file";
    item.dataset.renamed = renamed ? "true" : "false";

    const number = document.createElement("span");
    number.className = "clip-save-file-n";
    number.textContent = clipSavePad(index + 1, ranges.length);
    item.appendChild(number);

    const span = document.createElement("span");
    span.className = "clip-save-file-range";
    span.textContent = `${formatClipTime(range.inMs)} – ${formatClipTime(range.outMs)}`;
    item.appendChild(span);

    const length = document.createElement("span");
    length.className = "clip-save-file-len";
    length.textContent = clipSaveLength(range.outMs - range.inMs);
    item.appendChild(length);

    if (clipSaveRenaming === key) {
      const input = document.createElement("input");
      input.className = "clip-save-file-input";
      input.type = "text";
      input.spellcheck = false;
      input.autocomplete = "off";
      input.value = stem;
      input.setAttribute("aria-label", `Name for clip ${index + 1}`);
      const commit = () => {
        if (clipSaveRenaming !== key) return;
        const value = clipSaveSanitize(input.value);
        // Typing the numbered name back, or clearing the field, is a reset.
        clipSaveOverrides.delete(key);
        const numbered = clipSaveStems(ranges)[index];
        if (value && value !== numbered) clipSaveOverrides.set(key, value);
        clipSaveRenaming = null;
        clipSaveRender();
        clipSavePanel.querySelector(`[data-clip-save-row="${key}"]`)?.focus();
      };
      input.addEventListener("keydown", event => {
        if (event.key === "Enter") {
          event.preventDefault();
          event.stopPropagation();
          commit();
        } else if (event.key === "Escape") {
          event.preventDefault();
          event.stopPropagation();
          clipSaveRenaming = null;
          clipSaveRender();
          clipSavePanel.querySelector(`[data-clip-save-row="${key}"]`)?.focus();
        }
      });
      input.addEventListener("blur", commit);
      item.appendChild(input);
      renameInput = input;
    } else {
      const name = document.createElement("button");
      name.type = "button";
      name.className = "clip-save-file-name";
      name.dataset.clipSaveRow = key;
      name.title = "Rename this clip";
      const firstIndex = seen.get(stem.toLowerCase());
      const duplicate = stem && firstIndex !== undefined && firstIndex !== index;
      if (stem) {
        const stemText = document.createElement("span");
        stemText.className = "clip-save-file-stem";
        stemText.textContent = stem;
        name.appendChild(stemText);
        const ext = document.createElement("span");
        ext.className = "clip-save-file-ext";
        ext.textContent = duplicate ? " (1).mp4" : ".mp4";
        name.appendChild(ext);
      } else {
        name.classList.add("is-template");
        name.textContent = "Named by your template";
      }
      if (duplicate) {
        name.title = `Same name as ${clipSavePad(firstIndex + 1, ranges.length)}, so both are kept`;
      }
      name.addEventListener("click", event => {
        event.stopPropagation();
        clipSaveRenaming = key;
        clipSaveRender();
      });
      item.appendChild(name);

      if (renamed) {
        const reset = document.createElement("button");
        reset.type = "button";
        reset.className = "clip-save-file-reset";
        reset.title = "Back to the numbered name";
        reset.setAttribute("aria-label", `Back to the numbered name for clip ${index + 1}`);
        reset.innerHTML = '<svg aria-hidden="true"><use href="#icon-close"></use></svg>';
        reset.addEventListener("click", event => {
          event.stopPropagation();
          clipSaveOverrides.delete(key);
          clipSaveRender();
          clipSavePanel.querySelector(`[data-clip-save-row="${key}"]`)?.focus();
        });
        item.appendChild(reset);
      }
    }
    clipSaveFiles.appendChild(item);
  });
  // Focused here, once the input is in the document, not on a later frame: a
  // deferred focus can land after the user has already started typing.
  if (renameInput) {
    renameInput.focus();
    renameInput.select();
  }
};

const clipSaveRenderFolders = () => {
  const folders = clipSaveFolders();
  if (clipSaveFolderIndex >= folders.length) clipSaveFolderIndex = 0;
  const signature = JSON.stringify([folders, clipSaveFolderIndex, Boolean(state.clipSaveCanChoose)]);
  if (signature === clipSaveFoldersDrawn) return;
  clipSaveFoldersDrawn = signature;
  const focusedValue = clipSaveFolderList.contains(document.activeElement)
    ? document.activeElement.value
    : null;
  clipSaveFolderList.textContent = "";
  folders.forEach((folder, index) => {
    const { leaf, parent } = clipSaveSplitPath(folder.path);
    const label = document.createElement("label");
    label.className = "clip-save-folder";
    label.title = folder.path;

    const radio = document.createElement("input");
    radio.type = "radio";
    radio.name = "clipSaveFolder";
    radio.value = String(index);
    radio.checked = index === clipSaveFolderIndex;
    radio.addEventListener("change", () => {
      clipSaveFolderIndex = index;
      clipSaveRenderFolders();
      clipSaveFolderList.querySelector(`input[value="${index}"]`)?.focus();
    });
    label.appendChild(radio);

    const mark = document.createElement("span");
    mark.className = "clip-save-folder-mark";
    mark.setAttribute("aria-hidden", "true");
    label.appendChild(mark);

    const name = document.createElement("span");
    name.className = "clip-save-folder-leaf";
    name.textContent = leaf;
    label.appendChild(name);

    const where = document.createElement("span");
    where.className = "clip-save-folder-parent";
    where.textContent = parent;
    label.appendChild(where);

    const tags = [];
    if (folder.isLastUsed) tags.push(["last used", "last"]);
    if (folder.isClipsFolder) tags.push(["clips folder", "clips"]);
    tags.forEach(([text, kind]) => {
      const tag = document.createElement("span");
      tag.className = "clip-save-folder-tag";
      tag.dataset.kind = kind;
      tag.textContent = text;
      label.appendChild(tag);
    });

    clipSaveFolderList.appendChild(label);
  });
  if (focusedValue != null) {
    clipSaveFolderList.querySelector(`input[value="${focusedValue}"]`)?.focus();
  }
  clipSaveChoose.hidden = !state.clipSaveCanChoose;
};

// The sheet sits in the bottom stack, which grows upward, so a long batch
// pushed it -- and the title above it -- over the header buttons. Its height is
// capped to what is free between the header and where the sheet ends. The title
// and the Save row always stay; the part between them scrolls.
const clipSaveFitHeight = () => {
  if (!clipSaveOpen) return;
  const header = document.querySelector(".header");
  const metadata = document.querySelector(".metadata");
  const bottom = clipSavePanel.getBoundingClientRect().bottom;
  const top = (header ? header.getBoundingClientRect().bottom : 0) + 12;
  const above = metadata ? metadata.offsetHeight + 10 : 0;
  clipSavePanel.style.maxHeight = `${Math.max(240, Math.floor(bottom - top - above))}px`;
};
window.addEventListener("resize", clipSaveFitHeight);

const clipSaveRender = () => {
  clipSavePanel.hidden = !clipSaveOpen;
  clipSaveCaret.setAttribute("aria-expanded", clipSaveOpen ? "true" : "false");
  clipSaveCaret.disabled = clipExportButton.disabled;
  clipSaveCaret.hidden = clipExportButton.hidden;
  if (!clipSaveOpen) return;

  const ranges = clipSaveRanges();
  if (ranges.length === 0) {
    clipSaveClose();
    return;
  }
  // A folder the picker just returned goes to the top of the list; select it.
  const token = Number(state.clipSavePickToken) || 0;
  if (token !== clipSaveSeenPickToken) {
    clipSaveSeenPickToken = token;
    clipSaveFolderIndex = 0;
  }

  const count = ranges.length;
  const stems = clipSaveStems(ranges);
  clipSaveTitle.textContent = count > 1 ? `Save ${count} clips as` : "Save clip as";
  clipSaveSuffix.textContent = count > 1
    ? ` ${clipSavePad(1, count)}–${clipSavePad(count, count)}.mp4`
    : ".mp4";
  clipSaveSuffix.hidden = !clipSaveSanitize(clipSaveBase);
  clipSaveFitInput();
  clipSaveRenderFiles(ranges, stems);
  clipSaveRenderFolders();

  const totalMs = ranges.reduce((sum, range) => sum + (range.outMs - range.inMs), 0);
  const parts = count > 1
    ? [`${clipSaveLength(totalMs)} in all`]
    : [`${formatClipTime(ranges[0].inMs)} – ${formatClipTime(ranges[0].outMs)}`, clipSaveLength(totalMs)];
  if (state.clipSubtitlesAvailable) {
    // Same reading as the row's Subtitles button, so the two never disagree.
    parts.push(state.clipBurnSubtitles ? "subtitles burned in" : "no subtitles");
  }
  clipSaveSummary.textContent = parts.join(" · ");
  clipSaveConfirm.textContent = count > 1 ? `Save ${count} clips` : "Save clip";
  clipSaveConfirm.disabled = clipSaveFolders().length === 0;
  clipSaveFitHeight();
};

// --- open, close, save ------------------------------------------------------

const clipSaveShow = () => {
  if (clipExportButton.disabled || clipSaveRanges().length === 0) return;
  clipSaveOpen = true;
  const titleKey = String(state.clipSaveBaseName || "");
  clipSaveBase = clipSaveBaseByTitle.get(titleKey) ?? titleKey;
  clipSaveOverrides = new Map();
  clipSaveRenaming = null;
  clipSaveFolderIndex = 0;
  clipSaveSeenPickToken = Number(state.clipSavePickToken) || 0;
  clipSaveFilesDrawn = null;
  clipSaveFoldersDrawn = null;
  clipSaveBaseInput.value = clipSaveBase;
  clipSaveRender();
  if (typeof renderClipUi === "function") renderClipUi();
  clipSaveBaseInput.focus();
  clipSaveBaseInput.select();
};

function clipSaveClose(restoreFocus = true) {
  if (!clipSaveOpen) return;
  clipSaveOpen = false;
  clipSaveRenaming = null;
  clipSavePanel.hidden = true;
  clipSaveCaret.setAttribute("aria-expanded", "false");
  if (restoreFocus && !clipSaveCaret.disabled) {
    clipSaveCaret.focus({ preventScroll: true });
  } else if (typeof focusShortcutRoot === "function") {
    focusShortcutRoot();
  }
}

const clipSaveToggle = () => (clipSaveOpen ? clipSaveClose() : clipSaveShow());

const clipSaveCommit = () => {
  if (!clipSaveOpen || clipSaveConfirm.disabled) return;
  const ranges = clipSaveRanges();
  if (ranges.length === 0) return;
  const stems = clipSaveStems(ranges);
  const folderIndex = clipSaveFolderIndex;
  clipSaveBaseByTitle.set(String(state.clipSaveBaseName || ""), clipSaveBase);
  clipSaveClose(false);
  // The folder goes once, before the clips: Kotlin moves it to the top of the
  // list as it lands, so a per-clip index would point elsewhere by clip two.
  send("clipSaveFolder", folderIndex);
  clipQueueExports(ranges, (range, index) => {
    send("clipSaveNameReset", 0);
    for (const ch of stems[index] || "") send("clipSaveNameChar", ch.codePointAt(0));
    send("clipSaveExport", 0);
  });
};

// --- wiring -----------------------------------------------------------------

// renderClipUi enables Export as ranges are marked, locally and without going
// through window.clipUi.render, so the caret follows the button itself.
new MutationObserver(() => {
  clipSaveCaret.disabled = clipExportButton.disabled;
  clipSaveCaret.hidden = clipExportButton.hidden;
}).observe(clipExportButton, { attributes: true, attributeFilter: ["disabled", "hidden"] });

clipSaveCaret.addEventListener("click", event => {
  event.stopPropagation();
  clipSaveToggle();
});

clipSaveBaseInput.addEventListener("input", () => {
  clipSaveBase = clipSaveBaseInput.value;
  clipSaveRender();
});

// The field is wider than its input; a click on the empty part still types.
clipSaveBaseInput.parentElement.addEventListener("mousedown", event => {
  if (event.target === clipSaveBaseInput) return;
  event.preventDefault();
  clipSaveBaseInput.focus();
  const end = clipSaveBaseInput.value.length;
  clipSaveBaseInput.setSelectionRange(end, end);
});

clipSaveChoose.addEventListener("click", event => {
  event.stopPropagation();
  send("clipSaveChooseFolder", clipSaveFolderIndex);
});

clipSaveConfirm.addEventListener("click", event => {
  event.stopPropagation();
  clipSaveCommit();
});

clipSavePanel.querySelectorAll("[data-clip-save-cancel]").forEach(button => {
  button.addEventListener("click", event => {
    event.stopPropagation();
    clipSaveClose();
  });
});

// Enter saves from anywhere in the sheet except a button (which answers it
// itself) and a row being renamed (where it commits the name).
clipSavePanel.addEventListener("keydown", event => {
  if (event.key !== "Enter" || event.isComposing) return;
  const target = event.target;
  if (target instanceof HTMLButtonElement) return;
  if (target.classList && target.classList.contains("clip-save-file-input")) return;
  event.preventDefault();
  event.stopPropagation();
  clipSaveCommit();
});

// The player root pauses on a click and goes fullscreen on a double-click on
// anything that is not a control; the sheet's padding is neither.
clipSavePanel.addEventListener("click", event => event.stopPropagation());
clipSavePanel.addEventListener("dblclick", event => event.stopPropagation());
// The player root turns the wheel into volume and calls preventDefault, which
// kept a long file list from scrolling at all. The sheet's wheel stays its own.
clipSavePanel.addEventListener("wheel", event => event.stopPropagation(), { passive: true });

// Escape: controls.js answers it before anything else (it leaves the player),
// so the sheet has to take it on the capture pass -- and only while open.
window.addEventListener("keydown", event => {
  if (event.key !== "Escape" || !clipSaveOpen) return;
  if (typeof activeModal !== "undefined" && activeModal) return;
  // A row being renamed takes the first Escape itself.
  if (clipSaveRenaming != null) return;
  event.preventDefault();
  event.stopPropagation();
  clipSaveClose();
}, true);

// Shift+X from inside the sheet's own inputs would otherwise type an X.
window.addEventListener("keydown", event => {
  if (event.code !== "KeyX" || !event.shiftKey || event.metaKey || event.ctrlKey || event.altKey) return;
  if (!clipSaveOpen || !clipSavePanel.contains(event.target)) return;
  if (event.target instanceof HTMLInputElement && event.target.type === "text") return;
  event.preventDefault();
  event.stopPropagation();
  clipSaveClose();
}, true);

// --- hook into the clip UI without editing its file ---

const clipSavePreviousUi = window.clipUi || {};
const clipSavePreviousRender = clipSavePreviousUi.render;
const clipSavePreviousPin = clipSavePreviousUi.shouldPinChrome;
const clipSavePreviousKey = clipSavePreviousUi.handleKey;

window.clipUi = Object.assign({}, clipSavePreviousUi, {
  render() {
    if (clipSavePreviousRender) clipSavePreviousRender();
    clipSaveRender();
  },
  shouldPinChrome() {
    // The sheet lives in the bottom controls; letting them fade while a name
    // is half typed would take the field away mid-word.
    if (clipSaveOpen) return true;
    return clipSavePreviousPin ? clipSavePreviousPin() : false;
  },
  handleKey(event) {
    if (event.code === "KeyX" && event.shiftKey && !event.metaKey && !event.ctrlKey && !event.altKey &&
        state.showClip) {
      event.preventDefault();
      clipSaveToggle();
      return true;
    }
    return clipSavePreviousKey ? clipSavePreviousKey(event) : false;
  },
});

clipSaveRender();
