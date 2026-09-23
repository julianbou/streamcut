package com.nuvio.app.features.settings

import com.nuvio.app.core.build.AppFeaturePolicy
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.settings_appearance_desktop_navigation_sidebar
import nuvio.composeapp.generated.resources.settings_appearance_desktop_navigation_top_bar
import org.jetbrains.compose.resources.StringResource

enum class DesktopNavigationLayout(
    val labelRes: StringResource,
) {
    Sidebar(Res.string.settings_appearance_desktop_navigation_sidebar),
    TopBar(Res.string.settings_appearance_desktop_navigation_top_bar),
    ;

    companion object {
        // StreamCut picked the top bar for the clipper build (2026-09-23);
        // upstream builds keep upstream's sidebar.
        val Default: DesktopNavigationLayout
            get() = if (AppFeaturePolicy.viewingChromeEnabled) Sidebar else TopBar

        fun fromName(name: String?): DesktopNavigationLayout =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: Default
    }
}
