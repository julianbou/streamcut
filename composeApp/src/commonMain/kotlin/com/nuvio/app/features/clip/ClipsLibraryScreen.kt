package com.nuvio.app.features.clip

import com.nuvio.app.core.ui.Riso
import com.nuvio.app.core.ui.RisoBloom
import com.nuvio.app.core.ui.RisoDisplay
import com.nuvio.app.core.ui.risoBlooms
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.clip_action_cancel
import nuvio.composeapp.generated.resources.clip_action_clear
import nuvio.composeapp.generated.resources.clip_action_delete
import nuvio.composeapp.generated.resources.clip_action_reveal
import nuvio.composeapp.generated.resources.clip_delete_many_message
import nuvio.composeapp.generated.resources.clip_delete_many_title_format
import nuvio.composeapp.generated.resources.clip_delete_one_message_format
import nuvio.composeapp.generated.resources.clip_delete_one_title
import nuvio.composeapp.generated.resources.clip_empty_saved_to_format
import nuvio.composeapp.generated.resources.clip_empty_step_export
import nuvio.composeapp.generated.resources.clip_empty_step_find
import nuvio.composeapp.generated.resources.clip_empty_step_format
import nuvio.composeapp.generated.resources.clip_empty_step_mark
import nuvio.composeapp.generated.resources.clip_empty_title
import nuvio.composeapp.generated.resources.clip_library_count_format
import nuvio.composeapp.generated.resources.clip_library_filter_placeholder
import nuvio.composeapp.generated.resources.clip_library_free_format
import nuvio.composeapp.generated.resources.clip_library_no_match_format
import nuvio.composeapp.generated.resources.clip_library_no_match_hint
import nuvio.composeapp.generated.resources.clip_library_open_folder
import nuvio.composeapp.generated.resources.clip_library_selected_format
import nuvio.composeapp.generated.resources.clip_library_sort_by_film
import nuvio.composeapp.generated.resources.clip_library_drag_hint
import nuvio.composeapp.generated.resources.clip_library_group_count_one
import nuvio.composeapp.generated.resources.clip_library_group_count_many
import nuvio.composeapp.generated.resources.clip_library_sort_longest
import nuvio.composeapp.generated.resources.clip_library_sort_newest
import nuvio.composeapp.generated.resources.clip_library_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * How the grid is ordered. By film is the default: a clipper cuts several
 * moments from one title, and a flat grid of ten cards all titled "Taxi Driver"
 * could only be told apart by their smallest line. Grouped, each film is a
 * heading and its clips run in film order under it; the film cut most recently
 * comes first, so the last cut is still at the top.
 */
private enum class ClipSort(val label: StringResource) {
    ByFilm(Res.string.clip_library_sort_by_film),
    Newest(Res.string.clip_library_sort_newest),
    Longest(Res.string.clip_library_sort_longest),
}

/** One film's clips under its heading, in the order they occur in the film. */
private data class ClipFilmGroup(val key: String, val label: String, val clips: List<ClipEntry>)

/**
 * The clips page: everything exported on this machine, as a grid.
 *
 * This is the library a clipper actually has. The tab it replaces held saved
 * shows, which is a viewing concept -- nothing here syncs, nothing here needs a
 * network, and the only thing that matters about a clip is whether you can find
 * it and get it out of the app.
 */
@Composable
internal fun ClipsLibraryScreen(
    // Nullable to match the tab host, which leaves it unset where the window
    // chrome does not overlap the content.
    topChromePadding: androidx.compose.ui.unit.Dp?,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { ClipLibrary.ensureLoaded() }
    val entries by ClipLibrary.entries.collectAsStateWithLifecycle()

    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(ClipSort.ByFilm) }
    var selection by remember { mutableStateOf(emptySet<String>()) }
    var pendingDelete by remember { mutableStateOf<List<ClipEntry>>(emptyList()) }

    // Recomputed rather than held: the library is user-scale (tens), and a
    // cached copy would go stale the moment an export finishes behind this.
    val visible = remember(entries, query, sort) {
        val filtered = if (query.isBlank()) {
            entries
        } else {
            entries.filter { entry ->
                entry.content.label.contains(query, ignoreCase = true) ||
                    entry.fileName.contains(query, ignoreCase = true)
            }
        }
        when (sort) {
            ClipSort.ByFilm, ClipSort.Newest -> filtered.sortedByDescending { it.createdAtEpochMs }
            ClipSort.Longest -> filtered.sortedByDescending { it.durationMs }
        }
    }
    val groups = remember(visible, sort) {
        if (sort != ClipSort.ByFilm) {
            emptyList()
        } else {
            // `visible` is newest first, so groupBy's first-seen order is
            // "film cut most recently" first.
            visible.groupBy { it.contentKey }.map { (key, clips) ->
                ClipFilmGroup(
                    key = key,
                    label = clips.first().content.label.ifBlank { clips.first().fileName },
                    clips = clips.sortedBy { it.startMs },
                )
            }
        }
    }

    // A selection that outlives the clips in it would batch-delete nothing and
    // report success, so it is pruned against what actually exists.
    LaunchedEffect(entries) {
        val ids = entries.map { it.id }.toSet()
        if (selection.any { it !in ids }) selection = selection.intersect(ids)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            // Sun ink: exported clips are the ranges you set aside, made real.
            .risoBlooms(ClipsPageBlooms)
            .padding(top = topChromePadding ?: 0.dp, start = 32.dp, end = 32.dp),
    ) {
        ClipsLibraryHeader(
            total = entries.size,
            shown = visible.size,
            query = query,
            onQueryChange = { query = it },
            sort = sort,
            onSortChange = { sort = it },
            selection = selection,
            onClearSelection = { selection = emptySet() },
            onRevealSelection = {
                selection.forEach { ClipLibrary.reveal(it) }
                selection = emptySet()
            },
            onDeleteSelection = {
                pendingDelete = entries.filter { it.id in selection }
            },
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (entries.isEmpty()) {
            ClipsEmptyCard()
            return@Column
        }

        // A filter that matches nothing used to leave a blank page, which reads
        // as "the clips are gone" rather than "the filter is too narrow".
        if (visible.isEmpty()) {
            ClipsNoMatch(query = query.trim())
            return@Column
        }

        LazyVerticalGrid(
            // Adaptive rather than a fixed count: the window is resizable and a
            // clip card stops being readable below about this width.
            columns = GridCells.Adaptive(minSize = 220.dp),
            contentPadding = PaddingValues(bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            val clipItem: @Composable (ClipEntry, Boolean) -> Unit = { entry, grouped ->
                val selectionMode = selection.isNotEmpty()
                ClipCard(
                    entry = entry,
                    selected = entry.id in selection,
                    selectionMode = selectionMode,
                    grouped = grouped,
                    onClick = {
                        // Once a selection exists every click extends or shrinks
                        // it; opening a clip mid-selection is never what is meant.
                        if (selectionMode) {
                            selection = if (entry.id in selection) selection - entry.id else selection + entry.id
                        } else {
                            ClipLibrary.open(entry.id)
                        }
                    },
                    onReveal = { ClipLibrary.reveal(entry.id) },
                    // One clip goes without asking -- it is undoable for seven
                    // seconds. A batch still asks: the undo window is a poor
                    // safety net for twenty files you may not be watching.
                    onDelete = { ClipLibrary.delete(entry.id) },
                )
            }
            if (sort == ClipSort.ByFilm) {
                groups.forEachIndexed { index, group ->
                    item(key = "film:${group.key}", span = { GridItemSpan(maxLineSpan) }) {
                        ClipFilmHeading(group, first = index == 0)
                    }
                    items(group.clips, key = { it.id }) { entry -> clipItem(entry, true) }
                }
            } else {
                items(visible, key = { it.id }) { entry -> clipItem(entry, false) }
            }
        }
    }

    val deleting = pendingDelete
    if (deleting.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingDelete = emptyList() },
            // A selection of exactly one is possible, so both wordings stay:
            // "Move 1 clips to Trash?" is the kind of thing that makes a
            // confirmation dialog read as machine output rather than a question.
            title = {
                Text(
                    if (deleting.size == 1) {
                        stringResource(Res.string.clip_delete_one_title)
                    } else {
                        stringResource(Res.string.clip_delete_many_title_format, deleting.size)
                    },
                )
            },
            text = {
                Text(
                    if (deleting.size == 1) {
                        stringResource(
                            Res.string.clip_delete_one_message_format,
                            deleting.first().fileName,
                        )
                    } else {
                        stringResource(Res.string.clip_delete_many_message)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // One batch, one undo: deleting them one at a time would
                    // have each delete commit the one before it.
                    ClipLibrary.delete(deleting.map { it.id })
                    selection = emptySet()
                    pendingDelete = emptyList()
                }) { Text(stringResource(Res.string.clip_action_delete), color = Riso.Red) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = emptyList() }) { Text(stringResource(Res.string.clip_action_cancel)) }
            },
        )
    }
}

@Composable
private fun ClipsLibraryHeader(
    total: Int,
    shown: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    sort: ClipSort,
    onSortChange: (ClipSort) -> Unit,
    selection: Set<String>,
    onClearSelection: () -> Unit,
    onRevealSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
) {
    // Read once per composition of the header: a stat() per frame would be a
    // syscall on every recomposition for a number that changes slowly.
    val outputDir = remember { ClipRepository.outputDirPath() }
    val freeBytes = remember(total) { ClipRepository.outputDirFreeBytes() }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(Res.string.clip_library_title),
                style = TextStyle(fontFamily = RisoDisplay, fontSize = 52.sp, lineHeight = 52.sp, fontWeight = FontWeight.ExtraBold),
                color = Riso.Paper,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = if (shown == total) {
                    "$total"
                } else {
                    stringResource(Res.string.clip_library_count_format, shown, total)
                },
                style = TextStyle(fontFamily = RisoDisplay, fontSize = 30.sp, fontWeight = FontWeight.Bold),
                // A total is not something set aside: dim paper, not sun.
                color = Riso.PaperDim,
            )
            Spacer(modifier = Modifier.weight(1f))
            ClipsChip(
                label = stringResource(Res.string.clip_library_open_folder),
                onClick = { ClipRepository.revealOutputDir() },
            )
        }

        if (outputDir.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = listOfNotNull(
                    outputDir,
                    formatClipBytes(freeBytes).takeIf { it.isNotBlank() }
                        ?.let { stringResource(Res.string.clip_library_free_format, it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = Riso.PaperDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Dragging out is how a clip leaves the app, and nothing on a card
        // says it can be dragged, so the page says it once.
        if (total > 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(Res.string.clip_library_drag_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Riso.PaperDim,
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ClipsSearchField(query = query, onQueryChange = onQueryChange)
            ClipSort.entries.forEach { option ->
                ClipsChip(
                    label = stringResource(option.label),
                    selected = option == sort,
                    onClick = { onSortChange(option) },
                )
            }
        }

        // The batch bar only exists while something is selected, so the page has
        // no permanent mode switch to notice or get stuck in.
        if (selection.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = stringResource(Res.string.clip_library_selected_format, selection.size),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                ClipsChip(
                    label = stringResource(Res.string.clip_action_reveal),
                    onClick = onRevealSelection,
                )
                ClipsChip(
                    label = stringResource(Res.string.clip_action_delete),
                    danger = true,
                    onClick = onDeleteSelection,
                )
                ClipsChip(
                    label = stringResource(Res.string.clip_action_clear),
                    onClick = onClearSelection,
                )
            }
        }
    }
}

@Composable
private fun ClipFilmHeading(group: ClipFilmGroup, first: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = if (first) 0.dp else 18.dp, bottom = 2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = group.label,
            style = TextStyle(fontFamily = RisoDisplay, fontSize = 30.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold),
            color = Riso.Paper,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = if (group.clips.size == 1) {
                stringResource(Res.string.clip_library_group_count_one)
            } else {
                stringResource(Res.string.clip_library_group_count_many, group.clips.size)
            },
            style = MaterialTheme.typography.bodySmall,
            color = Riso.PaperDim,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

@Composable
private fun ClipsSearchField(query: String, onQueryChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .width(260.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(Riso.Paper.copy(alpha = 0.07f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (query.isEmpty()) {
            Text(
                text = stringResource(Res.string.clip_library_filter_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                color = Riso.PaperDim,
            )
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.merge(
                TextStyle(color = MaterialTheme.colorScheme.onSurface),
            ),
            cursorBrush = SolidColor(Riso.Pink),
            // Escape clears a non-empty filter, as it does in the home masthead.
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && query.isNotEmpty()) {
                        onQueryChange("")
                        true
                    } else {
                        false
                    }
                },
        )
    }
}

@Composable
private fun ClipsChip(
    label: String,
    selected: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    // Hover and keyboard focus are one affordance (DESIGN.md): both raise the
    // chip's stock, and focus adds the pink ink line, so a Tab through the
    // header always shows where it is.
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val lifted = hovered || focused
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = when {
            selected -> Riso.Stock
            danger -> Riso.Red
            lifted -> Riso.Paper
            else -> Riso.Paper.copy(alpha = 0.82f)
        },
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(
                when {
                    selected -> Riso.Pink
                    lifted -> Riso.Paper.copy(alpha = 0.14f)
                    else -> Riso.Paper.copy(alpha = 0.07f)
                },
            )
            .then(
                if (focused) {
                    // On the pink selected chip a pink ring would vanish; it rings in paper.
                    Modifier.border(1.5.dp, if (selected) Riso.Paper else Riso.Pink, RoundedCornerShape(percent = 50))
                } else {
                    Modifier
                },
            )
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** What the grid says when the filter leaves nothing to show. */
@Composable
private fun ClipsNoMatch(query: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            text = stringResource(Res.string.clip_library_no_match_format, query),
            style = TextStyle(fontFamily = RisoDisplay, fontSize = 34.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold),
            color = Riso.Paper,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.clip_library_no_match_hint),
            style = MaterialTheme.typography.bodySmall,
            color = Riso.PaperDim,
        )
    }
}

/**
 * What the app shows before the first clip exists, on home and on the clips
 * page alike.
 *
 * The row used to delete itself when empty, so a first run gave no evidence the
 * app clips anything at all -- the one moment a user most needs telling. This
 * doubles as the onboarding: the three keystrokes, and where the files land.
 * Shared between the two surfaces so the steps cannot drift apart from each
 * other, which they had already started to do.
 */
@Composable
internal fun ClipsEmptyCard(modifier: Modifier = Modifier) {
    val outputDir = remember { ClipRepository.outputDirPath() }
    // No box: the steps are set like a programme's running order, numbered in
    // sun ink, straight on the page.
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Text(
            text = stringResource(Res.string.clip_empty_title),
            style = TextStyle(fontFamily = RisoDisplay, fontSize = 34.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold),
            color = Riso.Paper,
        )
        Spacer(modifier = Modifier.height(18.dp))
        listOf(
            Res.string.clip_empty_step_find,
            Res.string.clip_empty_step_mark,
            Res.string.clip_empty_step_export,
        ).forEachIndexed { index, step ->
            Row(
                modifier = Modifier.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${index + 1}",
                    style = TextStyle(fontFamily = RisoDisplay, fontSize = 40.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold),
                    color = Riso.Sun,
                    modifier = Modifier.width(40.dp),
                )
                Text(
                    text = stringResource(step),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Riso.Paper.copy(alpha = 0.86f),
                )
            }
        }
        if (outputDir.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(Res.string.clip_empty_saved_to_format, outputDir),
                style = MaterialTheme.typography.bodySmall,
                color = Riso.PaperDim,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private val ClipsPageBlooms = listOf(
    RisoBloom(color = Riso.Sun, centerX = 0.12f, centerY = 0.06f, radius = 0.55f, squash = 0.6f, strength = 0.34f),
    RisoBloom(color = Riso.Pink, centerX = 0.95f, centerY = 0.9f, radius = 0.5f, squash = 0.8f, strength = 0.18f),
)
