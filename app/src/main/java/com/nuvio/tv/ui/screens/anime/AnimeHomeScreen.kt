package com.nuvio.tv.ui.screens.anime

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterDrama
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.legacyKey
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.ContentCard
import com.nuvio.tv.ui.components.ContinueWatchingSection
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.GridContinueWatchingSection
import com.nuvio.tv.ui.components.HeroCarousel
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardDefaults
import com.nuvio.tv.ui.screens.home.ClassicFocusArtwork
import com.nuvio.tv.ui.screens.home.ClassicFocusGradientBackdrop
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.HeroPreview
import com.nuvio.tv.ui.screens.home.HeroTitleBlock
import com.nuvio.tv.ui.screens.home.ModernHeroScene
import com.nuvio.tv.ui.screens.home.ModernHeroSceneState
import com.nuvio.tv.ui.screens.home.extractYearText
import com.nuvio.tv.ui.screens.home.firstNonBlank
import com.nuvio.tv.ui.screens.home.formatHeroRuntime
import com.nuvio.tv.ui.screens.home.isSeriesType
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.asStable
import com.nuvio.tv.ui.util.dpadRepeatThrottle
import com.nuvio.tv.ui.util.localizedContentType

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun AnimeHomeScreen(
    viewModel: AnimeHomeViewModel = hiltViewModel(),
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
                        title = stringResource(R.string.anime_home_empty_title),
                        subtitle = stringResource(R.string.anime_home_empty_subtitle),
                        icon = Icons.Default.FilterDrama
                    )
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
                    Button(onClick = onOpenSettings) {
                        Text(stringResource(R.string.anime_home_empty_action))
                    }
                }
            }

            rows.isEmpty() -> {
                EmptyScreenState(
                    title = stringResource(R.string.anime_home_no_catalogs_title),
                    subtitle = stringResource(R.string.anime_home_no_catalogs_subtitle),
                    icon = Icons.Default.FilterDrama
                )
            }

            else -> {
                val onRemoveContinueWatching: (ContinueWatchingItem) -> Unit = viewModel::removeContinueWatching
                when (uiState.homeLayout) {
                    HomeLayout.MODERN -> AnimeModernContent(uiState = uiState, onNavigateToDetail = onNavigateToDetail, onNavigateToSeeAll = onNavigateToSeeAll, onRemoveContinueWatching = onRemoveContinueWatching)
                    HomeLayout.CLASSIC -> AnimeClassicContent(uiState = uiState, onNavigateToDetail = onNavigateToDetail, onNavigateToSeeAll = onNavigateToSeeAll, onRemoveContinueWatching = onRemoveContinueWatching)
                    HomeLayout.GRID -> AnimeGridContent(uiState = uiState, onNavigateToDetail = onNavigateToDetail, onNavigateToSeeAll = onNavigateToSeeAll, onRemoveContinueWatching = onRemoveContinueWatching)
                }
            }
        }
    }
}

@Composable
private fun AnimeHeroItem(
    uiState: AnimeHomeUiState,
    onOpen: (MetaPreview, String) -> Unit,
    onItemFocus: (MetaPreview) -> Unit = {}
) {
    if (!uiState.heroEnabled || uiState.heroItems.isEmpty()) return
    HeroCarousel(
        items = uiState.heroItems.asStable(),
        onItemClick = { item ->
            onOpen(item, uiState.heroAddonBaseUrl.orEmpty())
        },
        onItemFocus = onItemFocus,
        modifier = Modifier.padding(bottom = NuvioTheme.spacing.lg)
    )
}

@Composable
private fun AnimeModernContent(
    uiState: AnimeHomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (String, String, String) -> Unit,
    onRemoveContinueWatching: (ContinueWatchingItem) -> Unit
) {
    val heroItem = uiState.heroItem
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .dpadRepeatThrottle(),
        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl)
    ) {
        if (uiState.heroEnabled && heroItem != null) {
            item(key = "anime_hero") {
                AnimeHomeModernHero(
                    item = heroItem,
                    fullScreenBackdrop = uiState.modernHeroFullScreenBackdropEnabled,
                    onOpen = {
                        onNavigateToDetail(
                            heroItem.id,
                            heroItem.rawType,
                            uiState.heroAddonBaseUrl.orEmpty()
                        )
                    },
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.lg)
                )
            }
        }

        if (uiState.continueWatchingItems.isNotEmpty() || uiState.upcomingItems.isNotEmpty()) {
            item(key = "anime_continue_watching") {
                AnimeContinueWatchingRow(
                    uiState = uiState,
                    onNavigateToDetail = onNavigateToDetail,
                    onRemoveItem = onRemoveContinueWatching
                )
            }
        }

        items(
            items = uiState.rows,
            key = { row -> row.legacyKey() }
        ) { row ->
            if (uiState.modernLandscapePostersEnabled) {
                AnimeWidePosterRowSection(
                    catalogRow = row,
                    onItemClick = onNavigateToDetail,
                    onSeeAll = {
                        onNavigateToSeeAll(row.catalogId, row.addonId, row.apiType)
                    },
                    showSeeAll = row.hasMore || row.items.size >= 15,
                    showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled
                )
            } else {
                CatalogRowSection(
                    catalogRow = row,
                    onItemClick = onNavigateToDetail,
                    onSeeAll = {
                        onNavigateToSeeAll(row.catalogId, row.addonId, row.apiType)
                    },
                    showSeeAll = row.hasMore || row.items.size >= 15,
                    showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled
                )
            }
        }
    }
}

@Composable
private fun AnimeClassicContent(
    uiState: AnimeHomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (String, String, String) -> Unit,
    onRemoveContinueWatching: (ContinueWatchingItem) -> Unit
) {
    var focusedArtwork by remember { mutableStateOf<ClassicFocusArtwork?>(null) }
    LaunchedEffect(uiState.classicFocusGradientEnabled) {
        if (!uiState.classicFocusGradientEnabled) focusedArtwork = null
    }
    val handleMetaFocus: (MetaPreview) -> Unit = { item ->
        if (uiState.classicFocusGradientEnabled) {
            focusedArtwork = item.toAnimeClassicFocusArtwork()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ClassicFocusGradientBackdrop(
            artworkProvider = { focusedArtwork },
            enabled = uiState.classicFocusGradientEnabled,
            modifier = Modifier
                .fillMaxSize()
                .background(NuvioTheme.colors.Background)
        )
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .dpadRepeatThrottle(),
            contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl)
        ) {
            if (uiState.heroEnabled && uiState.heroItems.isNotEmpty()) {
                item(key = "anime_hero") {
                    AnimeHeroItem(
                        uiState = uiState,
                        onOpen = { item, addonBaseUrl ->
                            onNavigateToDetail(item.id, item.rawType, addonBaseUrl)
                        },
                        onItemFocus = { focusedArtwork = null }
                    )
                }
            }

            if (uiState.continueWatchingItems.isNotEmpty() || uiState.upcomingItems.isNotEmpty()) {
                item(key = "anime_continue_watching") {
                    AnimeContinueWatchingRow(
                        uiState = uiState,
                        onNavigateToDetail = onNavigateToDetail,
                        onRemoveItem = onRemoveContinueWatching
                    )
                }
            }

            items(
                items = uiState.rows,
                key = { row -> row.legacyKey() }
            ) { row ->
                CatalogRowSection(
                    catalogRow = row,
                    onItemClick = onNavigateToDetail,
                    onSeeAll = {
                        onNavigateToSeeAll(row.catalogId, row.addonId, row.apiType)
                    },
                    showSeeAll = row.hasMore || row.items.size >= 15,
                    showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled,
                    onItemFocus = handleMetaFocus
                )
            }
        }
    }
}

@Composable
private fun AnimeGridContent(
    uiState: AnimeHomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (String, String, String) -> Unit,
    onRemoveContinueWatching: (ContinueWatchingItem) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .dpadRepeatThrottle(),
        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl)
    ) {
        if (uiState.heroEnabled && uiState.heroItems.isNotEmpty()) {
            item(key = "anime_hero") {
                AnimeHeroItem(
                    uiState = uiState,
                    onOpen = { item, addonBaseUrl ->
                        onNavigateToDetail(item.id, item.rawType, addonBaseUrl)
                    }
                )
            }
        }

        if (uiState.continueWatchingItems.isNotEmpty() || uiState.upcomingItems.isNotEmpty()) {
            item(key = "anime_continue_watching") {
                AnimeGridContinueWatchingRow(
                    uiState = uiState,
                    onNavigateToDetail = onNavigateToDetail,
                    onRemoveItem = onRemoveContinueWatching
                )
            }
        }

        items(
            items = uiState.rows,
            key = { row -> row.legacyKey() }
        ) { row ->
            AnimeGridCatalogSection(
                catalogRow = row,
                columns = animeGridColumnCount(),
                onItemClick = onNavigateToDetail,
                onSeeAll = {
                    onNavigateToSeeAll(row.catalogId, row.addonId, row.apiType)
                },
                showSeeAll = row.hasMore || row.items.size >= 15,
                showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled
            )
        }
    }
}

@Composable
private fun animeGridColumnCount(): Int {
    val configuration = LocalConfiguration.current
    val cardWidth = PosterCardDefaults.Style.width
    val horizontalPadding = NuvioTheme.spacing.xxxl * 2
    val spacing = NuvioTheme.spacing.md
    val available = (configuration.screenWidthDp - horizontalPadding.value).coerceAtLeast(0f)
    val cols = ((available + spacing.value) / (cardWidth.value + spacing.value)).toInt()
    return cols.coerceAtLeast(1)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AnimeGridCatalogSection(
    catalogRow: CatalogRow,
    columns: Int,
    onItemClick: (String, String, String) -> Unit,
    onSeeAll: () -> Unit,
    showSeeAll: Boolean,
    showCatalogTypeSuffix: Boolean,
    modifier: Modifier = Modifier
) {
    val catalogContext = LocalContext.current
    val typeLabel = remember(catalogRow.rawType, catalogRow.apiType, catalogContext) {
        val raw = catalogRow.rawType.takeIf { it.isNotBlank() } ?: catalogRow.apiType
        localizedContentType(catalogContext, raw)
    }
    val catalogTitle = remember(catalogRow.catalogName, typeLabel, showCatalogTypeSuffix) {
        val formattedName = catalogRow.catalogName.replaceFirstChar { it.uppercase() }
        if (formattedName.isBlank()) ""
        else if (showCatalogTypeSuffix && typeLabel.isNotEmpty()) "$formattedName - $typeLabel" else formattedName
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, bottom = NuvioTheme.spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                Text(
                    text = catalogTitle.ifBlank { " " },
                    style = MaterialTheme.typography.headlineMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Clip
                )
                if (catalogTitle.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.catalog_from_addon, catalogRow.addonName),
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioTheme.colors.TextTertiary
                    )
                }
            }
        }

        val chunks = remember(catalogRow.items, columns) {
            catalogRow.items.chunked(columns.coerceAtLeast(1))
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = NuvioTheme.spacing.xxxl),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            chunks.forEachIndexed { chunkIndex, chunk ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                ) {
                    chunk.forEach { item ->
                        ContentCard(
                            item = item,
                            posterCardStyle = PosterCardDefaults.Style,
                            showLabels = true,
                            onClick = {
                                onItemClick(item.id, item.apiType, catalogRow.addonBaseUrl)
                            }
                        )
                    }
                    if (showSeeAll && chunkIndex == chunks.lastIndex && chunk.size < columns) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
            if (showSeeAll) {
                Box(modifier = Modifier.padding(bottom = NuvioTheme.spacing.md)) {
                    androidx.tv.material3.Button(onClick = onSeeAll) {
                        Text(stringResource(R.string.action_see_all))
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimeHomeModernHero(
    item: MetaPreview,
    fullScreenBackdrop: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val heroHeightPx = with(density) {
        (configuration.screenHeightDp * 0.60f).dp.roundToPx()
    }
    val requestWidthPx = with(density) { configuration.screenWidthDp.dp.roundToPx() }
    val heroPreview = remember(item) { buildAnimeHeroPreview(item) }
    val heroSceneState = remember(item, fullScreenBackdrop) {
        {
            ModernHeroSceneState(
                heroBackdrop = firstNonBlank(
                    item.backdropUrl,
                    item.background,
                    item.landscapePoster,
                    item.poster
                ),
                preview = heroPreview,
                enrichmentActive = false,
                shouldPlayTrailer = false,
                trailerFirstFrameRendered = false,
                trailerUrl = null,
                trailerAudioUrl = null,
                trailerPlaybackKey = null,
                trailerMuted = true,
                fullScreenBackdrop = fullScreenBackdrop
            )
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(with(density) { heroHeightPx.toDp() })
            .clickable { onOpen() }
    ) {
        ModernHeroScene(
            state = heroSceneState,
            isFullScreen = { fullScreenBackdrop },
            bgColor = NuvioTheme.colors.Background,
            modifier = Modifier.fillMaxSize(),
            requestWidthPx = requestWidthPx,
            requestHeightPx = heroHeightPx,
            onTrailerEnded = {},
            onFirstFrameRendered = {}
        )
        HeroTitleBlock(
            previewProvider = { heroPreview },
            portraitMode = true,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = NuvioTheme.spacing.xxxl,
                    end = NuvioTheme.spacing.xxxl,
                    bottom = NuvioTheme.spacing.xxxl
                )
                .fillMaxWidth(0.72f)
        )
    }
}

private fun buildAnimeHeroPreview(item: MetaPreview): HeroPreview {
    val isSeries = isSeriesType(item.apiType)
    val contentTypeText = when {
        item.rawType.isNotBlank() -> item.rawType.replaceFirstChar { it.uppercase() }
        isSeries -> "Series"
        else -> "Movie"
    }
    return HeroPreview(
        title = item.name,
        logo = null,
        description = item.description,
        contentTypeText = contentTypeText,
        isSeries = isSeries,
        yearText = extractYearText(item.type, item.releaseInfo, item.released),
        runtimeText = formatHeroRuntime(item.runtime),
        imdbText = item.imdbRating?.let { String.format(java.util.Locale.US, "%.1f", it) },
        ageRatingText = item.ageRating,
        statusText = item.status,
        countryText = item.country,
        languageText = item.language?.uppercase(),
        genres = item.genres.take(3).asStable(),
        poster = item.poster,
        backdrop = item.backdropUrl,
        imageUrl = item.poster ?: item.backdropUrl
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AnimeContinueWatchingRow(
    uiState: AnimeHomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onRemoveItem: (ContinueWatchingItem) -> Unit
) {
    if (uiState.continueWatchingItems.isEmpty() && uiState.upcomingItems.isEmpty()) return
    val (cardWidth, imageHeight) = animeCwCardSize(uiState.continueWatchingCardStyle)
    val onItemClick: (ContinueWatchingItem) -> Unit = { item ->
        handleAnimeCwClick(item, onNavigateToDetail)
    }
    val cornerRadius = PosterCardDefaults.Style.cornerRadius
    Column(modifier = Modifier.padding(bottom = NuvioTheme.spacing.lg)) {
        ContinueWatchingSection(
            items = uiState.continueWatchingItems.asStable(),
            onItemClick = onItemClick,
            onDetailsClick = onItemClick,
            onRemoveItem = onRemoveItem,
            onStartFromBeginning = onItemClick,
            showManualPlayOption = false,
            title = stringResource(R.string.continue_watching),
            blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
            useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
            cardStyle = uiState.continueWatchingCardStyle,
            cardWidth = cardWidth,
            imageHeight = imageHeight,
            cornerRadius = cornerRadius
        )
        if (uiState.upcomingItems.isNotEmpty()) {
            ContinueWatchingSection(
                items = uiState.upcomingItems.asStable(),
                onItemClick = onItemClick,
                onDetailsClick = onItemClick,
                onRemoveItem = onRemoveItem,
                onStartFromBeginning = onItemClick,
                showManualPlayOption = false,
                title = stringResource(R.string.upcoming_section_title),
                blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
                useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                cardStyle = uiState.continueWatchingCardStyle,
                cardWidth = cardWidth,
                imageHeight = imageHeight,
                cornerRadius = cornerRadius
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AnimeGridContinueWatchingRow(
    uiState: AnimeHomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onRemoveItem: (ContinueWatchingItem) -> Unit
) {
    if (uiState.continueWatchingItems.isEmpty() && uiState.upcomingItems.isEmpty()) return
    val onItemClick: (ContinueWatchingItem) -> Unit = { item ->
        handleAnimeCwClick(item, onNavigateToDetail)
    }
    Column(modifier = Modifier.padding(bottom = NuvioTheme.spacing.lg)) {
        GridContinueWatchingSection(
            items = uiState.continueWatchingItems.asStable(),
            onItemClick = onItemClick,
            onDetailsClick = onItemClick,
            onRemoveItem = onRemoveItem,
            onStartFromBeginning = onItemClick,
            showManualPlayOption = false,
            title = stringResource(R.string.continue_watching),
            blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
            useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
            cardStyle = uiState.continueWatchingCardStyle,
            cornerRadius = PosterCardDefaults.Style.cornerRadius
        )
        if (uiState.upcomingItems.isNotEmpty()) {
            GridContinueWatchingSection(
                items = uiState.upcomingItems.asStable(),
                onItemClick = onItemClick,
                onDetailsClick = onItemClick,
                onRemoveItem = onRemoveItem,
                onStartFromBeginning = onItemClick,
                showManualPlayOption = false,
                title = stringResource(R.string.upcoming_section_title),
                blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
                useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                cardStyle = uiState.continueWatchingCardStyle,
                cornerRadius = PosterCardDefaults.Style.cornerRadius
            )
        }
    }
}

private fun animeCwCardSize(style: ContinueWatchingCardStyle): Pair<Dp, Dp> {
    val base = PosterCardDefaults.Style
    return when (style) {
        ContinueWatchingCardStyle.POSTER -> base.width to base.height
        ContinueWatchingCardStyle.WIDE -> base.width * 2.5f to base.width * 2.5f * 0.4f
        ContinueWatchingCardStyle.CARD -> base.width * (16f / 9f) to base.width
    }
}

private fun handleAnimeCwClick(
    item: ContinueWatchingItem,
    onNavigateToDetail: (String, String, String) -> Unit
) {
    when (item) {
        is ContinueWatchingItem.InProgress ->
            onNavigateToDetail(
                item.progress.contentId,
                item.progress.contentType,
                item.progress.addonBaseUrl.orEmpty()
            )
        is ContinueWatchingItem.NextUp ->
            onNavigateToDetail(item.info.contentId, item.info.contentType, "")
    }
}

private fun MetaPreview.toAnimeClassicFocusArtwork(): ClassicFocusArtwork =
    ClassicFocusArtwork(
        imageUrl = firstNonBlank(backdropUrl, background, landscapePoster, poster),
        seed = id
    )

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AnimeWidePosterRowSection(
    catalogRow: CatalogRow,
    onItemClick: (String, String, String) -> Unit,
    onSeeAll: () -> Unit,
    showSeeAll: Boolean,
    showCatalogTypeSuffix: Boolean
) {
    val catalogContext = LocalContext.current
    val typeLabel = remember(catalogRow.rawType, catalogRow.apiType, catalogContext) {
        val raw = catalogRow.rawType.takeIf { it.isNotBlank() } ?: catalogRow.apiType
        localizedContentType(catalogContext, raw)
    }
    val catalogTitle = remember(catalogRow.catalogName, typeLabel, showCatalogTypeSuffix) {
        val formattedName = catalogRow.catalogName.replaceFirstChar { it.uppercase() }
        if (formattedName.isBlank()) ""
        else if (showCatalogTypeSuffix && typeLabel.isNotEmpty()) "$formattedName - $typeLabel" else formattedName
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = NuvioTheme.spacing.xxxl, end = NuvioTheme.spacing.xxxl, bottom = NuvioTheme.spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                Text(
                    text = catalogTitle.ifBlank { " " },
                    style = MaterialTheme.typography.headlineMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Clip
                )
                if (catalogTitle.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.catalog_from_addon, catalogRow.addonName),
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioTheme.colors.TextTertiary
                    )
                }
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = NuvioTheme.spacing.xxxl),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
        ) {
            items(catalogRow.items, key = { it.id }) { item ->
                AnimeWideCard(
                    item = item,
                    onClick = { onItemClick(item.id, item.apiType, catalogRow.addonBaseUrl) }
                )
            }
        }
        if (showSeeAll) {
            Box(modifier = Modifier.padding(bottom = NuvioTheme.spacing.md)) {
                androidx.tv.material3.Button(onClick = onSeeAll) {
                    Text(stringResource(R.string.action_see_all))
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AnimeWideCard(
    item: MetaPreview,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(PosterCardDefaults.Style.cornerRadius)
    val context = LocalContext.current
    var isFocused by remember { mutableStateOf(false) }
    val imageUrl = firstNonBlank(item.backdropUrl, item.background, item.landscapePoster, item.poster)
    Box(
        modifier = Modifier
            .width(260.dp)
            .height(148.dp)
            .clip(shape)
            .background(NuvioTheme.colors.BackgroundCard)
            .onFocusChanged { isFocused = it.isFocused }
            .border(
                width = if (isFocused) NuvioTheme.spacing.xxs else 0.dp,
                color = NuvioTheme.colors.FocusRing,
                shape = shape
            )
            .clickable(onClick = onClick)
    ) {
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Text(
            text = item.name,
            style = MaterialTheme.typography.titleSmall,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(NuvioTheme.spacing.sm)
        )
    }
}
