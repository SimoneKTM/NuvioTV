package com.nuvio.tv.data.anilist

import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.serialization.Serializable

const val ANILIST_STATUS_SELECTION_GROUP = "anilist:status"

@Serializable
data class AniListSyncSnapshot(
    val items: List<AniListLibraryItem> = emptyList(),
    val lastCheckedAtEpochMs: Long? = null
)

data class AniListSyncState(
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
    val snapshot: AniListSyncSnapshot = AniListSyncSnapshot()
)

data class AniListStatusDefinition(
    val status: AniListMediaListStatus,
    val key: String,
    val titleRes: Int,
    val type: LibraryListTab.Type,
    val isMembershipDestination: Boolean = true,
    val supportedContentTypes: Set<String> = setOf("anime", "series")
)

internal fun statusDefinition(status: AniListMediaListStatus): AniListStatusDefinition? =
    anilistStatusDefinitions.firstOrNull { definition -> definition.status == status }

internal fun statusDefinition(key: String): AniListStatusDefinition? =
    anilistStatusDefinitions.firstOrNull { definition -> definition.key == key }

val anilistStatusDefinitions = listOf(
    AniListStatusDefinition(
        status = AniListMediaListStatus.CURRENT,
        key = "anilist:status:current",
        titleRes = R.string.anilist_status_watching,
        type = LibraryListTab.Type.STATUS
    ),
    AniListStatusDefinition(
        status = AniListMediaListStatus.PLANNING,
        key = "anilist:status:planning",
        titleRes = R.string.anilist_status_planning,
        type = LibraryListTab.Type.WATCHLIST
    ),
    AniListStatusDefinition(
        status = AniListMediaListStatus.COMPLETED,
        key = "anilist:status:completed",
        titleRes = R.string.anilist_status_completed,
        type = LibraryListTab.Type.STATUS
    ),
    AniListStatusDefinition(
        status = AniListMediaListStatus.PAUSED,
        key = "anilist:status:paused",
        titleRes = R.string.anilist_status_paused,
        type = LibraryListTab.Type.STATUS
    ),
    AniListStatusDefinition(
        status = AniListMediaListStatus.DROPPED,
        key = "anilist:status:dropped",
        titleRes = R.string.anilist_status_dropped,
        type = LibraryListTab.Type.STATUS
    ),
    AniListStatusDefinition(
        status = AniListMediaListStatus.REPEATING,
        key = "anilist:status:repeating",
        titleRes = R.string.anilist_status_repeating,
        type = LibraryListTab.Type.STATUS
    )
)

data class AniListLibraryProjection(
    val items: List<LibraryEntry> = emptyList(),
    val itemsByStatus: Map<String, List<LibraryEntry>> = emptyMap(),
    val tabs: List<LibraryListTab> = emptyList()
) {
    companion object {
        val Empty = AniListLibraryProjection()
    }
}

fun AniListSyncSnapshot.toAniListLibraryProjection(
    tabTitle: (Int) -> String
): AniListLibraryProjection {
    val itemsByStatus = anilistStatusDefinitions.associate { definition ->
        definition.key to items.filter { item -> item.status == definition.status }
            .mapNotNull { item -> item.toLibraryEntry(definition.key) }
            .distinctBy(LibraryEntry::id)
            .sortedByDescending(LibraryEntry::listedAt)
    }
    val tabs = anilistStatusDefinitions.map { definition ->
        LibraryListTab(
            key = definition.key,
            title = tabTitle(definition.titleRes),
            type = definition.type,
            trackingProviderId = TrackingProviderId.ANILIST.storageId,
            selectionGroup = ANILIST_STATUS_SELECTION_GROUP,
            supportedContentTypes = setOf("anime", "series"),
            isMembershipDestination = definition.isMembershipDestination
        )
    }
    return AniListLibraryProjection(
        items = itemsByStatus.values.flatten()
            .distinctBy(LibraryEntry::id)
            .sortedByDescending(LibraryEntry::listedAt),
        itemsByStatus = itemsByStatus,
        tabs = tabs
    )
}

private fun AniListLibraryItem.toLibraryEntry(listKey: String): LibraryEntry = LibraryEntry(
    id = buildAniListContentId(id),
    type = "series",
    name = title,
    poster = posterUrl,
    posterShape = PosterShape.POSTER,
    background = bannerUrl,
    logo = null,
    description = null,
    releaseInfo = null,
    imdbRating = null,
    genres = emptyList(),
    addonBaseUrl = null,
    listKeys = setOf(listKey),
    listedAt = updatedAt.coerceAtLeast(0L) * 1_000L,
    trackingProviderId = TrackingProviderId.ANILIST.storageId,
    trackingProviderItemId = id.toString(),
    trackingSourceUrl = "https://anilist.co/anime/$id"
)

fun buildAniListContentId(mediaId: Long): String = "anilist:$mediaId"

/** Extracts the AniList media id from a content id such as "anilist:123" or "123". */
fun parseAniListContentId(contentId: String?): Long? {
    val trimmed = contentId?.trim() ?: return null
    val parsed = runCatching { parseTrackingExternalIds(trimmed).anilist }.getOrNull()
    return parsed ?: trimmed.toLongOrNull()
}

fun AniListLibraryItem.matchesAniListContentId(contentId: String?): Boolean =
    parseAniListContentId(contentId) == id