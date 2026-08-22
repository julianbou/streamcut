package com.nuvio.app.features.clip

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioSectionLabel
import com.nuvio.app.core.ui.NuvioAsyncImage

/**
 * "Your clips" row on the home screen: every clip exported on this machine,
 * newest first.
 *
 * Clip files are local, so this row is deliberately independent of catalogs,
 * addons and sync -- it renders straight from [ClipLibrary] and needs no
 * network. Tapping plays the file in the system video player; long-pressing
 * offers to reveal or delete it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeClipsSection(
    sectionPadding: Dp,
    modifier: Modifier = Modifier,
) {
    if (!ClipRepository.isSupported) return
    // Must run before the empty-list early return below: on a cold start the
    // library is empty until it is read back from disk.
    LaunchedEffect(Unit) { ClipLibrary.ensureLoaded() }
    val entries by ClipLibrary.entries.collectAsStateWithLifecycle()
    if (entries.isEmpty()) return

    var pendingAction by remember { mutableStateOf<ClipEntry?>(null) }
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
                    onClick = { ClipLibrary.open(entry.id) },
                    onLongPress = { pendingAction = entry },
                )
            }
        }
    }

    val actionTarget = pendingAction
    if (actionTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text(actionTarget.fileName) },
            text = { Text(clipSubtitle(actionTarget)) },
            confirmButton = {
                TextButton(onClick = {
                    ClipLibrary.reveal(actionTarget.id)
                    pendingAction = null
                }) { Text("Show in file manager") }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingDelete = actionTarget
                    pendingAction = null
                }) { Text("Delete") }
            },
        )
    }

    val deleteTarget = pendingDelete
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this clip?") },
            // Deletion removes the file from disk, not just the list, so say so.
            text = { Text("${deleteTarget.fileName} will be permanently removed from your clips folder.") },
            confirmButton = {
                TextButton(onClick = {
                    ClipLibrary.delete(deleteTarget.id)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ClipCard(
    entry: ClipEntry,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(196.dp)
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.06f)),
        ) {
            if (entry.content.posterUrl.isNotBlank()) {
                NuvioAsyncImage(
                    model = entry.content.posterUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            }
            Text(
                text = formatClipLength(entry.durationMs),
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.66f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = entry.content.label.ifBlank { entry.fileName },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = clipSubtitle(entry),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun clipSubtitle(entry: ClipEntry): String =
    "${formatClipClockLabel(entry.startMs)} - ${formatClipClockLabel(entry.endMs)}"

private fun formatClipLength(durationMs: Long): String {
    val tenths = (durationMs.coerceAtLeast(0L) + 50) / 100
    return if (tenths >= 600) {
        val totalSeconds = tenths / 10
        "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
    } else {
        "${tenths / 10}.${tenths % 10}s"
    }
}

private fun formatClipClockLabel(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val mm = minutes.toString().padStart(2, '0')
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:$mm:$ss" else "$mm:$ss"
}

/** Home-screen slot for [HomeClipsSection]; a no-op when there are no clips. */
internal fun LazyListScope.homeClipsSection(sectionPadding: Dp) {
    item(key = "home_clips_row") {
        HomeClipsSection(
            sectionPadding = sectionPadding,
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
}
