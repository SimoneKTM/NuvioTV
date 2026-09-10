package com.nuvio.tv.ui.screens.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.components.PosterCardStyle
import com.nuvio.tv.ui.screens.home.ClassicHomeContent
import com.nuvio.tv.ui.screens.home.GridHomeContent
import com.nuvio.tv.ui.screens.home.ModernHomeContent
import com.nuvio.tv.ui.screens.home.HomeEvent
import com.nuvio.tv.ui.util.StableList
import com.nuvio.tv.ui.util.asStable
import kotlinx.coroutines.flow.StateFlow

private fun <T> Any?.getStateFlowValue(propertyName: String): StateFlow<T>? {
    return try {
        val clazz = this?.javaClass
        val field = clazz?.getDeclaredField(propertyName)
        field?.isAccessible = true
        val value = field?.get(this)
        if (value is StateFlow<*>) value as StateFlow<T> else null
    } catch (_: Exception) {
        null
    }
}

private fun <K, V> Any?.getMapValue(propertyName: String): Map<K, V>? {
    return try {
        val clazz = this?.javaClass
        val field = clazz?.getDeclaredField(propertyName)
        field?.isAccessible = true
        val value = field?.get(this)
        if (value is Map<*, *>) value as Map<K, V> else null
    } catch (_: Exception) {
        null
    }
}

private fun Any?.callMethod(methodName: String, vararg args: Any?): Any? {
    return try {
        val clazz = this.javaClass
        val methods = clazz.getDeclaredMethods()
        val method = methods.firstOrNull { it.name == methodName && it.parameterTypes.size == args.size }
        method?.isAccessible = true
        method?.invoke(this, *args)
    } catch (_: Exception) {
        null
    }
}

@Composable
internal fun ClassicHomeRoute(
    viewModel: Any,
    uiState: Any,
    posterCardStyle: PosterCardStyle,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit
) {
    val focusState = viewModel.getStateFlowValue<HomeScreenFocusState>("focusState")?.collectAsStateWithLifecycle()
    val scrollToTopTrigger = viewModel.getStateFlowValue<Int>("scrollToTopTrigger")?.collectAsStateWithLifecycle()
    val trailerPreviewUrls = viewModel.getMapValue<String, String>("trailerPreviewUrls") ?: emptyMap()
    val trailerPreviewAudioUrls = viewModel.getMapValue<String, String>("trailerPreviewAudioUrls") ?: emptyMap()
    ClassicHomeContent(
        uiState = uiState as HomeUiState,
        posterCardStyle = posterCardStyle,
        focusState = focusState?.value,
        scrollToTopTrigger = scrollToTopTrigger?.value,
        trailerPreviewUrls = trailerPreviewUrls,
        trailerPreviewAudioUrls = trailerPreviewAudioUrls,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingPlayManually = onContinueWatchingPlayManually,
        showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
        onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
        onNavigateToFolderDetail = onNavigateToFolderDetail,
        onRemoveContinueWatching = { contentId, season, episode, isNextUp ->
            viewModel.callMethod("onEvent", HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
        },
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onRequestTrailerPreview = { item ->
            viewModel.callMethod("requestTrailerPreview", item)
        },
        onItemFocus = { item ->
            viewModel.callMethod("onItemFocus", item)
        },
        onSaveFocusState = { vi, vo, rk, ikm, m, ri, ii ->
            viewModel.callMethod("saveFocusState", vi, vo, rk, ikm, m, ri, ii)
        },
        onRequestLazyCatalogLoad = remember(viewModel) {
            { catalogKey: String -> viewModel.callMethod("requestLazyCatalogLoad", catalogKey) }
        }
    )
}

@Composable
internal fun GridHomeRoute(
    viewModel: Any,
    uiState: Any,
    posterCardStyle: PosterCardStyle,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit
) {
    val gridFocusState = viewModel.getStateFlowValue<HomeScreenFocusState>("gridFocusState")?.collectAsStateWithLifecycle()
    val scrollToTopTrigger = viewModel.getStateFlowValue<Int>("scrollToTopTrigger")?.collectAsStateWithLifecycle()
    GridHomeContent(
        uiState = uiState as HomeUiState,
        posterCardStyle = posterCardStyle,
        gridFocusState = gridFocusState?.value,
        scrollToTopTrigger = scrollToTopTrigger?.value,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingPlayManually = onContinueWatchingPlayManually,
        showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
        onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
        onNavigateToFolderDetail = onNavigateToFolderDetail,
        onRemoveContinueWatching = remember(viewModel) {
            { contentId, season, episode, isNextUp ->
                viewModel.callMethod("onEvent", HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
            }
        },
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onItemFocus = remember(viewModel) {
            { item ->
                viewModel.callMethod("onItemFocus", item)
            }
        },
        onSaveGridFocusState = remember(viewModel) {
            { vi, vo, key ->
                viewModel.callMethod("saveGridFocusState", vi, vo, key)
            }
        }
    )
}

@Composable
internal fun ModernHomeRoute(
    viewModel: Any,
    uiState: Any,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingPlayManually: (ContinueWatchingItem) -> Unit,
    showContinueWatchingManualPlayOption: Boolean,
    onNavigateToFolderDetail: (String, String) -> Unit = { _, _ -> },
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit
) {
    val focusState = viewModel.getStateFlowValue<HomeScreenFocusState>("focusState")?.collectAsStateWithLifecycle()
    val scrollToTopTrigger = viewModel.getStateFlowValue<Int>("scrollToTopTrigger")?.collectAsStateWithLifecycle()
    val enrichingItemId = viewModel.getStateFlowValue<String?>("enrichingItemId")?.collectAsStateWithLifecycle()
    val lastEnrichedPreview = viewModel.getStateFlowValue<MetaPreview?>("lastEnrichedPreview")?.collectAsStateWithLifecycle()
    val enrichedPreviews = viewModel.getStateFlowValue<Map<String, MetaPreview>>("enrichedPreviews")?.collectAsStateWithLifecycle()
    val failedEnrichmentIds = viewModel.getStateFlowValue<Set<String>>("failedEnrichmentIds")?.collectAsStateWithLifecycle()
    val requestTrailerPreview = remember(viewModel) {
        { itemId: String, title: String, releaseInfo: String?, apiType: String ->
            viewModel.callMethod("requestTrailerPreview", itemId, title, releaseInfo, apiType)
        }
    }
    val loadMoreCatalog = remember(viewModel) {
        { catalogId: String, addonId: String, type: String ->
            viewModel.callMethod("onEvent", HomeEvent.OnLoadMoreCatalog(catalogId, addonId, type))
        }
    }
    val removeContinueWatching = remember(viewModel) {
        { contentId: String, season: Int?, episode: Int?, isNextUp: Boolean ->
            viewModel.callMethod("onEvent", HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
        }
    }
    val saveModernFocusState = remember(viewModel) {
        { vi: Int, vo: Int, rk: String?, ikm: Map<String, String>, m: Map<String, Int>, ri: Int, ii: Int ->
            viewModel.callMethod("saveFocusState", vi, vo, rk, ikm, m, ri, ii)
        }
    }
    val preloadAdjacentItem = remember(viewModel) {
        { item: MetaPreview ->
            viewModel.callMethod("preloadAdjacentItem", item)
        }
    }
    ModernHomeContent(
        uiState = uiState as HomeUiState,
        focusState = focusState?.value,
        scrollToTopTrigger = scrollToTopTrigger?.value,
        enrichingItemId = enrichingItemId?.value,
        lastEnrichedPreview = lastEnrichedPreview?.value,
        enrichedPreviews = enrichedPreviews?.value,
        failedEnrichmentIds = failedEnrichmentIds?.value,
        trailerPreviewUrls = viewModel.getMapValue<String, String>("trailerPreviewUrls") ?: emptyMap(),
        trailerPreviewAudioUrls = viewModel.getMapValue<String, String>("trailerPreviewAudioUrls") ?: emptyMap(),
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingPlayManually = onContinueWatchingPlayManually,
        showContinueWatchingManualPlayOption = showContinueWatchingManualPlayOption,
        onRequestTrailerPreview = requestTrailerPreview,
        onLoadMoreCatalog = loadMoreCatalog,
        onRemoveContinueWatching = removeContinueWatching,
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onNavigateToFolderDetail = onNavigateToFolderDetail,
        onItemFocus = remember(viewModel) {
            { item -> viewModel.callMethod("onItemFocus", item) }
        },
        onPreloadAdjacentItem = preloadAdjacentItem,
        onSaveFocusState = saveModernFocusState,
        onRequestLazyCatalogLoad = remember(viewModel) {
            { catalogKey: String -> viewModel.callMethod("requestLazyCatalogLoad", catalogKey) }
        }
    )
}