package com.nuvio.tv.ui.screens.customtab

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.CustomTab
import com.nuvio.tv.domain.model.HomeLayout
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.ui.screens.home.ContinueWatchingItem
import com.nuvio.tv.ui.util.StableList
import com.nuvio.tv.ui.util.StableMap

@Immutable
data class CustomTabHomeUiState(
    val customTab: CustomTab,
    val isLoading: Boolean = true,
    val error: String? = null,
    val installedAddonsCount: Int = 0,
    val rows: StableList<CatalogRow> = StableList(emptyList()),
    val homeLayout: HomeLayout = HomeLayout.MODERN,
    val heroItems: StableList<MetaPreview> = StableList(emptyList()),
    val customCatalogTitles: StableMap<String, String> = StableMap(emptyMap()),
    val continueWatchingItems: StableList<ContinueWatchingItem> = StableList(emptyList()),
    val continueWatchingTitle: String = "",
    val continueWatchingAirsDateTemplate: String = "",
    val continueWatchingUpcomingLabel: String = "",
    val showUnairedNextUp: Boolean = true,
    val nextUpFromFurthestEpisode: Boolean = true,
    val blurContinueWatchingNextUp: Boolean = false,
    val continueWatchingSortMode: com.nuvio.tv.domain.model.ContinueWatchingSortMode = com.nuvio.tv.domain.model.ContinueWatchingSortMode.DEFAULT,
    val posterCardWidthDp: Int = 126,
    val posterCardHeightDp: Int = 189,
    val posterCardCornerRadiusDp: Int = 12,
    val modernLandscapePostersEnabled: Boolean = false,
    val modernHeroFullScreenBackdropEnabled: Boolean = false,
    val classicFocusGradientEnabled: Boolean = false,
    val continueWatchingCardStyle: com.nuvio.tv.domain.model.ContinueWatchingCardStyle = com.nuvio.tv.domain.model.ContinueWatchingCardStyle.CARD,
    val useEpisodeThumbnailsInCw: Boolean = true,
    val blurUnwatchedEpisodes: Boolean = false,
    val focusedPosterBackdropExpandEnabled: Boolean = false,
    val focusedPosterBackdropExpandDelaySeconds: Int = 3,
    val focusedPosterBackdropTrailerEnabled: Boolean = false,
    val focusedPosterBackdropTrailerMuted: Boolean = true,
    val focusedPosterBackdropTrailerPlaybackTarget: com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget = com.nuvio.tv.domain.model.FocusedPosterTrailerPlaybackTarget.HERO_MEDIA
)