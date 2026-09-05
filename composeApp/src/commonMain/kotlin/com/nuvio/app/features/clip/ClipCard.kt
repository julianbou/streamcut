package com.nuvio.app.features.clip

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.NuvioAsyncImage
import com.nuvio.app.core.ui.fileDragSource
import com.nuvio.app.core.ui.secondaryClick
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.clip_action_delete
import nuvio.composeapp.generated.resources.clip_action_delete_short
import nuvio.composeapp.generated.resources.clip_action_play
import nuvio.composeapp.generated.resources.clip_action_reveal
import nuvio.composeapp.generated.resources.clip_action_reveal_short
import org.jetbrains.compose.resources.stringResource

/**
 * One clip, as it appears in the home row and in the clips grid.
 *
 * Desktop idioms throughout, because this is a desktop-only feature: right-click
 * for the menu, hover for the quick actions, and drag to hand the file to
 * whatever is on the other side of the window. The long-press dialog this
 * replaces was a touch gesture nobody was going to find with a mouse.
 */
@Composable
internal fun ClipCard(
    entry: ClipEntry,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onClick: () -> Unit,
    onReveal: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    // Resolved once per entry: the drag modifier needs a path, while identity
    // everywhere else in the clip feature is the file: URI.
    val filePath = remember(entry.outputFileUri) { ClipRepository.filePathOf(entry.outputFileUri) }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .hoverable(interactionSource)
            .fileDragSource(filePath)
            .secondaryClick { menuOpen = true }
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.White.copy(alpha = 0.06f))
                .then(
                    if (selected) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(12.dp))
                    } else {
                        Modifier
                    },
                ),
        ) {
            // The clip's own midpoint frame, falling back to the title's poster
            // for clips exported before stills were captured.
            val artwork = entry.thumbnailUri.ifBlank { entry.content.posterUrl }
            if (artwork.isNotBlank()) {
                NuvioAsyncImage(
                    model = artwork,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }

            ClipBadge(
                text = formatClipLength(entry.durationMs),
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
            )

            // Quick actions on hover. Suppressed while selecting, where a click
            // means "add to the selection" and a stray Delete would be a trap.
            if (hovered && !selectionMode) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ClipHoverAction(
                        label = stringResource(Res.string.clip_action_reveal_short),
                        onClick = onReveal,
                    )
                    ClipHoverAction(
                        label = stringResource(Res.string.clip_action_delete_short),
                        onClick = onDelete,
                    )
                }
            }

            if (selected) {
                ClipBadge(
                    text = "✓",
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                )
            }

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.clip_action_play)) },
                    onClick = {
                        menuOpen = false
                        onClick()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.clip_action_reveal)) },
                    onClick = {
                        menuOpen = false
                        onReveal()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(Res.string.clip_action_delete)) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }

        Column(modifier = Modifier.padding(top = 6.dp)) {
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
            val fits = entry.fitsLabels
            if (fits.isNotEmpty()) {
                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    fits.forEach { target ->
                        Text(
                            text = target,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(5.dp))
                                .background(Color.White.copy(alpha = 0.09f))
                                .padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.66f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun ClipHoverAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/**
 * The line under a clip's title: where it came from in the film, then what it
 * is. Facts that were never captured are dropped rather than shown as blanks --
 * every clip exported before stills and sizes existed has none of them.
 */
internal fun clipSubtitle(entry: ClipEntry): String = listOf(
    "${formatClipClockLabel(entry.startMs)} - ${formatClipClockLabel(entry.endMs)}",
    entry.resolutionLabel,
    entry.fileSizeLabel,
).filter { it.isNotBlank() }.joinToString(" · ")

internal fun formatClipLength(durationMs: Long): String {
    val tenths = (durationMs.coerceAtLeast(0L) + 50) / 100
    return if (tenths >= 600) {
        val totalSeconds = tenths / 10
        "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
    } else {
        "${tenths / 10}.${tenths % 10}s"
    }
}

internal fun formatClipClockLabel(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0L) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    val mm = minutes.toString().padStart(2, '0')
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:$mm:$ss" else "$mm:$ss"
}

/** Free-space and size figures for the library header. */
internal fun formatClipBytes(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes < 1_000_000L -> "${(bytes / 1_000L).coerceAtLeast(1L)} KB"
    bytes < 1_000_000_000L -> "${bytes / 1_000_000L} MB"
    else -> {
        val tenths = bytes / 100_000_000L
        "${tenths / 10}.${tenths % 10} GB"
    }
}
