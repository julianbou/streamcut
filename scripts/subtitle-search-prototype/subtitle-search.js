/*
 * Phrase search over parsed subtitle cues.
 *
 * The whole reason this prototype exists is that the two hard parts are not
 * obvious until you try them on a real film:
 *
 *  1. CROSS-CUE MATCHING. Subtitlers break a sentence across two cues whenever it
 *     is long, which is exactly what happens to the memorable lines people search
 *     for. Searching cue-by-cue misses them. So the index is one continuous
 *     normalized string with an offset -> cue map, and a hit is mapped back to a
 *     RANGE of cues -- which conveniently is also the clip range.
 *
 *  2. FUZZY MATCHING, because people type a line the way they remember it, not
 *     the way it was written. Punctuation, capitalisation and accents are
 *     normalized away, then a token-window pass tolerates a wrong or missing word.
 */
(function (root) {
  "use strict";

  // --- normalization ------------------------------------------------------

  /**
   * Apostrophes are DROPPED rather than kept, so "can't" and "cant" collapse to
   * the same token and it stops mattering which one the user typed. Letters and
   * digits of any script survive: this app plays films in languages whose
   * subtitles are not Latin script.
   */
  function normalize(text) {
    return String(text)
      .normalize("NFD")
      .replace(/[̀-ͯ]/g, "")
      .toLowerCase()
      .replace(/[’ʼ'`´]/g, "")
      .replace(/[^\p{L}\p{N}]+/gu, " ")
      .replace(/\s+/g, " ")
      .trim();
  }

  // --- index --------------------------------------------------------------

  function buildIndex(cues) {
    const spans = [];
    const tokens = [];
    const df = new Map();
    let haystack = "";

    cues.forEach((cue, cueIndex) => {
      const norm = normalize(cue.text);
      if (!norm) {
        spans.push({ cueIndex, start: haystack.length, end: haystack.length });
        return;
      }
      if (haystack.length > 0) haystack += " ";
      const start = haystack.length;
      haystack += norm;
      spans.push({ cueIndex, start, end: haystack.length });

      let offset = start;
      const seen = new Set();
      for (const token of norm.split(" ")) {
        tokens.push({ text: token, cueIndex, start: offset, end: offset + token.length });
        offset += token.length + 1;
        seen.add(token);
      }
      for (const token of seen) df.set(token, (df.get(token) || 0) + 1);
    });

    return { cues, haystack, spans, tokens, df, docCount: Math.max(1, cues.length) };
  }

  /**
   * Inverse document frequency, so "the" cannot carry a match on its own. Without
   * this, a query of mostly common words scores well against any busy line and the
   * result list fills with near-misses.
   */
  function tokenWeight(index, token) {
    const df = index.df.get(token) || 0;
    return Math.log(1 + index.docCount / (1 + df));
  }

  /** Which cue owns a given offset into the haystack. */
  function cueAtOffset(index, offset) {
    const spans = index.spans;
    let lo = 0;
    let hi = spans.length - 1;
    let best = 0;
    while (lo <= hi) {
      const mid = (lo + hi) >> 1;
      if (spans[mid].start <= offset) { best = mid; lo = mid + 1; } else { hi = mid - 1; }
    }
    return spans[best].cueIndex;
  }

  // --- token similarity ---------------------------------------------------

  function levenshteinWithin(a, b, max) {
    if (Math.abs(a.length - b.length) > max) return max + 1;
    let prev = new Array(b.length + 1);
    let curr = new Array(b.length + 1);
    for (let j = 0; j <= b.length; j += 1) prev[j] = j;

    for (let i = 1; i <= a.length; i += 1) {
      curr[0] = i;
      let rowMin = curr[0];
      for (let j = 1; j <= b.length; j += 1) {
        const cost = a[i - 1] === b[j - 1] ? 0 : 1;
        curr[j] = Math.min(prev[j] + 1, curr[j - 1] + 1, prev[j - 1] + cost);
        if (curr[j] < rowMin) rowMin = curr[j];
      }
      if (rowMin > max) return max + 1;
      const swap = prev; prev = curr; curr = swap;
    }
    return prev[b.length];
  }

  const EXACT = 1;
  const APPROX = 0.85;

  /** Returns 0 for no match, else how much the match is worth. */
  function tokenMatchQuality(a, b) {
    if (a === b) return EXACT;
    const shorter = Math.min(a.length, b.length);
    if (shorter >= 5 && (a.startsWith(b) || b.startsWith(a))) return APPROX;
    // Six, not four: "rain" and "train" are one edit apart and unrelated.
    if (shorter >= 6 && levenshteinWithin(a, b, 1) <= 1) return APPROX;
    return 0;
  }

  /**
   * Recall-weighted overlap: covering the words the user typed matters more than
   * the window being tight around them, because a remembered phrase is usually a
   * fragment of a longer spoken line.
   */
  function windowScore(index, windowTokens, queryTokens) {
    const used = new Array(windowTokens.length).fill(false);
    let matchedWeight = 0;
    let anyMatch = false;

    for (const q of queryTokens) {
      let bestIndex = -1;
      let bestQuality = 0;
      for (let i = 0; i < windowTokens.length; i += 1) {
        if (used[i]) continue;
        const quality = tokenMatchQuality(q, windowTokens[i]);
        if (quality > bestQuality) { bestQuality = quality; bestIndex = i; }
      }
      if (bestIndex < 0) continue;
      used[bestIndex] = true;
      anyMatch = true;
      matchedWeight += bestQuality * tokenWeight(index, q);
    }
    if (!anyMatch) return null;

    const queryWeight = queryTokens.reduce((sum, t) => sum + tokenWeight(index, t), 0);
    const windowWeight = windowTokens.reduce((sum, t) => sum + tokenWeight(index, t), 0);
    if (queryWeight === 0 || windowWeight === 0) return null;

    const recall = matchedWeight / queryWeight;
    const precision = matchedWeight / windowWeight;
    return { score: 0.8 * recall + 0.2 * precision, recall };
  }

  // --- search -------------------------------------------------------------

  const DEFAULTS = {
    fuzzyThreshold: 0.6,
    /**
     * A hit must also carry this share of the query's total weight, independently
     * of the blended score. Without it, a two-word window that matches perfectly
     * but misses the one word that identifies the line ("the train" for a query of
     * "the last train") scores well on precision and floats into the results.
     */
    minRecall: 0.65,
    limit: 50,
    padMs: 250,
    delayMs: 0,
  };

  function search(index, rawQuery, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const query = normalize(rawQuery);
    if (!query) return [];

    const queryTokens = query.split(" ");
    const byRange = new Map();

    const record = (firstCue, lastCue, score, exact) => {
      const key = firstCue + ":" + lastCue;
      const existing = byRange.get(key);
      if (existing && existing.score >= score) return;
      byRange.set(key, { firstCue, lastCue, score, exact });
    };

    // Pass A -- exact substring over the whole haystack, so a phrase broken
    // across a cue boundary is found like any other.
    let at = index.haystack.indexOf(query);
    while (at >= 0) {
      record(cueAtOffset(index, at), cueAtOffset(index, at + query.length - 1), 1, true);
      at = index.haystack.indexOf(query, at + 1);
    }

    // Pass B -- fuzzy token windows.
    const tokens = index.tokens;

    /**
     * The spoken line is usually WORDIER than the phrase someone types, so the
     * window has to be allowed to grow past the query length -- "nobody leaves
     * this room clean" is five words standing in for a seven-word line, and a
     * window capped at six can never cover it.
     */
    const sizes = new Set();
    for (let size = Math.max(1, queryTokens.length - 1); size <= queryTokens.length + 3; size += 1) {
      sizes.add(size);
    }

    /**
     * Cheap prefilter: a window with no exact query token in it is not going to
     * clear the recall gate, and skipping those keeps a full-length film from
     * scoring tens of thousands of windows per keystroke. The cost is a query
     * where EVERY token is misspelled, which would score poorly anyway.
     */
    const querySet = new Set(queryTokens);
    const hits = new Array(tokens.length + 1).fill(0);
    for (let i = 0; i < tokens.length; i += 1) {
      hits[i + 1] = hits[i] + (querySet.has(tokens[i].text) ? 1 : 0);
    }

    for (const size of sizes) {
      for (let i = 0; i + size <= tokens.length; i += 1) {
        if (hits[i + size] - hits[i] === 0) continue;
        const first = tokens[i];
        const last = tokens[i + size - 1];
        // A window straddling a scene change is noise, not a phrase.
        if (last.cueIndex - first.cueIndex > 2) continue;

        const slice = [];
        for (let j = i; j < i + size; j += 1) slice.push(tokens[j].text);
        const scored = windowScore(index, slice, queryTokens);
        if (!scored) continue;
        if (scored.recall < opts.minRecall) continue;
        if (scored.score < opts.fuzzyThreshold) continue;
        record(first.cueIndex, last.cueIndex, scored.score, false);
      }
    }

    const results = Array.from(byRange.values())
      .map((hit) => toResult(index, hit, opts))
      .sort((a, b) => (b.score - a.score) || (a.startMs - b.startMs));

    return dropCoveredDuplicates(results).slice(0, opts.limit);
  }

  function toResult(index, hit, opts) {
    const cues = index.cues.slice(hit.firstCue, hit.lastCue + 1);
    const startMs = cues[0].startMs + opts.delayMs;
    const endMs = cues[cues.length - 1].endMs + opts.delayMs;
    return {
      score: hit.score,
      exact: hit.exact,
      firstCue: hit.firstCue,
      lastCue: hit.lastCue,
      cueCount: cues.length,
      startMs,
      endMs,
      text: cues.map((c) => c.text).join(" "),
      cueTexts: cues.map((c) => c.text),
      clipInMs: Math.max(0, startMs - opts.padMs),
      clipOutMs: endMs + opts.padMs,
    };
  }

  /**
   * The sliding window reports the same moment once per offset it fits at, and a
   * fuzzy window often overlaps a range an exact hit already claimed. Results
   * arrive best-first, so keeping any range that does not touch a kept one leaves
   * exactly one entry per moment.
   */
  function dropCoveredDuplicates(results) {
    const kept = [];
    for (const result of results) {
      const overlaps = kept.some(
        (k) => result.firstCue <= k.lastCue && k.firstCue <= result.lastCue
      );
      if (!overlaps) kept.push(result);
    }
    return kept;
  }

  // --- helpers ------------------------------------------------------------

  function formatTimecode(ms) {
    const total = Math.max(0, Math.round(ms));
    const h = Math.floor(total / 3600000);
    const m = Math.floor((total % 3600000) / 60000);
    const s = Math.floor((total % 60000) / 1000);
    const millis = total % 1000;
    const pad = (v, n) => String(v).padStart(n, "0");
    return pad(h, 2) + ":" + pad(m, 2) + ":" + pad(s, 2) + "." + pad(millis, 3);
  }

  const api = { normalize, buildIndex, search, formatTimecode, DEFAULTS };
  if (typeof module === "object" && module.exports) module.exports = api;
  root.SubtitleSearch = api;
})(typeof globalThis !== "undefined" ? globalThis : this);
