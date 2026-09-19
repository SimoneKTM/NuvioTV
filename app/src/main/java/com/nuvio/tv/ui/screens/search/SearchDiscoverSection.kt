package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.ui.theme.NuvioTheme

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardStyle
import kotlin.math.roundToInt

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
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
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
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DiscoverDropdownPicker(
                label = "Tipo",
                options = availableTypes.map { it to localizedName(it) },
                selectedValue = selectedType,
                onSelect = { onEvent(SearchEvent.DiscoverTypeChanged(it)) }
            )
            DiscoverDropdownPicker(
                label = "Catalogo",
                options = catalogsForType.map { it.key to it.catalogName },
                selectedValue = selectedCatalogKey,
                onSelect = { onEvent(SearchEvent.DiscoverCatalogChanged(it)) }
            )
            if (availableGenres.isNotEmpty()) {
                val genreOptions = listOf("__default__" to "Tutti") + availableGenres.map { it to it.replaceFirstChar { c -> c.uppercase() } }
                DiscoverDropdownPicker(
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DiscoverDropdownPicker(
    label: String,
    options: List<Pair<String, String>>,
    selectedValue: String?,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.find { it.first == selectedValue }?.second
        ?: options.firstOrNull()?.second
        ?: label

    Box {
        androidx.compose.material3.OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.height(36.dp),
            shape = RoundedCornerShape(8.dp),
            colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                contentColor = NuvioTheme.colors.TextPrimary
            ),
            border = BorderStroke(1.dp, NuvioTheme.colors.Border)
        ) {
            Text(
                text = "$label: $selectedLabel",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (value, displayName) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = displayName,
                            fontWeight = if (value == selectedValue) FontWeight.Bold else FontWeight.Normal,
                            color = if (value == selectedValue) NuvioTheme.colors.Secondary else NuvioTheme.colors.TextPrimary
                        )
                    },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    }
                )
            }
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
