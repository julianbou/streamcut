package com.nuvio.app.features.clip

/**
 * The user's clip-filename template, cached in memory over [ClipStorage].
 *
 * Read on the export path, which runs on an IO thread once per clip, so it is
 * cached rather than hitting the preference store each time. It is a single
 * string with no per-profile scoping, so a plain field is the whole of the
 * state it needs.
 */
internal object ClipFilenameSettings {

    private var cached: String? = null
    private var loaded = false

    /** The template to render names with; never blank. */
    fun template(): String {
        if (!loaded) {
            cached = ClipStorage.loadFilenameTemplate()
            loaded = true
        }
        return cached?.takeIf { it.isNotBlank() } ?: ClipFilenameTemplate.Default
    }

    /** Whether the user has chosen a template of their own. */
    fun isDefault(): Boolean = template() == ClipFilenameTemplate.Default

    /** Sets the template; blank or the default value clears the stored preference. */
    fun setTemplate(template: String?) {
        val normalized = template?.trim()?.takeIf {
            it.isNotBlank() && it != ClipFilenameTemplate.Default
        }
        ClipStorage.saveFilenameTemplate(normalized)
        cached = normalized
        loaded = true
    }

    /**
     * What [template] would name a clip, for the settings preview.
     *
     * Uses a fixed example rather than the user's most recent clip: the preview
     * has to say something on a machine that has never exported one, and a
     * preview that changes under you is harder to read than one that does not.
     */
    fun preview(template: String): String = ClipFilenameTemplate.render(
        template = template,
        title = "Severance S02E05",
        startMs = 3_723_000L,
        endMs = 3_731_000L,
        dateLabel = ClipClock.localStamp(ClipClock.nowEpochMs()).date,
        timeLabel = ClipClock.localStamp(ClipClock.nowEpochMs()).time,
    ) + ".mp4"
}
