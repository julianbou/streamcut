package com.nuvio.app.core.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Typography
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.unit.em
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import com.nuvio.app.isDesktop
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.jetbrains_sans_bold
import nuvio.composeapp.generated.resources.jetbrains_sans_regular
import nuvio.composeapp.generated.resources.jetbrains_sans_semibold
import org.jetbrains.compose.resources.Font
import com.nuvio.app.core.build.AppFeaturePolicy

val LocalAppTheme = staticCompositionLocalOf { AppTheme.WHITE }

internal val LocalNuvioPlatformDensity = staticCompositionLocalOf<Density> {
    error("Platform density is unavailable outside NuvioTheme")
}

val MaterialTheme.appTheme: AppTheme
    @Composable
    @ReadOnlyComposable
    get() = LocalAppTheme.current

private fun contentColorFor(background: Color): Color =
    if (background.luminance() > 0.5f) Color(0xFF111111) else Color(0xFFF5F7F8)

private fun buildColorScheme(palette: ThemeColorPalette, amoled: Boolean = false) = darkColorScheme(
    primary = palette.secondary,
    onPrimary = palette.onSecondary,
    primaryContainer = palette.focusBackground,
    onPrimaryContainer = contentColorFor(palette.focusBackground),
    secondary = palette.secondaryVariant,
    onSecondary = palette.onSecondaryVariant,
    background = if (amoled) Color.Black else palette.background,
    onBackground = Color(0xFFF5F7F8),
    surface = palette.backgroundElevated,
    onSurface = Color(0xFFF5F7F8),
    surfaceVariant = palette.backgroundCard,
    onSurfaceVariant = Color(0xFF969CA3),
    outline = Color(0xFF252A2A),
    error = Color(0xFFE36A8A),
    onError = Color(0xFFFCE5EC),
)

private val JetBrainsSans: FontFamily
    @Composable
    get() = FontFamily(
        Font(Res.font.jetbrains_sans_bold, FontWeight.Bold, FontStyle.Normal),
        Font(Res.font.jetbrains_sans_semibold, FontWeight.SemiBold, FontStyle.Normal),
        Font(Res.font.jetbrains_sans_regular, FontWeight.Normal, FontStyle.Normal),
    )

private val NuvioTypography: Typography
    @Composable
    get() = Typography(
        displayLarge = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.pageDisplay,
            lineHeight = NuvioTokens.LineHeight.pageDisplay,
            fontWeight = FontWeight.Bold,
            letterSpacing = NuvioTokens.LetterSpacing.pageDisplay,
        ),
        headlineLarge = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.headline,
            lineHeight = NuvioTokens.LineHeight.headline,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = NuvioTokens.LetterSpacing.headline,
        ),
        titleLarge = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.titleSm,
            lineHeight = NuvioTokens.LineHeight.materialTitleLarge,
            fontWeight = FontWeight.SemiBold,
        ),
        titleMedium = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.bodyLg,
            lineHeight = NuvioTokens.LineHeight.bodyMd,
            fontWeight = FontWeight.SemiBold,
        ),
        bodyLarge = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.bodyApp,
            lineHeight = NuvioTokens.LineHeight.bodyApp,
            fontWeight = FontWeight.Normal,
        ),
        bodyMedium = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.bodyMd,
            lineHeight = NuvioTokens.LineHeight.bodyMd,
            fontWeight = FontWeight.Normal,
        ),
        labelLarge = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.bodyMd,
            lineHeight = NuvioTokens.LineHeight.bodySm,
            fontWeight = FontWeight.SemiBold,
        ),
        labelMedium = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.labelSm,
            lineHeight = NuvioTokens.LineHeight.labelXs,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = NuvioTokens.LetterSpacing.label,
        ),
    )

private val NuvioTypeTokens: NuvioTypeScale
    @Composable
    get() = NuvioTypeScale(
        labelXs = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.labelXs,
            lineHeight = NuvioTokens.LineHeight.labelXs,
            fontWeight = FontWeight.SemiBold,
        ),
        labelSm = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.labelSm,
            lineHeight = NuvioTokens.LineHeight.labelSm,
            fontWeight = FontWeight.SemiBold,
        ),
        bodySm = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.bodySm,
            lineHeight = NuvioTokens.LineHeight.bodySm,
            fontWeight = FontWeight.Normal,
        ),
        bodyMd = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.bodyMd,
            lineHeight = NuvioTokens.LineHeight.bodyMd,
            fontWeight = FontWeight.Normal,
        ),
        bodyLg = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.bodyLg,
            lineHeight = NuvioTokens.LineHeight.bodyLg,
            fontWeight = FontWeight.Normal,
        ),
        titleSm = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.titleSm,
            lineHeight = NuvioTokens.LineHeight.titleSm,
            fontWeight = FontWeight.SemiBold,
        ),
        titleMd = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.titleMd,
            lineHeight = NuvioTokens.LineHeight.titleMd,
            fontWeight = FontWeight.SemiBold,
        ),
        titleLg = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.titleLg,
            lineHeight = NuvioTokens.LineHeight.titleLg,
            fontWeight = FontWeight.SemiBold,
        ),
        displaySm = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.displaySm,
            lineHeight = NuvioTokens.LineHeight.displaySm,
            fontWeight = FontWeight.Bold,
        ),
        displayMd = TextStyle(
            fontFamily = JetBrainsSans,
            fontSize = NuvioTokens.Type.displayMd,
            lineHeight = NuvioTokens.LineHeight.displayMd,
            fontWeight = FontWeight.Bold,
        ),
    )

private val NuvioRippleConfiguration = RippleConfiguration(
    color = Color.Black,
)

private const val NuvioDesktopFontScale = 1.08f
private const val NuvioDesktopBaseWidthDp = 1280f
private const val NuvioDesktopBaseHeightDp = 820f
private const val NuvioDesktopMinUiScale = 1f
private const val NuvioDesktopMaxUiScale = 1.18f

internal fun desktopUiScaleForWindow(widthDp: Float, heightDp: Float): Float {
    if (!isDesktop || widthDp <= 0f || heightDp <= 0f) return NuvioDesktopMinUiScale

    val rawScale = minOf(
        widthDp / NuvioDesktopBaseWidthDp,
        heightDp / NuvioDesktopBaseHeightDp,
    )
    return rawScale.coerceIn(NuvioDesktopMinUiScale, NuvioDesktopMaxUiScale)
}

@Composable
fun NuvioTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    appTheme: AppTheme = AppTheme.WHITE,
    amoled: Boolean = false,
    desktopUiScale: Float = NuvioDesktopMinUiScale,
    content: @Composable () -> Unit,
) {
    // The clipper build wears the riso world whatever theme is stored: the
    // picker is hidden there, and the stored value is kept for upstream builds.
    val riso = !AppFeaturePolicy.viewingChromeEnabled
    val palette = if (riso) ThemeColors.Riso else ThemeColors.getColorPalette(appTheme)
    val colorScheme = buildColorScheme(palette, amoled = amoled && !riso)
        .let { if (riso) it.withRisoInks() else it }
    val tokens = defaultNuvioThemeTokens(palette, amoled = amoled && !riso, colorScheme = colorScheme)
        .let { if (riso) it.withRisoInks() else it }
    val typography = if (riso) NuvioTypography.withRisoDisplay() else NuvioTypography
    val typeScale = if (riso) NuvioTypeTokens.withRisoDisplay() else NuvioTypeTokens

    val density = LocalDensity.current
    val effectiveDesktopUiScale = if (isDesktop) {
        desktopUiScale.coerceIn(NuvioDesktopMinUiScale, NuvioDesktopMaxUiScale)
    } else {
        NuvioDesktopMinUiScale
    }
    CompositionLocalProvider(
        LocalNuvioPlatformDensity provides density,
        LocalDensity provides Density(
            density = density.density * effectiveDesktopUiScale,
            fontScale = if (isDesktop) NuvioDesktopFontScale else 1f,
        ),
        LocalNuvioThemeTokens provides tokens,
        LocalNuvioTypeScale provides typeScale,
        LocalRippleConfiguration provides NuvioRippleConfiguration,
        LocalAppTheme provides appTheme,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
        ) {
            if (riso) {
                // One paper grain over the whole print, drawn once here rather
                // than per screen. The desktop video is a native view above
                // Compose, so this can never land on the picture.
                Box(Modifier.fillMaxSize().risoGrain()) { content() }
            } else {
                content()
            }
        }
    }
}

// --- riso world (clipper build) -------------------------------------------
//
// Applied over the regular palette plumbing rather than instead of it, so every
// screen that reads MaterialTheme.colorScheme or MaterialTheme.nuvio picks the
// world up without being touched. See RisoMaterial.kt and DESIGN.md.

private fun ColorScheme.withRisoInks(): ColorScheme = copy(
    background = Riso.Stock,
    onBackground = Riso.Paper,
    surface = Riso.StockRaised,
    onSurface = Riso.Paper,
    surfaceVariant = Color(0xFF241C2C),
    onSurfaceVariant = Color(0xFFB3ACA8),
    // No borders at rest (DESIGN.md): fields and groups separate by raised
    // stock and space; focus draws pink ink instead.
    outline = Color.Transparent,
    outlineVariant = Color.Transparent,
    error = Riso.Red,
    onError = Riso.Stock,
)

private fun NuvioThemeTokens.withRisoInks(): NuvioThemeTokens = copy(
    // Organic, not boxy: pills for controls, generous radii for fields.
    shapes = shapes.copy(
        card = RoundedCornerShape(22.dp),
        compactCard = RoundedCornerShape(18.dp),
        sheet = RoundedCornerShape(28.dp),
        dialog = RoundedCornerShape(28.dp),
        button = RoundedCornerShape(percent = 50),
        chip = RoundedCornerShape(percent = 50),
    ),
    colors = colors.copy(
        // In-page fields are translucent so the page's ink and grain show
        // through; anything that floats over other content stays opaque.
        surface = Riso.StockRaised.copy(alpha = 0.78f),
        surfaceElevated = Riso.StockRaised.copy(alpha = 0.78f),
        surfaceCard = Color(0xFF261D2F).copy(alpha = 0.72f),
        surfaceSheet = Color(0xFF1F1826),
        surfaceDialog = Color(0xFF1F1826),
        surfacePopover = Color(0xFF261D2F),
        textPrimary = Riso.Paper,
        textSecondary = Color(0xFFCFC8C2),
        textMuted = Color(0xFFA39D98),
        textDisabled = Riso.Paper.copy(alpha = 0.34f),
        textInverse = Riso.Stock,
        // Boxes give way to space and ink: borders all but vanish.
        borderSubtle = Color.Transparent,
        borderDefault = Color.Transparent,
        borderStrong = Riso.Paper.copy(alpha = 0.16f),
        // One meaning per ink.
        success = Riso.Blue,
        warning = Riso.Sun,
        danger = Riso.Red,
        info = Riso.Blue,
        overlayScrim = Color(0xFF0A080D).copy(alpha = 0.72f),
        // Player: white is where you are (played fill, buffering), so the pink
        // range being marked and the sun ranges set aside stay readable on it.
        playerTimelineFill = Riso.Paper.copy(alpha = 0.92f),
        playerBuffering = Riso.Paper,
        playerControlsBackground = Riso.Stock.copy(alpha = 0.82f),
        shimmer = Riso.Paper.copy(alpha = 0.08f),
        skeleton = Riso.Paper.copy(alpha = 0.05f),
    ),
)

/**
 * What a programme sheet sets big -- page and section display lines -- goes to
 * the condensed poster face, scaled up because it is narrow. Everything read in
 * running text stays in the UI face.
 */
@Composable
private fun TextStyle.inRisoDisplay(scale: Float = 1.12f): TextStyle = copy(
    fontFamily = RisoDisplay,
    fontWeight = FontWeight.ExtraBold,
    fontSize = fontSize * scale,
    lineHeight = lineHeight * scale,
    letterSpacing = 0.01.em,
)

@Composable
private fun Typography.withRisoDisplay(): Typography = copy(
    displayLarge = displayLarge.inRisoDisplay(),
    headlineLarge = headlineLarge.inRisoDisplay(),
)

@Composable
private fun NuvioTypeScale.withRisoDisplay(): NuvioTypeScale = copy(
    displaySm = displaySm.inRisoDisplay(),
    displayMd = displayMd.inRisoDisplay(),
)
