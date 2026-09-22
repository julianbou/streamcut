package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.clip.ClipExtractor
import com.nuvio.app.features.clip.ClipRepository
import com.nuvio.app.features.clip.ClipToolStatus
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.clip_settings_app_appearance_description
import nuvio.composeapp.generated.resources.clip_settings_app_section
import nuvio.composeapp.generated.resources.clip_settings_languages_link
import nuvio.composeapp.generated.resources.clip_settings_languages_note
import nuvio.composeapp.generated.resources.clip_settings_sources_addons_description
import nuvio.composeapp.generated.resources.clip_settings_sources_integrations_description
import nuvio.composeapp.generated.resources.clip_settings_sources_section
import nuvio.composeapp.generated.resources.clip_settings_tools_available
import nuvio.composeapp.generated.resources.clip_settings_tools_checking
import nuvio.composeapp.generated.resources.clip_settings_tools_encoder
import nuvio.composeapp.generated.resources.clip_settings_tools_encoder_none
import nuvio.composeapp.generated.resources.clip_settings_tools_ffmpeg
import nuvio.composeapp.generated.resources.clip_settings_tools_hdr
import nuvio.composeapp.generated.resources.clip_settings_tools_hdr_missing
import nuvio.composeapp.generated.resources.clip_settings_tools_missing
import nuvio.composeapp.generated.resources.clip_settings_tools_section
import nuvio.composeapp.generated.resources.clip_settings_tools_subtitles
import nuvio.composeapp.generated.resources.clip_settings_tools_subtitles_missing
import nuvio.composeapp.generated.resources.compose_settings_page_addons
import nuvio.composeapp.generated.resources.compose_settings_page_appearance
import nuvio.composeapp.generated.resources.compose_settings_page_integrations
import nuvio.composeapp.generated.resources.compose_settings_page_plugins
import org.jetbrains.compose.resources.stringResource

// Settings for the clipper build, organised around making clips rather than
// watching: Clips, Sources, Player, Account, App. Upstream's categories and
// pages are untouched and still used when viewing chrome is on -- this file
// only decides which of them the clipper build shows, and where.

/** True in the clipper build, where settings use the clip-first categories. */
internal val clipperSettings: Boolean
    get() = !AppFeaturePolicy.viewingChromeEnabled

/** The sidebar categories this build shows, in order. */
internal fun settingsCategoriesForBuild(): List<SettingsCategory> =
    if (clipperSettings) {
        listOf(
            SettingsCategory.Clips,
            SettingsCategory.Sources,
            SettingsCategory.Player,
            SettingsCategory.Account,
            SettingsCategory.App,
        )
    } else {
        listOf(
            SettingsCategory.Account,
            SettingsCategory.General,
            SettingsCategory.About,
            SettingsCategory.Advanced,
        )
    }

internal fun defaultSettingsCategory(): SettingsCategory =
    if (clipperSettings) SettingsCategory.Clips else SettingsCategory.General

/**
 * Which sidebar category a page belongs to in this build. Upstream's pages keep
 * their own category; the clipper build files them under its five.
 */
internal fun SettingsPage.categoryForBuild(): SettingsCategory {
    if (!clipperSettings) return category
    return when (this) {
        SettingsPage.ContentDiscovery,
        SettingsPage.Addons,
        SettingsPage.Plugins,
        SettingsPage.Integrations,
        SettingsPage.TmdbEnrichment,
        SettingsPage.MdbListRatings,
        SettingsPage.Debrid,
        -> SettingsCategory.Sources
        SettingsPage.Playback,
        SettingsPage.Streams,
        -> SettingsCategory.Player
        SettingsPage.Account,
        SettingsPage.TraktAuthentication,
        -> SettingsCategory.Account
        SettingsPage.Root -> SettingsCategory.Clips
        else -> SettingsCategory.App
    }
}

/** The Clips category: where exports go and what the machine can export. */
internal fun LazyListScope.clipsCategoryContent(
    isTablet: Boolean,
    onPlayerSettingsClick: () -> Unit,
) {
    item { ClipsSettingsSection(isTablet = isTablet) }
    item { ClipToolsSection(isTablet = isTablet) }
    item {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Text(
                text = stringResource(Res.string.clip_settings_languages_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.nuvio.colors.textMuted,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(Res.string.clip_settings_languages_link),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.nuvio.colors.accent,
                modifier = Modifier.clickable(onClick = onPlayerSettingsClick),
            )
        }
    }
}

/**
 * Which ffmpeg exports use and whether it can burn subtitles and tonemap HDR.
 * Both degrade silently when missing, so the place to learn it is here, not
 * from a grey or subtitle-less clip.
 */
@Composable
private fun ClipToolsSection(isTablet: Boolean) {
    if (!ClipRepository.isSupported) return
    var status by remember { mutableStateOf<ClipToolStatus?>(null) }
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        status = ClipExtractor.toolStatus()
        checked = true
    }
    SettingsSection(
        title = stringResource(Res.string.clip_settings_tools_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            val current = status
            when {
                !checked -> ToolRow(stringResource(Res.string.clip_settings_tools_checking), null, ok = null)
                current?.path == null -> ToolRow(
                    title = stringResource(Res.string.clip_settings_tools_ffmpeg),
                    detail = stringResource(Res.string.clip_settings_tools_missing),
                    ok = false,
                )
                else -> {
                    ToolRow(
                        title = stringResource(Res.string.clip_settings_tools_ffmpeg),
                        detail = listOf(current.version, current.path).filter { it.isNotBlank() }.joinToString(" · "),
                        ok = true,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    ToolRow(
                        title = stringResource(Res.string.clip_settings_tools_subtitles),
                        detail = if (current.burnInSubtitles) stringResource(Res.string.clip_settings_tools_available)
                        else stringResource(Res.string.clip_settings_tools_subtitles_missing),
                        ok = current.burnInSubtitles,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    ToolRow(
                        title = stringResource(Res.string.clip_settings_tools_hdr),
                        detail = if (current.hdrTonemap) stringResource(Res.string.clip_settings_tools_available)
                        else stringResource(Res.string.clip_settings_tools_hdr_missing),
                        ok = current.hdrTonemap,
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    ToolRow(
                        title = stringResource(Res.string.clip_settings_tools_encoder),
                        detail = current.hardwareEncoder ?: stringResource(Res.string.clip_settings_tools_encoder_none),
                        ok = current.hardwareEncoder != null,
                    )
                }
            }
        }
    }
}

/** A capability line: ok (blue check), missing (red), or unknown (no mark). */
@Composable
private fun ToolRow(title: String, detail: String?, ok: Boolean?) {
    val colors = MaterialTheme.nuvio.colors
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ok != null) {
            Icon(
                imageVector = if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = if (ok) colors.success else colors.danger,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(14.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = colors.textPrimary)
            if (!detail.isNullOrBlank()) {
                Text(text = detail, style = MaterialTheme.typography.bodyMedium, color = colors.textMuted)
            }
        }
    }
}

/** The Sources category's links: addons, plugins, and the services behind streams. */
internal fun LazyListScope.sourcesCategoryLinks(
    isTablet: Boolean,
    onOpenPage: (SettingsPage) -> Unit,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.clip_settings_sources_section),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_addons),
                    description = stringResource(Res.string.clip_settings_sources_addons_description),
                    icon = Icons.Rounded.Extension,
                    isTablet = isTablet,
                    onClick = { onOpenPage(SettingsPage.Addons) },
                )
                if (AppFeaturePolicy.pluginsEnabled) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.compose_settings_page_plugins),
                        description = null,
                        icon = Icons.Rounded.Apps,
                        isTablet = isTablet,
                        onClick = { onOpenPage(SettingsPage.Plugins) },
                    )
                }
                SettingsGroupDivider(isTablet = isTablet)
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_integrations),
                    description = stringResource(Res.string.clip_settings_sources_integrations_description),
                    icon = Icons.Rounded.Link,
                    isTablet = isTablet,
                    onClick = { onOpenPage(SettingsPage.Integrations) },
                )
            }
        }
    }
}

/** The App category's own links; About and Advanced follow from the upstream root. */
internal fun LazyListScope.appCategoryLinks(
    isTablet: Boolean,
    onOpenPage: (SettingsPage) -> Unit,
) {
    item {
        SettingsSection(
            title = stringResource(Res.string.clip_settings_app_section),
            isTablet = isTablet,
        ) {
            SettingsGroup(isTablet = isTablet) {
                SettingsNavigationRow(
                    title = stringResource(Res.string.compose_settings_page_appearance),
                    description = stringResource(Res.string.clip_settings_app_appearance_description),
                    icon = Icons.Rounded.Palette,
                    isTablet = isTablet,
                    onClick = { onOpenPage(SettingsPage.Appearance) },
                )
            }
        }
    }
}
