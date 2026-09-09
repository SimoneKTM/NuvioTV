package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName

@Immutable
data class CustomTab(
    @SerializedName("id") val id: String,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("enabled") val enabled: Boolean = true,
    @SerializedName("icon") val icon: IconType = IconType.DEFAULT,
    @SerializedName("config") val config: CustomTabConfig = CustomTabConfig()
) {
    val routeName: String
        get() = "custom_tab_${id}"
    
    val route: String
        get() = routeName
}

enum class IconType(val resourceName: String) {
    DEFAULT("sidebar_custom_tab"),
    MOVIE("sidebar_movie"),
    SERIES("sidebar_series"),
    ANIME("sidebar_anime"),
    LIVE_TV("sidebar_live_tv"),
    SEARCH("sidebar_search"),
    LIBRARY("sidebar_library"),
    SETTINGS("sidebar_settings"),
    COLLECTION("sidebar_collection"),
    FAVORITE("sidebar_favorite"),
    DOWNLOAD("sidebar_download"),
    HISTORY("sidebar_history"),
    CALENDAR("sidebar_calendar"),
    STATISTICS("sidebar_statistics"),
    PROFILE("sidebar_profile"),
    CLOUD("sidebar_cloud"),
    GENRE("sidebar_genre"),
    YEAR("sidebar_year"),
    NETWORK("sidebar_network"),
    PERSON("sidebar_person")
}

@Immutable
data class CustomTabConfig(
    @SerializedName("selectedAddons") val selectedAddons: List<String> = emptyList(),
    @SerializedName("homeLayout") val homeLayout: HomeLayout = HomeLayout.MODERN,
    @SerializedName("showHeroSection") val showHeroSection: Boolean = true,
    @SerializedName("showContinueWatching") val showContinueWatching: Boolean = true,
    @SerializedName("catalogOrder") val catalogOrder: List<String> = emptyList(),
    @SerializedName("disabledCatalogs") val disabledCatalogs: List<String> = emptyList(),
    @SerializedName("customCatalogTitles") val customCatalogTitles: Map<String, String> = emptyMap(),
    @SerializedName("posterCardWidthDp") val posterCardWidthDp: Int = 126,
    @SerializedName("posterCardHeightDp") val posterCardHeightDp: Int = 189,
    @SerializedName("posterCardCornerRadiusDp") val posterCardCornerRadiusDp: Int = 12,
    @SerializedName("modernLandscapePosters") val modernLandscapePosters: Boolean = false,
    @SerializedName("heroFullScreenBackdrop") val heroFullScreenBackdrop: Boolean = false,
    @SerializedName("catalogTypeSuffix") val catalogTypeSuffix: Boolean = true,
    @SerializedName("posterLabels") val posterLabels: Boolean = true,
    @SerializedName("catalogAddonName") val catalogAddonName: Boolean = false,
    @SerializedName("classicFocusGradient") val classicFocusGradient: Boolean = false,
    @SerializedName("focusedPosterBackdropExpand") val focusedPosterBackdropExpand: Boolean = true,
    @SerializedName("focusedPosterBackdropExpandDelay") val focusedPosterBackdropExpandDelay: Int = 3,
    @SerializedName("focusedPosterBackdropTrailer") val focusedPosterBackdropTrailer: Boolean = false,
    @SerializedName("focusedPosterBackdropTrailerMuted") val focusedPosterBackdropTrailerMuted: Boolean = true,
    @SerializedName("blurUnwatchedEpisodes") val blurUnwatchedEpisodes: Boolean = false,
    @SerializedName("useEpisodeThumbnailsInCw") val useEpisodeThumbnailsInCw: Boolean = true,
    @SerializedName("continueWatchingCardStyle") val continueWatchingCardStyle: ContinueWatchingCardStyle = ContinueWatchingCardStyle.CARD,
    @SerializedName("showUnairedNextUp") val showUnairedNextUp: Boolean = true,
    @SerializedName("nextUpFromFurthestEpisode") val nextUpFromFurthestEpisode: Boolean = true,
    @SerializedName("blurContinueWatchingNextUp") val blurContinueWatchingNextUp: Boolean = false,
    @SerializedName("continueWatchingSortMode") val continueWatchingSortMode: ContinueWatchingSortMode = ContinueWatchingSortMode.DEFAULT,
    @SerializedName("detailPageTrailerButton") val detailPageTrailerButton: Boolean = true,
    @SerializedName("preferExternalMetaAddonDetail") val preferExternalMetaAddonDetail: Boolean = true,
    @SerializedName("hideUnreleasedContent") val hideUnreleasedContent: Boolean = false,
    @SerializedName("showFullReleaseDate") val showFullReleaseDate: Boolean = true,
    @SerializedName("memoryOnlyVerticalScroll") val memoryOnlyVerticalScroll: Boolean = true,
    @SerializedName("smoothBringIntoView") val smoothBringIntoView: Boolean = true,
    @SerializedName("fastHorizontalNavigation") val fastHorizontalNavigation: Boolean = false,
    @SerializedName("followAddonsOrder") val followAddonsOrder: Boolean = false
)

const val MAX_CUSTOM_TABS = 3