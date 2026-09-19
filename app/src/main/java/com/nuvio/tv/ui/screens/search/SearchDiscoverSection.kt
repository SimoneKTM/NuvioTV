package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip

import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardStyle

private val DropdownSurfaceColor = Color(0xFF1E1E2E)
private val DropdownItemHoverColor = Color(0xFF2A2A3C)
private val ChipSurfaceColor = Color(0xFF2A2A3C)
private val ChipSelectedColor = Color(0xFFE50914)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DiscoverSection(
    uiState: SearchUiState,
    posterCardStyle: PosterCardStyle,
    watchedMovieIds: Set<String> = emptySet(),
    watchedSeriesIds: Set<String> = emptySet(),
    showBuiltInHeader: Boolean = true,
    onNavigateToDetail: (String, String, String) -> Unit,
    onItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
    onEvent: (SearchEvent) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val discoverCatalogs = uiState.discoverCatalogs
    val selectedType = uiState.selectedDiscoverType
    val selectedCatalogKey = uiState.selectedDiscoverCatalogKey
    val selectedGenre = uiState.selectedDiscoverGenre
    val results = uiState.discoverResults

    val availableTypes = remember(discoverCatalogs) {
        discoverCatalogs.map { it.type }.distinct()
    }
    val catalogsForType = remember(discoverCatalogs, selectedType) {
        discoverCatalogs.filter { it.type == selectedType }
    }
    val selectedCatalog = discoverCatalogs.find { it.key == selectedCatalogKey }
    val availableGenres = selectedCatalog?.genres ?: emptyList()

    val gridState = rememberLazyGridState()

    LaunchedEffect(uiState.discoverHasMore, uiState.pendingDiscoverResults) {
        if (uiState.discoverHasMore && uiState.pendingDiscoverResults.isEmpty()) {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (lastVisible >= results.size - 6) {
                onEvent(SearchEvent.LoadMoreDiscoverResults)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.xxxl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        if (showBuiltInHeader) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.discover_title),
                    style = MaterialTheme.typography.headlineMedium,
                    color = NuvioTheme.colors.TextPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
                SectionBadge(count = results.size)
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            DiscoverChip(
                label = "Tipo",
                options = availableTypes.map { it to localizedName(it) },
                selectedValue = selectedType,
                onSelect = { onEvent(SearchEvent.DiscoverTypeChanged(it)) }
            )
            DiscoverChip(
                label = "Catalogo",
                options = catalogsForType.map { it.key to it.catalogName },
                selectedValue = selectedCatalogKey,
                onSelect = { onEvent(SearchEvent.DiscoverCatalogChanged(it)) }
            )
            if (availableGenres.isNotEmpty()) {
                val genreOptions = listOf("__default__" to "Tutti") + availableGenres.map { it to it.replaceFirstChar { c -> c.uppercase() } }
                DiscoverChip(
                    label = "Genere",
                    options = genreOptions,
                    selectedValue = selectedGenre ?: "__default__",
                    onSelect = { onEvent(SearchEvent.DiscoverGenreChanged(if (it == "__default__") null else it)) }
                )
            }
        }

        if (selectedCatalog != null) {
            Text(
                text = "${selectedCatalog.addonName} \u2022 ${localizedName(selectedType)}${selectedGenre?.let { " \u2022 $it" } ?: ""}",
                style = MaterialTheme.typography.labelMedium,
                color = NuvioTheme.colors.TextTertiary
            )
        }

        when {
            uiState.discoverLoading && results.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(400.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }

            discoverCatalogs.isEmpty() && uiState.discoverInitialized -> {
                EmptyScreenState(
                    title = stringResource(R.string.discover_empty_no_content_title),
                    subtitle = stringResource(R.string.discover_empty_no_content_subtitle),
                    icon = Icons.Default.Explore
                )
            }

            results.isEmpty() && !uiState.discoverLoading -> {
                EmptyScreenState(
                    title = stringResource(R.string.discover_empty_no_content_title),
                    subtitle = stringResource(R.string.discover_empty_no_content_subtitle),
                    icon = Icons.Default.Explore
                )
            }

            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = posterCardStyle.width),
                    state = gridState,
                    contentPadding = PaddingValues(bottom = 80.dp),
                    horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(
                        items = results,
                        key = { index, item -> item.id.ifEmpty { "discover_$index" } }
                    ) { _, item ->
                        GridContentCard(
                            item = item,
                            onClick = {
                                onNavigateToDetail(
                                    item.id,
                                    item.apiType,
                                    item.sourceAddonBaseUrl ?: ""
                                )
                            },
                            onLongPress = {
                                onItemLongPress(item, item.sourceAddonBaseUrl ?: "")
                            },
                            posterCardStyle = posterCardStyle,
                            isWatched = run {
                                val isSeries = item.apiType.equals("series", ignoreCase = true) ||
                                    item.apiType.equals("tv", ignoreCase = true)
                                if (isSeries) item.id in watchedSeriesIds else item.id in watchedMovieIds
                            },
                            modifier = Modifier.width(posterCardStyle.width)
                        )
                    }

                    if (uiState.discoverLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .width(posterCardStyle.width)
                                    .height(posterCardStyle.height),
                                contentAlignment = Alignment.Center
                            ) {
                                LoadingIndicator(modifier = Modifier.size(32.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DiscoverChip(
    label: String,
    options: List<Pair<String, String>>,
    selectedValue: String?,
    onSelect: (String) -> Unit
) {
    val selectedLabel = options.find { it.first == selectedValue }?.second
        ?: options.firstOrNull()?.second
        ?: label
    val isSelected = options.any { it.first == selectedValue }
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                when {
                    isFocused -> ChipSelectedColor.copy(alpha = 0.8f)
                    isSelected -> ChipSelectedColor
                    else -> ChipSurfaceColor
                }
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable(interactionSource = remember { MutableInteractionSource() })
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyUp &&
                    (keyEvent.key == Key.DirectionCenter || keyEvent.key == Key.Enter)
                ) {
                    val currentIndex = options.indexOfFirst { it.first == selectedValue }
                    val nextIndex = if (currentIndex >= 0) (currentIndex + 1) % options.size else 0
                    if (options.isNotEmpty()) {
                        onSelect(options[nextIndex].first)
                    }
                    true
                } else false
            }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f)
            )
            Text(
                text = selectedLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SectionBadge(count: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(NuvioTheme.colors.Secondary.copy(alpha = 0.2f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = NuvioTheme.colors.Secondary
        )
    }
}

private fun localizedName(type: String): String = when (type.lowercase()) {
    "movie" -> "Film"
    "series" -> "Serie TV"
    "tv" -> "Serie TV"
    "anime" -> "Anime"
    "altro" -> "Altro"
    else -> type.replaceFirstChar { it.uppercase() }
}
