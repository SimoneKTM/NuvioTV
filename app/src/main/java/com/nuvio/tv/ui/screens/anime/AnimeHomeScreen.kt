package com.nuvio.tv.ui.screens.anime

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.FilterDrama
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.legacyKey
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.ContentCard
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.HeroCarousel
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardDefaults
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
                when (uiState.homeLayout) {
                    HomeLayout.MODERN -> AnimeModernContent(uiState = uiState, onNavigateToDetail = onNavigateToDetail, onNavigateToSeeAll = onNavigateToSeeAll)
                    HomeLayout.CLASSIC -> AnimeClassicContent(uiState = uiState, onNavigateToDetail = onNavigateToDetail, onNavigateToSeeAll = onNavigateToSeeAll)
                    HomeLayout.GRID -> AnimeGridContent(uiState = uiState, onNavigateToDetail = onNavigateToDetail, onNavigateToSeeAll = onNavigateToSeeAll)
                }
            }
        }
    }
}

@Composable
private fun AnimeHeroItem(
    uiState: AnimeHomeUiState,
    onOpen: (MetaPreview, String) -> Unit
) {
    if (!uiState.heroEnabled || uiState.heroItems.isEmpty()) return
    HeroCarousel(
        items = uiState.heroItems.asStable(),
        onItemClick = { item ->
            onOpen(item, uiState.heroAddonBaseUrl.orEmpty())
        },
        modifier = Modifier.padding(bottom = NuvioTheme.spacing.lg)
    )
}

@Composable
private fun AnimeModernContent(
    uiState: AnimeHomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (String, String, String) -> Unit
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
                showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled
            )
        }
    }
}

@Composable
private fun AnimeClassicContent(
    uiState: AnimeHomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (String, String, String) -> Unit
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
                showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled
            )
        }
    }
}

@Composable
private fun AnimeGridContent(
    uiState: AnimeHomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (String, String, String) -> Unit
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
    val heroSceneState = remember(item) {
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
                fullScreenBackdrop = false
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
            isFullScreen = { false },
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
