package com.nuvio.app.features.clip

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioSectionLabel

/**
 * "Your clips" at the top of home: every clip exported on this machine, newest
 * first.
 *
 * Clip files are local, so this row is deliberately independent of catalogs,
 * addons and sync -- it renders straight from [ClipLibrary] and needs no
 * network. The cards themselves are [ClipCard], shared with the clips page.
 */
@Composable
internal fun HomeClipsSection(
    sectionPadding: Dp,
    modifier: Modifier = Modifier,
) {
    if (!ClipRepository.isSupported) return
    // Must run before the empty-list branch below: on a cold start the library
    // is empty until it is read back from disk.
    LaunchedEffect(Unit) { ClipLibrary.ensureLoaded() }
    val entries by ClipLibrary.entries.collectAsStateWithLifecycle()
    if (entries.isEmpty()) {
        ClipsEmptyState(sectionPadding = sectionPadding, modifier = modifier)
        return
    }

    var pendingDelete by remember { mutableStateOf<ClipEntry?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        NuvioSectionLabel(
            text = "Your clips",
            modifier = Modifier.padding(horizontal = sectionPadding),
        )
        Spacer(modifier = Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = sectionPadding),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(entries, key = { it.id }) { entry ->
                ClipCard(
                    entry = entry,
                    modifier = Modifier.width(196.dp),
                    onClick = { ClipLibrary.open(entry.id) },
                    onReveal = { ClipLibrary.reveal(entry.id) },
                    onDelete = { pendingDelete = entry },
                )
            }
        }
    }

    val deleteTarget = pendingDelete
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Move this clip to Trash?") },
            // Says where the file goes, because that is the difference between
            // a mistake being recoverable and not.
            text = { Text("${deleteTarget.fileName} will be moved to your system Trash.") },
            confirmButton = {
                TextButton(onClick = {
                    ClipLibrary.delete(deleteTarget.id)
                    pendingDelete = null
                }) { Text("Move to Trash") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

/**
 * What home shows before the first clip exists.
 *
 * The row used to delete itself when empty, so a first run gave no evidence the
 * app clips anything at all -- the one moment a user most needs telling. This
 * doubles as the onboarding: the three steps, and where the files will land.
 */
@Composable
private fun ClipsEmptyState(
    sectionPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val outputDir = remember { ClipRepository.outputDirPath() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = sectionPadding),
    ) {
        NuvioSectionLabel(text = "Your clips")
        Spacer(modifier = Modifier.height(10.dp))
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
            if (outputDir.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Clips are saved to $outputDir",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Home-screen slot for [HomeClipsSection]. */
internal fun LazyListScope.homeClipsSection(sectionPadding: Dp) {
    item(key = "home_clips_row") {
        HomeClipsSection(
            sectionPadding = sectionPadding,
            modifier = Modifier.padding(bottom = 18.dp),
        )
    }
}
