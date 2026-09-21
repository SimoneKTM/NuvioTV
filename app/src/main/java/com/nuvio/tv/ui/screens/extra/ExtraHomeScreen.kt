package com.nuvio.tv.ui.screens.extra

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.legacyKey
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.ContinueWatchingCard
import com.nuvio.tv.ui.components.ContinueWatchingSection
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.GridContinueWatchingSection
import com.nuvio.tv.ui.components.HeroCarousel
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.extractYearText
import com.nuvio.tv.ui.screens.home.firstNonBlank
import com.nuvio.tv.ui.screens.home.formatHeroRuntime
import com.nuvio.tv.ui.screens.home.isSeriesType
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.asStable

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun ExtraHomeScreen(
    viewModel: ExtraHomeViewModel = hiltViewModel(),
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
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
                val tabName = uiState.extraTabName.ifBlank { stringResource(R.string.nav_extra) }
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    EmptyScreenState(
                        title = stringResource(R.string.extra_home_empty_title, tabName),
                        subtitle = stringResource(R.string.extra_home_empty_subtitle),
                        icon = Icons.Default.Extension
                    )
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
                    Button(onClick = onOpenSettings) {
                        Text(stringResource(R.string.extra_home_empty_action))
                    }
                }
            }

            rows.isEmpty() -> {
                val tabName = uiState.extraTabName.ifBlank { stringResource(R.string.nav_extra) }
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    EmptyScreenState(
                        title = stringResource(R.string.extra_home_empty_title, tabName),
                        subtitle = stringResource(R.string.extra_home_empty_subtitle),
                        icon = Icons.Default.Extension
                    )
                    Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
                    Button(onClick = onOpenSettings) {
                        Text(stringResource(R.string.extra_home_empty_action))
                    }
                }
            }

            else -> {
                val onRemoveContinueWatching: (ContinueWatchingItem) -> Unit = viewModel::removeContinueWatching
                when (uiState.homeLayout) {
                    HomeLayout.MODERN -> ExtraClassicContent(
                        uiState = uiState,
                        onNavigateToDetail = onNavigateToDetail,
                        onNavigateToSeeAll = onNavigateToSeeAll,
                        onContinueWatchingClick = onContinueWatchingClick,
                        onRemoveContinueWatching = onRemoveContinueWatching
                    )
                    HomeLayout.CLASSIC -> ExtraClassicContent(
                        uiState = uiState,
                        onNavigateToDetail = onNavigateToDetail,
                        onNavigateToSeeAll = onNavigateToSeeAll,
                        onContinueWatchingClick = onContinueWatchingClick,
                        onRemoveContinueWatching = onRemoveContinueWatching
                    )
                    HomeLayout.GRID -> ExtraGridContent(
                        uiState = uiState,
                        onNavigateToDetail = onNavigateToDetail,
                        onNavigateToSeeAll = onNavigateToSeeAll,
                        onContinueWatchingClick = onContinueWatchingClick,
                        onRemoveContinueWatching = onRemoveContinueWatching
                    )
                }
            }
        }
    }
}

@Composable
private fun extraPosterCardStyle(uiState: ExtraHomeUiState): PosterCardStyle {
    return remember(
        uiState.posterCardWidthDp,
        uiState.posterCardHeightDp,
        uiState.posterCardCornerRadiusDp
    ) {
        PosterCardStyle(
            width = uiState.posterCardWidthDp.dp,
            height = uiState.posterCardHeightDp.dp,
            cornerRadius = uiState.posterCardCornerRadiusDp.dp
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ExtraClassicContent(
    uiState: ExtraHomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onRemoveContinueWatching: (ContinueWatchingItem) -> Unit
) {
    val posterCardStyle = extraPosterCardStyle(uiState)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background),
        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl)
    ) {
        if (uiState.heroEnabled && uiState.heroItems.isNotEmpty()) {
            item(key = "extra_hero") {
                HeroCarousel(
                    items = uiState.heroItems.asStable(),
                    onItemClick = { item ->
                        onNavigateToDetail(item.id, item.rawType, uiState.heroAddonBaseUrl.orEmpty())
                    },
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.lg)
                )
            }
        }

        if (uiState.continueWatchingItems.isNotEmpty() || uiState.upcomingItems.isNotEmpty()) {
            item(key = "extra_continue_watching") {
                ContinueWatchingSection(
                    items = uiState.continueWatchingItems,
                    title = stringResource(R.string.continue_watching),
                    onItemClick = onContinueWatchingClick,
                    onRemoveItem = onRemoveContinueWatching,
                    cardWidth = posterCardStyle.width,
                    imageHeight = posterCardStyle.height,
                    cardStyle = uiState.continueWatchingCardStyle,
                    blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
                    useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.md)
                )
            }
        }

        if (uiState.upcomingItems.isNotEmpty()) {
            item(key = "extra_upcoming") {
                ContinueWatchingSection(
                    items = uiState.upcomingItems,
                    title = stringResource(R.string.cw_upcoming),
                    onItemClick = onContinueWatchingClick,
                    onRemoveItem = onRemoveContinueWatching,
                    cardWidth = posterCardStyle.width,
                    imageHeight = posterCardStyle.height,
                    cardStyle = uiState.continueWatchingCardStyle,
                    blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
                    useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.md)
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
                posterCardStyle = posterCardStyle,
                showPosterLabels = uiState.posterLabelsEnabled,
                showAddonName = uiState.catalogAddonNameEnabled,
                showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled,
                focusedPosterBackdropExpandEnabled = uiState.focusedPosterBackdropExpandEnabled,
                focusedPosterBackdropExpandDelaySeconds = uiState.focusedPosterBackdropExpandDelaySeconds,
                focusedPosterBackdropTrailerEnabled = uiState.focusedPosterBackdropTrailerEnabled,
                focusedPosterBackdropTrailerMuted = uiState.focusedPosterBackdropTrailerMuted
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ExtraGridContent(
    uiState: ExtraHomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onNavigateToSeeAll: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onRemoveContinueWatching: (ContinueWatchingItem) -> Unit
) {
    val posterCardStyle = extraPosterCardStyle(uiState)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background),
        contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl)
    ) {
        if (uiState.heroEnabled && uiState.heroItems.isNotEmpty()) {
            item(key = "extra_hero") {
                HeroCarousel(
                    items = uiState.heroItems.asStable(),
                    onItemClick = { item ->
                        onNavigateToDetail(item.id, item.rawType, uiState.heroAddonBaseUrl.orEmpty())
                    },
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.lg)
                )
            }
        }

        if (uiState.continueWatchingItems.isNotEmpty() || uiState.upcomingItems.isNotEmpty()) {
            item(key = "extra_continue_watching") {
                GridContinueWatchingSection(
                    items = uiState.continueWatchingItems + uiState.upcomingItems,
                    onItemClick = onContinueWatchingClick,
                    onRemoveItem = onRemoveContinueWatching,
                    cardStyle = uiState.continueWatchingCardStyle,
                    blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
                    useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw
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
                posterCardStyle = posterCardStyle,
                showPosterLabels = uiState.posterLabelsEnabled,
                showAddonName = uiState.catalogAddonNameEnabled,
                showCatalogTypeSuffix = uiState.catalogTypeSuffixEnabled
            )
        }
    }
}
