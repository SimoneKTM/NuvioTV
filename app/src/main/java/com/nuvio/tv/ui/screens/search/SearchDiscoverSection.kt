package com.nuvio.tv.ui.screens.search

import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.screens.home.HeroBackdropState

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.EmptyScreenState
import com.nuvio.tv.ui.components.GridContentCard
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.util.localizedContentType
import com.nuvio.tv.ui.util.localizedGenreLabel

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
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val localContext = LocalContext.current
    fun localizedTypeLabel(type: String): String = localizedContentType(localContext, type)

    val movieResults = uiState.discoverMovieResults
    val seriesResults = uiState.discoverSeriesResults
    val animeResults = uiState.discoverAnimeResults
    val hasAnyContent = movieResults.isNotEmpty() || seriesResults.isNotEmpty() || animeResults.isNotEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = NuvioTheme.spacing.xxxl),
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.lg)
    ) {
        if (showBuiltInHeader) {
            Text(
                text = stringResource(R.string.discover_title),
                style = MaterialTheme.typography.headlineMedium,
                color = NuvioTheme.colors.TextPrimary
            )
        }

        when {
            uiState.discoverLoading && !hasAnyContent -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 28.dp, bottom = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }

            uiState.discoverError != null && !uiState.discoverLoading -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    EmptyScreenState(
                        title = stringResource(R.string.discover_error_title),
                        subtitle = uiState.discoverError ?: stringResource(R.string.discover_error_subtitle),
                        icon = Icons.Default.Search
                    )
                    androidx.compose.material3.TextButton(onClick = onRetry) {
                        androidx.compose.material3.Text(text = stringResource(R.string.action_retry))
                    }
                }
            }

            !hasAnyContent && uiState.discoverInitialized -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    EmptyScreenState(
                        title = stringResource(R.string.discover_empty_no_content_title),
                        subtitle = stringResource(R.string.discover_empty_no_content_subtitle),
                        icon = Icons.Default.Search
                    )
                    androidx.compose.material3.TextButton(onClick = onRetry) {
                        androidx.compose.material3.Text(text = stringResource(R.string.action_retry))
                    }
                }
            }

            else -> {
                if (movieResults.isNotEmpty()) {
                    DiscoverTypeSection(
                        title = localizedTypeLabel("movie"),
                        items = movieResults,
                        posterCardStyle = posterCardStyle,
                        watchedMovieIds = watchedMovieIds,
                        watchedSeriesIds = watchedSeriesIds,
                        onNavigateToDetail = onNavigateToDetail,
                        onItemLongPress = onItemLongPress
                    )
                }

                if (seriesResults.isNotEmpty()) {
                    DiscoverTypeSection(
                        title = localizedTypeLabel("series"),
                        items = seriesResults,
                        posterCardStyle = posterCardStyle,
                        watchedMovieIds = watchedMovieIds,
                        watchedSeriesIds = watchedSeriesIds,
                        onNavigateToDetail = onNavigateToDetail,
                        onItemLongPress = onItemLongPress
                    )
                }

                if (animeResults.isNotEmpty()) {
                    DiscoverTypeSection(
                        title = localizedTypeLabel("anime"),
                        items = animeResults,
                        posterCardStyle = posterCardStyle,
                        watchedMovieIds = watchedMovieIds,
                        watchedSeriesIds = watchedSeriesIds,
                        onNavigateToDetail = onNavigateToDetail,
                        onItemLongPress = onItemLongPress
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DiscoverTypeSection(
    title: String,
    items: List<MetaPreview>,
    posterCardStyle: PosterCardStyle,
    watchedMovieIds: Set<String>,
    watchedSeriesIds: Set<String>,
    onNavigateToDetail: (String, String, String) -> Unit,
    onItemLongPress: (MetaPreview, String) -> Unit
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.sm)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = NuvioTheme.colors.TextPrimary,
            modifier = Modifier.padding(bottom = NuvioTheme.spacing.xxs)
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(NuvioTheme.spacing.md),
            contentPadding = PaddingValues(bottom = NuvioTheme.spacing.sm)
        ) {
            itemsIndexed(
                items = items,
                key = { index, item -> item.id.ifEmpty { "discover_${title}_$index" } }
            ) { _, item ->
                val cardWidth = posterCardStyle.width
                val cardHeight = posterCardStyle.height
                GridContentCard(
                    item = item,
                    onClick = {
                        HeroBackdropState.update(item.backdropUrl)
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
                    modifier = Modifier
                        .width(cardWidth)
                )
            }
        }
    }
}
