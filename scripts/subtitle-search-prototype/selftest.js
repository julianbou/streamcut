/*
 * Match-quality checks. Run: node tools/subtitle-search-prototype/selftest.js
 *
 * Each case is a way a real search goes wrong, not a unit test of a function.
 * Cue numbers are the ones in sample.srt (1-based), so a failure can be read
 * against the file directly.
 */
"use strict";

const fs = require("fs");
const path = require("path");

const { parse } = require("./subtitle-cues.js");
const { buildIndex, search, formatTimecode } = require("./subtitle-search.js");

const srt = fs.readFileSync(path.join(__dirname, "sample.srt"), "utf8");
const parsed = parse(srt, "sample.srt");
const index = buildIndex(parsed.cues);

/** Cue numbers as printed in the .srt (1-based) for a result. */
const cueNumbers = (r) => {
  const out = [];
  for (let i = r.firstCue; i <= r.lastCue; i += 1) out.push(i + 1);
  return out;
};

const CASES = [
  {
    name: "phrase split across a cue boundary",
    query: "the truth when what you really want",
    expect: [10, 11],
    why: "the case cue-by-cue search cannot do at all",
  },
  {
    name: "accents typed without accents",
    query: "no sabes lo que me costo llegar",
    expect: [14],
    why: "nobody types acute accents into a search box",
  },
  {
    name: "apostrophe omitted",
    query: "cant carry this alone",
    expect: [5],
    why: "can't / cant must collapse to one token",
  },
  {
    name: "remembered with the wrong verb",
    query: "nobody leaves this room clean",
    expect: [16],
    why: "fuzzy pass: 'walks out of' vs 'leaves'",
  },
  {
    name: "singular/plural slip",
    query: "put the lights out",
    expect: [24],
    why: "fuzzy pass: light vs lights",
  },
  {
    name: "digits in the line",
    query: "room 412",
    expect: [7],
    why: "numbers must survive normalization",
  },
  {
    name: "markup stripped",
    query: "somewhere a door closed",
    expect: [3],
    why: "the cue is wrapped in <i> tags",
  },
  {
    name: "typed in caps with punctuation",
    query: "SIT DOWN, MAREK!",
    expect: [4],
    why: "case and punctuation must not matter",
  },
];

const MULTI = [
  {
    name: "same phrase said twice",
    query: "you keep asking me for the truth",
    expectAll: [[10], [26]],
    why: "both occurrences must come back, not just the first",
  },
  {
    name: "two lines share a fragment",
    query: "the last train",
    expectAll: [[8], [21]],
    why: "ranking must not hide the second one",
  },
];

const sameCues = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);

let failures = 0;
const row = (ok, name, detail) => {
  if (!ok) failures += 1;
  console.log(`  ${ok ? "PASS" : "FAIL"}  ${name}\n        ${detail}`);
};

console.log(`\nParsed ${parsed.cues.length} cues as ${parsed.format} from sample.srt`);
console.log(`Haystack: ${index.haystack.length} chars, ${index.tokens.length} tokens\n`);

console.log("Top hit must be the right cue(s):");
for (const c of CASES) {
  const results = search(index, c.query);
  const top = results[0];
  const got = top ? cueNumbers(top) : [];
  const ok = top && sameCues(got, c.expect);
  row(
    ok,
    `"${c.query}"`,
    ok
      ? `cue ${got.join("+")} @ ${formatTimecode(top.startMs)}  ${top.exact ? "exact" : "fuzzy " + top.score.toFixed(2)}  — ${c.why}`
      : `expected cue ${c.expect.join("+")}, got ${got.length ? got.join("+") : "nothing"}  — ${c.why}`
  );
}

console.log("\nEvery occurrence must come back:");
for (const c of MULTI) {
  const results = search(index, c.query);
  const got = results.map(cueNumbers);
  const ok = c.expectAll.every((want) => got.some((g) => sameCues(g, want)));
  row(
    ok,
    `"${c.query}"`,
    ok
      ? `${got.length} hits: ${got.map((g) => g.join("+")).join(", ")}  — ${c.why}`
      : `expected ${c.expectAll.map((g) => g.join("+")).join(" and ")}, got ${got.map((g) => g.join("+")).join(", ") || "nothing"}`
  );
}

console.log("\nClip range derived from a hit:");
{
  const top = search(index, "the truth when what you really want")[0];
  const ok = top && top.clipInMs < top.startMs && top.clipOutMs > top.endMs;
  row(
    ok,
    "cue range -> in/out",
    top
      ? `in ${formatTimecode(top.clipInMs)}  out ${formatTimecode(top.clipOutMs)}  (${((top.clipOutMs - top.clipInMs) / 1000).toFixed(2)}s over ${top.cueCount} cues)`
      : "no result"
  );

  const noEnd = parsed.cues.filter((c) => c.endMs === null || c.endMs <= c.startMs);
  row(noEnd.length === 0, "every cue has a usable end time", `${noEnd.length} cues without one`);
}

console.log(`\n${failures === 0 ? "all checks passed" : failures + " check(s) failed"}\n`);
process.exit(failures === 0 ? 0 : 1);
