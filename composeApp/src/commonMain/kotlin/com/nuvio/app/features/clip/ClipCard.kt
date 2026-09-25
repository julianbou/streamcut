package com.nuvio.app.features.clip

import com.nuvio.app.core.ui.Riso
import com.nuvio.app.core.ui.risoInkTile
import com.nuvio.app.core.ui.risoSelectionInk
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
    // Tabbing onto a clip lights it like pointing at it, plus a pink ring below.
    val focused by interactionSource.collectIsFocusedAsState()
    // Resolved once per entry: the drag modifier needs a path, while identity
    // everywhere else in the clip feature is the file: URI.
    val filePath = remember(entry.outputFileUri) { ClipRepository.filePathOf(entry.outputFileUri) }

    val glow by animateFloatAsState(if (hovered || focused || selected) 1f else 0f, tween(320), label = "clipCardGlow")
    Column(
        modifier = modifier
            .hoverable(interactionSource)
            .fileDragSource(filePath)
            .secondaryClick { menuOpen = true }
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                // Hover prints sun ink behind the still; a selected clip is
                // held in pink (you are about to act on it).
                .risoSelectionInk(if (selected) Riso.Pink else Riso.Sun, glow, spread = 1.2f)
                .clip(RoundedCornerShape(14.dp))
                // Sun is the brightest ink; at full strength a missing still
                // shouts louder than the clips that have one.
                // Selection is the pink bloom plus the check -- never an outline.
                .risoInkTile(Riso.Sun, strength = 0.42f)
                // Keyboard focus draws pink ink over the still: the glow alone
                // sits behind a full-bleed frame and barely shows, so a Tab
                // through the grid had nothing to follow.
                .then(
                    if (focused && !hovered) {
                        Modifier.border(2.dp, Riso.Pink, RoundedCornerShape(14.dp))
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
                        color = Riso.Red,
                    )
                }
            }

            if (selected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = Riso.Pink,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(22.dp),
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
                    text = { Text(stringResource(Res.string.clip_action_delete), color = Riso.Red) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }

        // Three lines, each one fact: the film, the file it became, and where in
        // the film it was cut. The file name matters since Save as -- a clip
        // named for an edit ("lift reveal 03") is found by that name, not by
        // its title.
        Column(
            modifier = Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val film = entry.content.label
            if (film.isNotBlank()) {
                Text(
                    text = film,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Riso.Paper,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = entry.fileName,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (film.isBlank()) FontWeight.SemiBold else FontWeight.Normal,
                color = if (film.isBlank()) Riso.Paper else Riso.Paper.copy(alpha = 0.82f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = clipSubtitle(entry),
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                color = Riso.PaperDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ClipBadge(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Riso.Paper,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Riso.Stock.copy(alpha = 0.8f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun ClipHoverAction(label: String, onClick: () -> Unit, color: Color = Riso.Paper) {
    Text(
        text = label,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Riso.Stock.copy(alpha = 0.82f))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/**
 * The line under a clip's title: where it came from in the film, then what it
 * is. Facts that were never captured are dropped rather than shown as blanks --
 * every clip exported before stills and sizes existed has none of them.
 */
internal fun clipSubtitle(entry: ClipEntry): String = listOf(
    "${formatClipClockLabel(entry.startMs)} \u2013 ${formatClipClockLabel(entry.endMs)}",
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
