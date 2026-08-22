package com.nuvio.app.features.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.app.features.clip.ClipFolderPicker
import com.nuvio.app.features.clip.ClipRepository

/**
 * "Where clips are saved" — desktop only, since clip export is desktop only.
 *
 * Lives in its own clipper-owned file rather than inside
 * [PlaybackSettingsPage], so the only upstream edit is the single call site.
 */
@Composable
internal fun ClipsSettingsSection(isTablet: Boolean) {
    if (!ClipRepository.isSupported) return

    var outputDir by remember { mutableStateOf(ClipRepository.outputDirPath()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val defaultDir = remember { ClipRepository.defaultOutputDirPath() }
    val isDefault = outputDir == defaultDir

    SettingsSection(
        title = "Clips",
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = "Clips folder",
                description = errorMessage
                    ?: outputDir.ifBlank { defaultDir }.let {
                        if (isDefault) "$it (default)" else it
                    },
                icon = Icons.Rounded.FolderOpen,
                enabled = ClipFolderPicker.canPick,
                isTablet = isTablet,
                onClick = {
                    errorMessage = null
                    ClipFolderPicker.pickDirectory(outputDir.takeIf { it.isNotBlank() }) { picked ->
                        if (picked == null) return@pickDirectory
                        if (ClipRepository.setOutputDirPath(picked)) {
                            outputDir = ClipRepository.outputDirPath()
                        } else {
                            errorMessage = "Can't write to that folder — keeping the previous one"
                        }
                    }
                },
            )
            if (!isDefault) {
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = "Reset to default folder",
                    description = defaultDir,
                    icon = Icons.Rounded.Refresh,
                    isTablet = isTablet,
                    onClick = {
                        errorMessage = null
                        ClipRepository.setOutputDirPath(null)
                        outputDir = ClipRepository.outputDirPath()
                    },
                )
            }
        }
    }
}
