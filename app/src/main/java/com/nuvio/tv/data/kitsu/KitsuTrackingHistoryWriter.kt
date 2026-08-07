package com.nuvio.tv.data.kitsu

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingProviderId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KitsuTrackingHistoryWriter @Inject constructor(
    private val api: KitsuApi,
    private val syncRepository: KitsuSyncRepository,
    private val authRepository: KitsuAuthRepository,
    private val authStorage: KitsuAuthStorage,
    private val profileManager: ProfileManager
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.KITSU

    override suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>
    ): TrackingMutationResult {
        if (profileId != profileManager.activeProfileId.value) return TrackingMutationResult(0)
        syncRepository.ensureLoaded()
        require(authStorage.state.value.isAuthenticated) { "Kitsu authentication is required" }
        val snapshot = syncRepository.state.value.snapshot
        var notFoundCount = 0
        items.forEach { item ->
            val mediaId = item.media.resolveKitsuMediaId(snapshot)
                ?: run { notFoundCount++; return@forEach }
            val entry = snapshot.items.firstOrNull { candidate -> candidate.id == mediaId }
            val isMovie = item.media.kind == TrackingMediaKind.MOVIE || item.media.episode == null
            val targetProgress = if (isMovie) {
                1
            } else {
                maxOf(entry?.progress ?: 0, item.media.episode?.number ?: 1)
            }
            val targetStatus = if (isMovie) {
                KitsuMediaListStatus.COMPLETED
            } else {
                KitsuMediaListStatus.CURRENT
            }
            check(updateOrCreateEntry(mediaId, entry, targetStatus, targetProgress)) {
                "Kitsu could not update watched history"
            }
            syncRepository.commitProgressChange(mediaId, targetProgress, targetStatus)
        }
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = notFoundCount)
    }

    override suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>
    ): TrackingMutationResult {
        if (profileId != profileManager.activeProfileId.value) return TrackingMutationResult(0)
        syncRepository.ensureLoaded()
        require(authStorage.state.value.isAuthenticated) { "Kitsu authentication is required" }
        val snapshot = syncRepository.state.value.snapshot
        var notFoundCount = 0
        items.forEach { reference ->
            val mediaId = reference.resolveKitsuMediaId(snapshot)
                ?: run { notFoundCount++; return@forEach }
            val entry = snapshot.items.firstOrNull { candidate -> candidate.id == mediaId }
            val isMovie = reference.kind == TrackingMediaKind.MOVIE || reference.episode == null
            if (isMovie) {
                val entryId = entry?.entryId
                if (entryId != null) {
                    check(api.deleteLibraryEntry(entryId)) {
                        "Kitsu could not remove watched history"
                    }
                }
                syncRepository.commitMembershipChange(mediaId, null)
            } else {
                val current = entry?.progress ?: 0
                val targetProgress = (current - 1).coerceAtLeast(0)
                val targetStatus = entry?.status
                    ?.takeIf { targetProgress > 0 }
                    ?: KitsuMediaListStatus.PLANNED
                check(updateOrCreateEntry(mediaId, entry, targetStatus, targetProgress)) {
                    "Kitsu could not remove watched history"
                }
                syncRepository.commitProgressChange(mediaId, targetProgress, targetStatus)
            }
        }
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = notFoundCount)
    }

    private suspend fun updateOrCreateEntry(
        mediaId: Long,
        entry: KitsuLibraryItem?,
        status: KitsuMediaListStatus,
        progress: Int
    ): Boolean {
        val token = authStorage.accessToken().orEmpty()
        val existingEntryId = entry?.entryId
        return if (existingEntryId != null) {
            api.updateLibraryEntry(
                token = token,
                entryId = existingEntryId,
                status = status.wireValue,
                progress = progress,
                rating = entry?.rating
            )
        } else {
            val userId = authStorage.state.value.userId
                ?: throw IllegalArgumentException("Kitsu user id is unavailable")
            api.saveLibraryEntry(
                token = token,
                kitsuMediaId = mediaId,
                userId = userId,
                status = status.wireValue,
                progress = progress,
                rating = null
            )
        }
    }
}