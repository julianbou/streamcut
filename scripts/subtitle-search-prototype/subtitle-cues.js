/*
 * Cue parsing for the subtitle phrase-search prototype.
 *
 * This is a deliberate JS mirror of PlayerSubtitleCueParser.kt so the port back
 * to Kotlin is mechanical. One difference, and it is the point of the exercise:
 * a cue here carries an END time. Auto-sync only ever needed the start, so
 * SubtitleSyncCue has no end -- and without an end there is no clip range.
 */
(function (root) {
  "use strict";

  // --- timestamps ---------------------------------------------------------

  function parseTimestamp(raw) {
    if (!raw) return null;
    const cleaned = String(raw).trim().split(" ")[0].replace(",", ".");
    const parts = cleaned.split(":");
    if (parts.length < 2 || parts.length > 3) return null;

    const secondsPart = parts[parts.length - 1];
    const seconds = parseInt(secondsPart.split(".")[0], 10);
    if (Number.isNaN(seconds)) return null;
    const millis = parseInt(
      (secondsPart.split(".")[1] || "").slice(0, 3).padEnd(3, "0") || "0",
      10
    );
    const minutes = parseInt(parts[parts.length - 2], 10);
    if (Number.isNaN(minutes)) return null;
    const hours = parts.length === 3 ? parseInt(parts[0], 10) : 0;
    if (Number.isNaN(hours)) return null;

    return Math.max(0, hours * 3600000 + minutes * 60000 + seconds * 1000 + millis);
  }

  function parseTtmlTimestamp(raw) {
    const cleaned = String(raw || "").trim().split(" ")[0];
    if (!cleaned) return null;

    const frames = cleaned.split(":");
    if (frames.length === 4) {
      const [h, m, s, f] = frames.map((p) => parseInt(p.split(".")[0], 10));
      if (![h, m, s, f].some(Number.isNaN)) {
        return Math.max(0, h * 3600000 + m * 60000 + s * 1000 + Math.round((f * 1000) / 30));
      }
    }

    const clock = parseTimestamp(cleaned);
    if (clock !== null) return clock;

    const match = /^([0-9]+(?:\.[0-9]+)?)(ms|h|m|s)$/i.exec(cleaned);
    if (!match) return null;
    const value = parseFloat(match[1]);
    const multiplier = { h: 3600000, m: 60000, s: 1000, ms: 1 }[match[2].toLowerCase()];
    return Math.max(0, Math.round(value * multiplier));
  }

  // --- text cleanup -------------------------------------------------------

  function cleanCueText(text) {
    return String(text)
      .replace(/<[^>]+>/g, "")
      .replace(/&nbsp;/g, " ")
      .replace(/&amp;/g, "&")
      .replace(/&lt;/g, "<")
      .replace(/&gt;/g, ">")
      .replace(/&quot;/g, '"')
      .replace(/&apos;/g, "'")
      .replace(/\s+/g, " ")
      .trim();
  }

  function cleanAssText(text) {
    return String(text)
      .replace(/\{[^}]*\}/g, "")
      .replace(/\\N/g, " ")
      .replace(/\\n/g, " ")
      .replace(/\\h/g, " ");
  }

  // --- format detection ---------------------------------------------------

  const FORMAT = { SRT: "srt", VTT: "vtt", ASS: "ass", TTML: "ttml" };

  function detectFormat(sourceName, text) {
    const path = String(sourceName || "").split("?")[0].split("#")[0].toLowerCase();
    const sample = text.slice(0, 4096).toLowerCase();

    if (path.endsWith(".vtt") || path.endsWith(".webvtt") || text.startsWith("WEBVTT")) return FORMAT.VTT;
    if (path.endsWith(".ass") || path.endsWith(".ssa") ||
        (sample.includes("[events]") && sample.includes("dialogue:"))) return FORMAT.ASS;
    if (path.endsWith(".ttml") || path.endsWith(".dfxp") || path.endsWith(".xml") ||
        /<tt[\s>]/i.test(text.slice(0, 512))) return FORMAT.TTML;
    return FORMAT.SRT;
  }

  // --- block formats (SRT / WebVTT) --------------------------------------

  function parseBlocks(text, dropVttHeader) {
    let body = text;
    if (dropVttHeader) {
      const lines = body.split("\n");
      let i = 0;
      while (i < lines.length && (lines[i].trim() === "" || lines[i].trim().startsWith("WEBVTT"))) i += 1;
      body = lines.slice(i).join("\n");
    }

    const cues = [];
    for (const block of body.split(/\n{2,}/)) {
      const lines = block
        .split("\n")
        .map((l) => l.trim())
        .filter((l) => l !== "" && !l.startsWith("NOTE"));
      const timingIndex = lines.findIndex((l) => l.includes("-->"));
      if (timingIndex < 0) continue;

      const timing = lines[timingIndex];
      const startMs = parseTimestamp(timing.split("-->")[0]);
      const endMs = parseTimestamp(timing.split("-->")[1]);
      if (startMs === null) continue;

      const cueText = cleanCueText(lines.slice(timingIndex + 1).join(" "));
      if (!cueText) continue;
      cues.push({ startMs, endMs, text: cueText });
    }
    return cues;
  }

  // --- ASS / SSA ----------------------------------------------------------

  const DEFAULT_ASS_FIELDS = [
    "Layer", "Start", "End", "Style", "Name",
    "MarginL", "MarginR", "MarginV", "Effect", "Text",
  ];

  function parseAss(text) {
    const cues = [];
    let inEvents = false;
    let fields = null;

    for (const rawLine of text.split("\n")) {
      const line = rawLine.trim();
      if (/^\[events\]$/i.test(line)) { inEvents = true; continue; }
      if (line.startsWith("[") && line.endsWith("]")) { inEvents = false; continue; }
      if (!inEvents) continue;

      if (/^format:/i.test(line)) {
        fields = line.slice(line.indexOf(":") + 1).split(",").map((f) => f.trim());
        continue;
      }
      if (!/^dialogue:/i.test(line)) continue;

      const active = fields && fields.length ? fields : DEFAULT_ASS_FIELDS;
      const payload = line.slice(line.indexOf(":") + 1);
      const parts = splitLimit(payload, ",", active.length).map((p) => p.trim());

      const indexOf = (name) => {
        const i = active.findIndex((f) => f.toLowerCase() === name.toLowerCase());
        return i >= 0 ? i : null;
      };
      const startIndex = indexOf("Start") ?? 1;
      const endIndex = indexOf("End") ?? 2;
      const textIndex = indexOf("Text") ?? 9;
      if (parts.length <= startIndex || parts.length <= textIndex) continue;

      const startMs = parseTimestamp(parts[startIndex]);
      if (startMs === null) continue;
      const endMs = parts.length > endIndex ? parseTimestamp(parts[endIndex]) : null;
      const cueText = cleanCueText(cleanAssText(parts[textIndex]));
      if (!cueText) continue;
      cues.push({ startMs, endMs, text: cueText });
    }
    return cues;
  }

  /** Kotlin's split(limit) semantics: the last element keeps the remaining commas. */
  function splitLimit(value, separator, limit) {
    const out = [];
    let rest = value;
    while (out.length < limit - 1) {
      const at = rest.indexOf(separator);
      if (at < 0) break;
      out.push(rest.slice(0, at));
      rest = rest.slice(at + separator.length);
    }
    out.push(rest);
    return out;
  }

  // --- TTML ---------------------------------------------------------------

  function parseTtml(text) {
    const cues = [];
    const re = /<p\b([^>]*)>([\s\S]*?)<\/p>/gi;
    let match;
    while ((match = re.exec(text)) !== null) {
      const attrs = match[1];
      const attr = (name) => {
        const m = new RegExp("\\b" + name + "\\s*=\\s*[\"']([^\"']+)[\"']", "i").exec(attrs);
        return m ? m[1] : null;
      };
      const startMs = parseTtmlTimestamp(attr("begin") || attr("start"));
      if (startMs === null) continue;

      let endMs = parseTtmlTimestamp(attr("end"));
      if (endMs === null) {
        const dur = parseTtmlTimestamp(attr("dur"));
        if (dur !== null) endMs = startMs + dur;
      }

      const cueText = cleanCueText(match[2].replace(/<br\s*\/?>/gi, " "));
      if (!cueText) continue;
      cues.push({ startMs, endMs, text: cueText });
    }
    return cues;
  }

  // --- end-time repair ----------------------------------------------------

  const MIN_CUE_MS = 700;
  const MAX_CUE_MS = 7000;

  /**
   * Some sources omit or corrupt the end time. A cue with no end cannot define a
   * clip, so fall back to the next cue's start, capped so one dangling cue at the
   * end of a reel does not swallow the rest of the film.
   */
  function fillMissingEnds(cues) {
    for (let i = 0; i < cues.length; i += 1) {
      const cue = cues[i];
      if (cue.endMs !== null && cue.endMs > cue.startMs) {
        cue.endMs = Math.min(cue.endMs, cue.startMs + MAX_CUE_MS);
        continue;
      }
      const nextStart = i + 1 < cues.length ? cues[i + 1].startMs : null;
      const guess = nextStart !== null ? nextStart : cue.startMs + MAX_CUE_MS;
      cue.endMs = Math.max(cue.startMs + MIN_CUE_MS, Math.min(guess, cue.startMs + MAX_CUE_MS));
      cue.endInferred = true;
    }
    return cues;
  }

  // --- entry point --------------------------------------------------------

  function parse(text, sourceName) {
    const normalized = String(text)
      .replace(/^\uFEFF/, "")
      .replace(/\r\n/g, "\n")
      .replace(/\r/g, "\n")
      .trim();
    if (!normalized) return { format: null, cues: [] };

    const format = detectFormat(sourceName, normalized);
    let cues;
    switch (format) {
      case FORMAT.VTT: cues = parseBlocks(normalized, true); break;
      case FORMAT.ASS: cues = parseAss(normalized); break;
      case FORMAT.TTML: cues = parseTtml(normalized); break;
      default: cues = parseBlocks(normalized, false); break;
    }

    cues.sort((a, b) => a.startMs - b.startMs);
    return { format, cues: fillMissingEnds(cues) };
  }

  const api = { parse, parseTimestamp, detectFormat, FORMAT };
  if (typeof module === "object" && module.exports) module.exports = api;
  root.SubtitleCues = api;
})(typeof globalThis !== "undefined" ? globalThis : this);
