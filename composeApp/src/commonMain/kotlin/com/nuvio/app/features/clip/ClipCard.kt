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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
    /** Under a film's heading on the clips page, where naming the film again on every card is noise. */
    grouped: Boolean = false,
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

    // Hover scrub: a few stills across the clip, picked by where the pointer is
    // over the card, so a clip can be recognised without opening it. Fetched on
    // the first hover (built once, cached on disk beside the clip's still).
    var hoverFrames by remember(entry.outputFileUri) { mutableStateOf<List<String>>(emptyList()) }
    var scrubFraction by remember { mutableFloatStateOf(-1f) }
    LaunchedEffect(hovered, entry.outputFileUri) {
        if (hovered && hoverFrames.isEmpty()) hoverFrames = ClipExtractor.hoverFrames(entry.outputFileUri, entry.durationMs)
        if (!hovered) scrubFraction = -1f
    }

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
                // Observes the pointer without consuming it: click, right-click
                // and drag-out all still reach the card.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val x = event.changes.firstOrNull()?.position?.x ?: continue
                            if (size.width > 0) scrubFraction = (x / size.width).coerceIn(0f, 0.999f)
                        }
                    }
                }
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
            val scrubbing = hovered && !selectionMode && hoverFrames.isNotEmpty() && scrubFraction >= 0f
            val shown = if (scrubbing) hoverFrames[(scrubFraction * hoverFrames.size).toInt()] else artwork
            if (shown.isNotBlank()) {
                NuvioAsyncImage(
                    model = shown,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            if (scrubbing) {
                // Where in the clip the still is from: a paper tick along the foot.
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(scrubFraction.coerceAtLeast(0.02f))
                        .height(3.dp)
                        .background(Riso.Paper.copy(alpha = 0.9f)),
                )
            }

            ClipBadge(
                text = formatClipLength(entry.durationMs),
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
            )
            // What the file is, for the one moment it matters: when choosing it.
            val fileFacts = listOf(entry.resolutionLabel, entry.fileSizeLabel).filter { it.isNotBlank() }
            if (hovered && fileFacts.isNotEmpty()) {
                Row(
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    fileFacts.forEach { ClipBadge(text = it) }
                }
            }

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

        // Under a film's heading the range leads -- it is what tells one clip of
        // a film from the next -- and the file name follows, since Save as
        // names clips for an edit ("lift reveal 03") and they are found by it.
        // Anywhere else the film leads. Resolution and size wait for hover.
        Column(
            modifier = Modifier.padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val film = entry.content.label
            val range = clipRangeLabel(entry)
            val lead = if (grouped || film.isBlank()) range else film
            Text(
                text = lead,
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                color = Riso.Paper,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = entry.fileName,
                style = MaterialTheme.typography.bodySmall,
                color = Riso.Paper.copy(alpha = 0.82f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (lead != range) {
                Text(
                    text = range,
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = Riso.PaperDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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

/** Where in the film the clip was cut, e.g. `24:31 – 25:09`. */
internal fun clipRangeLabel(entry: ClipEntry): String =
    "${formatClipClockLabel(entry.startMs)} \u2013 ${formatClipClockLabel(entry.endMs)}"

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
