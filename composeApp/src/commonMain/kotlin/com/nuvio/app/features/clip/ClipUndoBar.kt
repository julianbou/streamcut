package com.nuvio.app.features.clip

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.Riso
import com.nuvio.app.core.ui.risoGrain
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.clip_undo_action
import nuvio.composeapp.generated.resources.clip_undo_many_format
import nuvio.composeapp.generated.resources.clip_undo_one_format
import org.jetbrains.compose.resources.stringResource

/**
 * "Deleted <clip> — Undo", for as long as that is still true.
 *
 * Mounted once at app level rather than per screen, because the window it
 * offers outlives the surface the delete happened on: deleting from the home
 * row and then opening the clips page must not silently drop the chance to
 * take it back. It reads [ClipLibrary.pendingUndo] directly for the same
 * reason -- there is no state here to hand around.
 *
 * Deliberately not the app's toast host: that carries a message and nothing
 * else, and adding an action to it would mean editing an upstream component
 * for something only the clipper needs.
 */
@Composable
internal fun ClipUndoBar(modifier: Modifier = Modifier) {
    val pending by ClipLibrary.pendingUndo.collectAsStateWithLifecycle()
    // Kept across the exit animation: the batch goes null the instant the
    // window closes, and rendering it directly would blank the bar's text out
    // from under the animation on its way off screen.
    var shown by remember { mutableStateOf<ClipUndoBatch?>(null) }
    LaunchedEffect(pending) { if (pending != null) shown = pending }

    AnimatedVisibility(
        visible = pending != null,
        enter = fadeIn() + slideInVertically { it / 2 },
        exit = fadeOut() + slideOutVertically { it / 2 },
        modifier = modifier,
    ) {
        val entries = shown?.entries.orEmpty()

        // Raised stock with the paper grain, like every other surface that
        // floats in the print; pink is the one ink, on the thing to act on.
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Riso.StockRaised,
            modifier = Modifier.padding(20.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .risoGrain()
                    .padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
            ) {
                Text(
                    text = if (entries.size == 1) {
                        stringResource(
                            Res.string.clip_undo_one_format,
                            entries.first().content.label.ifBlank { entries.first().fileName },
                        )
                    } else {
                        stringResource(Res.string.clip_undo_many_format, entries.size)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Riso.Paper,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(Res.string.clip_undo_action),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = Riso.Pink,
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .clickable { ClipLibrary.undoDelete() }
                        .background(Riso.Paper.copy(alpha = 0.08f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}
