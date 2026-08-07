package com.nuvio.tv.data.mal

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
class MalLibraryProvider @Inject constructor(
    private val syncRepository: MalSyncRepository,
    private val api: MalApi,
    private val authStorage: MalAuthStorage,
    private val authRepository: MalAuthRepository
) : TrackingLibraryProvider {
    override val providerId = TrackingProviderId.MAL
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
            this[statusDefinition(MalMediaListStatus.PLAN_TO_WATCH)!!.key] = true
        }
    }

    override suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot {
        syncRepository.ensureLoaded()
        val entry = syncRepository.state.value.snapshot.items
            .firstOrNull { candidate -> candidate.matchesMalContentId(item.itemId) }
        val selectedKey = entry?.status?.let { status ->
            statusDefinition(status)?.key
        }
        return ListMembershipSnapshot(
            malStatusDefinitions.associate { definition ->
                definition.key to (definition.key == selectedKey)
            }
        )
    }

    override suspend fun applyMembershipChanges(
        item: LibraryEntryInput,
        changes: ListMembershipChanges,
        destructiveRemovalConfirmed: Boolean
    ) {
        require(authStorage.state.value.isAuthenticated) { "MyAnimeList authentication is required" }
        val token = authRepository.currentAccessToken()
            ?: throw IllegalArgumentException("MyAnimeList access token is unavailable")
        syncRepository.ensureLoaded()
        val snapshot = syncRepository.state.value.snapshot
        val currentEntry = snapshot.items.firstOrNull { candidate ->
            candidate.matchesMalContentId(item.itemId)
        }
        val desiredDefinitions = malStatusDefinitions.filter { definition ->
            changes.desiredMembership[definition.key] == true
        }
        require(desiredDefinitions.size <= 1) { "A MyAnimeList item can have only one list status" }
        val desired = desiredDefinitions.singleOrNull()
        require(desired == null || desired.supportedContentTypes.any { supported ->
            supported.equals(item.itemType.normalizedMalContentType(), ignoreCase = true)
        }) { "${desired!!.key} does not support ${item.itemType}" }
        if (desired?.status == currentEntry?.status) return

        val mediaId = parseMalContentId(item.itemId)
            ?: throw IllegalArgumentException(
                "Cannot resolve ${item.itemId} to a MyAnimeList media id"
            )
        if (desired == null) {
            if (
                currentEntry?.destructiveMalRemovalImpacts().orEmpty().isNotEmpty() &&
                !destructiveRemovalConfirmed
            ) {
                throw MalDestructiveRemovalRequiredException()
            }
            check(api.deleteMyAnimeListEntry(token, mediaId)) {
                "MyAnimeList could not remove library entry"
            }
        } else {
            check(
                api.updateMyAnimeListStatus(
                    token = token,
                    animeId = mediaId,
                    status = desired.status.wireValue,
                    score = currentEntry?.score,
                    numWatchedEpisodes = currentEntry?.progress
                )
            ) { "MyAnimeList could not update library entry" }
        }
        syncRepository.commitMembershipChange(mediaId, desired?.status)
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = syncRepository.refresh(intent)

    override suspend fun membershipRemovalConfirmation(
        item: LibraryEntryInput,
        changes: ListMembershipChanges
    ): TrackingMembershipRemovalConfirmation? {
        syncRepository.ensureLoaded()
        val desired = malStatusDefinitions.filter { definition ->
            changes.desiredMembership[definition.key] == true
        }
        if (desired.isNotEmpty()) return null
        val currentEntry = syncRepository.state.value.snapshot.items.firstOrNull { candidate ->
            candidate.matchesMalContentId(item.itemId)
        } ?: return null
        val impacts = currentEntry.destructiveMalRemovalImpacts()
        return impacts.takeIf { values -> values.isNotEmpty() }?.let {
            TrackingMembershipRemovalConfirmation(TrackingProviderId.MAL, it)
        }
    }
}

internal fun MalLibraryItem.destructiveMalRemovalImpacts(): Set<TrackingMembershipRemovalImpact> =
    buildSet {
        if (progress > 0 || status != MalMediaListStatus.PLAN_TO_WATCH) {
            add(TrackingMembershipRemovalImpact.WATCHED_HISTORY)
        }
        if (score != null) add(TrackingMembershipRemovalImpact.RATING)
    }

class MalDestructiveRemovalRequiredException : IllegalStateException(
    "Removing this MyAnimeList status would also clear watched progress or a score"
)

private fun String.normalizedMalContentType(): String = when (trim().lowercase()) {
    "tv", "show", "anime" -> "series"
    else -> trim().lowercase()
}