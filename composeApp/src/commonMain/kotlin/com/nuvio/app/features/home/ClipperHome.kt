package com.nuvio.app.features.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.nuvio.app.core.ui.Riso
import com.nuvio.app.core.ui.RisoBloom
import com.nuvio.app.core.ui.RisoDisplay
import com.nuvio.app.core.ui.risoInkTile
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import com.nuvio.app.features.player.formatPlaybackTime
import com.nuvio.app.features.search.SearchEmptyStateReason
import com.nuvio.app.features.search.SearchUiState
import com.nuvio.app.features.watchprogress.ContinueWatchingItem
import com.nuvio.app.features.watchprogress.WatchProgressRepository
import com.nuvio.app.features.watchprogress.continueWatchingItemKey
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.clip_home_browse_hide
import nuvio.composeapp.generated.resources.clip_home_browse_hint
import nuvio.composeapp.generated.resources.clip_home_browse_title
import nuvio.composeapp.generated.resources.clip_home_no_addons
import nuvio.composeapp.generated.resources.clip_home_no_results
import nuvio.composeapp.generated.resources.clip_home_recent_empty
import nuvio.composeapp.generated.resources.clip_home_recent_remove
import nuvio.composeapp.generated.resources.clip_home_recent_title
import nuvio.composeapp.generated.resources.clip_home_search_failed
import nuvio.composeapp.generated.resources.clip_home_search_hint
import nuvio.composeapp.generated.resources.clip_home_search_placeholder
import nuvio.composeapp.generated.resources.clip_home_searching
import nuvio.composeapp.generated.resources.clip_home_stopped_at
import org.jetbrains.compose.resources.stringResource

// Home for the clipper build, in the riso programme world (see RisoMaterial):
// a search masthead, the films you have opened, and the addon catalogs folded
// away at the bottom. The upstream home (hero, continue watching, catalog rows
// on top) is untouched and still renders when viewing chrome is on.

private val ListingWidth = 156.dp
private val ListingShape = RoundedCornerShape(14.dp)

/** Emits the clipper home's own items ahead of the (collapsible) catalog rows. */
internal fun LazyListScope.clipperHomeSections(
    query: String,
    onQueryChange: (String) -> Unit,
    searchFocusRequester: FocusRequester,
    onSearchFocusChange: (Boolean) -> Unit,
    mastheadHeight: Dp,
    searchState: SearchUiState,
    recentItems: List<ContinueWatchingItem>,
    browseExpanded: Boolean,
    onBrowseToggle: () -> Unit,
    sectionPadding: Dp,
    onRecentClick: ((ContinueWatchingItem) -> Unit)?,
    onResultClick: ((MetaPreview) -> Unit)?,
) {
    item(key = "clipper_masthead") {
        ClipperMasthead(
            query = query,
            onQueryChange = onQueryChange,
            focusRequester = searchFocusRequester,
            onFocusChange = onSearchFocusChange,
            height = mastheadHeight,
            sectionPadding = sectionPadding,
        )
    }
    if (query.isNotBlank()) {
        clipperSearchResults(query, searchState, sectionPadding, onResultClick)
    } else {
        item(key = "clipper_recent") {
            ClipperRecentSection(
                items = recentItems,
                sectionPadding = sectionPadding,
                onClick = onRecentClick,
            )
        }
    }
    item(key = "clipper_browse_toggle") {
        ClipperBrowseToggle(
            expanded = browseExpanded,
            onToggle = onBrowseToggle,
            sectionPadding = sectionPadding,
        )
    }
}

/** Titles actually opened in the player: no next-up suggestions, no release alerts. */
internal fun List<ContinueWatchingItem>.openedInPlayer(): List<ContinueWatchingItem> =
    filter { !it.isNextUp && !it.isReleaseAlert && !it.isNewSeasonRelease }

/**
 * The home page's ink, in page space (fractions of the viewport, lifted by the
 * scroll). Pink sits behind the search, because searching is acting; blue sits
 * behind the films you opened, because blue is where you were.
 */
internal val ClipperHomeBlooms = listOf(
    RisoBloom(color = Riso.Pink, centerX = 0.2f, centerY = 0.22f, radius = 0.66f, squash = 0.72f, strength = 0.6f),
    // Overlaps the pink's lower edge, so the two inks overprint into a third
    // colour where the search meets the films you opened.
    RisoBloom(color = Riso.Blue, centerX = 0.46f, centerY = 0.78f, radius = 0.62f, squash = 0.78f, strength = 0.5f),
)

@Composable
private fun ClipperMasthead(
    query: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    onFocusChange: (Boolean) -> Unit,
    height: Dp,
    sectionPadding: Dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // The page's ink lives on the page, not here; it swells when you act.
    LaunchedEffect(focused) { onFocusChange(focused) }
    val focusManager = LocalFocusManager.current
    val underline by animateFloatAsState(
        targetValue = if (focused || query.isNotEmpty()) 1f else 0f,
        animationSpec = tween(durationMillis = 420),
        label = "mastheadUnderline",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height),
        contentAlignment = Alignment.BottomStart,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = sectionPadding, end = sectionPadding, bottom = 44.dp),
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                interactionSource = interaction,
                cursorBrush = SolidColor(Riso.Pink),
                textStyle = MastheadTextStyle.copy(color = Riso.Paper),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onPreviewKeyEvent { event ->
                        when {
                            event.type != KeyEventType.KeyDown -> false
                            event.key == Key.Escape && query.isNotEmpty() -> {
                                onQueryChange("")
                                true
                            }
                            // Down leaves the field for the first listing or result.
                            event.key == Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                            else -> false
                        }
                    }
                    .drawBehind {
                        // No resting rule: pink ink runs under the field only
                        // while you are in it or have typed something.
                        if (underline > 0f) {
                            val y = size.height + 12.dp.toPx()
                            val stroke = 3.dp.toPx()
                            drawRoundRect(
                                brush = Brush.horizontalGradient(
                                    0f to Riso.Pink,
                                    0.7f to Riso.Pink.copy(alpha = 0.55f),
                                    1f to Riso.Pink.copy(alpha = 0f),
                                ),
                                topLeft = Offset(0f, y),
                                size = Size(size.width * underline, stroke),
                                cornerRadius = CornerRadius(stroke / 2f),
                            )
                        }
                    },
                decorationBox = { field ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(Res.string.clip_home_search_placeholder),
                                style = MastheadTextStyle,
                                color = Riso.Paper.copy(alpha = 0.62f),
                                maxLines = 1,
                            )
                        }
                        field()
                    }
                },
            )
            Spacer(Modifier.height(22.dp))
            Text(
                text = stringResource(Res.string.clip_home_search_hint),
                style = TextStyle(fontSize = 14.sp, letterSpacing = 0.01.em),
                color = Riso.Paper.copy(alpha = 0.8f),
            )
        }
    }
}

// Set in the programme face. Condensed, so it can run large: the search line
// is the loudest thing on the page, as a programme's masthead would be.
private val MastheadTextStyle: TextStyle
    @Composable
    get() = TextStyle(
        fontFamily = RisoDisplay,
        fontSize = 124.sp,
        lineHeight = 120.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.em,
    )

private val SectionTitleStyle: TextStyle
    @Composable
    get() = TextStyle(
        fontFamily = RisoDisplay,
        fontSize = 34.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.01.em,
    )

private val ListingTitleStyle = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
private val ListingMetaStyle = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClipperRecentSection(
    items: List<ContinueWatchingItem>,
    sectionPadding: Dp,
    onClick: ((ContinueWatchingItem) -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = sectionPadding, end = sectionPadding, top = 28.dp, bottom = 20.dp),
    ) {
        Text(
            text = stringResource(Res.string.clip_home_recent_title),
            style = SectionTitleStyle,
            color = Riso.Paper,
        )
        Spacer(Modifier.height(16.dp))
        if (items.isEmpty()) {
            Text(
                text = stringResource(Res.string.clip_home_recent_empty),
                style = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
                color = Riso.PaperDim,
                modifier = Modifier.widthIn65ch(),
            )
            return@Column
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(22.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            items.take(RecentLimit).forEach { item ->
                val fraction = when {
                    item.durationMs > 0 -> (item.resumePositionMs.toFloat() / item.durationMs).coerceIn(0f, 1f)
                    else -> item.progressFraction.coerceIn(0f, 1f)
                }
                RisoListing(
                    key = continueWatchingItemKey(item),
                    imageUrl = item.poster ?: item.imageUrl,
                    title = item.title,
                    meta = item.subtitle.takeIf { it.isNotBlank() && it != item.title },
                    positionLabel = if (item.resumePositionMs > 0) {
                        stringResource(Res.string.clip_home_stopped_at, formatPlaybackTime(item.resumePositionMs))
                    } else {
                        null
                    },
                    positionFraction = fraction.takeIf { item.resumePositionMs > 0 },
                    hoverInk = Riso.Blue,
                    onClick = onClick?.let { { it(item) } },
                    removeLabel = stringResource(Res.string.clip_home_recent_remove, item.title),
                    // Upstream's own "Remove from Continue Watching": the saved
                    // position goes, here and on the synced account, so the film
                    // leaves this row until it is opened in the player again.
                    // Next-up items never reach this row (openedInPlayer), so
                    // there is no dismissed-next-up key to record.
                    onRemove = { WatchProgressRepository.removeProgress(contentId = item.parentMetaId) },
                )
            }
        }
    }
}

private const val RecentLimit = 12

private const val ResultLimit = 18

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.clipperSearchResults(
    query: String,
    state: SearchUiState,
    sectionPadding: Dp,
    onClick: ((MetaPreview) -> Unit)?,
) {
    val sections = state.sections.filter { it.items.isNotEmpty() }
    if (sections.isEmpty()) {
        item(key = "clipper_search_status") {
            val text = when {
                state.isLoading -> stringResource(Res.string.clip_home_searching)
                state.emptyStateReason == SearchEmptyStateReason.NoActiveAddons ||
                    state.emptyStateReason == SearchEmptyStateReason.NoSearchCatalogs ->
                    stringResource(Res.string.clip_home_no_addons)
                state.emptyStateReason == SearchEmptyStateReason.RequestFailed ->
                    stringResource(Res.string.clip_home_search_failed, state.errorMessage.orEmpty())
                else -> stringResource(Res.string.clip_home_no_results, query.trim())
            }
            Text(
                text = text,
                style = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
                color = Riso.PaperDim,
                modifier = Modifier.padding(start = sectionPadding, end = sectionPadding, top = 28.dp, bottom = 12.dp),
            )
        }
        return
    }
    sections.forEach { section ->
        item(key = "clipper_result_${section.key}") {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 6.dp)) {
                Row(
                    modifier = Modifier.padding(horizontal = sectionPadding),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(text = section.title, style = SectionTitleStyle, color = Riso.Paper)
                    if (section.addonName.isNotBlank()) {
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = section.addonName,
                            style = ListingMetaStyle,
                            color = Riso.PaperDim,
                            modifier = Modifier.padding(bottom = 3.dp),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                // Wrapping, like the recents: every result reachable by the
                // wheel and the keyboard, none parked off the right edge.
                FlowRow(
                    modifier = Modifier.padding(horizontal = sectionPadding),
                    horizontalArrangement = Arrangement.spacedBy(22.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    section.items.take(ResultLimit).forEach { meta ->
                        RisoListing(
                            key = meta.id,
                            imageUrl = meta.poster,
                            title = meta.name,
                            meta = meta.releaseInfo,
                            positionLabel = null,
                            positionFraction = null,
                            hoverInk = Riso.Pink,
                            onClick = onClick?.let { { it(meta) } },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One film as a programme listing: the poster in its own colour, the title,
 * and -- for a film you opened -- where you stopped, marked in blue ink.
 * Hovering prints a soft bloom of the listing's ink behind it.
 */
@Composable
private fun RisoListing(
    key: String,
    imageUrl: String?,
    title: String,
    meta: String?,
    positionLabel: String?,
    positionFraction: Float?,
    hoverInk: androidx.compose.ui.graphics.Color,
    onClick: (() -> Unit)?,
    removeLabel: String? = null,
    onRemove: (() -> Unit)? = null,
) {
    val interaction = remember(key) { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val removeInteraction = remember(key) { MutableInteractionSource() }
    val removeHovered by removeInteraction.collectIsHoveredAsState()
    val removeFocused by removeInteraction.collectIsFocusedAsState()
    // Keyboard focus prints the same ink as hover: one affordance, two inputs.
    val glow by animateFloatAsState(
        targetValue = if (hovered || focused) 1f else 0f,
        animationSpec = tween(durationMillis = 360),
        label = "listingGlow",
    )
    Column(
        modifier = Modifier
            .width(ListingWidth)
            .hoverable(interaction)
            .then(
                if (onClick != null) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .focusable(interactionSource = interaction)
                        .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .then(
                // Keyboard path to the ✕, which only shows on hover or focus.
                if (onRemove != null) {
                    Modifier.onPreviewKeyEvent { event ->
                        val isRemoveKey = event.key == Key.Delete || event.key == Key.Backspace
                        if (focused && isRemoveKey && event.type == KeyEventType.KeyDown) {
                            onRemove()
                            true
                        } else {
                            false
                        }
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .drawBehind {
                    if (glow > 0f) {
                        val center = Offset(size.width / 2f, size.height * 0.55f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                0f to hoverInk.copy(alpha = 0.55f * glow),
                                1f to hoverInk.copy(alpha = 0f),
                                center = center,
                                radius = size.maxDimension * 0.85f,
                            ),
                            radius = size.maxDimension * 0.85f,
                            center = center,
                        )
                    }
                }
                .clip(ListingShape)
                // Printed ink until (or unless) the poster arrives; never a flat box.
                .risoInkTile(hoverInk),
        ) {
            if (!imageUrl.isNullOrBlank()) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
            if (onRemove != null) {
                // Shown only while the listing is pointed at or focused: a row
                // of posters each wearing an ✕ reads as a list to clear, not a
                // shelf to pick from. Red ink on hover, since removing is
                // destructive; stock at rest so it sits on any poster.
                // Qualified: inside the listing's Column the ColumnScope overload
                // would otherwise win and refuse this Box's receiver.
                androidx.compose.animation.AnimatedVisibility(
                    visible = hovered || focused || removeHovered || removeFocused,
                    enter = fadeIn(tween(140)),
                    exit = fadeOut(tween(140)),
                    modifier = Modifier.align(Alignment.TopEnd).padding(7.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                if (removeHovered || removeFocused) Riso.Red else Riso.Stock.copy(alpha = 0.82f),
                            )
                            .hoverable(removeInteraction)
                            .pointerHoverIcon(PointerIcon.Hand)
                            .clickable(
                                interactionSource = removeInteraction,
                                indication = null,
                                onClickLabel = removeLabel,
                                onClick = onRemove,
                            ),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = removeLabel,
                            tint = Riso.Paper,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = title,
            style = ListingTitleStyle,
            color = Riso.Paper,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!meta.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = meta,
                style = ListingMetaStyle,
                color = Riso.PaperDim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (positionFraction != null) {
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .drawBehind {
                        val radius = CornerRadius(size.height / 2f)
                        drawRoundRect(Riso.PaperFaint, cornerRadius = radius)
                        drawRoundRect(
                            Riso.Blue,
                            size = Size(size.width * positionFraction, size.height),
                            cornerRadius = radius,
                        )
                    },
            )
        }
        if (positionLabel != null) {
            Spacer(Modifier.height(5.dp))
            Text(text = positionLabel, style = ListingMetaStyle, color = Riso.PaperDim, maxLines = 1)
        }
    }
}

@Composable
private fun ClipperBrowseToggle(
    expanded: Boolean,
    onToggle: () -> Unit,
    sectionPadding: Dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(320), label = "browseChevron")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = sectionPadding, end = sectionPadding, top = 56.dp, bottom = if (expanded) 18.dp else 48.dp),
    ) {
        Row(
            modifier = Modifier
                .hoverable(interaction)
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(interactionSource = interaction, indication = null, onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(if (expanded) Res.string.clip_home_browse_hide else Res.string.clip_home_browse_title),
                style = SectionTitleStyle,
                color = if (hovered) Riso.Paper else Riso.PaperDim,
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = if (hovered) Riso.Paper else Riso.PaperDim,
                modifier = Modifier.size(26.dp).rotate(rotation),
            )
        }
        AnimatedVisibility(visible = !expanded, enter = fadeIn(), exit = fadeOut()) {
            Text(
                text = stringResource(Res.string.clip_home_browse_hint),
                style = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
                color = Riso.PaperDim,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** Keeps running text near a comfortable measure on wide windows. */
private fun Modifier.widthIn65ch(): Modifier = this.then(Modifier.fillMaxWidth(0.6f))
