package com.nuvio.app.features.clip

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** How the grid is ordered. Newest first is the default: the last cut is the one being looked for. */
private enum class ClipSort(val label: String) {
    Newest("Newest"),
    Longest("Longest"),
    Largest("Largest"),
}

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
    var sort by remember { mutableStateOf(ClipSort.Newest) }
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
            ClipSort.Newest -> filtered.sortedByDescending { it.createdAtEpochMs }
            ClipSort.Longest -> filtered.sortedByDescending { it.durationMs }
            ClipSort.Largest -> filtered.sortedByDescending { it.fileSizeBytes }
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
            .padding(top = topChromePadding ?: 0.dp, start = 24.dp, end = 24.dp),
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
            ClipsLibraryEmpty()
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
            items(visible, key = { it.id }) { entry ->
                val selectionMode = selection.isNotEmpty()
                ClipCard(
                    entry = entry,
                    selected = entry.id in selection,
                    selectionMode = selectionMode,
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
                    onDelete = { pendingDelete = listOf(entry) },
                )
            }
        }
    }

    val deleting = pendingDelete
    if (deleting.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { pendingDelete = emptyList() },
            title = {
                Text(if (deleting.size == 1) "Move this clip to Trash?" else "Move ${deleting.size} clips to Trash?")
            },
            text = {
                Text(
                    if (deleting.size == 1) {
                        "${deleting.first().fileName} will be moved to your system Trash."
                    } else {
                        "They will be moved to your system Trash."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    deleting.forEach { ClipLibrary.delete(it.id) }
                    selection = emptySet()
                    pendingDelete = emptyList()
                }) { Text("Move to Trash") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = emptyList() }) { Text("Cancel") }
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
                text = "Clips",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = if (shown == total) "$total" else "$shown of $total",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
            Spacer(modifier = Modifier.weight(1f))
            ClipsChip(label = "Open folder", onClick = { ClipRepository.revealOutputDir() })
        }

        if (outputDir.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = listOfNotNull(
                    outputDir,
                    formatClipBytes(freeBytes).takeIf { it.isNotBlank() }?.let { "$it free" },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
                    label = option.label,
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
                    text = "${selection.size} selected",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                ClipsChip(label = "Show in file manager", onClick = onRevealSelection)
                ClipsChip(label = "Move to Trash", onClick = onDeleteSelection)
                ClipsChip(label = "Clear", onClick = onClearSelection)
            }
        }
    }
}

@Composable
private fun ClipsSearchField(query: String, onQueryChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .width(240.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        if (query.isEmpty()) {
            Text(
                text = "Filter by title",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.merge(
                TextStyle(color = MaterialTheme.colorScheme.onSurface),
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ClipsChip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
        color = if (selected) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        modifier = Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.07f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 7.dp),
    )
}

@Composable
private fun ClipsLibraryEmpty() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(
            text = "No clips yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(10.dp))
        listOf(
            "Find a title and start playing it",
            "Press I and O to mark the in and out points",
            "Press X to export",
        ).forEachIndexed { index, step ->
            Text(
                text = "${index + 1}.  $step",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
    }
}
