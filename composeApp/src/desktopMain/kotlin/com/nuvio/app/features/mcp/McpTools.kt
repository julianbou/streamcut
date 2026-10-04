package com.nuvio.app.features.mcp

import com.nuvio.app.core.deeplink.buildMetaDeepLinkUrl
import com.nuvio.app.core.deeplink.handleAppUrl
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.ManagedAddon
import com.nuvio.app.features.addons.buildAddonResourceUrl
import com.nuvio.app.features.addons.enabledAddons
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import com.nuvio.app.features.catalog.fetchCatalogPage
import com.nuvio.app.features.clip.ClipAspect
import com.nuvio.app.features.clip.ClipContentRef
import com.nuvio.app.features.clip.ClipEntry
import com.nuvio.app.features.clip.ClipExtractor
import com.nuvio.app.features.clip.ClipJob
import com.nuvio.app.features.clip.ClipLibrary
import com.nuvio.app.features.clip.ClipPlayerRequests
import com.nuvio.app.features.clip.ClipRepository
import com.nuvio.app.features.clip.ClipSaveTarget
import com.nuvio.app.features.clip.ClipStatus
import com.nuvio.app.features.clip.ClipSubtitleSelection
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.LocalDebridAvailabilityService
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.player.AddonSubtitle
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.PlayerSubtitleCueParser
import com.nuvio.app.features.player.SubtitleLanguageOption
import com.nuvio.app.features.player.SubtitleSyncCue
import com.nuvio.app.features.player.addonSubtitleRequests
import com.nuvio.app.features.player.loadAddonSubtitles
import com.nuvio.app.features.player.normalizeLanguageCode
import com.nuvio.app.features.player.sanitizePlaybackHeaders
import com.nuvio.app.features.streams.AddonStreamGroup
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.features.streams.StreamParser
import com.nuvio.app.features.streams.streamAddonInstanceId
import com.nuvio.app.features.streams.supportsStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What an assistant can do with StreamCut: find a title, find a line in it,
 * pick a source and cut a clip.
 *
 * The tools go through the same addon, debrid and export code the screens use,
 * but never through the screens' own repositories. [com.nuvio.app.features.search.SearchRepository]
 * and [com.nuvio.app.features.streams.StreamsRepository] each hold the single
 * state their screen renders, so a search made here would replace what the user
 * is looking at, and a stream load could auto-play. Everything below fetches
 * for itself and leaves the UI's state alone; the one exception is the clip
 * queue, which is shared on purpose so an export started here shows up in the
 * app like any other.
 *
 * Streams and subtitle files are handed out as short ids (`s12`, `sub3`)
 * rather than URLs: a resolved link is a credential for the user's debrid
 * account, and nothing an assistant does with it needs the link itself.
 */
internal class McpTools {

    private val titles = HandleCache<TitleInfo>("", MaxRememberedTitles)
    private val streams = HandleCache<StreamHandle>("s", MaxRememberedStreams)
    private val subtitles = HandleCache<SubtitleHandle>("sub", MaxRememberedSubtitles)

    val all: List<McpTool> = listOf(
        jsonTool(
            name = "search_titles",
            description = "Search the user's catalog addons for a film or series by name. " +
                "Returns ids to pass to the other tools.",
            inputSchema = schema(required = listOf("query")) {
                string("query", "Title to look for.")
                string("type", "Only return this type, e.g. movie or series.")
            },
            handler = ::searchTitles,
        ),
        jsonTool(
            name = "get_title",
            description = "Details of one title. For a series, lists its seasons, and the episodes of " +
                "`season` when given.",
            inputSchema = schema(required = listOf("type", "id")) {
                titleProperties()
                integer("season", "Season whose episodes to list.")
            },
            handler = ::getTitle,
        ),
        jsonTool(
            name = "list_subtitles",
            description = "Subtitle files the user's addons offer for a film or episode. Only needed to " +
                "choose a specific one; find_line picks one by itself.",
            inputSchema = schema(required = listOf("type", "id")) {
                targetProperties()
                string("language", "Language to keep, as a code or English name (en, spa, french).")
            },
            handler = ::listSubtitles,
        ),
        jsonTool(
            name = "find_line",
            description = "Find where a line of dialogue is spoken in a film or episode, by searching its " +
                "subtitles. Tolerates a misremembered quote. Returns the best matches with start_ms and " +
                "end_ms. A subtitle file is timed for whichever release its author had, and can sit most " +
                "of a minute away from a given stream: pass stream_id to have the matches re-timed against " +
                "that stream, and trust only matches whose `timing` is \"stream\" without looking first.",
            inputSchema = schema(required = listOf("type", "id", "query")) {
                targetProperties()
                string("query", "The line, as remembered. Use the language of the subtitles being searched.")
                string(
                    "language",
                    "Subtitle language to search (en, spa, french). Without it, the user's preferred " +
                        "language and English are tried in turn.",
                )
                string("subtitle_id", "Search this file from list_subtitles instead of picking one.")
                string(
                    "stream_id",
                    "The stream the clip will be cut from. The matches are then re-timed against that " +
                        "stream's own subtitle track, which is the only timing that is exact for it.",
                )
                integer("limit", "Most matches to return. Default $DefaultLineLimit.")
            },
            handler = ::findLine,
        ),
        jsonTool(
            name = "read_subtitles",
            description = "The subtitle lines between two times, for reading the dialogue around a moment " +
                "and choosing where a clip should start and end.",
            inputSchema = schema(required = listOf("subtitle_id", "from_ms", "to_ms")) {
                string("subtitle_id", "From find_line or list_subtitles.")
                integer("from_ms", "Start of the window, in milliseconds.")
                integer("to_ms", "End of the window, in milliseconds.")
            },
            handler = ::readSubtitles,
        ),
        jsonTool(
            name = "read_stream_subtitles",
            description = "The lines of a stream's own subtitle track between two times. Unlike a subtitle " +
                "file, these are timed for exactly this stream, so a range taken from them can be cut " +
                "without checking. Reads the window from the stream, so keep it to a few minutes. " +
                "probe_stream lists the tracks a stream has.",
            inputSchema = schema(required = listOf("stream_id", "from_ms", "to_ms")) {
                string("stream_id", "From list_streams.")
                integer("from_ms", "Start of the window, in milliseconds.")
                integer("to_ms", "End of the window, in milliseconds. At most ${MaxStreamSubtitleWindowMs / 60_000} minutes after from_ms.")
                string("language", "Which track, by language (en, spa, french). Default: the first text track.")
                integer("track", "Which track, by its number in probe_stream. Overrides language.")
            },
            handler = ::readStreamSubtitles,
        ),
        jsonTool(
            name = "list_streams",
            description = "Sources the user's addons offer for a film or episode. A clip is cut from one " +
                "of these, so call this before create_clip and pick a stream whose `clippable` is true. " +
                "A stream with a `warning` is probably a different film or episode: addons match loosely. " +
                "Prefer a small file (see approx_mbps): every read costs in proportion to its bitrate.",
            inputSchema = schema(required = listOf("type", "id")) {
                targetProperties()
            },
            handler = ::listStreams,
        ),
        jsonTool(
            name = "probe_stream",
            description = "What is inside a stream or a finished clip: duration, resolution, whether it is " +
                "HDR, its audio tracks with their languages, its subtitle tracks, and its chapters. Call " +
                "it before create_clip when a stream carries more than one language, to pick the audio.",
            inputSchema = schema {
                sourceProperties()
            },
            handler = ::probeStream,
        ),
        jsonTool(
            name = "detect_cuts",
            description = "Where the picture cuts to a new shot between two times, to the frame. The way " +
                "to start or end a clip exactly on a cut: one pass over the window, no images to look at. " +
                "At most ${MaxCutWindowMs / 1_000} seconds.",
            inputSchema = schema(required = listOf("from_ms", "to_ms")) {
                sourceProperties()
                integer("from_ms", "Start of the window, in milliseconds.")
                integer("to_ms", "End of the window, in milliseconds.")
                string(
                    "sensitivity",
                    "normal finds hard cuts. high also catches fast pans and flashes. low keeps only the " +
                        "most abrupt. Default: normal.",
                    enum = CutThresholds.keys.toList(),
                )
            },
            handler = ::detectCuts,
        ),
        McpTool(
            name = "get_contact_sheet",
            description = "One image with many small frames, each stamped with its time, evenly spaced " +
                "between two times. For finding a moment nobody speaks in: scan a stretch of the film at a " +
                "glance, then look closer with get_frames. Each cell is a separate read, so a sheet over a " +
                "long stretch of a large stream is slow.",
            inputSchema = schema(required = listOf("from_ms", "to_ms")) {
                sourceProperties()
                integer("from_ms", "Time of the first cell, in milliseconds.")
                integer("to_ms", "Time of the last cell, in milliseconds.")
                integer("columns", "Cells per row, 2 to $MaxSheetColumns. Default $DefaultSheetColumns.")
                integer("rows", "Rows, 1 to $MaxSheetRows. Default $DefaultSheetRows.")
            },
            handler = ::getContactSheet,
        ),
        McpTool(
            name = "get_frames",
            description = "Look at the picture: returns still frames as images, each labelled with its " +
                "time. Use it to find the exact moment a shot starts or ends before cutting, to check " +
                "that a find_line match lines up with this stream, or to check a finished clip. Give " +
                "either at_ms (specific moments) or from_ms, to_ms and count (evenly spaced, both ends " +
                "included). A from/to window of ${SweepWindowMs / 1_000} s or less is read in one pass, " +
                "which is the cheap way to step through a moment; otherwise each frame downloads a " +
                "second or two of video, so prefer a smaller stream over a remux.",
            inputSchema = schema {
                sourceProperties()
                integerArray("at_ms", "Moments to grab, in milliseconds. At most $MaxFrames.")
                integer("from_ms", "First frame of an evenly spaced run, in milliseconds.")
                integer("to_ms", "Last frame of the run, in milliseconds.")
                integer("count", "How many frames in the run, 2 to $MaxFrames.")
                integer("width", "Frame width in pixels, $MinFrameWidth to $MaxFrameWidth. Default $DefaultFrameWidth.")
            },
            handler = ::getFrames,
        ),
        jsonTool(
            name = "create_clip",
            description = "Cut a clip out of a stream and save it as an MP4, in the user's clips folder " +
                "unless save_dir says otherwise. Only the selected range is downloaded. Returns a job_id " +
                "straight away; the export runs in the background, so follow it with get_clip. At most " +
                "${MaxClipMs / 60_000} minutes long. A stream with several audio tracks is cut with its " +
                "first unless audio_language or audio_track says otherwise.",
            inputSchema = schema(required = listOf("stream_id", "start_ms", "end_ms")) {
                string("stream_id", "From list_streams.")
                integer("start_ms", "Where the clip starts in the source, in milliseconds.")
                integer("end_ms", "Where the clip ends in the source, in milliseconds.")
                string("aspect", "Crop the frame to this shape. Default: source.", enum = AspectNames.keys.toList())
                integer("max_size_mb", "Keep the file under this size, trading quality. Default: no cap.")
                string("audio_language", "Use the audio track in this language (en, spa, french).")
                integer("audio_track", "Use this audio track, by its number in probe_stream. Overrides audio_language.")
                string("burn_subtitle_id", "Render this subtitle file into the picture.")
                integer("burn_stream_subtitle_track", "Render one of the stream's own subtitle tracks, by its number in probe_stream.")
                integer(
                    "subtitle_delay_ms",
                    "Shift the burned subtitle by this much; positive is later. For a subtitle file, " +
                        "find_line's stream_offset_ms is the value that lines it up with the stream.",
                )
                string("save_dir", "Folder to save into instead of the clips folder. Must exist.")
                string("file_name", "Name for the file, without extension. Default: the user's naming template.")
            },
            effect = McpEffect.Changes,
            handler = ::createClip,
        ),
        jsonTool(
            name = "get_clip",
            description = "Status of a clip export: queued, running, completed, failed or cancelled, with " +
                "progress and, once completed, the file path. Pass wait_seconds to hold the call until " +
                "the export settles instead of polling.",
            inputSchema = schema(required = listOf("job_id")) {
                string("job_id", "From create_clip.")
                integer("wait_seconds", "Wait up to this long for the export to finish. Max $MaxWaitSeconds.")
            },
            handler = ::getClip,
        ),
        jsonTool(
            name = "cancel_clip",
            description = "Cancel a clip export that is queued or running.",
            inputSchema = schema(required = listOf("job_id")) {
                string("job_id", "From create_clip.")
            },
            effect = McpEffect.Changes,
            handler = ::cancelClip,
        ),
        jsonTool(
            name = "list_clips",
            description = "Clips already saved on this computer, newest first. Each has a job_id that " +
                "get_clip, get_frames, probe_stream, rename_clip and delete_clip accept.",
            inputSchema = schema {
                integer("limit", "Most clips to return. Default $DefaultClipLimit.")
            },
            handler = ::listClips,
        ),
        jsonTool(
            name = "rename_clip",
            description = "Give a saved clip a new file name, in the folder it is already in.",
            inputSchema = schema(required = listOf("job_id", "file_name")) {
                string("job_id", "From create_clip or list_clips.")
                string("file_name", "The new name, without extension.")
            },
            effect = McpEffect.Changes,
            handler = ::renameClip,
        ),
        jsonTool(
            name = "delete_clip",
            description = "Remove a saved clip. It leaves the library at once and its file goes to the " +
                "system Trash a few seconds later, where the user can still get it back.",
            inputSchema = schema(required = listOf("job_id")) {
                string("job_id", "From create_clip or list_clips.")
            },
            effect = McpEffect.Destroys,
            handler = ::deleteClip,
        ),
        jsonTool(
            name = "open_in_player",
            description = "Open StreamCut's player on a stream, for the user to watch or cut by hand. With " +
                "start_ms and end_ms the In and Out points are already marked on that range, so the user " +
                "only has to check it and export: the way to propose a cut instead of making it.",
            inputSchema = schema(required = listOf("stream_id")) {
                string("stream_id", "From list_streams.")
                integer("start_ms", "Where the proposed clip starts, in milliseconds.")
                integer("end_ms", "Where the proposed clip ends, in milliseconds.")
            },
            effect = McpEffect.Changes,
            handler = ::openInPlayer,
        ),
        jsonTool(
            name = "open_in_app",
            description = "Bring a title up in the StreamCut window, for the user to look at or cut by hand.",
            inputSchema = schema(required = listOf("type", "id")) {
                titleProperties()
            },
            effect = McpEffect.Changes,
            handler = ::openInApp,
        ),
    )

    // --- titles ---

    private suspend fun searchTitles(args: JsonObject): JsonElement {
        val query = args.requireString("query")
        val typeFilter = args.string("type")
        val addons = readyAddons()
        if (addons.isEmpty()) throw McpToolException(NoAddonsMessage)

        val requests = addons.flatMap { addon ->
            val manifest = addon.manifest ?: return@flatMap emptyList()
            manifest.catalogs
                .filter { catalog ->
                    // A catalog that demands another extra (a genre, say) cannot be searched by name alone.
                    catalog.extra.any { it.name == "search" } &&
                        catalog.extra.none { it.isRequired && it.name != "search" } &&
                        (typeFilter == null || catalog.type == typeFilter)
                }
                .map { catalog -> Triple(addon.displayTitle, manifest.transportUrl, catalog) }
        }
        if (requests.isEmpty()) {
            throw McpToolException("None of the installed addons has a searchable catalog.")
        }

        val pages = coroutineScope {
            requests.map { (addonName, transportUrl, catalog) ->
                async {
                    val page = attempt(AddonTimeoutMs) {
                        fetchCatalogPage(
                            manifestUrl = transportUrl,
                            type = catalog.type,
                            catalogId = catalog.id,
                            search = query,
                        )
                    }
                    addonName to page?.items.orEmpty()
                }
            }.awaitAll()
        }

        val seen = HashSet<String>()
        val results = pages
            .flatMap { (addonName, items) -> items.map { addonName to it } }
            .filter { (_, item) -> seen.add("${item.type}:${item.id}") }
            .take(MaxSearchResults)
        results.forEach { (_, item) ->
            titles.put("${item.type}:${item.id}", TitleInfo(item.name, item.poster.orEmpty()))
        }

        return buildJsonObject {
            put(
                "results",
                buildJsonArray {
                    results.forEach { (addonName, item) ->
                        add(
                            buildJsonObject {
                                put("type", item.type)
                                put("id", item.id)
                                put("name", item.name)
                                putIfPresent("year", item.releaseInfo)
                                put("addon", addonName)
                            },
                        )
                    }
                },
            )
            if (results.isEmpty()) put("note", "No title matched. Try a shorter or differently spelled query.")
        }
    }

    private suspend fun getTitle(args: JsonObject): JsonElement {
        val type = args.requireString("type")
        val id = args.requireString("id")
        val season = args.int("season")
        val meta = fetchMeta(type, id) ?: throw McpToolException("No addon returned details for $type $id.")

        val episodes = meta.videos.filter { it.season != null && it.episode != null }
        return buildJsonObject {
            put("type", meta.type)
            put("id", meta.id)
            put("name", meta.name)
            putIfPresent("year", meta.releaseInfo)
            putIfPresent("runtime", meta.runtime)
            putIfPresent("description", meta.description?.take(MaxDescriptionChars))
            if (meta.genres.isNotEmpty()) put("genres", JsonArray(meta.genres.map(::JsonPrimitive)))
            if (episodes.isNotEmpty()) {
                put(
                    "seasons",
                    buildJsonArray {
                        episodes.groupBy { it.season ?: 0 }.toSortedMap().forEach { (number, list) ->
                            add(
                                buildJsonObject {
                                    put("season", number)
                                    put("episodes", list.size)
                                },
                            )
                        }
                    },
                )
                if (season != null) {
                    put(
                        "episodes",
                        buildJsonArray {
                            episodes.filter { it.season == season }.sortedBy { it.episode }.forEach { video ->
                                add(
                                    buildJsonObject {
                                        put("season", video.season)
                                        put("episode", video.episode)
                                        put("title", video.title)
                                        putIfPresent("released", video.released?.take(10))
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    private suspend fun openInApp(args: JsonObject): JsonElement {
        val type = args.requireString("type")
        val id = args.requireString("id")
        handleAppUrl(buildMetaDeepLinkUrl(type = type, id = id))
        return buildJsonObject { put("opened", true) }
    }

    private suspend fun openInPlayer(args: JsonObject): JsonElement {
        val handle = streamHandle(args.requireString("stream_id"))
        val startMs = args.long("start_ms")
        val endMs = args.long("end_ms")
        if ((startMs == null) != (endMs == null)) throw McpToolException("Give both start_ms and end_ms, or neither.")
        if (startMs != null && endMs != null) {
            if (startMs < 0) throw McpToolException("start_ms cannot be negative.")
            if (endMs <= startMs) throw McpToolException("end_ms must be after start_ms.")
        }

        val source = handle.playableSource()
        val stream = handle.stream
        val content = handle.target.content
        ClipPlayerRequests.open(
            launch = PlayerLaunch(
                profileId = ProfileScopedKey.ScopeId,
                title = content.title,
                sourceUrl = source.location,
                sourceHeaders = source.headers,
                externalSubtitles = stream.externalSubtitles,
                streamType = stream.streamType,
                poster = content.posterUrl.ifBlank { null },
                seasonNumber = content.seasonNumber,
                episodeNumber = content.episodeNumber,
                streamTitle = stream.streamLabel,
                streamSubtitle = stream.streamSubtitle,
                bingeGroup = stream.behaviorHints.bingeGroup,
                providerName = stream.addonName,
                providerAddonId = stream.addonId,
                contentType = handle.target.type,
                videoId = handle.target.videoId,
                parentMetaId = handle.target.titleId,
                parentMetaType = handle.target.type,
                // So the picture is already on the range even before the page marks it.
                initialPositionMs = startMs ?: 0L,
            ),
            startMs = startMs,
            endMs = endMs,
        )
        return buildJsonObject {
            put("opened", content.label)
            if (startMs != null) put("note", "The range is marked in the player; nothing is exported until the user does it.")
        }
    }

    // --- subtitles ---

    private suspend fun listSubtitles(args: JsonObject): JsonElement {
        val target = resolveTarget(args)
        val offered = loadSubtitles(target).filterByLanguage(args.string("language"))
        return buildJsonObject {
            put("subtitles", buildJsonArray { offered.take(MaxSubtitleResults).forEach { add(it.toJson()) } })
            if (offered.isEmpty()) put("note", NoSubtitlesMessage)
        }
    }

    private suspend fun findLine(args: JsonObject): JsonElement {
        val query = args.requireString("query")
        val limit = (args.int("limit") ?: DefaultLineLimit).coerceIn(1, SubtitlePhraseIndex.ResultLimit)
        val language = args.string("language")
        val stream = args.string("stream_id")?.let(::streamHandle)

        val candidates = args.string("subtitle_id")?.let { listOf(subtitleHandle(it)) } ?: run {
            // The stream already says which film or episode this is about.
            val target = stream?.target ?: resolveTarget(args)
            val offered = loadSubtitles(target)
            if (offered.isEmpty()) throw McpToolException(NoSubtitlesMessage)
            searchCandidates(offered, language).ifEmpty {
                throw McpToolException(
                    "No subtitles in \"$language\" for this title. Call list_subtitles to see what is offered.",
                )
            }
        }

        // The first file that has the line wins. A file that will not download
        // or parse is one fewer to try, not the end of the search.
        var chosen: SubtitleHandle? = null
        var hits: List<SubtitlePhraseHit> = emptyList()
        val tried = ArrayList<SubtitleHandle>()
        for (candidate in candidates) {
            val found = try {
                candidate.index().search(query, limit)
            } catch (error: McpToolException) {
                continue
            }
            tried += candidate
            if (chosen == null || found.isNotEmpty()) {
                chosen = candidate
                hits = found
            }
            if (found.isNotEmpty()) break
        }
        val searched = chosen ?: throw McpToolException("None of the subtitle files could be read. Try list_subtitles.")
        val retimed = if (stream != null && hits.isNotEmpty()) retimeOnStream(stream, searched, query, hits) else null

        return buildJsonObject {
            put("subtitle", searched.toJson())
            put(
                "matches",
                buildJsonArray {
                    hits.forEachIndexed { index, hit ->
                        val exact = retimed?.timings?.get(index)
                        add(
                            buildJsonObject {
                                put("start_ms", exact?.first ?: hit.startMs)
                                put("end_ms", exact?.second ?: hit.endMs)
                                put("at", formatTimestamp(exact?.first ?: hit.startMs))
                                put("text", hit.text.singleSpaced())
                                put("exact", hit.isExact)
                                put("score", (hit.score * 100).toInt() / 100.0)
                                put("timing", if (exact != null) "stream" else "subtitle_file")
                            },
                        )
                    }
                },
            )
            retimed?.offsetMs?.let { put("stream_offset_ms", it) }
            val notes = listOfNotNull(
                retimed?.note,
                TimingCaveat.takeIf { hits.isNotEmpty() && stream == null },
                if (hits.isNotEmpty()) {
                    null
                } else {
                    "Nothing close to that in ${tried.joinToString { it.subtitle.language }.ifEmpty { "the subtitles" }}. " +
                        "Try fewer words, name the language the line is in, or pick a file from list_subtitles."
                },
            )
            if (notes.isNotEmpty()) put("note", notes.joinToString(" "))
        }
    }

    /**
     * Which files to search when none was named, best guess first.
     *
     * Not simply the first one offered: addons list their files in no useful
     * order, and for one film that first file was Dutch -- a search in English
     * came back empty with nothing to say why. Without a language, the user's
     * own subtitle language is tried, then this machine's, then English, which
     * between them cover the language a line is most likely remembered in.
     */
    private fun searchCandidates(offered: List<SubtitleHandle>, language: String?): List<SubtitleHandle> {
        if (language != null) return offered.filterByLanguage(language).take(FilesPerLanguage + 1)
        PlayerSettingsRepository.ensureLoaded()
        val preferred = PlayerSettingsRepository.uiState.value.preferredSubtitleLanguage
            .takeUnless { it == SubtitleLanguageOption.NONE || it == SubtitleLanguageOption.FORCED }
            ?.let { if (it == SubtitleLanguageOption.DEVICE) Locale.getDefault().language else it }
        val languages = listOfNotNull(preferred, Locale.getDefault().language, "en")
            .mapNotNull { normalizeLanguageCode(it) }
            .distinct()
        return languages.flatMap { offered.filterByLanguage(it).take(FilesPerLanguage) }
            .ifEmpty { offered.take(FilesPerLanguage) }
    }

    private class Retiming(val timings: Map<Int, Pair<Long, Long>>, val offsetMs: Long?, val note: String?)

    /**
     * Finds the same lines in the stream's own subtitle track, whose timing is
     * the stream's by construction.
     *
     * The first match is looked for across a wide window, since nothing is
     * known yet about how far the file is from the stream. Its offset then
     * tells where the others are, because two releases of one film differ by a
     * shift, so they need only a narrow look each.
     */
    private suspend fun retimeOnStream(
        stream: StreamHandle,
        searched: SubtitleHandle,
        query: String,
        hits: List<SubtitlePhraseHit>,
    ): Retiming {
        val unverified = "These times come from the subtitle file, not the stream, and can be off by up to a " +
            "minute: check with get_frames before cutting."
        val source = stream.playableSource()
        val layout = attempt(ProbeTimeoutMs) { stream.layout(source) }
            ?: return Retiming(emptyMap(), null, "The stream could not be probed. $unverified")
        val track = layout.textTrackIn(searched.subtitle.language)
            ?: return Retiming(
                emptyMap(),
                null,
                "This stream has no text subtitle track in ${searched.subtitle.language} " +
                    "(it has: ${layout.subtitles.describe()}). $unverified",
            )
        stream.bytesFor(layout, 2 * RetimeWideMs)?.let { bytes ->
            if (bytes > MaxRetimeBytes) {
                return Retiming(
                    emptyMap(),
                    null,
                    "This stream is too large to scan for the line (about ${bytes / 1_000_000} MB per look). " +
                        "Pick a smaller stream, or: $unverified",
                )
            }
        }

        val timings = HashMap<Int, Pair<Long, Long>>()
        var offset: Long? = null
        for ((index, hit) in hits.take(MaxRetimedHits).withIndex()) {
            val expected = hit.startMs + (offset ?: 0L)
            val reach = if (offset == null) RetimeWideMs else RetimeNarrowMs
            val cues = attempt(WindowTimeoutMs) {
                McpMediaReader.embeddedCues(source, track.index, (expected - reach).coerceAtLeast(0L), hit.endMs + (offset ?: 0L) + reach)
            }.orEmpty()
            val found = SubtitlePhraseIndex(cues).search(query, 5).minByOrNull { kotlin.math.abs(it.startMs - expected) }
                ?: continue
            timings[index] = found.startMs to found.endMs
            if (offset == null) offset = found.startMs - hit.startMs
        }
        val note = when {
            timings.isEmpty() -> "The line was not found in the stream's own ${track.language} track. $unverified"
            timings.size < hits.size -> "Matches whose timing is \"subtitle_file\" were not checked against the stream."
            else -> null
        }
        return Retiming(timings, offset, note)
    }

    private suspend fun readSubtitles(args: JsonObject): JsonElement {
        val handle = subtitleHandle(args.requireString("subtitle_id"))
        val fromMs = args.requireLong("from_ms")
        val toMs = args.requireLong("to_ms")
        if (toMs <= fromMs) throw McpToolException("to_ms must be after from_ms.")

        val lines = handle.cues().filter { it.endTimeMs > fromMs && it.startTimeMs < toMs }
        return buildJsonObject {
            put(
                "lines",
                buildJsonArray {
                    lines.take(MaxTranscriptLines).forEach { cue ->
                        add(
                            buildJsonObject {
                                put("start_ms", cue.startTimeMs)
                                put("end_ms", cue.endTimeMs)
                                put("text", cue.text.singleSpaced())
                            },
                        )
                    }
                },
            )
            if (lines.size > MaxTranscriptLines) {
                put("note", "Showing the first $MaxTranscriptLines of ${lines.size} lines. Ask for a shorter window.")
            }
        }
    }

    private suspend fun readStreamSubtitles(args: JsonObject): JsonElement {
        val stream = streamHandle(args.requireString("stream_id"))
        val fromMs = args.requireLong("from_ms")
        val toMs = args.requireLong("to_ms")
        if (fromMs < 0) throw McpToolException("Times cannot be negative.")
        if (toMs <= fromMs) throw McpToolException("to_ms must be after from_ms.")
        if (toMs - fromMs > MaxStreamSubtitleWindowMs) {
            throw McpToolException("At most ${MaxStreamSubtitleWindowMs / 60_000} minutes a call; ask for a shorter window.")
        }

        val source = stream.playableSource()
        val layout = stream.layout(source)
        val texts = layout.subtitles.filter { it.isText }
        val wanted = args.int("track")
        val language = args.string("language")
        val track = when {
            wanted != null -> texts.firstOrNull { it.index == wanted }
                ?: throw McpToolException("Track $wanted is not a text subtitle track. This stream has: ${layout.subtitles.describe()}.")
            language != null -> layout.textTrackIn(language)
                ?: throw McpToolException("No text subtitle track in \"$language\". This stream has: ${layout.subtitles.describe()}.")
            else -> texts.firstOrNull { !it.isForced } ?: texts.firstOrNull()
                ?: throw McpToolException(
                    "This stream has no text subtitle track (it has: ${layout.subtitles.describe()}). " +
                        "Use find_line and read_subtitles with a subtitle file instead.",
                )
        }

        val cues = McpMediaReader.embeddedCues(source, track.index, fromMs, toMs)
        return buildJsonObject {
            put("track", track.toJson())
            put(
                "lines",
                buildJsonArray {
                    cues.take(MaxTranscriptLines).forEach { cue ->
                        add(
                            buildJsonObject {
                                put("start_ms", cue.startTimeMs)
                                put("end_ms", cue.endTimeMs)
                                put("text", cue.text.singleSpaced())
                            },
                        )
                    }
                },
            )
            if (cues.isEmpty()) put("note", "Nobody speaks in that window, according to this track.")
        }
    }

    private suspend fun loadSubtitles(target: Target): List<SubtitleHandle> {
        readyAddons()
        val requests = addonSubtitleRequests(target.type, target.videoId)
        return loadAddonSubtitles(requests).map { subtitle ->
            val key = "${target.videoId}|${subtitle.url}"
            subtitles.getByKey(key) ?: SubtitleHandle(subtitle).also { it.id = subtitles.put(key, it) }
        }
    }

    private fun subtitleHandle(id: String): SubtitleHandle =
        subtitles.get(id) ?: throw McpToolException(
            "Unknown subtitle_id \"$id\". Ids come from find_line or list_subtitles and do not outlive the app.",
        )

    private fun List<SubtitleHandle>.filterByLanguage(language: String?): List<SubtitleHandle> {
        if (language == null) return this
        val wanted = normalizeLanguageCode(language) ?: language.lowercase()
        return filter { (normalizeLanguageCode(it.subtitle.language) ?: it.subtitle.language.lowercase()) == wanted }
    }

    // --- streams ---

    private suspend fun listStreams(args: JsonObject): JsonElement {
        val target = resolveTarget(args)
        val addons = readyAddons().mapNotNull { addon ->
            val manifest = addon.manifest ?: return@mapNotNull null
            if (!manifest.supportsStream(target.type, target.videoId)) return@mapNotNull null
            addon to manifest
        }
        if (addons.isEmpty()) {
            throw McpToolException("None of the installed addons provides streams for this ${target.type}.")
        }

        val groups = coroutineScope {
            addons.map { (addon, manifest) ->
                async {
                    val name = addon.displayTitle.ifBlank { manifest.name }
                    val id = addon.streamAddonInstanceId(manifest.id)
                    val found = attempt(AddonTimeoutMs) {
                        val url = buildAddonResourceUrl(
                            manifestUrl = manifest.transportUrl,
                            resource = "stream",
                            type = target.type,
                            id = target.videoId,
                        )
                        StreamParser.parse(
                            payload = fetchAddonResponseText(url),
                            addonName = name,
                            addonId = id,
                            addonLogo = manifest.logoUrl,
                        )
                    }
                    AddonStreamGroup(addonName = name, addonId = id, streams = found.orEmpty())
                }
            }.awaitAll()
        }
        // Says which torrents the user's debrid account already has, which is
        // what decides whether a torrent-only stream can be cut at all.
        val annotated = attempt(DebridCheckTimeoutMs) {
            LocalDebridAvailabilityService.annotateCachedAvailability(groups)
        } ?: groups

        val found = annotated.flatMap { it.streams }
        val rows = found.map { stream ->
            StreamRow(
                stream = stream,
                source = stream.clipSource(),
                warning = stream.behaviorHints.filename?.let { fileName ->
                    McpTitleMatch.mismatch(
                        fileName = fileName,
                        title = target.content.title,
                        year = target.year,
                        season = target.content.seasonNumber,
                        episode = target.content.episodeNumber,
                    )
                },
            )
        }
        // What can be cut and is what was asked for comes first, so that taking
        // the top row is a sound default.
        val listed = rows.sortedBy { (if (it.source == null) 2 else 0) + (if (it.warning != null) 1 else 0) }
            .take(MaxStreamResults)
        val doubtful = listed.count { it.warning != null }
        return buildJsonObject {
            put("title", target.content.label)
            put(
                "streams",
                buildJsonArray {
                    listed.forEach { row ->
                        val stream = row.stream
                        // The same stream keeps its id across calls, so listing
                        // again does not orphan an id already in use.
                        val key = listOf(
                            target.videoId, stream.addonId, stream.url ?: stream.infoHash,
                            stream.fileIdx, stream.behaviorHints.filename,
                        ).joinToString("|")
                        add(
                            buildJsonObject {
                                put("stream_id", streams.idForKey(key) ?: streams.put(key, StreamHandle(stream, target)))
                                put("addon", stream.addonName)
                                putIfPresent("name", stream.name?.singleLine())
                                putIfPresent(
                                    "description",
                                    (stream.description ?: stream.title)?.singleLine()?.take(MaxDescriptionChars),
                                )
                                putIfPresent("filename", stream.behaviorHints.filename)
                                stream.behaviorHints.videoSize?.let { size ->
                                    put("size_bytes", size)
                                    target.runtimeMinutes?.takeIf { it > 0 }?.let { minutes ->
                                        put("approx_mbps", (size * 8 / (minutes * 60L) / 100_000) / 10.0)
                                    }
                                }
                                put("clippable", row.source != null)
                                if (row.source != null) put("source", row.source) else put("why_not", stream.notClippableReason())
                                row.warning?.let { put("warning", "Probably not ${target.content.label}: $it.") }
                            },
                        )
                    }
                },
            )
            val notes = listOfNotNull(
                "Showing ${listed.size} of ${found.size} streams.".takeIf { found.size > listed.size },
                "$doubtful of these look like a different title and are listed last.".takeIf { doubtful > 0 },
                "The addons returned no streams for this title.".takeIf { found.isEmpty() },
            )
            if (notes.isNotEmpty()) put("note", notes.joinToString(" "))
        }
    }

    private class StreamRow(val stream: StreamItem, val source: String?, val warning: String?)

    /** How a clip would read this stream, or null when it cannot. */
    private fun StreamItem.clipSource(): String? = when {
        playableDirectUrl != null -> "direct"
        DirectDebridPlaybackResolver.shouldResolveToPlayableStream(this) -> "debrid"
        else -> null
    }

    private fun StreamItem.notClippableReason(): String = when {
        isTorrentStream -> "Torrent only. Cutting from a torrent needs it open in the player; " +
            "with a debrid service configured it could be read directly."
        externalOpenUrl != null -> "Opens in an external site, not a media file."
        else -> "No playable link."
    }

    // --- clips ---

    private suspend fun createClip(args: JsonObject): JsonElement {
        if (!ClipRepository.isSupported) throw McpToolException("Clip export is not available on this platform.")
        val handle = streamHandle(args.requireString("stream_id"))
        val startMs = args.requireLong("start_ms")
        val endMs = args.requireLong("end_ms")
        if (startMs < 0) throw McpToolException("start_ms cannot be negative.")
        if (endMs <= startMs) throw McpToolException("end_ms must be after start_ms.")
        if (endMs - startMs > MaxClipMs) {
            throw McpToolException("A clip can be at most ${MaxClipMs / 60_000} minutes; this range is longer.")
        }
        val aspect = args.string("aspect")?.let { name ->
            AspectNames[name] ?: throw McpToolException("aspect must be one of ${AspectNames.keys.joinToString()}.")
        } ?: ClipAspect.Source
        val delayMs = args.int("subtitle_delay_ms") ?: 0
        val fileSubtitle = args.string("burn_subtitle_id")
        val streamSubtitle = args.int("burn_stream_subtitle_track")
        if (fileSubtitle != null && streamSubtitle != null) {
            throw McpToolException("Burn one subtitle: burn_subtitle_id or burn_stream_subtitle_track, not both.")
        }
        val saveTo = saveTarget(args.string("save_dir"), args.string("file_name"))

        val target = handle.target
        val source = handle.playableSource()
        // Best effort: a clip of a stream that will not probe is still a clip,
        // it is only the choice of audio that cannot be made for it.
        val layout = attempt(ProbeTimeoutMs) { handle.layout(source) }
        val audio = chooseAudio(layout, args.int("audio_track"), args.string("audio_language"))
        val subtitle = when {
            fileSubtitle != null -> ClipSubtitleSelection.External(subtitleHandle(fileSubtitle).subtitle.url, delayMs)
            streamSubtitle != null -> {
                if (layout != null && layout.subtitles.none { it.index == streamSubtitle }) {
                    throw McpToolException("No subtitle track $streamSubtitle. This stream has: ${layout.subtitles.describe()}.")
                }
                ClipSubtitleSelection.Embedded(streamSubtitle, delayMs)
            }
            else -> null
        }

        ClipRepository.startClip(
            sourceUrl = source.location,
            sourceHeaders = source.headers,
            content = target.content,
            startMs = startMs,
            endMs = endMs,
            audioTrackIndex = audio?.index ?: -1,
            subtitle = subtitle,
            aspect = aspect,
            targetSizeMb = (args.int("max_size_mb") ?: 0).coerceAtLeast(0),
            saveTo = saveTo,
        )
        // startClip queues on the main thread and returns nothing, so the job is
        // recognised by what it was asked for. An identical range already in
        // the queue is deliberately not duplicated, and is the one found here.
        val job = withTimeoutOrNull(JobAppearTimeoutMs) {
            ClipRepository.jobs.first { jobs -> jobs.any { it.matches(target.content, startMs, endMs) } }
                .first { it.matches(target.content, startMs, endMs) }
        } ?: throw McpToolException("StreamCut did not accept the export.")

        return JsonObject(
            job.toJson() + buildJsonObject {
                val tracks = layout?.audio.orEmpty()
                if (tracks.size > 1) {
                    val used = audio ?: tracks.first()
                    put("audio", used.toJson())
                    // Said every time, not only when it was left to chance: a
                    // dubbed clip looks fine until someone plays it.
                    put(
                        "audio_note",
                        "This stream has ${tracks.size} audio tracks (${tracks.joinToString { it.language.ifBlank { "unlabelled" } }}); " +
                            "the clip uses track ${used.index}" +
                            if (audio == null) ", its first. Pass audio_language to choose another." else ".",
                    )
                }
            },
        )
    }

    /** The audio track a clip was asked to use, or null to leave the exporter's default (the first). */
    private fun chooseAudio(layout: StreamLayout?, track: Int?, language: String?): StreamLayout.AudioTrack? {
        if (track == null && language == null) return null
        val tracks = layout?.audio
            ?: throw McpToolException("The stream could not be probed, so its audio tracks are unknown. Leave the audio arguments out, or try another stream.")
        if (track != null) {
            return tracks.firstOrNull { it.index == track }
                ?: throw McpToolException("No audio track $track. This stream has: ${tracks.describeAudio()}.")
        }
        val wanted = normalizeLanguageCode(language) ?: language!!.lowercase()
        return tracks.firstOrNull { (normalizeLanguageCode(it.language) ?: it.language.lowercase()) == wanted }
            ?: throw McpToolException("No audio track in \"$language\". This stream has: ${tracks.describeAudio()}.")
    }

    /** Where Save as would put the clip, or null to let the clips folder and the naming template decide. */
    private fun saveTarget(directory: String?, fileName: String?): ClipSaveTarget? {
        if (directory == null && fileName == null) return null
        val folder = directory?.let { File(it.replaceFirst(Regex("^~(?=/|$)"), System.getProperty("user.home"))) }
        if (folder != null && !(folder.isDirectory && folder.canWrite())) {
            throw McpToolException("save_dir \"$directory\" is not a folder StreamCut can write to. It has to exist already.")
        }
        return ClipSaveTarget(
            directory = folder?.absolutePath ?: ClipRepository.outputDirPath(),
            fileStem = fileName?.let(::fileStem).orEmpty(),
        )
    }

    /** The link and headers ffmpeg reads this stream with, resolving it through debrid when that is what it needs. */
    private suspend fun StreamHandle.playableSource(): FrameSource {
        val resolved = DirectDebridPlaybackResolver.resolveToPlayableStream(
            stream = stream,
            season = target.content.seasonNumber,
            episode = target.content.episodeNumber,
        )
        val playable = when (resolved) {
            is DirectDebridPlayableResult.Success -> resolved.stream
            DirectDebridPlayableResult.MissingApiKey ->
                throw McpToolException("This stream needs a debrid API key, and none is set in StreamCut.")
            DirectDebridPlayableResult.NotCached ->
                throw McpToolException("The debrid service does not have this stream cached. Pick another.")
            DirectDebridPlayableResult.Stale ->
                throw McpToolException("This stream link has expired. Call list_streams again.")
            DirectDebridPlayableResult.Error ->
                throw McpToolException("The debrid service could not resolve this stream. Pick another.")
        }
        return FrameSource(
            location = playable.playableDirectUrl ?: throw McpToolException(stream.notClippableReason()),
            headers = sanitizePlaybackHeaders(playable.behaviorHints.proxyHeaders?.request),
        )
    }

    // --- frames ---

    private suspend fun getFrames(args: JsonObject): List<McpContent> {
        val width = (args.int("width") ?: DefaultFrameWidth).coerceIn(MinFrameWidth, MaxFrameWidth)
        val source = sourceOf(args)

        val plan = FramePlan.from(
            atMs = args.longs("at_ms"),
            fromMs = args.long("from_ms"),
            toMs = args.long("to_ms"),
            count = args.int("count"),
        )
        val frames = when (plan) {
            is FramePlan.Sweep -> McpFrameGrabber.sweep(source, plan.fromMs, plan.stepMs, plan.count, width)
            is FramePlan.Seeks -> McpFrameGrabber.atTimes(source, plan.timesMs, width)
        }
        if (frames.isEmpty()) {
            throw McpToolException(
                "No frame could be read at ${plan.timesMs.joinToString { formatTimestamp(it, millis = true) }}. " +
                    "The time may be past the end, or the stream may have stopped answering: try another stream.",
            )
        }

        return buildList {
            frames.forEach { frame ->
                add(McpContent.Text("${formatTimestamp(frame.atMs, millis = true)} (${frame.atMs} ms)"))
                add(McpContent.Image(frame.jpeg, "image/jpeg"))
            }
            val missing = plan.timesMs - frames.map { it.atMs }.toSet()
            if (missing.isNotEmpty()) {
                add(McpContent.Text("No frame at ${missing.joinToString { "$it ms" }}: past the end, or the read failed."))
            }
        }
    }

    private suspend fun getContactSheet(args: JsonObject): List<McpContent> {
        val columns = (args.int("columns") ?: DefaultSheetColumns).coerceIn(2, MaxSheetColumns)
        val rows = (args.int("rows") ?: DefaultSheetRows).coerceIn(1, MaxSheetRows)
        val source = sourceOf(args)
        val plan = FramePlan.from(
            atMs = null,
            fromMs = args.requireLong("from_ms"),
            toMs = args.requireLong("to_ms"),
            count = columns * rows,
            maxFrames = MaxSheetColumns * MaxSheetRows,
        )
        val frames = when (plan) {
            is FramePlan.Sweep -> McpFrameGrabber.sweep(source, plan.fromMs, plan.stepMs, plan.count, SheetCellWidth)
            is FramePlan.Seeks -> McpFrameGrabber.atTimes(source, plan.timesMs, SheetCellWidth)
        }
        val sheet = McpContactSheet.compose(frames, columns) { formatTimestamp(it) }
            ?: throw McpToolException("No frame could be read in that range. It may be past the end, or the stream stopped answering.")
        val step = plan.timesMs.let { if (it.size > 1) it[1] - it[0] else 0L }
        return listOf(
            McpContent.Text(
                "${frames.size} frames from ${formatTimestamp(frames.first().atMs)} to ${formatTimestamp(frames.last().atMs)}, " +
                    "one every ${step / 1_000.0} s, left to right then top to bottom. Each cell shows its own time.",
            ),
            McpContent.Image(sheet, "image/jpeg"),
        )
    }

    // --- what a source contains ---

    private suspend fun probeStream(args: JsonObject): JsonElement {
        val stream = args.string("stream_id")?.let(::streamHandle)
        val layout = stream?.let { it.layout(it.playableSource()) } ?: McpMediaReader.probe(sourceOf(args))
        return buildJsonObject {
            layout.durationMs?.let {
                put("duration_ms", it)
                put("duration", formatTimestamp(it))
            }
            layout.video?.let { video ->
                put(
                    "video",
                    buildJsonObject {
                        put("codec", video.codec)
                        put("resolution", "${video.width}x${video.height}")
                        video.fps?.let { put("fps", (it * 1_000).toInt() / 1_000.0) }
                        put("hdr", video.hdr)
                    },
                )
            }
            put("audio_tracks", buildJsonArray { layout.audio.forEach { add(it.toJson()) } })
            put("subtitle_tracks", buildJsonArray { layout.subtitles.forEach { add(it.toJson()) } })
            if (layout.chapters.isNotEmpty()) {
                put(
                    "chapters",
                    buildJsonArray {
                        layout.chapters.forEach { chapter ->
                            add(
                                buildJsonObject {
                                    put("start_ms", chapter.startMs)
                                    put("at", formatTimestamp(chapter.startMs))
                                    putIfPresent("title", chapter.title)
                                },
                            )
                        }
                    },
                )
            }
            if (layout.video?.hdr == true) {
                put("note", "HDR source: clips are tonemapped, but frames from get_frames and get_contact_sheet look washed out.")
            }
        }
    }

    private suspend fun detectCuts(args: JsonObject): JsonElement {
        val fromMs = args.requireLong("from_ms")
        val toMs = args.requireLong("to_ms")
        if (fromMs < 0) throw McpToolException("Times cannot be negative.")
        if (toMs <= fromMs) throw McpToolException("to_ms must be after from_ms.")
        if (toMs - fromMs > MaxCutWindowMs) {
            throw McpToolException("At most ${MaxCutWindowMs / 1_000} seconds a call; ask for a shorter window.")
        }
        val sensitivity = args.string("sensitivity") ?: "normal"
        val threshold = CutThresholds[sensitivity]
            ?: throw McpToolException("sensitivity must be one of ${CutThresholds.keys.joinToString()}.")

        val cuts = McpMediaReader.cuts(sourceOf(args), fromMs, toMs, threshold)
        return buildJsonObject {
            put(
                "cuts",
                buildJsonArray {
                    cuts.forEach { atMs ->
                        add(
                            buildJsonObject {
                                put("at_ms", atMs)
                                put("at", formatTimestamp(atMs, millis = true))
                            },
                        )
                    }
                },
            )
            put(
                "note",
                if (cuts.isEmpty()) {
                    "No cut in that window at this sensitivity: it may be one continuous shot, or a slow dissolve."
                } else {
                    "Each time is the first frame of a new shot. End a clip just before one, or start it on one."
                },
            )
        }
    }

    /** The stream or finished clip a call names, as something ffmpeg can read. */
    private suspend fun sourceOf(args: JsonObject): FrameSource {
        val streamId = args.string("stream_id")
        val jobId = args.string("job_id")
        return when {
            streamId != null && jobId != null -> throw McpToolException("Give stream_id or job_id, not both.")
            streamId != null -> streamHandle(streamId).playableSource()
            jobId != null -> FrameSource(clipFilePath(jobId))
            else -> throw McpToolException("Give a stream_id from list_streams, or the job_id of a finished clip.")
        }
    }

    private fun streamHandle(id: String): StreamHandle = streams.get(id) ?: throw McpToolException(
        "Unknown stream_id \"$id\". Ids come from list_streams and do not outlive the app.",
    )

    /**
     * Where a finished clip is on disk.
     *
     * The library is asked first. The export job remembers the path it wrote
     * to, which stops being true the moment the clip is renamed; the library
     * is what a rename updates.
     */
    private fun clipFilePath(jobId: String): String {
        ClipLibrary.ensureLoaded()
        val uri = ClipLibrary.entries.value.firstOrNull { it.id == jobId }?.outputFileUri
            ?: ClipRepository.jobs.value.firstOrNull { it.id == jobId }?.let { job ->
                job.outputFileUri ?: throw McpToolException(
                    "That export is ${job.status.name.lowercase()}, so there is no file to look at yet.",
                )
            }
            ?: throw McpToolException("No clip with job_id \"$jobId\".")
        val path = ClipRepository.filePathOf(uri)
        if (!File(path).exists()) throw McpToolException("That clip's file is gone: it was deleted or moved outside StreamCut.")
        return path
    }

    private suspend fun getClip(args: JsonObject): JsonElement {
        val jobId = args.requireString("job_id")
        val waitMs = (args.int("wait_seconds") ?: 0).coerceIn(0, MaxWaitSeconds) * 1_000L
        if (waitMs > 0) {
            withTimeoutOrNull(waitMs) {
                // A settled job is trimmed from the list after a few more
                // exports of the same title, so "gone" also ends the wait.
                ClipRepository.jobs.first { jobs -> jobs.firstOrNull { it.id == jobId }?.isActive != true }
            }
        }
        ClipRepository.jobs.value.firstOrNull { it.id == jobId }?.let { return it.toJson() }
        ClipLibrary.ensureLoaded()
        ClipLibrary.entries.value.firstOrNull { it.id == jobId }?.let { entry ->
            return buildJsonObject {
                put("job_id", entry.id)
                put("status", "completed")
                clipFile(entry)
            }
        }
        throw McpToolException("No clip export with job_id \"$jobId\". It may be from an earlier run of the app.")
    }

    private suspend fun cancelClip(args: JsonObject): JsonElement {
        val jobId = args.requireString("job_id")
        val job = ClipRepository.jobs.value.firstOrNull { it.id == jobId }
            ?: throw McpToolException("No clip export with job_id \"$jobId\".")
        if (!job.isActive) throw McpToolException("That export already ended as ${job.status.name.lowercase()}.")
        ClipRepository.cancel(jobId)
        return buildJsonObject {
            put("job_id", jobId)
            put("status", "cancelled")
        }
    }

    private suspend fun listClips(args: JsonObject): JsonElement {
        val limit = (args.int("limit") ?: DefaultClipLimit).coerceIn(1, MaxClipResults)
        ClipLibrary.ensureLoaded()
        val entries = ClipLibrary.entries.value
        return buildJsonObject {
            put("folder", ClipRepository.outputDirPath())
            put(
                "clips",
                buildJsonArray {
                    entries.take(limit).forEach { entry ->
                        add(
                            buildJsonObject {
                                put("job_id", entry.id)
                                put("title", entry.content.label)
                                put("start_ms", entry.startMs)
                                put("end_ms", entry.endMs)
                                put("created", CreatedStamp.format(Instant.ofEpochMilli(entry.createdAtEpochMs).atZone(ZoneId.systemDefault())))
                                clipFile(entry)
                            },
                        )
                    }
                },
            )
            if (entries.size > limit) put("note", "Showing $limit of ${entries.size} clips.")
        }
    }

    private suspend fun renameClip(args: JsonObject): JsonElement {
        val entry = libraryEntry(args.requireString("job_id"))
        val stem = fileStem(args.requireString("file_name"))
        if (stem.isEmpty()) throw McpToolException("file_name has nothing usable in it.")

        val current = File(ClipRepository.filePathOf(entry.outputFileUri))
        val renamed = File(current.parentFile, "$stem.${current.extension.ifEmpty { "mp4" }}")
        if (renamed == current) return buildJsonObject { clipFile(entry) }
        // Never over another file: a rename that silently replaces a clip is a delete nobody asked for.
        if (renamed.exists()) throw McpToolException("\"${renamed.name}\" already exists in that folder. Choose another name.")
        if (!current.renameTo(renamed)) throw McpToolException("The file could not be renamed. It may be open in another app.")

        // The scrub previews are cached under the old path; they are rebuilt on demand under the new one.
        ClipExtractor.deleteHoverFrames(entry.outputFileUri)
        val moved = withContext(Dispatchers.Main) {
            ClipLibrary.relocate(entry.id, renamed.toURI().toString(), renamed.name)
        } ?: entry
        return buildJsonObject {
            put("job_id", moved.id)
            clipFile(moved)
        }
    }

    private suspend fun deleteClip(args: JsonObject): JsonElement {
        val entry = libraryEntry(args.requireString("job_id"))
        // The app's own delete, undo window included: the clip can be brought
        // back from the bar in StreamCut, and after that from the Trash.
        withContext(Dispatchers.Main) { ClipLibrary.delete(entry.id) }
        return buildJsonObject {
            put("deleted", ClipRepository.filePathOf(entry.outputFileUri))
            put("note", "Out of the library now; the file goes to the Trash in a few seconds unless the user undoes it in StreamCut.")
        }
    }

    private fun libraryEntry(jobId: String): ClipEntry {
        ClipLibrary.ensureLoaded()
        return ClipLibrary.entries.value.firstOrNull { it.id == jobId }
            ?: throw McpToolException("No saved clip with job_id \"$jobId\". list_clips shows the ones there are.")
    }

    private fun ClipJob.toJson(): JsonObject = buildJsonObject {
        put("job_id", id)
        put("status", status.name.lowercase())
        put("title", content.label)
        put("start_ms", startMs)
        put("end_ms", endMs)
        if (status == ClipStatus.Running) put("progress", (progress * 100).toInt() / 100.0)
        putIfPresent("error", errorMessage)
        outputFileUri?.let { uri ->
            // By id, not by path: the library follows a rename and the job does not.
            val entry = ClipLibrary.entries.value.firstOrNull { it.id == id }
            when {
                entry != null -> clipFile(entry)
                File(ClipRepository.filePathOf(uri)).exists() -> put("file", ClipRepository.filePathOf(uri))
                else -> put("note", "This clip has since been deleted.")
            }
        }
    }

    private fun JsonObjectBuilder.clipFile(entry: ClipEntry) {
        put("file", ClipRepository.filePathOf(entry.outputFileUri))
        if (entry.fileSizeBytes > 0) put("size_bytes", entry.fileSizeBytes)
        if (entry.width > 0 && entry.height > 0) put("resolution", "${entry.width}x${entry.height}")
    }

    private fun ClipJob.matches(content: ClipContentRef, startMs: Long, endMs: Long): Boolean =
        contentKey == content.key && this.startMs == startMs && this.endMs == endMs && isActive

    private val ClipJob.isActive: Boolean
        get() = status == ClipStatus.Queued || status == ClipStatus.Running

    // --- shared lookups ---

    /**
     * Which film or episode a call is about, and how a clip of it is filed.
     *
     * Built the way the player builds its own [ClipContentRef], so a clip made
     * here lands beside the ones the user cut by hand from the same title
     * instead of in a second group.
     */
    private suspend fun resolveTarget(args: JsonObject): Target {
        val type = args.requireString("type")
        val id = args.requireString("id")
        val season = args.int("season")
        val episode = args.int("episode")
        val episodic = season != null && episode != null
        if (type == "series" && !episodic) {
            throw McpToolException("A series needs both season and episode. Call get_title to see what exists.")
        }

        val meta = fetchMeta(type, id)
        val video = if (episodic) meta?.videos?.firstOrNull { it.season == season && it.episode == episode } else null
        if (episodic && video == null && meta?.videos?.any { it.season != null } == true) {
            throw McpToolException("${meta.name} has no season $season episode $episode. Call get_title to see what exists.")
        }
        val remembered = titles.getByKey("$type:$id")
        return Target(
            type = type,
            titleId = id,
            // Addons that follow the Stremio convention name an episode this
            // way, which is the best guess left when no addon returned details.
            videoId = video?.id ?: if (episodic) "$id:$season:$episode" else id,
            year = McpTitleMatch.yearOf(meta?.releaseInfo),
            // "175 min" for a film; an episode carries its own, in minutes.
            runtimeMinutes = if (episodic) video?.runtime else meta?.runtime?.trim()?.takeWhile { it.isDigit() }?.toIntOrNull(),
            content = ClipContentRef(
                videoId = video?.id ?: if (episodic) "$id:$season:$episode" else id,
                title = meta?.name ?: remembered?.name ?: id,
                seasonNumber = if (episodic) season else null,
                episodeNumber = if (episodic) episode else null,
                posterUrl = (if (episodic) video?.thumbnail else null) ?: meta?.poster ?: remembered?.poster.orEmpty(),
            ),
        )
    }

    /** Not cached into the details screen's store: what is fetched here skips that screen's enrichment. */
    private suspend fun fetchMeta(type: String, id: String): MetaDetails? {
        readyAddons()
        return attempt(MetaTimeoutMs) { MetaDetailsRepository.fetch(type = type, id = id, cacheResult = false) }
            ?.also { titles.put("$type:$id", TitleInfo(it.name, it.poster.orEmpty())) }
    }

    /**
     * The enabled addons whose manifests have arrived.
     *
     * Waits for every manifest still in flight rather than using
     * [AddonRepository.awaitManifestsLoaded], which returns on the first one:
     * a call made right after launch would otherwise see whichever addon
     * answered first and report, say, that nothing can be searched.
     */
    private suspend fun readyAddons(): List<ManagedAddon> {
        AddonRepository.initialize()
        withTimeoutOrNull(AddonTimeoutMs) {
            AddonRepository.uiState.first { state -> state.addons.none { it.enabled && it.isRefreshing } }
        }
        return AddonRepository.uiState.value.addons.enabledAddons().filter { it.manifest != null }
    }

    private class SubtitleHandle(val subtitle: AddonSubtitle) {
        var id: String = ""
        private var cues: List<SubtitleSyncCue>? = null
        private var index: SubtitlePhraseIndex? = null

        suspend fun cues(): List<SubtitleSyncCue> = cues ?: run {
            val body = attempt(AddonTimeoutMs) { httpGetTextWithHeaders(subtitle.url, emptyMap()) }
                ?: throw McpToolException("Could not download that subtitle file. Try another from list_subtitles.")
            PlayerSubtitleCueParser.parse(body, subtitle.url)
                .ifEmpty { throw McpToolException("That subtitle file has no readable lines. Try another.") }
                .also { cues = it }
        }

        suspend fun index(): SubtitlePhraseIndex = index ?: SubtitlePhraseIndex(cues()).also { index = it }

        fun toJson(): JsonObject = buildJsonObject {
            put("subtitle_id", id)
            put("language", subtitle.language)
            putIfPresent("addon", subtitle.addonName)
        }
    }

    private class Target(
        val type: String,
        /** The film or series, as the catalog knows it; [videoId] is the film again, or one episode. */
        val titleId: String,
        val videoId: String,
        val year: Int?,
        val runtimeMinutes: Int?,
        val content: ClipContentRef,
    )

    private class StreamHandle(val stream: StreamItem, val target: Target) {
        private var layout: StreamLayout? = null

        /** Probed once: a stream's tracks do not change, and every probe is a round trip to the source. */
        suspend fun layout(source: FrameSource): StreamLayout = layout ?: McpMediaReader.probe(source).also { layout = it }

        /** Roughly how many bytes [windowMs] of this stream is, or null when its size or length is unknown. */
        fun bytesFor(layout: StreamLayout, windowMs: Long): Long? {
            val size = stream.behaviorHints.videoSize ?: return null
            val duration = layout.durationMs?.takeIf { it > 0 } ?: return null
            return size / duration * windowMs
        }
    }

    private class TitleInfo(val name: String, val poster: String)

    companion object {
        private const val AddonTimeoutMs = 20_000L
        private const val MetaTimeoutMs = 20_000L
        private const val DebridCheckTimeoutMs = 10_000L
        private const val JobAppearTimeoutMs = 5_000L

        /** Long enough for a scene, short enough that a mistaken range is not a whole film. */
        private const val MaxClipMs = 10 * 60_000L

        /** Under the HTTP layer's own limit, so a long wait ends as a status, not a dropped request. */
        private const val MaxWaitSeconds = 90

        private const val MaxSearchResults = 25
        private const val MaxStreamResults = 40
        private const val MaxSubtitleResults = 40
        private const val MaxTranscriptLines = 200
        private const val MaxClipResults = 100
        private const val MaxDescriptionChars = 400
        private const val DefaultLineLimit = 8

        private const val DefaultFrameWidth = 640
        private const val MinFrameWidth = 160
        private const val MaxFrameWidth = 1_280
        private const val DefaultClipLimit = 20

        private const val DefaultSheetColumns = 4
        private const val DefaultSheetRows = 3
        private const val MaxSheetColumns = 6
        private const val MaxSheetRows = 5

        /** Wide enough to tell what a shot is of; a full sheet stays near the size of one ordinary frame. */
        private const val SheetCellWidth = 320

        private const val MaxCutWindowMs = 120_000L
        private const val MaxStreamSubtitleWindowMs = 5 * 60_000L
        private const val ProbeTimeoutMs = 30_000L
        private const val WindowTimeoutMs = 100_000L

        /** Files tried per language before moving to the next: a second one covers a first that will not download. */
        private const val FilesPerLanguage = 2

        /**
         * How far either side of a subtitle file's time the stream is searched
         * for the same line. Two files for one film were seen 51 s apart, so the
         * first look is wide; once one line gives the offset, the rest are near.
         */
        private const val RetimeWideMs = 90_000L
        private const val RetimeNarrowMs = 20_000L
        private const val MaxRetimedHits = 3
        private const val MaxRetimeBytes = 600_000_000L

        private const val TimingCaveat =
            "These times come from a subtitle file, which can be up to a minute off from a given stream. " +
                "Pass stream_id to re-time them, or check with get_frames before cutting."

        private val CreatedStamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        /** ffmpeg's scene score, 0 to 1: how different a frame is from the one before it. */
        private val CutThresholds = mapOf("low" to 0.45, "normal" to 0.3, "high" to 0.18)

        private const val MaxRememberedTitles = 500
        private const val MaxRememberedStreams = 600
        private const val MaxRememberedSubtitles = 400

        private const val NoAddonsMessage =
            "StreamCut has no addons enabled, so there is nothing to search. The user adds them in Settings."
        private const val NoSubtitlesMessage =
            "No subtitles are offered for this title. A subtitles addon has to be installed in StreamCut."

        private val AspectNames: Map<String, ClipAspect> =
            ClipAspect.entries.associateBy { it.label.lowercase() }

        /** Null on failure or timeout: one addon being down is not the call failing. */
        private suspend fun <T> attempt(timeoutMs: Long, block: suspend () -> T): T? =
            try {
                withTimeoutOrNull(timeoutMs) { block() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                null
            }

        /** A file name with what a file system would choke on taken out, and no extension. */
        private fun fileStem(name: String): String =
            name.trim().removeSuffix(".mp4").replace(Regex("""[/\\:*?"<>|\p{Cntrl}]"""), " ").replace(Regex("\\s+"), " ").trim().take(120)

        private fun StreamLayout.textTrackIn(language: String): StreamLayout.SubtitleTrack? {
            val wanted = normalizeLanguageCode(language) ?: language.lowercase()
            val inLanguage = subtitles.filter { it.isText && (normalizeLanguageCode(it.language) ?: it.language.lowercase()) == wanted }
            // A forced track holds only the lines in a foreign tongue, which is not where a quote is.
            return inLanguage.firstOrNull { !it.isForced } ?: inLanguage.firstOrNull()
        }

        private fun List<StreamLayout.SubtitleTrack>.describe(): String =
            if (isEmpty()) "no subtitle tracks"
            else joinToString { "${it.index}: ${it.language.ifBlank { "unlabelled" }} ${if (it.isText) "text" else "picture"}" }

        private fun List<StreamLayout.AudioTrack>.describeAudio(): String =
            if (isEmpty()) "no audio tracks" else joinToString { "${it.index}: ${it.language.ifBlank { "unlabelled" }}" }

        private fun StreamLayout.AudioTrack.toJson(): JsonObject = buildJsonObject {
            put("track", index)
            putIfPresent("language", language)
            putIfPresent("title", title)
            put("codec", codec)
            put("channels", channels)
            if (isDefault) put("default", true)
        }

        private fun StreamLayout.SubtitleTrack.toJson(): JsonObject = buildJsonObject {
            put("track", index)
            putIfPresent("language", language)
            putIfPresent("title", title)
            put("kind", if (isText) "text" else "picture")
            if (isForced) put("forced", true)
        }

        private fun String.singleLine(): String = replace(Regex("\\s*\\n\\s*"), " | ").trim()

        /** A subtitle's line breaks are layout for the screen, not part of what was said. */
        private fun String.singleSpaced(): String = replace(Regex("\\s+"), " ").trim()

        private fun formatTimestamp(ms: Long, millis: Boolean = false): String {
            val totalSeconds = ms / 1_000
            val hours = totalSeconds / 3_600
            val minutes = (totalSeconds % 3_600) / 60
            val seconds = totalSeconds % 60
            val base = "%d:%02d:%02d".format(hours, minutes, seconds)
            return if (millis) "$base.%03d".format(ms % 1_000) else base
        }
    }
}

/** At most this many frames a call: each is an image the model has to pay attention for. */
internal const val MaxFrames = 12

/** A from/to run no longer than this is read as one pass instead of a seek per frame. */
internal const val SweepWindowMs = 30_000L

/**
 * Which frames a `get_frames` call asks for, and which of [McpFrameGrabber]'s
 * two ways reads them. Kept apart from the tool so the arithmetic can be tested
 * without a video.
 */
internal sealed interface FramePlan {
    val timesMs: List<Long>

    class Seeks(override val timesMs: List<Long>) : FramePlan

    class Sweep(val fromMs: Long, val stepMs: Long, val count: Int) : FramePlan {
        override val timesMs: List<Long> get() = List(count) { fromMs + it * stepMs }
    }

    companion object {
        fun from(atMs: List<Long>?, fromMs: Long?, toMs: Long?, count: Int?, maxFrames: Int = MaxFrames): FramePlan {
            if (!atMs.isNullOrEmpty()) {
                if (fromMs != null || toMs != null) throw McpToolException("Give at_ms, or from_ms with to_ms, not both.")
                if (atMs.any { it < 0 }) throw McpToolException("Times cannot be negative.")
                if (atMs.size > MaxFrames) throw McpToolException("At most $MaxFrames frames a call; at_ms has ${atMs.size}.")
                return Seeks(atMs.distinct().sorted())
            }
            if (fromMs == null || toMs == null) {
                throw McpToolException("Say which frames: at_ms, or from_ms and to_ms with count.")
            }
            if (fromMs < 0) throw McpToolException("Times cannot be negative.")
            if (toMs <= fromMs) throw McpToolException("to_ms must be after from_ms.")
            val frames = (count ?: DefaultRunFrames).coerceIn(2, maxFrames)
            // Whole milliseconds, so the last frame can land a hair before
            // to_ms; never after it, and never past what was asked for.
            val stepMs = ((toMs - fromMs) / (frames - 1)).coerceAtLeast(1)
            return if (toMs - fromMs <= SweepWindowMs) {
                Sweep(fromMs, stepMs, frames)
            } else {
                Seeks(List(frames) { fromMs + it * stepMs })
            }
        }

        private const val DefaultRunFrames = 6
    }
}

/**
 * Hands out short ids for things too large or too private to pass back and
 * forth, and forgets the oldest once [capacity] is reached.
 */
private class HandleCache<T : Any>(private val prefix: String, private val capacity: Int) {
    private val byId = LinkedHashMap<String, T>()
    private val idByKey = HashMap<String, String>()
    private var counter = 0

    /** Stores [value] and returns its id. A non-null [key] makes the id stable across repeated puts. */
    @Synchronized
    fun put(key: String?, value: T): String {
        val existing = key?.let { idByKey[it] }
        val id = existing ?: "$prefix${++counter}"
        byId[id] = value
        if (key != null) idByKey[key] = id
        if (byId.size > capacity) {
            val oldest = byId.keys.first()
            byId.remove(oldest)
            idByKey.values.remove(oldest)
        }
        return id
    }

    @Synchronized
    fun get(id: String): T? = byId[id]

    /** The id already handed out for [key], if it is still remembered. */
    @Synchronized
    fun idForKey(key: String): String? = idByKey[key]?.takeIf { it in byId }

    @Synchronized
    fun getByKey(key: String): T? = idByKey[key]?.let { byId[it] }
}

// --- argument reading ---

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonObject.int(name: String): Int? =
    (this[name] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }

private fun JsonObject.requireString(name: String): String =
    string(name) ?: throw McpToolException("Missing required argument: $name")

private fun JsonPrimitive.asLong(): Long? = longOrNull ?: contentOrNull?.toDoubleOrNull()?.toLong()

private fun JsonObject.long(name: String): Long? = (this[name] as? JsonPrimitive)?.asLong()

private fun JsonObject.requireLong(name: String): Long =
    long(name) ?: throw McpToolException("Missing required argument: $name")

private fun JsonObject.longs(name: String): List<Long>? =
    (this[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.asLong() }

private fun JsonObjectBuilder.putIfPresent(name: String, value: String?) {
    if (!value.isNullOrBlank()) put(name, value)
}

// --- schema building ---

private class SchemaBuilder {
    val properties = LinkedHashMap<String, JsonObject>()

    fun string(name: String, description: String, enum: List<String>? = null) {
        properties[name] = buildJsonObject {
            put("type", "string")
            put("description", description)
            if (enum != null) put("enum", JsonArray(enum.map(::JsonPrimitive)))
        }
    }

    fun integer(name: String, description: String) {
        properties[name] = buildJsonObject {
            put("type", "integer")
            put("description", description)
        }
    }

    fun integerArray(name: String, description: String) {
        properties[name] = buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "integer") })
            put("description", description)
        }
    }

    fun sourceProperties() {
        string("stream_id", "From list_streams. Give this or job_id.")
        string("job_id", "A finished clip, from create_clip or list_clips. Times are then counted from the clip's start.")
    }

    fun titleProperties() {
        string("type", "movie or series, as returned by search_titles.")
        string("id", "Title id, as returned by search_titles.")
    }

    fun targetProperties() {
        titleProperties()
        integer("season", "Season number. Required for a series.")
        integer("episode", "Episode number. Required for a series.")
    }
}

private fun schema(required: List<String> = emptyList(), build: SchemaBuilder.() -> Unit = {}): JsonObject {
    val builder = SchemaBuilder().apply(build)
    return buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(builder.properties))
        if (required.isNotEmpty()) put("required", JsonArray(required.map(::JsonPrimitive)))
    }
}
