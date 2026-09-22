package com.nuvio.app.core.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.random.Random
import com.nuvio.app.core.build.AppFeaturePolicy
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.Dp
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.big_shoulders_display
import org.jetbrains.compose.resources.Font

/**
 * StreamCut's riso material: dark card stock printed with translucent spot inks.
 *
 * Each ink means one thing everywhere it appears, so colour carries state
 * rather than decoration:
 * - [Pink] is acting: search, the thing you are about to do.
 * - [Blue] is where you were: resume positions, recently opened.
 * - [Sun] is set aside: the same meaning as the player's amber ranges.
 * - [Red] is danger: errors and destructive actions, and nothing else.
 */
@Immutable
object Riso {
    val Stock = Color(0xFF141019)
    val StockRaised = Color(0xFF1D1724)
    val Pink = Color(0xFFFF48B0)
    val Blue = Color(0xFF3D5AFE)
    val Sun = Color(0xFFFFD84A)
    val Red = Color(0xFFF15060)
    val Paper = Color(0xFFF2ECE4)
    val PaperDim = Color(0xFFF2ECE4).copy(alpha = 0.66f)
    val PaperFaint = Color(0xFFF2ECE4).copy(alpha = 0.14f)
    /**
     * An off switch's track. Switches read their track from the outline colour,
     * which the riso world makes transparent (no outlines at rest) -- without
     * this an off switch is a lone grey dot on the page.
     */
    val SwitchOffTrack = Color(0xFFF2ECE4).copy(alpha = 0.16f)
}

/** One patch of ink: where it sits (fractions of the area), how big, and its shape. */
@Immutable
data class RisoBloom(
    val color: Color,
    val centerX: Float,
    val centerY: Float,
    val radius: Float,
    val squash: Float = 1f,
    val strength: Float = 0.6f,
)

private const val GrainTileSize = 192

/**
 * One tile of stochastic grain, built once and tiled everywhere.
 *
 * Drawn point by point rather than loaded as an asset so it is resolution-free,
 * costs no file, and every platform gets the same seed.
 */
private val grainTile: ImageBitmap by lazy {
    val bitmap = ImageBitmap(GrainTileSize, GrainTileSize)
    val canvas = Canvas(bitmap)
    val random = Random(1966)
    val light = ArrayList<Offset>()
    val dark = ArrayList<Offset>()
    for (y in 0 until GrainTileSize) {
        for (x in 0 until GrainTileSize) {
            val roll = random.nextFloat()
            val point = Offset(x + 0.5f, y + 0.5f)
            if (roll < 0.16f) light += point else if (roll > 0.80f) dark += point
        }
    }
    canvas.drawPoints(PointMode.Points, light, Paint().apply { color = Color.White; strokeWidth = 1f })
    canvas.drawPoints(PointMode.Points, dark, Paint().apply { color = Color.Black; strokeWidth = 1f })
    bitmap
}

private val grainBrush: ShaderBrush by lazy {
    ShaderBrush(ImageShader(grainTile, TileMode.Repeated, TileMode.Repeated))
}

/**
 * Paper grain over everything this modifier wraps, content included.
 *
 * SrcAtop, not Overlay: grain lands only where something is already printed.
 * Overlay over a transparent pixel resolves to the grain itself at full
 * strength, which turned every screen without its own background into static.
 */
fun Modifier.risoGrain(alpha: Float = 0.07f): Modifier = drawWithContent {
    drawContent()
    drawRect(brush = grainBrush, alpha = alpha, blendMode = BlendMode.SrcAtop)
}

private fun DrawScope.drawBloom(bloom: RisoBloom, drift: Offset, swell: Float, scrollY: Float) {
    val center = Offset(
        x = size.width * bloom.centerX + drift.x,
        y = size.height * bloom.centerY + drift.y - scrollY,
    )
    val radius = size.minDimension * bloom.radius * swell
    val brush = Brush.radialGradient(
        // An eased falloff: a linear tail leaves a visible seam where the ink stops.
        0f to bloom.color.copy(alpha = bloom.strength),
        0.35f to bloom.color.copy(alpha = bloom.strength * 0.7f),
        0.6f to bloom.color.copy(alpha = bloom.strength * 0.32f),
        0.8f to bloom.color.copy(alpha = bloom.strength * 0.1f),
        0.92f to bloom.color.copy(alpha = bloom.strength * 0.025f),
        1f to bloom.color.copy(alpha = 0f),
        center = center,
        radius = radius,
    )
    // Squashed about its own centre, so blooms read as organic lobes rather
    // than perfect circles.
    withTransform({ scale(scaleX = 1f, scaleY = bloom.squash, pivot = center) }) {
        drawCircle(brush = brush, radius = radius, center = center, blendMode = BlendMode.Screen)
    }
}

/**
 * Ink blooms printed behind this element, drifting slowly.
 *
 * The blooms go on their own layer and then have grain punched out of them,
 * which is what makes them read as riso ink (dense in the middle, breaking
 * up into specks at the thin edges) instead of a smooth screen gradient. A
 * second, slightly offset pass of the first ink is the misregistration.
 *
 * [swell] scales every bloom; animate it to let the page breathe on focus.
 * [scrollY] lifts the blooms with the content they sit behind, so a bloom
 * printed behind a section scrolls away with it. Put this on a surface at
 * least as large as the blooms: the layer clips to its bounds, and a bloom
 * cut by an element's edge reads as a box, which is the one thing ink never is.
 */
@Composable
fun Modifier.risoBlooms(
    blooms: List<RisoBloom>,
    swell: Float = 1f,
    driftPx: Float = 36f,
    scrollY: () -> Float = { 0f },
): Modifier {
    val transition = rememberInfiniteTransition(label = "risoDrift")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 16_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "risoDriftPhase",
    )
    val offsets = remember(blooms.size) { List(blooms.size) { index -> if (index % 2 == 0) 1f else -1f } }
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawBehind {
            blooms.forEachIndexed { index, bloom ->
                val direction = offsets[index]
                val drift = Offset(driftPx * direction * (phase - 0.5f) * 2f, driftPx * 0.6f * (0.5f - phase))
                val lift = scrollY()
                drawBloom(bloom, drift, swell, lift)
                // Misregistration: every ink printed a second time, visibly off
                // register, the way a riso drum never lands twice in the same place.
                val slip = 5.dp.toPx() * (if (index % 2 == 0) 1f else -1f)
                drawBloom(bloom.copy(strength = bloom.strength * 0.4f), drift + Offset(slip, -slip * 0.7f), swell, lift)
            }
            // Erode the ink with grain so its edges break up like a riso drum.
            drawRect(brush = grainBrush, alpha = 0.55f, blendMode = BlendMode.DstOut)
        }
}

/**
 * A patch of solid ink with its grain, for places that would otherwise be a
 * flat box: a poster that never arrived prints as the listing's ink instead.
 */
fun Modifier.risoInkTile(color: Color, strength: Float = 1f): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawBehind {
        drawRect(
            brush = Brush.radialGradient(
                0f to color.copy(alpha = 0.72f * strength),
                0.7f to color.copy(alpha = 0.38f * strength),
                1f to color.copy(alpha = 0.16f * strength),
                center = Offset(size.width * 0.35f, size.height * 0.3f),
                radius = size.maxDimension,
            ),
        )
        drawRect(brush = grainBrush, alpha = 0.4f, blendMode = BlendMode.DstOut)
    }

/**
 * The programme's lettering: Big Shoulders Display, a condensed poster face
 * from Chicago's print tradition (SIL OFL, third_party/licenses). Used for the
 * lines a programme sheet would set big -- the search masthead, section
 * titles -- never for running text, which stays in the UI face.
 */
val RisoDisplay: FontFamily
    @Composable
    get() = FontFamily(
        Font(
            Res.font.big_shoulders_display,
            weight = FontWeight.ExtraBold,
            variationSettings = FontVariation.Settings(FontVariation.weight(800)),
        ),
        Font(
            Res.font.big_shoulders_display,
            weight = FontWeight.Bold,
            variationSettings = FontVariation.Settings(FontVariation.weight(700)),
        ),
    )

/** True where the riso world is the look: the clipper build. */
val risoWorldActive: Boolean
    get() = !AppFeaturePolicy.viewingChromeEnabled

/**
 * Marks a selected item with a soft bloom of [color] behind it instead of a
 * filled pill: selection is where ink pools. Drawn without a layer so the
 * bloom may spill past the element, the way ink spreads past its mark.
 */
fun Modifier.risoSelectionInk(color: Color, amount: Float, spread: Float = 1.25f): Modifier = drawBehind {
    if (amount <= 0f) return@drawBehind
    val radius = size.maxDimension * 0.5f * spread
    val center = Offset(size.width / 2f, size.height / 2f)
    drawCircle(
        brush = Brush.radialGradient(
            0f to color.copy(alpha = 0.62f * amount),
            0.5f to color.copy(alpha = 0.28f * amount),
            1f to color.copy(alpha = 0f),
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
}

/**
 * Loading, as ink: two soft blooms (pink and blue) circling and overprinting.
 * Replaces the segmented spinner in the riso world; it reads as the page
 * printing, not a machine waiting.
 */
@Composable
fun RisoLoadingBloom(modifier: Modifier = Modifier, size: Dp) {
    val transition = rememberInfiniteTransition(label = "risoLoading")
    val turn by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1_600, easing = LinearEasing)),
        label = "risoLoadingTurn",
    )
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2f
            val angle = turn * 2f * kotlin.math.PI.toFloat()
            listOf(Riso.Pink to 0f, Riso.Blue to kotlin.math.PI.toFloat()).forEach { (ink, phase) ->
                val c = Offset(
                    x = center.x + kotlin.math.cos(angle + phase) * r * 0.32f,
                    y = center.y + kotlin.math.sin(angle + phase) * r * 0.32f,
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        0f to ink.copy(alpha = 0.95f),
                        0.55f to ink.copy(alpha = 0.45f),
                        1f to ink.copy(alpha = 0f),
                        center = c,
                        radius = r * 0.7f,
                    ),
                    radius = r * 0.7f,
                    center = c,
                    blendMode = BlendMode.Screen,
                )
            }
        }
    }
}
