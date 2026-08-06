package com.nuvio.tv.data.anilist

import com.nuvio.tv.core.tracking.TrackingLibraryProvider
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalConfirmation
import com.nuvio.tv.core.tracking.TrackingMembershipRemovalImpact
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.domain.model.LibraryEntryInput
import com.nuvio.tv.domain.model.ListMembershipChanges
import com.nuvio.tv.domain.model.ListMembershipSnapshot
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@Singleton
class AniListLibraryProvider @Inject constructor(
    private val syncRepository: AniListSyncRepository,
    private val api: AniListApi,
    private val authStorage: AniListAuthStorage
) : TrackingLibraryProvider {
    override val providerId = TrackingProviderId.ANILIST
    override val isAuthenticated = authStorage.state.map { state -> state.isAuthenticated }
        .distinctUntilChanged()
    override val isRefreshing = syncRepository.state.map { state -> state.isLoading }
        .distinctUntilChanged()
    override val items = syncRepository.projection.map { projection -> projection.items }
        .onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()
    override val tabs = isAuthenticated.map { authenticated ->
        if (authenticated) {
            syncRepository.projection.value.tabs
        } else {
            emptyList()
        }
    }.distinctUntilChanged()

    override fun recognizesListKey(key: String): Boolean = statusDefinition(key) != null

    override fun observeMembership(itemId: String, itemType: String): Flow<Set<String>> =
        syncRepository.projection.map { projection ->
            projection.itemsByStatus.entries
                .firstOrNull { (_, items) -> items.any { entry -> entry.id == itemId } }
                ?.key
                ?.let(::setOf)
                .orEmpty()
        }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
            .distinctUntilChanged()

    override fun toggledDefaultMembership(
        currentMembership: Map<String, Boolean>
    ): Map<String, Boolean> = currentMembership.mapValues { false }.toMutableMap().apply {
        if (currentMembership.values.none { selected -> selected }) {
            this[statusDefinition(AniListMediaListStatus.PLANNING)!!.key] = true
        }
    }

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        syncRepository.ensureLoaded()
        val entry = syncRepository.state.value.snapshot.items
            .firstOrNull { candidate -> candidate.matchesAniListContentId(item.itemId) }
        val selectedKey = entry?.status?.let { status ->
            statusDefinition(status)?.key
        }
        return ListMembershipSnapshot(
            anilistStatusDefinitions.associate { definition ->
                definition.key to (definition.key == selectedKey)
            }
        )
    }

    override suspend fun applyMembershipChanges(
        item: LibraryEntryInput,
        changes: ListMembershipChanges,
        destructiveRemovalConfirmed: Boolean
    ) {
        require(authStorage.state.value.isAuthenticated) { "AniList authentication is required" }
        syncRepository.ensureLoaded()
        val snapshot = syncRepository.state.value.snapshot
        val currentEntry = snapshot.items.firstOrNull { candidate ->
            candidate.matchesAniListContentId(item.itemId)
        }
        val desiredDefinitions = anilistStatusDefinitions.filter { definition ->
            changes.desiredMembership[definition.key] == true
        }
        require(desiredDefinitions.size <= 1) { "An AniList item can have only one list status" }
        val desired = desiredDefinitions.singleOrNull()
        require(desired == null || desired.supportedContentTypes.any { supported ->
            supported.equals(item.itemType.normalizedAniListContentType(), ignoreCase = true)
        }) { "${desired!!.key} does not support ${item.itemType}" }
        if (desired?.status == currentEntry?.status) return

        val mediaId = parseAniListContentId(item.itemId)
            ?: throw IllegalArgumentException(
                "Cannot resolve ${item.itemId} to an AniList media id"
            )
        if (desired == null) {
            if (
                currentEntry?.destructiveAniListRemovalImpacts().orEmpty().isNotEmpty() &&
                !destructiveRemovalConfirmed
            ) {
                throw AniListDestructiveRemovalRequiredException()
            }
            val entryId = currentEntry?.entryId
            if (entryId != null) {
                check(api.deleteMediaListEntry(entryId)) {
                    "AniList could not remove library entry"
                }
            }
        } else {
            check(
                api.saveMediaListEntry(
                    mediaId = mediaId,
                    status = desired.status,
                    progress = currentEntry?.progress ?: 0,
                    score = currentEntry?.score
                )
            ) { "AniList could not update library entry" }
        }
        syncRepository.commitMembershipChange(mediaId, desired?.status)
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = syncRepository.refresh(intent)

    override suspend fun membershipRemovalConfirmation(
        item: LibraryEntryInput,
        changes: ListMembershipChanges
    ): TrackingMembershipRemovalConfirmation? {
        syncRepository.ensureLoaded()
        val desired = anilistStatusDefinitions.filter { definition ->
            changes.desiredMembership[definition.key] == true
        }
        if (desired.isNotEmpty()) return null
        val currentEntry = syncRepository.state.value.snapshot.items.firstOrNull { candidate ->
            candidate.matchesAniListContentId(item.itemId)
        } ?: return null
        val impacts = currentEntry.destructiveAniListRemovalImpacts()
        return impacts.takeIf { values -> values.isNotEmpty() }?.let {
            TrackingMembershipRemovalConfirmation(TrackingProviderId.ANILIST, it)
        }
    }
}

internal fun AniListLibraryItem.destructiveAniListRemovalImpacts(): Set<TrackingMembershipRemovalImpact> =
    buildSet {
        if (progress > 0 || status != AniListMediaListStatus.PLANNING) {
            add(TrackingMembershipRemovalImpact.WATCHED_HISTORY)
        }
        if (score != null) add(TrackingMembershipRemovalImpact.RATING)
    }

class AniListDestructiveRemovalRequiredException : IllegalStateException(
    "Removing this AniList status would also clear watched progress or a score"
)

private fun String.normalizedAniListContentType(): String = when (trim().lowercase()) {
    "tv", "show", "anime" -> "series"
    else -> trim().lowercase()
}