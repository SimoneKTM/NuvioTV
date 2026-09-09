package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.screens.home.ClassicHomeContent
import com.nuvio.tv.ui.screens.home.GridHomeContent
import com.nuvio.tv.ui.screens.home.ModernHomeContent
import com.nuvio.tv.ui.util.StableList
import com.nuvio.tv.ui.util.asStable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface HomeRouteViewModel {
    val uiState: StateFlow<HomeUiState>
    val focusState: StateFlow<HomeScreenFocusState>
    val gridFocusState: StateFlow<HomeScreenFocusState>
    val scrollToTopTrigger: StateFlow<Int>
    val trailerPreviewUrls: Map<String, String>
    val trailerPreviewAudioUrls: Map<String, String>
    val enrichingItemId: StateFlow<String?>
    val lastEnrichedPreview: StateFlow<MetaPreview?>
    val enrichedPreviews: StateFlow<Map<String, MetaPreview>>
    val failedEnrichmentIds: StateFlow<Set<String>>

    fun onEvent(event: HomeEvent)
    fun requestTrailerPreview(item: MetaPreview)
    fun onItemFocus(item: MetaPreview)
    fun saveFocusState(
        vi: Int, vo: Int, rk: String?, ikm: Map<String, String>,
        m: Map<String, Int>, ri: Int, ii: Int
    )
    fun saveGridFocusState(vi: Int, vo: Int, focusedItemKey: String)
    fun requestLazyCatalogLoad(catalogKey: String)
    fun preloadAdjacentItem(item: MetaPreview)
}