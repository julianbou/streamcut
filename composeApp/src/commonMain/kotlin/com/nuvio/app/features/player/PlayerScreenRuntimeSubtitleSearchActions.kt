package com.nuvio.app.features.player

import com.nuvio.app.core.i18n.localizedNoSubtitleLinesFound
import com.nuvio.app.core.i18n.localizedSubtitleLinesLoadError
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import kotlinx.coroutines.launch

/**
 * The addon subtitle the search panel reads.
 *
 * Picked in the panel itself, so a subtitle does not have to be switched on -- and
 * on screen -- just to be searched. Until something is picked it follows the
 * subtitle that is playing, then the first one the addons offered.
 */
internal val PlayerScreenRuntime.subtitleSearchSource: AddonSubtitle?
    get() {
        val visible = visibleAddonSubtitles
        subtitleSearchSourceId?.let { id ->
            visible.firstOrNull { it.id == id || it.url == id }?.let { return it }
        }
        return selectedAddonSubtitle ?: visible.firstOrNull()
    }

/**
 * The loaded search state, but only when it belongs to the source search should be
 * reading. Between a new pick and its download finishing, the old file's cues must
 * not be matched against as if they were the new one's.
 */
internal val PlayerScreenRuntime.currentSubtitleSearchState: SubtitleSearchUiState
    get() {
        val sourceId = subtitleSearchSource?.id ?: return SubtitleSearchUiState()
        val state = subtitleSearchState
        return if (state.loadedSourceId == sourceId) state else SubtitleSearchUiState()
    }

/**
 * Subtitle delay only describes the file it was set against. When search reads a
 * different file from the one on screen, nothing is known about that file's offset,
 * so its times are used as they are.
 */
internal val PlayerScreenRuntime.subtitleSearchDelayMs: Int
    get() {
        val source = subtitleSearchSource ?: return 0
        return if (source.id == selectedAddonSubtitle?.id) subtitleDelayMs else 0
    }

internal fun PlayerScreenRuntime.openSubtitleSearch() {
    subtitleSearchOpen = true
    // Addon subtitles are only fetched up front in some startup modes, so the panel
    // can open onto an empty list. Ask for them; the list arriving is what triggers
    // loading the default source (see RenderPlayerRuntimeUi).
    if (addonSubtitles.isEmpty() && !isLoadingAddonSubtitles) fetchAddonSubtitlesForActiveItem()
    loadSubtitleSearchCues()
}

internal fun PlayerScreenRuntime.selectSubtitleSearchSource(index: Int) {
    val subtitle = visibleAddonSubtitles.getOrNull(index) ?: return
    subtitleSearchSourceId = subtitle.id
    loadSubtitleSearchCues()
}

internal fun PlayerScreenRuntime.loadSubtitleSearchCues() {
    val subtitle = subtitleSearchSource ?: return
    val current = subtitleSearchState
    if (current.loadedSourceId == subtitle.id && (current.isLoading || current.cues.isNotEmpty())) return

    subtitleSearchState = SubtitleSearchUiState(loadedSourceId = subtitle.id, isLoading = true)
    scope.launch {
        val result = runCatching {
            val body = httpGetTextWithHeaders(
                url = subtitle.url,
                headers = sanitizePlaybackHeaders(activeSourceHeaders),
            )
            PlayerSubtitleCueParser.parse(body, subtitle.url)
        }
        // Another source may have been picked while this one downloaded.
        if (subtitleSearchState.loadedSourceId != subtitle.id) return@launch
        subtitleSearchState = result.fold(
            onSuccess = { cues ->
                SubtitleSearchUiState(
                    loadedSourceId = subtitle.id,
                    cues = cues,
                    errorMessage = if (cues.isEmpty()) localizedNoSubtitleLinesFound() else null,
                )
            },
            onFailure = { error ->
                SubtitleSearchUiState(
                    loadedSourceId = subtitle.id,
                    errorMessage = error.message ?: localizedSubtitleLinesLoadError(),
                )
            },
        )
    }
}
