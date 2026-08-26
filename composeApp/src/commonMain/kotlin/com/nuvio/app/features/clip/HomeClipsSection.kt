package com.nuvio.app.features.clip

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.ui.NuvioSectionLabel
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.clip_action_cancel
import nuvio.composeapp.generated.resources.clip_action_delete
import nuvio.composeapp.generated.resources.clip_delete_one_message_format
import nuvio.composeapp.generated.resources.clip_delete_one_title
import nuvio.composeapp.generated.resources.clip_section_your_clips
import org.jetbrains.compose.resources.stringResource

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
        Column(modifier = modifier.fillMaxWidth().padding(horizontal = sectionPadding)) {
            NuvioSectionLabel(text = stringResource(Res.string.clip_section_your_clips))
            Spacer(modifier = Modifier.height(10.dp))
            ClipsEmptyCard()
        }
        return
    }

    var pendingDelete by remember { mutableStateOf<ClipEntry?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        NuvioSectionLabel(
            text = stringResource(Res.string.clip_section_your_clips),
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
            title = { Text(stringResource(Res.string.clip_delete_one_title)) },
            // Says where the file goes, because that is the difference between
            // a mistake being recoverable and not.
            text = {
                Text(
                    stringResource(
                        Res.string.clip_delete_one_message_format,
                        deleteTarget.fileName,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    ClipLibrary.delete(deleteTarget.id)
                    pendingDelete = null
                }) { Text(stringResource(Res.string.clip_action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(Res.string.clip_action_cancel))
                }
            },
        )
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
