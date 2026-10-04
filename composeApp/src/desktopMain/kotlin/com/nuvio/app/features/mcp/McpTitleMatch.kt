package com.nuvio.app.features.mcp

import java.text.Normalizer

/**
 * Whether a stream's file is the title it was offered for.
 *
 * Stream addons match loosely: asked for Spider-Man (2002), one returned
 * Spider-Man 2 and Spider-Man 3 alongside it, and nothing in a list of
 * `clippable: true` rows says which is which. A person reads the file name
 * and skips them; an assistant running unattended cuts the wrong film.
 *
 * Deliberately one-sided. It only speaks when the file name says something
 * that contradicts the title -- another year, a sequel number, another
 * episode -- and stays silent when it cannot tell, because a release named in
 * another language or with no name at all is still the right film.
 */
internal object McpTitleMatch {

    /** Why [fileName] looks like something other than what was asked for, or null when nothing says so. */
    fun mismatch(fileName: String, title: String, year: Int?, season: Int?, episode: Int?): String? {
        val name = normalize(fileName)
        if (name.isBlank()) return null

        if (season != null && episode != null) {
            val found = Episode.find(name) ?: return null
            val foundSeason = (found.groupValues[1].ifEmpty { found.groupValues[3] }).toIntOrNull()
            val foundEpisode = (found.groupValues[2].ifEmpty { found.groupValues[4] }).toIntOrNull()
            return if (foundSeason == season && foundEpisode == episode) null
            else "the file name says S${foundSeason}E$foundEpisode, not S${season}E$episode"
        }

        val titleTokens = normalize(title).split(' ').filter { it.isNotEmpty() }
        if (titleTokens.isEmpty()) return null
        // Spaces optional, because "Spider-Man" is as often written "SpiderMan".
        val titleAt = Regex(titleTokens.joinToString("""\s*""") { Regex.escape(it) }).find(name)
        val after = titleAt?.let { name.substring(it.range.last + 1).trim().split(' ').firstOrNull().orEmpty() }

        if (after != null && after in SequelMarks && titleTokens.last() != after) {
            return "the file name looks like a sequel (\"${titleTokens.joinToString(" ")} $after\")"
        }
        if (year != null) {
            val years = Year.findAll(name).map { it.value.toInt() }.toList()
            // One year either side: a film released in December is dated the next year by half its releases.
            if (years.isNotEmpty() && years.none { it in year - 1..year + 1 }) {
                return "the file name is dated ${years.first()}, not $year"
            }
        }
        return null
    }

    /** The first four-digit year in a release label such as `2002` or `2022-`. */
    fun yearOf(releaseInfo: String?): Int? = releaseInfo?.let { Year.find(it)?.value?.toIntOrNull() }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Combining, "")
            .lowercase()
            .replace(NonWord, " ")
            .trim()

    private val Combining = Regex("[\\u0300-\\u036F]")
    private val NonWord = Regex("[^\\p{L}\\p{N}]+")
    private val Year = Regex("""\b(?:19|20)\d{2}\b""")

    /** `s02e05`, or `2x05`. */
    private val Episode = Regex("""\bs(\d{1,2})\s?e(\d{1,3})\b|\b(\d{1,2})x(\d{2,3})\b""")

    private val SequelMarks = setOf("2", "3", "4", "5", "6", "7", "8", "9", "ii", "iii", "iv", "v", "vi", "part")
}
