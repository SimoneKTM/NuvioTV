package com.nuvio.tv.ui.screens.anime

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterDrama
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.legacyKey
import com.nuvio.tv.ui.components.CatalogRowSection
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.dpadRepeatThrottle

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
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .dpadRepeatThrottle(),
                    contentPadding = PaddingValues(bottom = NuvioTheme.spacing.xxl)
                ) {
                    item(key = "anime_header") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    start = NuvioTheme.spacing.xxxl,
                                    end = NuvioTheme.spacing.xxxl,
                                    top = NuvioTheme.spacing.xl,
                                    bottom = NuvioTheme.spacing.lg
                                ),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.xs)) {
                                Text(
                                    text = stringResource(R.string.nav_anime),
                                    style = MaterialTheme.typography.headlineLarge,
                                    color = NuvioTheme.colors.TextPrimary
                                )
                                if (uiState.installedAddonsCount > 0) {
                                    Text(
                                        text = stringResource(R.string.anime_home_addons_count, uiState.installedAddonsCount),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = NuvioTheme.colors.TextSecondary
                                    )
                                }
                            }
                        }
                    }

                    val heroItem = uiState.heroItem
                    if (uiState.heroEnabled && heroItem != null) {
                        item(key = "anime_hero") {
                            AnimeHomeHeroBanner(
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
                        items = rows,
                        key = { row -> row.legacyKey() }
                    ) { row ->
                        CatalogRowSection(
                            catalogRow = row,
                            onItemClick = onNavigateToDetail,
                            onSeeAll = {
                                onNavigateToSeeAll(row.catalogId, row.addonId, row.apiType)
                            },
                            showSeeAll = row.hasMore || row.items.size >= 15
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimeHomeHeroBanner(
    item: MetaPreview,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(300.dp)
            .padding(horizontal = NuvioTheme.spacing.xxxl)
            .clip(RoundedCornerShape(NuvioTheme.radii.lg))
    ) {
        AsyncImage(
            model = item.backdropUrl,
            contentDescription = item.name,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            NuvioTheme.colors.BackgroundElevated,
                            Color.Transparent
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, NuvioTheme.colors.BackgroundElevated)
                    )
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(NuvioTheme.spacing.xl),
            verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md)
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.headlineLarge,
                color = NuvioTheme.colors.TextPrimary,
                maxLines = 2
            )
            val details = buildList {
                item.releaseInfo?.takeIf { it.isNotBlank() }?.let(::add)
                item.genres.takeIf { it.isNotEmpty() }?.joinToString(" • ")?.let(::add)
            }
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString("  •  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioTheme.colors.TextSecondary,
                    maxLines = 1
                )
            }
            Button(onClick = onOpen) {
                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.padding(horizontal = NuvioTheme.spacing.xs))
                Text(stringResource(R.string.hero_play))
            }
        }
    }
}
