package com.nuvio.app.features.mcp

import com.nuvio.app.features.player.SubtitleSyncCue
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/** One moment in a film where a searched phrase is spoken. */
internal data class SubtitlePhraseHit(
    val firstCue: Int,
    val lastCue: Int,
    val score: Double,
    val isExact: Boolean,
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * Finds a line of dialogue in a subtitle file the way it is remembered rather
 * than the way it was written.
 *
 * A port of `player-ui/clip-search.js`, which stays the matcher for the player:
 * the control bridge there carries only numbers, so a typed query cannot reach
 * Kotlin. The MCP server has no such limit and no page to run JavaScript in, so
 * it needs the same algorithm on this side. The tuning constants are the ones
 * settled in `scripts/subtitle-search-prototype`; change them in both places.
 */
internal class SubtitlePhraseIndex(private val cues: List<SubtitleSyncCue>) {

    /** Every cue's normalized text joined by spaces, so a phrase split across a cue break is still one substring. */
    private val haystack: String

    /** Offset in [haystack] where each cue starts. */
    private val spanStarts: IntArray

    private val tokenTexts: List<String>
    private val tokenCues: IntArray

    /** In how many cues each token appears. */
    private val frequency: Map<String, Int>

    private val documentCount = max(1, cues.size)

    init {
        val builder = StringBuilder()
        val starts = IntArray(cues.size)
        val texts = ArrayList<String>()
        val owners = ArrayList<Int>()
        val counts = HashMap<String, Int>()

        cues.forEachIndexed { cueIndex, cue ->
            val normalized = normalize(cue.text)
            if (normalized.isEmpty()) {
                starts[cueIndex] = builder.length
                return@forEachIndexed
            }
            if (builder.isNotEmpty()) builder.append(' ')
            starts[cueIndex] = builder.length
            builder.append(normalized)

            val seen = HashSet<String>()
            for (token in normalized.split(' ')) {
                if (token.isEmpty()) continue
                texts += token
                owners += cueIndex
                seen += token
            }
            for (token in seen) counts[token] = (counts[token] ?: 0) + 1
        }

        haystack = builder.toString()
        spanStarts = starts
        tokenTexts = texts
        tokenCues = owners.toIntArray()
        frequency = counts
    }

    fun search(rawQuery: String, limit: Int = ResultLimit): List<SubtitlePhraseHit> {
        if (cues.isEmpty()) return emptyList()
        val query = normalize(rawQuery)
        if (query.isEmpty()) return emptyList()
        val queryTokens = query.split(' ').filter { it.isNotEmpty() }
        if (queryTokens.isEmpty()) return emptyList()

        val hits = HashMap<Long, Candidate>()
        fun record(firstCue: Int, lastCue: Int, score: Double, isExact: Boolean) {
            val key = firstCue.toLong() shl 32 or lastCue.toLong()
            val existing = hits[key]
            if (existing != null && existing.score >= score) return
            hits[key] = Candidate(firstCue, lastCue, score, isExact)
        }

        // Pass A -- exact substring over the whole haystack, which is what finds
        // a phrase that straddles a cue break.
        var at = haystack.indexOf(query)
        while (at >= 0) {
            record(cueAtOffset(at), cueAtOffset(at + query.length - 1), 1.0, true)
            at = haystack.indexOf(query, at + 1)
        }

        // Pass B -- fuzzy token windows, for a line remembered imperfectly.
        if (tokenTexts.isNotEmpty()) {
            // A window with no exact query token cannot clear the recall gate;
            // skipping those keeps a full-length film cheap.
            val querySet = queryTokens.toSet()
            val prefix = IntArray(tokenTexts.size + 1)
            for (i in tokenTexts.indices) {
                prefix[i + 1] = prefix[i] + if (tokenTexts[i] in querySet) 1 else 0
            }

            val smallest = max(1, queryTokens.size - 1)
            for (size in smallest..queryTokens.size + WindowSlack) {
                if (size > tokenTexts.size) break
                for (start in 0..tokenTexts.size - size) {
                    if (prefix[start + size] - prefix[start] == 0) continue
                    val firstCue = tokenCues[start]
                    val lastCue = tokenCues[start + size - 1]
                    if (lastCue - firstCue > MaxCueSpan) continue

                    val scored = scoreWindow(tokenTexts.subList(start, start + size), queryTokens) ?: continue
                    if (scored.recall < MinRecall) continue
                    if (scored.score < FuzzyThreshold) continue
                    record(firstCue, lastCue, scored.score, false)
                }
            }
        }

        val ordered = hits.values.sortedWith(
            compareByDescending<Candidate> { it.score }.thenBy { cues[it.firstCue].startTimeMs },
        )

        // The sliding window reports the same moment once per offset it fits at,
        // and a fuzzy window often overlaps a range an exact hit already
        // claimed. Best-first order means keeping any range that touches none
        // of the kept ones leaves exactly one entry per moment.
        val kept = ArrayList<SubtitlePhraseHit>()
        for (hit in ordered) {
            if (kept.size >= limit) break
            if (kept.any { hit.firstCue <= it.lastCue && it.firstCue <= hit.lastCue }) continue
            val range = cues.subList(hit.firstCue, hit.lastCue + 1)
            kept += SubtitlePhraseHit(
                firstCue = hit.firstCue,
                lastCue = hit.lastCue,
                score = hit.score,
                isExact = hit.isExact,
                startMs = range.first().startTimeMs,
                endMs = range.last().endTimeMs,
                text = range.joinToString(" ") { it.text },
            )
        }
        return kept
    }

    private fun cueAtOffset(offset: Int): Int {
        var low = 0
        var high = spanStarts.size - 1
        var best = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (spanStarts[mid] <= offset) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    /**
     * Inverse document frequency, so "the" cannot carry a match on its own --
     * scoring by raw token count let a query of ordinary words match any busy
     * line.
     */
    private fun weight(token: String): Double =
        ln(1.0 + documentCount.toDouble() / (1 + (frequency[token] ?: 0)))

    private fun scoreWindow(window: List<String>, query: List<String>): WindowScore? {
        val used = BooleanArray(window.size)
        var matchedWeight = 0.0
        var anyMatch = false

        for (queryToken in query) {
            var bestIndex = -1
            var bestQuality = 0.0
            for (i in window.indices) {
                if (used[i]) continue
                val quality = tokenQuality(queryToken, window[i])
                if (quality > bestQuality) {
                    bestQuality = quality
                    bestIndex = i
                }
            }
            if (bestIndex < 0) continue
            used[bestIndex] = true
            anyMatch = true
            matchedWeight += bestQuality * weight(queryToken)
        }
        if (!anyMatch) return null

        val queryWeight = query.sumOf { weight(it) }
        val windowWeight = window.sumOf { weight(it) }
        if (queryWeight <= 0.0 || windowWeight <= 0.0) return null

        val recall = matchedWeight / queryWeight
        return WindowScore(score = 0.8 * recall + 0.2 * (matchedWeight / windowWeight), recall = recall)
    }

    private data class Candidate(val firstCue: Int, val lastCue: Int, val score: Double, val isExact: Boolean)

    private data class WindowScore(val score: Double, val recall: Double)

    companion object {
        /** Blended score a fuzzy window must beat. */
        private const val FuzzyThreshold = 0.6

        /** Share of the query's weight a hit must carry, checked separately. */
        private const val MinRecall = 0.65

        /** A window may run this many tokens past the query: spoken lines are wordier. */
        private const val WindowSlack = 3

        /** Further apart than this and a window spans a scene change, not a phrase. */
        private const val MaxCueSpan = 2

        const val ResultLimit = 40

        private val Combining = Regex("[\\u0300-\\u036F]")
        private val Apostrophes = Regex("[\\u2019\\u02BC'`\\u00B4]")
        private val NonWord = Regex("[^\\p{L}\\p{N}]+")

        /**
         * Apostrophes are dropped rather than kept, so "can't" and "cant"
         * collapse to one token and it stops mattering which was typed. Letters
         * of any script survive; only Latin accents fold.
         */
        fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(Combining, "")
                .lowercase()
                .replace(Apostrophes, "")
                .replace(NonWord, " ")
                .trim()

        /** 0 when the tokens are unrelated, otherwise how much the match is worth. */
        private fun tokenQuality(a: String, b: String): Double {
            if (a == b) return 1.0
            val shorter = min(a.length, b.length)
            if (shorter >= 5 && (a.startsWith(b) || b.startsWith(a))) return 0.85
            // Six, not four: "rain" and "train" are one edit apart and unrelated.
            if (shorter >= 6 && levenshteinWithin(a, b, 1) <= 1) return 0.85
            return 0.0
        }

        private fun levenshteinWithin(a: String, b: String, maxDistance: Int): Int {
            if (abs(a.length - b.length) > maxDistance) return maxDistance + 1
            var previous = IntArray(b.length + 1) { it }
            var current = IntArray(b.length + 1)

            for (i in 1..a.length) {
                current[0] = i
                var rowMin = current[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = min(min(previous[j] + 1, current[j - 1] + 1), previous[j - 1] + cost)
                    if (current[j] < rowMin) rowMin = current[j]
                }
                if (rowMin > maxDistance) return maxDistance + 1
                val swap = previous
                previous = current
                current = swap
            }
            return previous[b.length]
        }
    }
}
