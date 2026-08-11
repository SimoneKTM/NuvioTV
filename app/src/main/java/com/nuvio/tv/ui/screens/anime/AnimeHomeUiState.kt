package com.nuvio.tv.ui.screens.anime

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MetaPreview

@Immutable
data class AnimeHomeUiState(
    val rows: List<CatalogRow> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val installedAddonsCount: Int = 0,
    val heroEnabled: Boolean = true,
    val heroItem: MetaPreview? = null,
    val heroItems: List<MetaPreview> = emptyList(),
    val heroAddonBaseUrl: String? = null,
    val homeLayout: HomeLayout = HomeLayout.MODERN,
    val catalogTypeSuffixEnabled: Boolean = true,
    val hideUnreleasedContent: Boolean = false
)

sealed class AnimeHomeEvent {
    data class OnLoadMoreCatalog(val catalogId: String, val addonId: String, val type: String) : AnimeHomeEvent()
    data object OnRetry : AnimeHomeEvent()
}
