package com.nuvio.app.features.mcp

import com.nuvio.app.core.deeplink.buildMetaDeepLinkUrl
import com.nuvio.app.core.deeplink.handleAppUrl
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
import com.nuvio.app.features.clip.ClipJob
import com.nuvio.app.features.clip.ClipLibrary
import com.nuvio.app.features.clip.ClipRepository
import com.nuvio.app.features.clip.ClipStatus
import com.nuvio.app.features.clip.ClipSubtitleSelection
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.debrid.LocalDebridAvailabilityService
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.player.AddonSubtitle
import com.nuvio.app.features.player.PlayerSubtitleCueParser
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
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
                "end_ms. The subtitle file may be timed for a different release than the stream, so a " +
                "match can be a second or two off: pad the range when cutting a clip from it.",
            inputSchema = schema(required = listOf("type", "id", "query")) {
                targetProperties()
                string("query", "The line, as remembered. Use the language of the subtitles being searched.")
                string("language", "Subtitle language to search (en, spa, french). Defaults to the first offered.")
                string("subtitle_id", "Search this file from list_subtitles instead of picking one.")
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
            name = "list_streams",
            description = "Sources the user's addons offer for a film or episode. A clip is cut from one " +
                "of these, so call this before create_clip and pick a stream whose `clippable` is true.",
            inputSchema = schema(required = listOf("type", "id")) {
                targetProperties()
            },
            handler = ::listStreams,
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
                string("stream_id", "From list_streams. Give this or job_id.")
                string("job_id", "A completed clip from create_clip. Times are then counted from the clip's start.")
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
            description = "Cut a clip out of a stream and save it as an MP4 in the user's clips folder. " +
                "Only the selected range is downloaded. Returns a job_id straight away; the export runs " +
                "in the background, so follow it with get_clip. At most ${MaxClipMs / 60_000} minutes long.",
            inputSchema = schema(required = listOf("stream_id", "start_ms", "end_ms")) {
                string("stream_id", "From list_streams.")
                integer("start_ms", "Where the clip starts in the source, in milliseconds.")
                integer("end_ms", "Where the clip ends in the source, in milliseconds.")
                string("aspect", "Crop the frame to this shape. Default: source.", enum = AspectNames.keys.toList())
                integer("max_size_mb", "Keep the file under this size, trading quality. Default: no cap.")
                string("burn_subtitle_id", "Render this subtitle file into the picture.")
                integer("subtitle_delay_ms", "Shift the burned subtitle by this much; positive is later.")
            },
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
            handler = ::cancelClip,
        ),
        jsonTool(
            name = "list_clips",
            description = "Clips already saved on this computer, newest first.",
            inputSchema = schema {
                integer("limit", "Most clips to return. Default $DefaultClipLimit.")
            },
            handler = ::listClips,
        ),
        jsonTool(
            name = "open_in_app",
            description = "Bring a title up in the StreamCut window, for the user to look at or cut by hand.",
            inputSchema = schema(required = listOf("type", "id")) {
                titleProperties()
            },
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
        val handle = args.string("subtitle_id")?.let(::subtitleHandle) ?: run {
            val target = resolveTarget(args)
            val language = args.string("language")
            loadSubtitles(target).filterByLanguage(language).firstOrNull()
                ?: throw McpToolException(
                    if (language == null) NoSubtitlesMessage
                    else "No subtitles in \"$language\" for this title. Call list_subtitles to see what is offered.",
                )
        }

        val hits = handle.index().search(query, limit)
        return buildJsonObject {
            put("subtitle", handle.toJson())
            put(
                "matches",
                buildJsonArray {
                    hits.forEach { hit ->
                        add(
                            buildJsonObject {
                                put("start_ms", hit.startMs)
                                put("end_ms", hit.endMs)
                                put("at", formatTimestamp(hit.startMs))
                                put("text", hit.text.singleSpaced())
                                put("exact", hit.isExact)
                                put("score", (hit.score * 100).toInt() / 100.0)
                            },
                        )
                    }
                },
            )
            if (hits.isEmpty()) {
                put(
                    "note",
                    "Nothing close to that in this subtitle file. Try fewer words, the subtitle's own " +
                        "language, or another file from list_subtitles.",
                )
            }
        }
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
        val listed = found.sortedByDescending { it.clipSource() != null }.take(MaxStreamResults)
        return buildJsonObject {
            put("title", target.content.label)
            put(
                "streams",
                buildJsonArray {
                    listed.forEach { stream ->
                        val source = stream.clipSource()
                        add(
                            buildJsonObject {
                                put("stream_id", streams.put(null, StreamHandle(stream, target)))
                                put("addon", stream.addonName)
                                putIfPresent("name", stream.name?.singleLine())
                                putIfPresent(
                                    "description",
                                    (stream.description ?: stream.title)?.singleLine()?.take(MaxDescriptionChars),
                                )
                                putIfPresent("filename", stream.behaviorHints.filename)
                                stream.behaviorHints.videoSize?.let { put("size_bytes", it) }
                                put("clippable", source != null)
                                if (source != null) put("source", source) else put("why_not", stream.notClippableReason())
                            },
                        )
                    }
                },
            )
            if (found.size > listed.size) put("note", "Showing ${listed.size} of ${found.size} streams.")
            if (found.isEmpty()) put("note", "The addons returned no streams for this title.")
        }
    }

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
        val streamId = args.requireString("stream_id")
        val handle = streams.get(streamId) ?: throw McpToolException(
            "Unknown stream_id \"$streamId\". Ids come from list_streams and do not outlive the app.",
        )
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
        val subtitle = args.string("burn_subtitle_id")?.let { id ->
            ClipSubtitleSelection.External(
                url = subtitleHandle(id).subtitle.url,
                delayMs = args.int("subtitle_delay_ms") ?: 0,
            )
        }

        val target = handle.target
        val source = handle.playableSource()

        ClipRepository.startClip(
            sourceUrl = source.location,
            sourceHeaders = source.headers,
            content = target.content,
            startMs = startMs,
            endMs = endMs,
            audioTrackIndex = -1,
            subtitle = subtitle,
            aspect = aspect,
            targetSizeMb = (args.int("max_size_mb") ?: 0).coerceAtLeast(0),
        )
        // startClip queues on the main thread and returns nothing, so the job is
        // recognised by what it was asked for. An identical range already in
        // the queue is deliberately not duplicated, and is the one found here.
        val job = withTimeoutOrNull(JobAppearTimeoutMs) {
            ClipRepository.jobs.first { jobs -> jobs.any { it.matches(target.content, startMs, endMs) } }
                .first { it.matches(target.content, startMs, endMs) }
        } ?: throw McpToolException("StreamCut did not accept the export.")

        return job.toJson()
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
        val streamId = args.string("stream_id")
        val jobId = args.string("job_id")
        val source = when {
            streamId != null && jobId != null -> throw McpToolException("Give stream_id or job_id, not both.")
            streamId != null -> (streams.get(streamId) ?: throw McpToolException(
                "Unknown stream_id \"$streamId\". Ids come from list_streams and do not outlive the app.",
            )).playableSource()
            jobId != null -> FrameSource(clipFilePath(jobId))
            else -> throw McpToolException("Give a stream_id from list_streams, or the job_id of a finished clip.")
        }

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

    /** Where a finished clip is on disk, from the queue while its notice lasts and from the library after. */
    private fun clipFilePath(jobId: String): String {
        ClipLibrary.ensureLoaded()
        val uri = ClipRepository.jobs.value.firstOrNull { it.id == jobId }?.let { job ->
            job.outputFileUri ?: throw McpToolException(
                "That export is ${job.status.name.lowercase()}, so there is no file to look at yet.",
            )
        } ?: ClipLibrary.entries.value.firstOrNull { it.id == jobId }?.outputFileUri
            ?: throw McpToolException("No clip with job_id \"$jobId\".")
        return ClipRepository.filePathOf(uri)
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
                                put("title", entry.content.label)
                                put("start_ms", entry.startMs)
                                put("end_ms", entry.endMs)
                                clipFile(entry)
                            },
                        )
                    }
                },
            )
            if (entries.size > limit) put("note", "Showing $limit of ${entries.size} clips.")
        }
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
            ClipLibrary.entries.value.firstOrNull { it.outputFileUri == uri }?.let { clipFile(it) }
                ?: put("file", ClipRepository.filePathOf(uri))
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
            // Addons that follow the Stremio convention name an episode this
            // way, which is the best guess left when no addon returned details.
            videoId = video?.id ?: if (episodic) "$id:$season:$episode" else id,
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

    private class Target(val type: String, val videoId: String, val content: ClipContentRef)

    private class StreamHandle(val stream: StreamItem, val target: Target)

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
        fun from(atMs: List<Long>?, fromMs: Long?, toMs: Long?, count: Int?): FramePlan {
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
            val frames = (count ?: DefaultRunFrames).coerceIn(2, MaxFrames)
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
