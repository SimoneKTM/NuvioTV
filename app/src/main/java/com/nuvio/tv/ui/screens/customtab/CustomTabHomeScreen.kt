package com.nuvio.tv.ui.screens.customtab

import android.content.Context
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PLACEHOLDER_IMAGE_URL
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.ContinueWatchingSection
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.GridContinueWatchingSection
import com.nuvio.tv.ui.components.HeroCarousel
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.screens.home.ClassicFocusArtwork
import com.nuvio.tv.ui.screens.home.ClassicFocusGradientBackdrop
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.HeroPreview
import com.nuvio.tv.ui.screens.home.ModernHeroScene
import com.nuvio.tv.ui.screens.home.ModernHeroSceneState
import com.nuvio.tv.ui.screens.home.MODERN_HERO_MEDIA_WIDTH_FRACTION
import com.nuvio.tv.ui.screens.home.MODERN_HERO_TEXT_WIDTH_FRACTION
import com.nuvio.tv.ui.screens.home.extractYearText
import com.nuvio.tv.ui.screens.home.firstNonBlank
import com.nuvio.tv.ui.screens.home.formatHeroRuntime
import com.nuvio.tv.ui.screens.home.isSeriesType
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.asStable
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import com.nuvio.tv.ui.util.localizedContentType
import com.nuvio.tv.ui.util.localizedLanguageText
import kotlin.math.abs
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun CustomTabHomeScreen(
    viewModel: CustomTabHomeViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (String, String, String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val rows = uiState.rows

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            uiState.isLoading && rows.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            }

            uiState.installedAddonsCount == 0 -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    EmptyScreenState(
                        title = stringResource(R.string.custom_tab_empty_title),
                        subtitle = stringResource(R.string.custom_tab_empty_subtitle, uiState.customTab.name),
                        icon = Icons.Default.Movie
                    )
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
                    Button(onClick = onOpenSettings) {
                        Text(stringResource(R.string.custom_tab_empty_action))
                    }
                }
            }

            rows.isEmpty() -> {
                EmptyScreenState(
                    title = stringResource(R.string.custom_tab_no_catalogs_title),
                    subtitle = stringResource(R.string.custom_tab_no_catalogs_subtitle, uiState.customTab.name),
                    icon = Icons.Default.Movie
                )
            }

            else -> {
                val onRemoveContinueWatching: (ContinueWatchingItem) -> Unit = viewModel::removeContinueWatching
                val enrichHeroItem: suspend (MetaPreview) -> MetaPreview? = viewModel::enrichCustomTabHeroItem
                when (uiState.homeLayout) {
                    HomeLayout.MODERN -> CustomTabModernContent(
                        uiState = uiState,
                        enrichHeroItem = enrichHeroItem,
                        onNavigateToDetail = onNavigateToDetail,
                        onRemoveContinueWatching = onRemoveContinueWatching,
                        onLoadMoreCatalog = viewModel::loadMoreCatalogItems
                    )
                    HomeLayout.CLASSIC -> CustomTabClassicContent(
                        uiState = uiState,
                        onNavigateToDetail = onNavigateToDetail,
                        onNavigateToSeeAll = onNavigateToSeeAll,
                        onRemoveContinueWatching = onRemoveContinueWatching
                    )
                    HomeLayout.GRID -> CustomTabGridContent(
                        uiState = uiState,
                        onNavigateToDetail = onNavigateToDetail,
                        onNavigateToSeeAll = onNavigateToSeeAll,
                        onRemoveContinueWatching = onRemoveContinueWatching
                    )
                }
            }
        }
    }
}

// Modern Content - Reuses ModernHomeContent logic but filtered for custom tab
@Composable
private fun CustomTabModernContent(
    uiState: CustomTabHomeUiState,
    enrichHeroItem: suspend (MetaPreview) -> MetaPreview?,
    onNavigateToDetail: (String, String, String) -> Unit,
    onRemoveContinueWatching: (ContinueWatchingItem) -> Unit,
    onLoadMoreCatalog: (String) -> Unit
) {
    val defaultHeroItem = uiState.heroItems.firstOrNull()
    var focusedHeroItem by remember(uiState.rows, defaultHeroItem) {
        mutableStateOf(defaultHeroItem)
    }
    val heroItem = focusedHeroItem ?: defaultHeroItem
    var enrichedHeroItem by remember(heroItem) { mutableStateOf(heroItem) }
    LaunchedEffect(heroItem) {
        val current = heroItem ?: return@LaunchedEffect
        enrichedHeroItem = current
        enrichHeroItem(current)?.let { enrichedHeroItem = it }
    }
    val heroEnabled = uiState.heroItems.isNotEmpty() && heroItem != null
    val fullScreenBackdrop = uiState.modernHeroFullScreenBackdropEnabled
    val useLandscapePosters = uiState.modernLandscapePostersEnabled
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp

    val rowsViewportHeight = screenHeight * if (useLandscapePosters) 0.49f else 0.52f
    val rowTitleLineHeight = MaterialTheme.typography.titleMedium.lineHeight
    val rowTitleHeight = with(density) {
        runCatching { rowTitleLineHeight.toDp() }
            .getOrDefault(NuvioTheme.spacing.xl)
    }
    val heroBackdropHeight =
        (screenHeight - rowsViewportHeight + rowTitleHeight + 14.dp).coerceAtMost(screenHeight)

    // Same card metrics as the modern home tab so the rows render identically.
    val portraitBaseWidth = uiState.posterCardWidthDp.dp
    val portraitBaseHeight = uiState.posterCardHeightDp.dp
    val portraitModernPosterScale = 1.08f
    val landscapeModernPosterScale = 1.34f
    val portraitCatalogCardWidth = portraitBaseWidth * 0.84f * portraitModernPosterScale
    val portraitCatalogCardHeight = portraitBaseHeight * 0.84f * portraitModernPosterScale
    val landscapeCatalogCardWidth = portraitBaseWidth * 1.24f * landscapeModernPosterScale
    val landscapeCatalogCardHeight = landscapeCatalogCardWidth / 1.77f

    // Same continue-watching card metrics as the modern home tab.
    val continueWatchingCardStyle = uiState.continueWatchingCardStyle
    val continueWatchingScale = 1.34f
    val continueWatchingCardWidth = when (continueWatchingCardStyle) {
        ContinueWatchingCardStyle.POSTER -> portraitCatalogCardWidth
        ContinueWatchingCardStyle.WIDE -> portraitBaseWidth * 2.1f
        ContinueWatchingCardStyle.CARD -> portraitBaseWidth * 1.24f * continueWatchingScale
    }
    val continueWatchingCardHeight = when (continueWatchingCardStyle) {
        ContinueWatchingCardStyle.POSTER -> portraitCatalogCardHeight
        ContinueWatchingCardStyle.WIDE -> continueWatchingCardWidth * 0.4f
        ContinueWatchingCardStyle.CARD -> continueWatchingCardWidth / 1.77f
    }

    Box(modifier = Modifier.fillMaxSize()) {
        heroItem?.let { currentHeroItem ->
            if (heroEnabled) {
                ModernHeroScene(
                    state = {
                        val preview = HeroPreview(
                            title = currentHeroItem.name,
                            logo = currentHeroItem.logo,
                            description = currentHeroItem.description,
                            contentTypeText = localizedContentType(LocalContext.current, currentHeroItem.apiType),
                            isSeries = isSeriesType(currentHeroItem.apiType),
                            yearText = extractYearText(currentHeroItem.apiType, currentHeroItem.releaseInfo, currentHeroItem.released),
                            runtimeText = formatHeroRuntime(currentHeroItem.runtime),
                            imdbText = currentHeroItem.imdbRating?.let { String.format(java.util.Locale.US, "%.1f", it) },
                            ageRatingText = currentHeroItem.ageRating,
                            statusText = currentHeroItem.status,
                            languageText = localizedLanguageText(currentHeroItem.language),
                            genres = currentHeroItem.genres.take(3).asStable(),
                            poster = currentHeroItem.poster,
                            backdrop = currentHeroItem.backdropUrl,
                            imageUrl = currentHeroItem.poster,
                            frozenBackdropUrl = currentHeroItem.backdropUrl,
                            frozenLogoUrl = currentHeroItem.logo
                        )
                        ModernHeroSceneState(
                            heroBackdrop = firstNonBlank(
                                enrichedHeroItem?.backdropUrl,
                                enrichedHeroItem?.poster,
                                currentHeroItem.backdropUrl,
                                currentHeroItem.poster
                            ),
                            preview = preview,
                            enrichmentActive = false,
                            shouldPlayTrailer = false,
                            trailerFirstFrameRendered = false,
                            trailerUrl = null,
                            trailerAudioUrl = null,
                            trailerPlaybackKey = null,
                            trailerMuted = true,
                            fullScreenBackdrop = fullScreenBackdrop
                        )
                    },
                    isFullScreen = { fullScreenBackdrop },
                    bgColor = NuvioTheme.colors.Background,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(heroBackdropHeight)
                        .offset(x = NuvioTheme.spacing.huge)
                        .fillMaxWidth(MODERN_HERO_MEDIA_WIDTH_FRACTION),
                    requestWidthPx = (screenWidth * MODERN_HERO_MEDIA_WIDTH_FRACTION).roundToPx(),
                    requestHeightPx = heroBackdropHeight.roundToPx(),
                    onTrailerEnded = {},
                    onFirstFrameRendered = {}
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = NuvioTheme.spacing.xxxl),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            if (uiState.continueWatchingItems.isNotEmpty()) {
                ContinueWatchingSection(
                    items = uiState.continueWatchingItems.asStable(),
                    onItemClick = { item ->
                        onNavigateToDetail(item.contentId, item.contentType, item.addonBaseUrl)
                    },
                    onDetailsClick = { item ->
                        onNavigateToDetail(item.contentId, item.contentType, item.addonBaseUrl)
                    },
                    onRemoveItem = onRemoveContinueWatching,
                    onStartFromBeginning = { item ->
                        onNavigateToDetail(item.contentId, item.contentType, item.addonBaseUrl)
                    },
                    showManualPlayOption = false,
                    title = uiState.continueWatchingTitle.ifEmpty { null },
                    blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
                    useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                    cardStyle = uiState.continueWatchingCardStyle,
                    cardWidth = continueWatchingCardWidth,
                    imageHeight = continueWatchingCardHeight,
                    cornerRadius = uiState.posterCardCornerRadiusDp.dp
                )
            }

            uiState.rows.forEach { row ->
                val rowKey = row.stableKey()
                val hasMore = row.hasMore || row.items.size >= 15
                CatalogRowSection(
                    catalogRow = row,
                    onItemClick = { itemId, itemType, addonBaseUrl ->
                        onNavigateToDetail(itemId, itemType, addonBaseUrl)
                    },
                    onSeeAll = { onNavigateToSeeAll(row.catalogId, row.apiType, row.addonBaseUrl) },
                    showSeeAll = hasMore,
                    seeAllLabel = stringResource(R.string.action_see_all),
                    posterCardStyle = PosterCardStyle(
                        width = if (useLandscapePosters) landscapeCatalogCardWidth else portraitCatalogCardWidth,
                        height = if (useLandscapePosters) landscapeCatalogCardHeight else portraitCatalogCardHeight,
                        cornerRadius = NuvioTheme.radii.md,
                        showLabels = uiState.posterLabelsEnabled,
                        showAddonName = uiState.catalogAddonNameEnabled,
                        showCatalogTypeSuffix = true
                    ),
                    showPosterLabels = uiState.posterLabelsEnabled,
                    showAddonName = uiState.catalogAddonNameEnabled,
                    showCatalogTypeSuffix = true,
                    focusedPosterBackdropExpandEnabled = uiState.focusedPosterBackdropExpandEnabled,
                    focusedPosterBackdropExpandDelaySeconds = uiState.focusedPosterBackdropExpandDelaySeconds,
                    focusedPosterBackdropTrailerEnabled = uiState.focusedPosterBackdropTrailerEnabled,
                    focusedPosterBackdropTrailerMuted = uiState.focusedPosterBackdropTrailerMuted,
                    onLoadMore = { onLoadMoreCatalog(rowKey) }
                )
            }
        }
    }
}

// Classic Content
@Composable
private fun CustomTabClassicContent(
    uiState: CustomTabHomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (String, String, String) -> Unit,
    onRemoveContinueWatching: (ContinueWatchingItem) -> Unit
) {
    val onItemClick: (ContinueWatchingItem) -> Unit = { item ->
        onNavigateToDetail(item.contentId, item.contentType, item.addonBaseUrl)
    }
    val onRemoveItem: (ContinueWatchingItem) -> Unit = onRemoveContinueWatching
    val cornerRadius = uiState.posterCardCornerRadiusDp.dp

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            contentPadding = PaddingValues(NuvioTheme.spacing.xxxl)
        ) {
            if (uiState.continueWatchingItems.isNotEmpty()) {
                item {
                    ContinueWatchingSection(
                        items = uiState.continueWatchingItems.asStable(),
                        onItemClick = onItemClick,
                        onDetailsClick = onItemClick,
                        onRemoveItem = onRemoveItem,
                        onStartFromBeginning = onItemClick,
                        showManualPlayOption = false,
                        title = uiState.continueWatchingTitle.ifEmpty { null },
                        blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
                        useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                        cardStyle = uiState.continueWatchingCardStyle,
                        cardWidth = 288.dp,
                        imageHeight = 162.dp,
                        cornerRadius = cornerRadius
                    )
                }
            }

            items(uiState.rows) { row ->
                CatalogRowSection(
                    catalogRow = row,
                    onItemClick = { itemId, itemType, addonBaseUrl ->
                        onNavigateToDetail(itemId, itemType, addonBaseUrl)
                    },
                    onSeeAll = { onNavigateToSeeAll(row.catalogId, row.apiType, row.addonBaseUrl) },
                    posterCardStyle = PosterCardStyle(
                        width = 210.dp,
                        height = 315.dp,
                        cornerRadius = NuvioTheme.radii.md,
                        showLabels = uiState.posterLabelsEnabled,
                        showAddonName = uiState.catalogAddonNameEnabled,
                        showCatalogTypeSuffix = true
                    ),
                    showPosterLabels = uiState.posterLabelsEnabled,
                    showAddonName = uiState.catalogAddonNameEnabled,
                    showCatalogTypeSuffix = true
                )
            }
        }
    }
}

// Grid Content
@Composable
private fun CustomTabGridContent(
    uiState: CustomTabHomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (String, String, String) -> Unit,
    onRemoveContinueWatching: (ContinueWatchingItem) -> Unit
) {
    val onItemClick: (ContinueWatchingItem) -> Unit = { item ->
        onNavigateToDetail(item.contentId, item.contentType, item.addonBaseUrl)
    }
    val onRemoveItem: (ContinueWatchingItem) -> Unit = onRemoveContinueWatching
    val cornerRadius = uiState.posterCardCornerRadiusDp.dp

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            contentPadding = PaddingValues(NuvioTheme.spacing.xxxl)
        ) {
            if (uiState.continueWatchingItems.isNotEmpty()) {
                item {
                    GridContinueWatchingSection(
                        items = uiState.continueWatchingItems.asStable(),
                        onItemClick = onItemClick,
                        onDetailsClick = onItemClick,
                        onRemoveItem = onRemoveItem,
                        onStartFromBeginning = onItemClick,
                        showManualPlayOption = false,
                        title = uiState.continueWatchingTitle.ifEmpty { null },
                        blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
                        useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                        cardStyle = uiState.continueWatchingCardStyle,
                        cardWidth = 288.dp,
                        imageHeight = 162.dp,
                        cornerRadius = cornerRadius
                    )
                }
            }

            items(uiState.rows) { row ->
                CatalogRowSection(
                    catalogRow = row,
                    onItemClick = { itemId, itemType, addonBaseUrl ->
                        onNavigateToDetail(itemId, itemType, addonBaseUrl)
                    },
                    onSeeAll = { onNavigateToSeeAll(row.catalogId, row.apiType, row.addonBaseUrl) },
                    posterCardStyle = PosterCardStyle(
                        width = 210.dp,
                        height = 315.dp,
                        cornerRadius = NuvioTheme.radii.md,
                        showLabels = uiState.posterLabelsEnabled,
                        showAddonName = uiState.catalogAddonNameEnabled,
                        showCatalogTypeSuffix = true
                    ),
                    showPosterLabels = uiState.posterLabelsEnabled,
                    showAddonName = uiState.catalogAddonNameEnabled,
                    showCatalogTypeSuffix = true
                )
            }
        }
    }
}