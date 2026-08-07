package com.nuvio.tv.ui.screens.anime

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.CatalogRow

@Immutable
data class AnimeHomeUiState(
    val rows: List<CatalogRow> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val installedAddonsCount: Int = 0
)

sealed class AnimeHomeEvent {
    data class OnLoadMoreCatalog(val catalogId: String, val addonId: String, val type: String) : AnimeHomeEvent()
    data object OnRetry : AnimeHomeEvent()
}
