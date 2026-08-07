package com.nuvio.tv.data.kitsu

import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.PosterShape
import kotlinx.serialization.Serializable

const val KITSU_STATUS_SELECTION_GROUP = "kitsu:status"

@Serializable
data class KitsuSyncSnapshot(
    val items: List<KitsuLibraryItem> = emptyList(),
    val lastCheckedAtEpochMs: Long? = null
)

data class KitsuSyncState(
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
    val snapshot: KitsuSyncSnapshot = KitsuSyncSnapshot()
)

data class KitsuStatusDefinition(
    val status: KitsuMediaListStatus,
    val key: String,
    val titleRes: Int,
    val type: LibraryListTab.Type,
    val isMembershipDestination: Boolean = true,
    val supportedContentTypes: Set<String> = setOf("anime", "series")
)

internal fun statusDefinition(status: KitsuMediaListStatus): KitsuStatusDefinition? =
    kitsuStatusDefinitions.firstOrNull { definition -> definition.status == status }

internal fun statusDefinition(key: String): KitsuStatusDefinition? =
    kitsuStatusDefinitions.firstOrNull { definition -> definition.key == key }

val kitsuStatusDefinitions = listOf(
    KitsuStatusDefinition(
        status = KitsuMediaListStatus.CURRENT,
        key = "kitsu:status:current",
        titleRes = R.string.kitsu_status_current,
        type = LibraryListTab.Type.STATUS
    ),
    KitsuStatusDefinition(
        status = KitsuMediaListStatus.PLANNED,
        key = "kitsu:status:planned",
        titleRes = R.string.kitsu_status_planned,
        type = LibraryListTab.Type.WATCHLIST
    ),
    KitsuStatusDefinition(
        status = KitsuMediaListStatus.COMPLETED,
        key = "kitsu:status:completed",
        titleRes = R.string.kitsu_status_completed,
        type = LibraryListTab.Type.STATUS
    ),
    KitsuStatusDefinition(
        status = KitsuMediaListStatus.ON_HOLD,
        key = "kitsu:status:on_hold",
        titleRes = R.string.kitsu_status_on_hold,
        type = LibraryListTab.Type.STATUS
    ),
    KitsuStatusDefinition(
        status = KitsuMediaListStatus.DROPPED,
        key = "kitsu:status:dropped",
        titleRes = R.string.kitsu_status_dropped,
        type = LibraryListTab.Type.STATUS
    )
)

data class KitsuLibraryProjection(
    val items: List<LibraryEntry> = emptyList(),
    val itemsByStatus: Map<String, List<LibraryEntry>> = emptyMap(),
    val tabs: List<LibraryListTab> = emptyList()
) {
    companion object {
        val Empty = KitsuLibraryProjection()
    }
}

fun KitsuSyncSnapshot.toKitsuLibraryProjection(
    tabTitle: (Int) -> String
): KitsuLibraryProjection {
    val itemsByStatus = kitsuStatusDefinitions.associate { definition ->
        definition.key to items.filter { item -> item.status == definition.status }
            .mapNotNull { item -> item.toLibraryEntry(definition.key) }
            .distinctBy(LibraryEntry::id)
            .sortedByDescending(LibraryEntry::listedAt)
    }
    val tabs = kitsuStatusDefinitions.map { definition ->
        LibraryListTab(
            key = definition.key,
            title = tabTitle(definition.titleRes),
            type = definition.type,
            trackingProviderId = TrackingProviderId.KITSU.storageId,
            selectionGroup = KITSU_STATUS_SELECTION_GROUP,
            supportedContentTypes = setOf("anime", "series"),
            isMembershipDestination = definition.isMembershipDestination
        )
    }
    return KitsuLibraryProjection(
        items = itemsByStatus.values.flatten()
            .distinctBy(LibraryEntry::id)
            .sortedByDescending(LibraryEntry::listedAt),
        itemsByStatus = itemsByStatus,
        tabs = tabs
    )
}

private fun KitsuLibraryItem.toLibraryEntry(listKey: String): LibraryEntry = LibraryEntry(
    id = buildKitsuContentId(id),
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
    listedAt = updatedAt,
    trackingProviderId = TrackingProviderId.KITSU.storageId,
    trackingProviderItemId = id.toString(),
    trackingSourceUrl = "https://kitsu.app/anime/$id"
)

fun buildKitsuContentId(mediaId: Long): String = "kitsu:$mediaId"

/** Extracts the Kitsu media id from a content id such as "kitsu:123" or "123". */
fun parseKitsuContentId(contentId: String?): Long? {
    val trimmed = contentId?.trim() ?: return null
    val parsed = runCatching { parseTrackingExternalIds(trimmed).kitsu }.getOrNull()
    return parsed ?: trimmed.toLongOrNull()
}

fun KitsuLibraryItem.matchesKitsuContentId(contentId: String?): Boolean =
    parseKitsuContentId(contentId) == id