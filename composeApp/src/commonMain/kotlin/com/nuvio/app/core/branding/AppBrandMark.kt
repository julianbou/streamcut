package com.nuvio.app.core.branding

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_brand_mark
import org.jetbrains.compose.resources.painterResource

/** The logo's own blue, sampled from the artwork. */
internal val AppBrandBlue = Color(0xFF05A1EF)

/**
 * The scissors-and-film logo on a transparent ground.
 *
 * Knocked out of the navy artwork by `branding/make_brand.py`, so the navy
 * outlines between the blades and the film are holes: the mark reads on any
 * dark surface, and looks exactly like the artwork on navy. Size it by height;
 * the width follows the image's own aspect ratio.
 */
@Composable
internal fun AppBrandMark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Image(
        painter = painterResource(Res.drawable.app_brand_mark),
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = ContentScale.Fit,
    )
}

/**
 * Startup screen: the logo centred on the artwork's navy vignette.
 *
 * The three stops are the artwork's background measured in rings around the
 * logo, so the knocked-out mark sits on the colour it was drawn on.
 */
@Composable
internal fun BrandLaunchScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.radialGradient(
                0f to Color(0xFF181A3B),
                0.55f to Color(0xFF131432),
                1f to Color(0xFF0E0D28),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            AppBrandMark(
                modifier = Modifier.height(132.dp),
                contentDescription = ForkBranding.APP_NAME,
            )
            Spacer(modifier = Modifier.height(28.dp))
            NuvioLoadingIndicator(color = AppBrandBlue)
        }
    }
}
