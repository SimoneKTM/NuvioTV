package com.nuvio.tv.data.mal

import com.nuvio.tv.R
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.parseTrackingExternalIds
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibraryListTab
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.serialization.Serializable

const val MAL_STATUS_SELECTION_GROUP = "mal:status"

@Serializable
data class MalSyncSnapshot(
    val items: List<MalLibraryItem> = emptyList(),
    val lastCheckedAtEpochMs: Long? = null
)

data class MalSyncState(
    val isLoading: Boolean = false,
    val hasLoaded: Boolean = false,
    val errorMessage: String? = null,
    val snapshot: MalSyncSnapshot = MalSyncSnapshot()
)

data class MalStatusDefinition(
    val status: MalMediaListStatus,
    val key: String,
    val titleRes: Int,
    val type: LibraryListTab.Type,
    val isMembershipDestination: Boolean = true,
    val supportedContentTypes: Set<String> = setOf("anime", "series")
)

internal fun statusDefinition(status: MalMediaListStatus): MalStatusDefinition? =
    malStatusDefinitions.firstOrNull { definition -> definition.status == status }

internal fun statusDefinition(key: String): MalStatusDefinition? =
    malStatusDefinitions.firstOrNull { definition -> definition.key == key }

val malStatusDefinitions = listOf(
    MalStatusDefinition(
        status = MalMediaListStatus.WATCHING,
        key = "mal:status:watching",
        titleRes = R.string.mal_status_watching,
        type = LibraryListTab.Type.STATUS
    ),
    MalStatusDefinition(
        status = MalMediaListStatus.COMPLETED,
        key = "mal:status:completed",
        titleRes = R.string.mal_status_completed,
        type = LibraryListTab.Type.STATUS
    ),
    MalStatusDefinition(
        status = MalMediaListStatus.ON_HOLD,
        key = "mal:status:on_hold",
        titleRes = R.string.mal_status_on_hold,
        type = LibraryListTab.Type.STATUS
    ),
    MalStatusDefinition(
        status = MalMediaListStatus.PLAN_TO_WATCH,
        key = "mal:status:plan_to_watch",
        titleRes = R.string.mal_status_plan_to_watch,
        type = LibraryListTab.Type.WATCHLIST
    ),
    MalStatusDefinition(
        status = MalMediaListStatus.DROPPED,
        key = "mal:status:dropped",
        titleRes = R.string.mal_status_dropped,
        type = LibraryListTab.Type.STATUS
    )
)

data class MalLibraryProjection(
    val items: List<LibraryEntry> = emptyList(),
    val itemsByStatus: Map<String, List<LibraryEntry>> = emptyMap(),
    val tabs: List<LibraryListTab> = emptyList(),
    val progress: List<WatchProgress> = emptyList(),
    val watchedMovieIds: Set<String> = emptySet(),
    val watchedShowEpisodes: Map<String, Set<Pair<Int, Int>>> = emptyMap(),
    val watchedCounts: Map<Long, Int> = emptyMap(),
    private val recentNextUp: List<WatchProgress> = emptyList(),
    private val furthestNextUp: List<WatchProgress> = emptyList()
) {
    fun nextUp(preferFurthestEpisode: Boolean): List<WatchProgress> =
        if (preferFurthestEpisode) furthestNextUp else recentNextUp

    companion object {
        val Empty = MalLibraryProjection()
    }
}

fun MalSyncSnapshot.toMalLibraryProjection(
    tabTitle: (Int) -> String
): MalLibraryProjection {
    val itemsByStatus = malStatusDefinitions.associate { definition ->
        definition.key to items.filter { item -> item.status == definition.status }
            .mapNotNull { item -> item.toLibraryEntry(definition.key) }
            .distinctBy(LibraryEntry::id)
            .sortedByDescending(LibraryEntry::listedAt)
    }
    val tabs = malStatusDefinitions.map { definition ->
        LibraryListTab(
            key = definition.key,
            title = tabTitle(definition.titleRes),
            type = definition.type,
            trackingProviderId = TrackingProviderId.MAL.storageId,
            selectionGroup = MAL_STATUS_SELECTION_GROUP,
            supportedContentTypes = setOf("anime", "series"),
            isMembershipDestination = definition.isMembershipDestination
        )
    }
    val progressProjection = toMalProgressProjection()
    return MalLibraryProjection(
        items = itemsByStatus.values.flatten()
            .distinctBy(LibraryEntry::id)
            .sortedByDescending(LibraryEntry::listedAt),
        itemsByStatus = itemsByStatus,
        tabs = tabs,
        progress = progressProjection.progress,
        watchedMovieIds = progressProjection.watchedMovieIds,
        watchedShowEpisodes = progressProjection.watchedShowEpisodes,
        watchedCounts = progressProjection.watchedCounts,
        recentNextUp = progressProjection.recentNextUp,
        furthestNextUp = progressProjection.furthestNextUp
    )
}

private fun MalLibraryItem.toLibraryEntry(listKey: String): LibraryEntry = LibraryEntry(
    id = buildMalContentId(id),
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
    trackingProviderId = TrackingProviderId.MAL.storageId,
    trackingProviderItemId = id.toString(),
    trackingSourceUrl = "https://myanimelist.net/anime/$id"
)

fun buildMalContentId(mediaId: Long): String = "mal:$mediaId"

/** Extracts the MyAnimeList media id from a content id such as "mal:123" or "123". */
fun parseMalContentId(contentId: String?): Long? {
    val trimmed = contentId?.trim() ?: return null
    val parsed = runCatching { parseTrackingExternalIds(trimmed).mal }.getOrNull()
    return parsed ?: trimmed.toLongOrNull()
}

fun MalLibraryItem.matchesMalContentId(contentId: String?): Boolean =
    parseMalContentId(contentId) == id