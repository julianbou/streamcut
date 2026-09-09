# Subtitle phrase search — prototype

Validates the matching quality behind "type a line, jump to it, clip it" before any
of it is written in Kotlin. Nothing here ships; it exists so the Kotlin port starts
from a matcher that is already known to work.

```bash
node scripts/subtitle-search-prototype/selftest.js      # match-quality checks
python3 -m http.server 8731                           # then open index.html
```

`index.html` loads `sample.srt` by default and takes a dropped `.srt/.vtt/.ass/.ttml`
so the matcher can be tried against a real film. **That is the test that matters** —
the sample only proves the mechanics.

## Files

| File | Role | Ports to |
|---|---|---|
| `subtitle-cues.js` | parse SRT/VTT/ASS/TTML → `{startMs, endMs, text}` | `PlayerSubtitleCueParser.kt` |
| `subtitle-search.js` | normalize, index, match, derive clip range | new `SubtitleSearchIndex.kt` |
| `selftest.js` | the ways a search goes wrong, as assertions | unit tests |
| `sample.srt` | synthetic cues written to hit each failure mode | — |

`subtitle-cues.js` is a deliberate mirror of `PlayerSubtitleCueParser.kt`, with one
addition: **cues carry an end time.** `SubtitleSyncCue` has only a start, because
auto-sync never needed more — and without an end there is no clip range. That is the
one change the existing Kotlin needs.

## What the checks cover

All 12 pass. Beyond the obvious (case, punctuation, `<i>` tags, digits):

- **A phrase split across two cues.** The thing cue-by-cue search cannot do at all.
  Subtitlers break long sentences, and long sentences are what people search for.
- **Accents typed without accents**, apostrophes dropped (`can't` → `cant`).
- **A remembered line that is not the written line** — wrong verb, wrong number.
- **Every occurrence returned**, not just the first, when a line is said twice.

## Three things the tuning taught

The first version passed every assertion while returning **7 hits for a 2-occurrence
phrase**. Assertions passing is not the same as the feature being usable, and each
fix below came from reading the noise rather than the pass/fail line:

1. **Common words cannot be allowed to carry a match.** Scoring by token count let a
   query of mostly ordinary words match any busy line. Tokens are now weighted by
   inverse document frequency over the film's own cues.
2. **Edit distance 1 is too loose on short words.** `rain` and `train` are one edit
   apart and unrelated — that mismatch put the wrong line in the results. Fuzzy token
   matching now needs 6+ characters; shorter words match exactly or by prefix.
3. **The spoken line is wordier than the typed phrase.** A five-word query standing in
   for a seven-word line could never be covered by a window capped at query length + 1,
   so the true hit scored below threshold while fragments scored above it. Windows now
   grow to query length + 3, with a separate recall gate so a short window that matches
   perfectly but misses the identifying word ("the train" for "the last train") is cut.

A sliding window also reports the same moment once per offset it fits at, so results
are de-duplicated by overlapping cue range, best score first.

## Notes for the Kotlin port

- **Cost.** Windows scored per keystroke is roughly `tokens × window sizes`. A film's
  subtitles run ~10–15k tokens, so a prefilter (skip any window containing no exact
  query token) does the real work of keeping this cheap. Searches on the 206-token
  sample land at 20–40 ms in the browser including render; the prefilter is what makes
  that scale. Debounce regardless.
- **Delay.** Result times are `cueTime + subtitleDelayMs` — the sign follows
  `applySubtitleAutoSyncCue`, where `delay = capturedPosition − cue.start`. An addon
  subtitle for a different release lands near, not on, until auto-sync has run.
- **Clip range.** `firstCue.start − pad … lastCue.end + pad`, pad 250 ms, clamped at 0.
  Frame snapping needs the source fps that Phase 2 adds to `PlayerControlsState`.
- **Not covered.** CJK and other unspaced scripts fall back to exact substring only —
  the token passes assume whitespace. Worth a real test before promising it works.
