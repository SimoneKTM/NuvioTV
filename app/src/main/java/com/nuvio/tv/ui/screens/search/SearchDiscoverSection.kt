package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.ui.theme.NuvioTheme

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardStyle

private data class DiscoverOption(val label: String, val value: String)

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
    var expandedPicker by remember { mutableStateOf<String?>(null) }

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
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
    ) {
        if (showBuiltInHeader) {
            Text(
                text = stringResource(R.string.discover_title),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            DiscoverDropdownPicker(
                modifier = Modifier.weight(1f),
                title = "Tipo",
                value = localizedName(selectedType),
                selectedValue = selectedType,
                expanded = expandedPicker == "type",
                options = availableTypes.map { DiscoverOption(localizedName(it), it) },
                onExpandedChange = { expandedPicker = if (it) "type" else null },
                onSelect = {
                    onEvent(SearchEvent.DiscoverTypeChanged(it.value))
                    expandedPicker = null
                }
            )
            DiscoverDropdownPicker(
                modifier = Modifier.weight(1f),
                title = "Catalogo",
                value = selectedCatalog?.catalogName ?: "Seleziona catalogo",
                selectedValue = selectedCatalogKey,
                expanded = expandedPicker == "catalog",
                options = catalogsForType.map { DiscoverOption(it.catalogName, it.key) },
                onExpandedChange = { expandedPicker = if (it) "catalog" else null },
                onSelect = {
                    onEvent(SearchEvent.DiscoverCatalogChanged(it.value))
                    expandedPicker = null
                }
            )
            if (availableGenres.isNotEmpty()) {
                DiscoverDropdownPicker(
                    modifier = Modifier.weight(1f),
                    title = "Genere",
                    value = selectedGenre?.replaceFirstChar { c -> c.uppercase() } ?: "Tutti",
                    selectedValue = selectedGenre ?: "__default__",
                    expanded = expandedPicker == "genre",
                    options = buildList {
                        add(DiscoverOption("Tutti", "__default__"))
                        addAll(availableGenres.map { DiscoverOption(it.replaceFirstChar { c -> c.uppercase() }, it) })
                    },
                    onExpandedChange = { expandedPicker = if (it) "genre" else null },
                    onSelect = {
                        onEvent(SearchEvent.DiscoverGenreChanged(if (it.value == "__default__") null else it.value))
                        expandedPicker = null
                    }
                )
            }
        }

        when {
            uiState.discoverLoading && results.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 28.dp, bottom = 28.dp),
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

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun DiscoverDropdownPicker(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    selectedValue: String?,
    expanded: Boolean,
    options: List<DiscoverOption>,
    onExpandedChange: (Boolean) -> Unit,
    onSelect: (DiscoverOption) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    var anchorSize by remember { mutableStateOf(IntSize.Zero) }

    Box(modifier = modifier) {
        Card(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { anchorSize = it }
                .onFocusChanged { isFocused = it.isFocused },
            shape = CardDefaults.shape(shape = RoundedCornerShape(14.dp)),
            colors = CardDefaults.colors(
                containerColor = NuvioTheme.colors.BackgroundCard,
                focusedContainerColor = NuvioTheme.colors.Background
            ),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border),
                    shape = RoundedCornerShape(14.dp)
                ),
                focusedBorder = Border(
                    border = BorderStroke(2.dp, NuvioTheme.colors.Border),
                    shape = RoundedCornerShape(14.dp)
                )
            ),
            scale = CardDefaults.scale(
                focusedScale = 1.0f,
                pressedScale = 1.0f
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xxs)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioTheme.colors.TextTertiary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioTheme.colors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = if (isFocused) NuvioTheme.colors.TextTertiary else NuvioTheme.colors.TextSecondary
                    )
                }
            }
        }

        NuvioTheme {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { onExpandedChange(false) },
                modifier = Modifier
                    .width(with(LocalDensity.current) { anchorSize.width.toDp() })
                    .heightIn(max = 320.dp),
                shape = RoundedCornerShape(14.dp),
                containerColor = NuvioTheme.colors.BackgroundCard,
                tonalElevation = 0.dp,
                shadowElevation = NuvioTheme.spacing.sm,
                border = BorderStroke(NuvioTheme.spacing.hairline, NuvioTheme.colors.Border)
            ) {
            options.forEach { option ->
                val isSelected = option.value == selectedValue
                val itemTextColor = NuvioTheme.colors.TextPrimary
                val itemBackgroundColor = when {
                    isSelected -> NuvioTheme.colors.Secondary
                    else -> Color.Transparent
                }

                DropdownMenuItem(
                    modifier = Modifier
                        .padding(horizontal = 6.dp, vertical = NuvioTheme.spacing.xxs)
                        .background(
                            color = itemBackgroundColor,
                            shape = RoundedCornerShape(10.dp)
                        ),
                    text = {
                        Text(
                            text = option.label,
                            color = itemTextColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = { onSelect(option) },
                    colors = MenuDefaults.itemColors(
                        textColor = itemTextColor
                    )
                )
            }
        }
        }
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
