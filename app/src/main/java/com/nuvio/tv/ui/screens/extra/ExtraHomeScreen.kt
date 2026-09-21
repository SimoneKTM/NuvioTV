package com.nuvio.tv.ui.screens.extra

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.ui.components.ContinueWatchingCard
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.theme.NuvioTheme

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
                    HomeLayout.MODERN -> ExtraModernContent(
                        uiState = uiState,
                        onNavigateToDetail = onNavigateToDetail,
                        onContinueWatchingClick = onContinueWatchingClick,
                        onRemoveContinueWatching = onRemoveContinueWatching,
                        onCategorySelected = viewModel::selectCategory,
                        onLoadMoreCatalog = viewModel::loadMoreCatalogItems
                    )
                    HomeLayout.CLASSIC -> ExtraClassicContent(
                        uiState = uiState,
                        onNavigateToDetail = onNavigateToDetail,
                        onContinueWatchingClick = onContinueWatchingClick,
                        onRemoveContinueWatching = onRemoveContinueWatching,
                        onCategorySelected = viewModel::selectCategory,
                        onLoadMoreCatalog = viewModel::loadMoreCatalogItems
                    )
                    HomeLayout.GRID -> ExtraGridContent(
                        uiState = uiState,
                        onNavigateToDetail = onNavigateToDetail,
                        onContinueWatchingClick = onContinueWatchingClick,
                        onRemoveContinueWatching = onRemoveContinueWatching,
                        onCategorySelected = viewModel::selectCategory,
                        onLoadMoreCatalog = viewModel::loadMoreCatalogItems
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ExtraModernContent(
    uiState: ExtraHomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onRemoveContinueWatching: (ContinueWatchingItem) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit
) {
    val posterCardWidth = uiState.posterCardWidthDp.dp
    val posterCardHeight = uiState.posterCardHeightDp.dp
    val posterCardCornerRadius = uiState.posterCardCornerRadiusDp.dp

    val filteredRows = filterByCategory(uiState)

    Column(modifier = Modifier.fillMaxSize()) {
        if (uiState.continueWatchingItems.isNotEmpty() || uiState.upcomingItems.isNotEmpty()) {
            ExtraCompactContinueWatchingRow(
                items = uiState.continueWatchingItems + uiState.upcomingItems,
                cardWidth = posterCardWidth * 1.24f,
                cardHeight = posterCardHeight * 1.24f / 1.77f * 1.77f,
                cornerRadius = posterCardCornerRadius,
                blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
                useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                onItemClick = onContinueWatchingClick,
                onRemoveItem = onRemoveContinueWatching,
                modifier = Modifier.padding(start = 48.dp, top = NuvioTheme.spacing.md)
            )
        }

        if (uiState.categories.isNotEmpty()) {
            ExtraCategoryChips(
                categories = uiState.categories,
                selectedCategory = uiState.selectedCategory,
                onCategorySelected = onCategorySelected
            )
        }

        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 160.dp)
        ) {
            filteredRows.forEach { row ->
                item(key = "title_${row.catalogId}") {
                    Text(
                        text = formatCatalogName(row, uiState),
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier.padding(start = 48.dp, top = NuvioTheme.spacing.lg, bottom = NuvioTheme.spacing.xs)
                    )
                }
                item(key = "row_${row.catalogId}") {
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = PaddingValues(horizontal = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                    ) {
                        items(
                            items = row.items.filter { !it.id.startsWith("__placeholder_") },
                            key = { it.id }
                        ) { item ->
                            com.nuvio.tv.ui.components.ContentCard(
                                item = item,
                                posterCardStyle = PosterCardStyle(
                                    width = posterCardWidth,
                                    height = posterCardHeight,
                                    cornerRadius = posterCardCornerRadius
                                ),
                                showLabels = uiState.posterLabelsEnabled,
                                onClick = { onNavigateToDetail(item.id, item.apiType, row.addonBaseUrl) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ExtraClassicContent(
    uiState: ExtraHomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onRemoveContinueWatching: (ContinueWatchingItem) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit
) {
    val posterCardWidth = uiState.posterCardWidthDp.dp
    val posterCardHeight = uiState.posterCardHeightDp.dp
    val posterCardCornerRadius = uiState.posterCardCornerRadiusDp.dp

    val filteredRows = filterByCategory(uiState)

    Column(modifier = Modifier.fillMaxSize()) {
        if (uiState.continueWatchingItems.isNotEmpty() || uiState.upcomingItems.isNotEmpty()) {
            ExtraCompactContinueWatchingRow(
                items = uiState.continueWatchingItems + uiState.upcomingItems,
                cardWidth = posterCardWidth * 1.24f,
                cardHeight = posterCardHeight * 1.24f / 1.77f * 1.77f,
                cornerRadius = posterCardCornerRadius,
                blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
                useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                onItemClick = onContinueWatchingClick,
                onRemoveItem = onRemoveContinueWatching,
                modifier = Modifier.padding(start = 48.dp, top = NuvioTheme.spacing.md)
            )
        }

        if (uiState.categories.isNotEmpty()) {
            ExtraCategoryChips(
                categories = uiState.categories,
                selectedCategory = uiState.selectedCategory,
                onCategorySelected = onCategorySelected
            )
        }

        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 160.dp)
        ) {
            filteredRows.forEach { row ->
                item(key = "title_${row.catalogId}") {
                    Text(
                        text = formatCatalogName(row, uiState),
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        modifier = Modifier.padding(start = 48.dp, top = NuvioTheme.spacing.lg, bottom = NuvioTheme.spacing.xs)
                    )
                }
                item(key = "row_${row.catalogId}") {
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding = PaddingValues(horizontal = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
                    ) {
                        items(
                            items = row.items.filter { !it.id.startsWith("__placeholder_") },
                            key = { it.id }
                        ) { item ->
                            com.nuvio.tv.ui.components.ContentCard(
                                item = item,
                                posterCardStyle = PosterCardStyle(
                                    width = posterCardWidth,
                                    height = posterCardHeight,
                                    cornerRadius = posterCardCornerRadius
                                ),
                                showLabels = uiState.posterLabelsEnabled,
                                onClick = { onNavigateToDetail(item.id, item.apiType, row.addonBaseUrl) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ExtraGridContent(
    uiState: ExtraHomeUiState,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onRemoveContinueWatching: (ContinueWatchingItem) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onLoadMoreCatalog: (String, String, String) -> Unit
) {
    val posterCardWidth = uiState.posterCardWidthDp.dp
    val posterCardHeight = uiState.posterCardHeightDp.dp
    val posterCardCornerRadius = uiState.posterCardCornerRadiusDp.dp

    val filteredRows = filterByCategory(uiState)

    val allItems = filteredRows.flatMap { row ->
        row.items.filter { !it.id.startsWith("__placeholder_") }.map { it to row.addonBaseUrl }
    }

    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = posterCardWidth),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 52.dp,
            end = 52.dp,
            top = NuvioTheme.spacing.md,
            bottom = NuvioTheme.spacing.xxl
        ),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        if (uiState.continueWatchingItems.isNotEmpty() || uiState.upcomingItems.isNotEmpty()) {
            item(key = "extra_cw_title") {
                Text(
                    text = stringResource(R.string.continue_watching),
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioTheme.colors.TextPrimary,
                    modifier = Modifier.padding(bottom = NuvioTheme.spacing.xs)
                )
            }
            item(key = "extra_cw_row") {
                ExtraCompactContinueWatchingRow(
                    items = uiState.continueWatchingItems + uiState.upcomingItems,
                    cardWidth = posterCardWidth,
                    cardHeight = posterCardHeight,
                    cornerRadius = posterCardCornerRadius,
                    blurUnwatchedEpisodes = uiState.blurContinueWatchingNextUp,
                    useEpisodeThumbnails = uiState.useEpisodeThumbnailsInCw,
                    onItemClick = onContinueWatchingClick,
                    onRemoveItem = onRemoveContinueWatching
                )
            }
        }

        if (uiState.categories.isNotEmpty()) {
            item(key = "extra_categories") {
                ExtraCategoryChips(
                    categories = uiState.categories,
                    selectedCategory = uiState.selectedCategory,
                    onCategorySelected = onCategorySelected
                )
            }
        }

        items(
            items = allItems,
            key = { (item, _) -> item.id.ifEmpty { "extra_${allItems.indexOfFirst { it.first.id == item.id }}" } }
        ) { (item, addonBaseUrl) ->
            com.nuvio.tv.ui.components.ContentCard(
                item = item,
                posterCardStyle = PosterCardStyle(
                    width = posterCardWidth,
                    height = posterCardHeight,
                    cornerRadius = posterCardCornerRadius
                ),
                showLabels = uiState.posterLabelsEnabled,
                onClick = { onNavigateToDetail(item.id, item.apiType, addonBaseUrl) }
            )
        }
    }
}

private fun filterByCategory(uiState: ExtraHomeUiState): List<com.nuvio.tv.domain.model.CatalogRow> {
    return if (uiState.selectedCategory != null) {
        uiState.rows.filter { row ->
            formatCatalogName(row, uiState) == uiState.selectedCategory
        }
    } else {
        uiState.rows
    }
}

private fun formatCatalogName(
    row: com.nuvio.tv.domain.model.CatalogRow,
    uiState: ExtraHomeUiState
): String {
    val formatted = row.catalogName.replaceFirstChar { it.uppercase() }
    return if (uiState.catalogTypeSuffixEnabled && row.rawType.isNotBlank()) {
        val typeLabel = when (row.rawType.lowercase()) {
            "movie" -> "Film"
            "tv" -> "Serie"
            else -> row.rawType
        }
        "$formatted - $typeLabel"
    } else {
        formatted
    }
}

@Composable
private fun ExtraCategoryChips(
    categories: List<String>,
    selectedCategory: String?,
    onCategorySelected: (String?) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = NuvioTheme.spacing.xs, horizontal = 48.dp),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        item(key = "all") {
            val isSelected = selectedCategory == null
            val chipShape = RoundedCornerShape(24.dp)
            Box(
                modifier = Modifier
                    .clip(chipShape)
                    .background(
                        if (isSelected) NuvioTheme.colors.Secondary.copy(alpha = 0.2f)
                        else Color.Transparent
                    )
                    .then(
                        if (isSelected) Modifier
                        else Modifier.border(
                            BorderStroke(1.dp, NuvioTheme.colors.TextSecondary.copy(alpha = 0.3f)),
                            chipShape
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Tutti",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary
                )
            }
        }
        items(count = categories.size, key = { categories[it] }) { index ->
            val category = categories[index]
            val isSelected = selectedCategory == category
            val chipShape = RoundedCornerShape(24.dp)
            Box(
                modifier = Modifier
                    .clip(chipShape)
                    .background(
                        if (isSelected) NuvioTheme.colors.Secondary.copy(alpha = 0.2f)
                        else Color.Transparent
                    )
                    .then(
                        if (isSelected) Modifier
                        else Modifier.border(
                            BorderStroke(1.dp, NuvioTheme.colors.TextSecondary.copy(alpha = 0.3f)),
                            chipShape
                        )
                    )
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = category,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun ExtraCompactContinueWatchingRow(
    items: List<ContinueWatchingItem>,
    cardWidth: androidx.compose.ui.unit.Dp,
    cardHeight: androidx.compose.ui.unit.Dp,
    cornerRadius: androidx.compose.ui.unit.Dp,
    blurUnwatchedEpisodes: Boolean,
    useEpisodeThumbnails: Boolean,
    onItemClick: (ContinueWatchingItem) -> Unit,
    onRemoveItem: (ContinueWatchingItem) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 0.dp),
        horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        itemsIndexed(
            items = items,
            key = { _, item -> continueWatchingItemKey(item) }
        ) { _, item ->
            var cardFocused by remember { mutableStateOf(false) }
            ContinueWatchingCard(
                item = item,
                onClick = { onItemClick(item) },
                onLongPress = { onRemoveItem(item) },
                cardWidth = cardWidth,
                imageHeight = cardHeight,
                blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                useEpisodeThumbnails = useEpisodeThumbnails,
                cardStyle = ContinueWatchingCardStyle.POSTER,
                cornerRadius = cornerRadius,
                isFocused = cardFocused,
                modifier = Modifier.onFocusChanged { state ->
                    cardFocused = state.isFocused
                }
            )
        }
    }
}

private fun continueWatchingItemKey(item: ContinueWatchingItem): String = when (item) {
    is ContinueWatchingItem.InProgress -> "in_progress:${item.progress.contentId}:${item.progress.videoId}"
    is ContinueWatchingItem.NextUp -> "next_up:${item.info.contentId}:${item.info.videoId}"
}
