package com.nuvio.tv.ui.screens.anime

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterDrama
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
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
                            Button(onClick = onOpenSettings) {
                                androidx.tv.material3.Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = stringResource(R.string.anime_settings_title)
                                )
                                Spacer(modifier = Modifier.width(NuvioTheme.spacing.sm))
                                Text(stringResource(R.string.anime_settings_title))
                            }
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
