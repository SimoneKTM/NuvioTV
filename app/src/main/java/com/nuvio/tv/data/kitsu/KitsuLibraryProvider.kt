package com.nuvio.tv.data.kitsu

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
class KitsuLibraryProvider @Inject constructor(
    private val syncRepository: KitsuSyncRepository,
    private val api: KitsuApi,
    private val authStorage: KitsuAuthStorage
) : TrackingLibraryProvider {
    override val providerId = TrackingProviderId.KITSU
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
            this[statusDefinition(KitsuMediaListStatus.PLANNED)!!.key] = true
        }
    }

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        syncRepository.ensureLoaded()
        val entry = syncRepository.state.value.snapshot.items
            .firstOrNull { candidate -> candidate.matchesKitsuContentId(item.itemId) }
        val selectedKey = entry?.status?.let { status ->
            statusDefinition(status)?.key
        }
        return ListMembershipSnapshot(
            kitsuStatusDefinitions.associate { definition ->
                definition.key to (definition.key == selectedKey)
            }
        )
    }

    override suspend fun applyMembershipChanges(
        item: LibraryEntryInput,
        changes: ListMembershipChanges,
        destructiveRemovalConfirmed: Boolean
    ) {
        require(authStorage.state.value.isAuthenticated) { "Kitsu authentication is required" }
        val userId = authStorage.state.value.userId
            ?: throw IllegalArgumentException("Kitsu user id is unavailable")
        syncRepository.ensureLoaded()
        val snapshot = syncRepository.state.value.snapshot
        val currentEntry = snapshot.items.firstOrNull { candidate ->
            candidate.matchesKitsuContentId(item.itemId)
        }
        val desiredDefinitions = kitsuStatusDefinitions.filter { definition ->
            changes.desiredMembership[definition.key] == true
        }
        require(desiredDefinitions.size <= 1) { "A Kitsu item can have only one list status" }
        val desired = desiredDefinitions.singleOrNull()
        require(desired == null || desired.supportedContentTypes.any { supported ->
            supported.equals(item.itemType.normalizedKitsuContentType(), ignoreCase = true)
        }) { "${desired!!.key} does not support ${item.itemType}" }
        if (desired?.status == currentEntry?.status) return

        val mediaId = parseKitsuContentId(item.itemId)
            ?: throw IllegalArgumentException(
                "Cannot resolve ${item.itemId} to a Kitsu media id"
            )
        if (desired == null) {
            if (
                currentEntry?.destructiveKitsuRemovalImpacts().orEmpty().isNotEmpty() &&
                !destructiveRemovalConfirmed
            ) {
                throw KitsuDestructiveRemovalRequiredException()
            }
            val entryId = currentEntry?.entryId
            if (entryId != null) {
                check(api.deleteLibraryEntry(entryId)) {
                    "Kitsu could not remove library entry"
                }
            }
        } else {
            val success = currentEntry?.entryId?.let { entryId ->
                api.updateLibraryEntry(
                    token = authStorage.accessToken().orEmpty(),
                    entryId = entryId,
                    status = desired.status.wireValue,
                    progress = currentEntry.progress,
                    rating = currentEntry.rating
                )
            } ?: api.saveLibraryEntry(
                token = authStorage.accessToken().orEmpty(),
                kitsuMediaId = mediaId,
                userId = userId,
                status = desired.status.wireValue,
                progress = 0,
                rating = null
            )
            check(success) { "Kitsu could not update library entry" }
        }
        syncRepository.commitMembershipChange(mediaId, desired?.status)
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = syncRepository.refresh(intent)

    override suspend fun membershipRemovalConfirmation(
        item: LibraryEntryInput,
        changes: ListMembershipChanges
    ): TrackingMembershipRemovalConfirmation? {
        syncRepository.ensureLoaded()
        val desired = kitsuStatusDefinitions.filter { definition ->
            changes.desiredMembership[definition.key] == true
        }
        if (desired.isNotEmpty()) return null
        val currentEntry = syncRepository.state.value.snapshot.items.firstOrNull { candidate ->
            candidate.matchesKitsuContentId(item.itemId)
        } ?: return null
        val impacts = currentEntry.destructiveKitsuRemovalImpacts()
        return impacts.takeIf { values -> values.isNotEmpty() }?.let {
            TrackingMembershipRemovalConfirmation(TrackingProviderId.KITSU, it)
        }
    }
}

internal fun KitsuLibraryItem.destructiveKitsuRemovalImpacts(): Set<TrackingMembershipRemovalImpact> =
    buildSet {
        if (progress > 0 || status != KitsuMediaListStatus.PLANNED) {
            add(TrackingMembershipRemovalImpact.WATCHED_HISTORY)
        }
        if (rating != null) add(TrackingMembershipRemovalImpact.RATING)
    }

class KitsuDestructiveRemovalRequiredException : IllegalStateException(
    "Removing this Kitsu status would also clear watched progress or a score"
)

private fun String.normalizedKitsuContentType(): String = when (trim().lowercase()) {
    "tv", "show", "anime" -> "series"
    else -> trim().lowercase()
}