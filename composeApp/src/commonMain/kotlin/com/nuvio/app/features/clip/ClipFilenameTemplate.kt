package com.nuvio.app.features.clip

/**
 * How an exported clip is named.
 *
 * A clip's filename is the only label it carries once it leaves the app -- into
 * Finder, a chat window, an edit timeline -- so what goes in it is the user's
 * choice, not the exporter's. The template is a plain string with `{token}`
 * placeholders, which is the shape people already know from every other
 * renaming tool.
 *
 * Rendering lives in common code, and takes its facts as parameters rather than
 * reading a clock or a repository, so the whole of it is testable without a
 * platform.
 */
internal object ClipFilenameTemplate {

    /** What clips were named before the template existed; changing it renames nobody's old clips. */
    const val Default: String = "{title} {start}-{end}"

    /**
     * The tokens, in the order the settings screen lists them.
     *
     * Deliberately small: every token here is either already known at export
     * time or derived from it, so none of them can render empty and leave a
     * filename with a hole in it.
     */
    val Tokens: List<String> = listOf("title", "start", "end", "duration", "date", "time")

    /**
     * Substitutes [template]'s tokens and sanitizes the result into something a
     * filesystem will accept.
     *
     * Falls back to [Default] when the template is blank or renders to nothing
     * usable -- a user who clears the field wants the default back, not a file
     * called `.mp4`.
     */
    fun render(
        template: String,
        title: String,
        startMs: Long,
        endMs: Long,
        dateLabel: String,
        timeLabel: String,
    ): String {
        val safeTitle = title.ifBlank { "Clip" }
        val values = mapOf(
            "title" to safeTitle,
            "start" to formatClipFileTimeLabel(startMs),
            "end" to formatClipFileTimeLabel(endMs),
            "duration" to formatClipFileDurationLabel((endMs - startMs).coerceAtLeast(0L)),
            "date" to dateLabel,
            "time" to timeLabel,
        )
        val rendered = substitute(template.ifBlank { Default }, values)
        val sanitized = sanitize(rendered)
        if (sanitized.isNotBlank()) return sanitized
        return sanitize(substitute(Default, values)).ifBlank { "clip" }
    }

    /**
     * Single left-to-right pass, so a value that happens to contain `{title}`
     * -- an episode really can be called that -- is never itself substituted.
     * An unknown token is left standing rather than dropped, so a typo shows up
     * in the preview instead of silently vanishing from every filename. Its
     * braces do not survive [sanitize], so `{episode}` reaches the preview as
     * the bare word `episode` -- still visibly wrong, without leaving braces in
     * the filenames of a user who never notices.
     */
    private fun substitute(template: String, values: Map<String, String>): String = buildString {
        var index = 0
        while (index < template.length) {
            val open = template.indexOf('{', index)
            if (open < 0) {
                append(template, index, template.length)
                break
            }
            val close = template.indexOf('}', open + 1)
            if (close < 0) {
                append(template, index, template.length)
                break
            }
            append(template, index, open)
            val token = template.substring(open + 1, close)
            append(values[token.lowercase()] ?: template.substring(open, close + 1))
            index = close + 1
        }
    }

    /**
     * Keeps the characters every filesystem this app runs on accepts, and
     * collapses the runs that separators leave behind.
     *
     * Leading dots are dropped too: a name starting with one is hidden on Unix,
     * which for a file the user is about to go looking for is the same as
     * losing it. Dots and spaces are stripped together rather than in turn,
     * because `../../etc` collapses to `.. .. etc` and stripping only the first
     * run of dots would leave the name starting with one anyway.
     */
    fun sanitize(name: String): String = name
        .map { if (it.isLetterOrDigit() || it in ALLOWED_PUNCTUATION) it else ' ' }
        .joinToString("")
        .replace(Regex(" {2,}"), " ")
        .trim()
        .trimStart('.', ' ')
        .take(MAX_STEM_LENGTH)
        .trim()

    private const val MAX_STEM_LENGTH = 150
    private val ALLOWED_PUNCTUATION = setOf('.', '_', '-', ' ', '(', ')', '[', ']', '\'', '&', '+', ',')
}

/** `1h02m03s` / `4m07s`, the position a clip was cut from. */
internal fun formatClipFileTimeLabel(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0L)
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) {
        "${h}h${m.toString().padStart(2, '0')}m${s.toString().padStart(2, '0')}s"
    } else {
        "${m}m${s.toString().padStart(2, '0')}s"
    }
}

/**
 * `8s` / `1m12s`, how long a clip runs.
 *
 * Rounded to the nearest second rather than truncated: a 7.9s clip called `7s`
 * reads as a mistake.
 */
internal fun formatClipFileDurationLabel(ms: Long): String {
    val totalSeconds = ((ms.coerceAtLeast(0L) + 500) / 1000)
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return if (m > 0) "${m}m${s.toString().padStart(2, '0')}s" else "${s}s"
}
