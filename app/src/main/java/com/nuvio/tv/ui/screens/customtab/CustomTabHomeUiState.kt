package com.nuvio.tv.ui.screens.customtab

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContinueWatchingCardStyle
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.screens.home.HomeUiState
import com.nuvio.tv.ui.util.StableList

@Immutable
data class CustomTabHomeUiState(
    val tabId: String? = null,
    val displayName: String = "",
    val rows: List<CatalogRow> = emptyList(),
    val continueWatchingItems: List<ContinueWatchingItem> = emptyList(),
    val upcomingItems: List<ContinueWatchingItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val installedAddonsCount: Int = 0,
    val heroEnabled: Boolean = true,
    val heroItem: MetaPreview? = null,
    val heroItems: List<MetaPreview> = emptyList(),
    val heroAddonBaseUrl: String? = null,
    val homeLayout: HomeLayout = HomeLayout.MODERN,
    val catalogTypeSuffixEnabled: Boolean = true,
    val hideUnreleasedContent: Boolean = false,
    val modernLandscapePostersEnabled: Boolean = false,
    val modernHeroFullScreenBackdropEnabled: Boolean = false,
    val classicFocusGradientEnabled: Boolean = false,
    val continueWatchingCardStyle: ContinueWatchingCardStyle = ContinueWatchingCardStyle.CARD,
    val useEpisodeThumbnailsInCw: Boolean = true,
    val blurContinueWatchingNextUp: Boolean = false,
    val posterCardWidthDp: Int = 126,
    val posterCardHeightDp: Int = 189,
    val posterCardCornerRadiusDp: Int = 12,
    val posterLabelsEnabled: Boolean = true,
    val catalogAddonNameEnabled: Boolean = false,
    val focusedPosterBackdropExpandEnabled: Boolean = false,
    val focusedPosterBackdropExpandDelaySeconds: Int = 3,
    val focusedPosterBackdropTrailerEnabled: Boolean = false,
    val focusedPosterBackdropTrailerMuted: Boolean = true
) {
    fun toHomeUiState(): HomeUiState = HomeUiState(
        catalogRows = rows,
        continueWatchingItems = continueWatchingItems,
        upcomingItems = upcomingItems,
        isLoading = isLoading,
        error = error,
        installedAddonsCount = installedAddonsCount,
        homeLayout = homeLayout,
        modernLandscapePostersEnabled = modernLandscapePostersEnabled,
        modernHeroFullScreenBackdropEnabled = modernHeroFullScreenBackdropEnabled,
        heroItems = heroItems,
        heroCatalogKeys = emptyList(),
        heroSectionEnabled = heroEnabled,
        classicFocusGradientEnabled = classicFocusGradientEnabled,
        focusedPosterBackdropExpandEnabled = focusedPosterBackdropExpandEnabled,
        focusedPosterBackdropExpandDelaySeconds = focusedPosterBackdropExpandDelaySeconds,
        focusedPosterBackdropTrailerEnabled = focusedPosterBackdropTrailerEnabled,
        focusedPosterBackdropTrailerMuted = focusedPosterBackdropTrailerMuted,
        posterLabelsEnabled = posterLabelsEnabled,
        catalogAddonNameEnabled = catalogAddonNameEnabled,
        catalogTypeSuffixEnabled = catalogTypeSuffixEnabled,
        posterCardWidthDp = posterCardWidthDp,
        posterCardHeightDp = posterCardHeightDp,
        posterCardCornerRadiusDp = posterCardCornerRadiusDp,
        continueWatchingCardStyle = continueWatchingCardStyle,
        useEpisodeThumbnailsInCw = useEpisodeThumbnailsInCw,
        blurUnwatchedEpisodes = blurContinueWatchingNextUp,
        hideUnreleasedContent = hideUnreleasedContent
    )
}