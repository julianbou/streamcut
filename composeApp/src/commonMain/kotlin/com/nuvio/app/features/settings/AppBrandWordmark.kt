package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.nuvio.app.core.branding.AppBrandMark
import com.nuvio.app.core.branding.ForkBranding

/**
 * Logo lockup: the mark followed by the app name, both sized from the height
 * the caller gives it.
 *
 * Upstream draws a baked PNG wordmark per icon colour here. The clipper renders
 * the name as text instead, so a rename in `fork.appName` (gradle.properties)
 * needs no new artwork. [icon] stays for call-site compatibility; there is one
 * logo, so it is ignored.
 */
@Composable
internal fun AppBrandWordmark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    @Suppress("UNUSED_PARAMETER") icon: AppIconOption? = null,
) {
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
        val lockupHeight = if (constraints.hasBoundedHeight) maxHeight else 40.dp
        val nameSize = with(LocalDensity.current) { (lockupHeight * 0.6f).toSp() }
        Row(
            modifier = Modifier.height(lockupHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(lockupHeight * 0.24f),
        ) {
            AppBrandMark(
                modifier = Modifier.fillMaxHeight(),
                contentDescription = contentDescription,
            )
            Text(
                text = ForkBranding.APP_NAME,
                style = MaterialTheme.typography.headlineSmall.copy(
                    color = Color.White,
                    fontSize = nameSize,
                    lineHeight = nameSize * 1.15f,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.02).em,
                ),
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}
