package com.nuvio.app.features.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.clip.ClipFilenameSettings
import com.nuvio.app.features.clip.ClipFilenameTemplate
import com.nuvio.app.features.clip.ClipFolderPicker
import com.nuvio.app.features.clip.ClipGroupingSettings
import com.nuvio.app.features.clip.ClipRepository
import com.nuvio.app.features.mcp.McpServerControl
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.clip_action_cancel
import nuvio.composeapp.generated.resources.clip_action_save
import nuvio.composeapp.generated.resources.clip_settings_filename_dialog_title
import nuvio.composeapp.generated.resources.clip_settings_filename_preview_format
import nuvio.composeapp.generated.resources.clip_settings_filename_reset
import nuvio.composeapp.generated.resources.clip_settings_filename_title
import nuvio.composeapp.generated.resources.clip_settings_filename_tokens
import nuvio.composeapp.generated.resources.clip_settings_folder_default_format
import nuvio.composeapp.generated.resources.clip_settings_folder_error
import nuvio.composeapp.generated.resources.clip_settings_folder_reset_title
import nuvio.composeapp.generated.resources.clip_settings_folder_title
import nuvio.composeapp.generated.resources.clip_settings_group_description_off
import nuvio.composeapp.generated.resources.clip_settings_group_description_on
import nuvio.composeapp.generated.resources.clip_settings_group_example_format
import nuvio.composeapp.generated.resources.clip_settings_group_title
import nuvio.composeapp.generated.resources.clip_settings_mcp_copied
import nuvio.composeapp.generated.resources.clip_settings_mcp_copy_description
import nuvio.composeapp.generated.resources.clip_settings_mcp_copy_title
import nuvio.composeapp.generated.resources.clip_settings_mcp_description_off
import nuvio.composeapp.generated.resources.clip_settings_mcp_desktop_description
import nuvio.composeapp.generated.resources.clip_settings_mcp_desktop_title
import nuvio.composeapp.generated.resources.clip_settings_mcp_description_on_format
import nuvio.composeapp.generated.resources.clip_settings_mcp_error_format
import nuvio.composeapp.generated.resources.clip_settings_mcp_section
import nuvio.composeapp.generated.resources.clip_settings_mcp_title
import nuvio.composeapp.generated.resources.clip_settings_section
import org.jetbrains.compose.resources.stringResource

/**
 * "Where clips are saved" and "what they are called" — desktop only, since clip
 * export is desktop only.
 *
 * Lives in its own clipper-owned file rather than inside
 * [PlaybackSettingsPage], so the only upstream edit is the single call site.
 */
@Composable
internal fun ClipsSettingsSection(isTablet: Boolean) {
    if (!ClipRepository.isSupported) return

    var outputDir by remember { mutableStateOf(ClipRepository.outputDirPath()) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var template by remember { mutableStateOf(ClipFilenameSettings.template()) }
    var groupByTitle by remember { mutableStateOf(ClipGroupingSettings.isEnabled()) }
    var editingTemplate by remember { mutableStateOf<String?>(null) }
    val defaultDir = remember { ClipRepository.defaultOutputDirPath() }
    val isDefault = outputDir == defaultDir
    val folderError = stringResource(Res.string.clip_settings_folder_error)

    SettingsSection(
        title = stringResource(Res.string.clip_settings_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsNavigationRow(
                title = stringResource(Res.string.clip_settings_folder_title),
                description = errorMessage
                    ?: outputDir.ifBlank { defaultDir }.let {
                        if (isDefault) stringResource(Res.string.clip_settings_folder_default_format, it) else it
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
                            errorMessage = folderError
                        }
                    }
                },
            )
            if (!isDefault) {
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.clip_settings_folder_reset_title),
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
            SettingsGroupDivider(isTablet = isTablet)
            SettingsSwitchRow(
                title = stringResource(Res.string.clip_settings_group_title),
                // What the setting does, then what it would produce: the rule
                // alone does not answer "so where does my next clip go".
                description = stringResource(
                    if (groupByTitle) {
                        Res.string.clip_settings_group_description_on
                    } else {
                        Res.string.clip_settings_group_description_off
                    },
                ) + "\n" + stringResource(
                    Res.string.clip_settings_group_example_format,
                    ClipGroupingSettings.preview(groupByTitle),
                ),
                checked = groupByTitle,
                isTablet = isTablet,
                onCheckedChange = { enabled ->
                    ClipGroupingSettings.setEnabled(enabled)
                    groupByTitle = ClipGroupingSettings.isEnabled()
                },
            )
            SettingsGroupDivider(isTablet = isTablet)
            SettingsNavigationRow(
                title = stringResource(Res.string.clip_settings_filename_title),
                // The rendered example, not the template: what a filename will
                // look like is the thing being chosen, and the tokens that
                // produce it are one tap away in the editor.
                description = ClipFilenameSettings.preview(template),
                icon = Icons.Rounded.DriveFileRenameOutline,
                isTablet = isTablet,
                onClick = { editingTemplate = template },
            )
        }
    }

    val draft = editingTemplate
    if (draft != null) {
        ClipFilenameDialog(
            draft = draft,
            onDraftChange = { editingTemplate = it },
            onDismiss = { editingTemplate = null },
            onSave = {
                ClipFilenameSettings.setTemplate(draft)
                template = ClipFilenameSettings.template()
                editingTemplate = null
            },
        )
    }

    McpSettingsSection(isTablet = isTablet)
}

/**
 * The switch for the MCP server, and the one thing needed to use it.
 *
 * The setup command is copied rather than shown: it carries the access token,
 * and a settings page is the kind of screen that ends up in a screenshot.
 */
@Composable
private fun McpSettingsSection(isTablet: Boolean) {
    if (!McpServerControl.isSupported) return

    var enabled by remember { mutableStateOf(McpServerControl.isEnabled()) }
    var error by remember { mutableStateOf(McpServerControl.lastError()) }
    // Which of the two snippets was copied last, so only that row says so.
    var copied by remember { mutableStateOf<String?>(null) }
    val clipboardManager = LocalClipboardManager.current
    val desktopConfig = remember { McpServerControl.desktopConfigSnippet() }

    SettingsSection(
        title = stringResource(Res.string.clip_settings_mcp_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.clip_settings_mcp_title),
                description = when {
                    !enabled -> stringResource(Res.string.clip_settings_mcp_description_off)
                    error != null -> stringResource(Res.string.clip_settings_mcp_error_format, error.orEmpty())
                    else -> stringResource(
                        Res.string.clip_settings_mcp_description_on_format,
                        McpServerControl.endpointUrl(),
                    )
                },
                checked = enabled,
                isTablet = isTablet,
                onCheckedChange = { checked ->
                    McpServerControl.setEnabled(checked)
                    enabled = McpServerControl.isEnabled()
                    error = McpServerControl.lastError()
                    copied = null
                },
            )
            if (enabled && error == null) {
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.clip_settings_mcp_copy_title),
                    description = stringResource(
                        if (copied == "code") {
                            Res.string.clip_settings_mcp_copied
                        } else {
                            Res.string.clip_settings_mcp_copy_description
                        },
                    ),
                    icon = Icons.Rounded.ContentCopy,
                    isTablet = isTablet,
                    onClick = {
                        clipboardManager.setText(AnnotatedString(McpServerControl.setupCommand()))
                        copied = "code"
                    },
                )
                if (desktopConfig != null) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.clip_settings_mcp_desktop_title),
                        description = stringResource(
                            if (copied == "desktop") {
                                Res.string.clip_settings_mcp_copied
                            } else {
                                Res.string.clip_settings_mcp_desktop_description
                            },
                        ),
                        icon = Icons.Rounded.ContentCopy,
                        isTablet = isTablet,
                        onClick = {
                            clipboardManager.setText(AnnotatedString(desktopConfig))
                            copied = "desktop"
                        },
                    )
                }
            }
        }
    }
}

/**
 * The template editor.
 *
 * The preview updates as you type, because a template language you cannot see
 * the output of is one you have to export a clip to test.
 */
@Composable
private fun ClipFilenameDialog(
    draft: String,
    onDraftChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.clip_settings_filename_dialog_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.merge(
                        TextStyle(color = MaterialTheme.colorScheme.onSurface),
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color.White.copy(alpha = 0.07f))
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(Res.string.clip_settings_filename_tokens),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        Res.string.clip_settings_filename_preview_format,
                        ClipFilenameSettings.preview(draft),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (draft.trim() != ClipFilenameTemplate.Default) {
                    Spacer(modifier = Modifier.height(10.dp))
                    TextButton(onClick = { onDraftChange(ClipFilenameTemplate.Default) }) {
                        Text(stringResource(Res.string.clip_settings_filename_reset))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave) { Text(stringResource(Res.string.clip_action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.clip_action_cancel)) }
        },
    )
}
