package com.nuvio.tv.data.anilist

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
class AniListTrackingHistoryWriter @Inject constructor(
    private val api: AniListApi,
    private val syncRepository: AniListSyncRepository,
    private val authRepository: AniListAuthRepository,
    private val authStorage: AniListAuthStorage,
    private val profileManager: ProfileManager
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.ANILIST

    override suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>
    ): TrackingMutationResult {
        if (profileId != profileManager.activeProfileId.value) return TrackingMutationResult(0)
        syncRepository.ensureLoaded()
        require(authStorage.state.value.isAuthenticated) { "AniList authentication is required" }
        val snapshot = syncRepository.state.value.snapshot
        var notFoundCount = 0
        items.forEach { item ->
            val mediaId = item.media.resolveAniListMediaId(snapshot)
                ?: run { notFoundCount++; return@forEach }
            val entry = snapshot.items.firstOrNull { candidate -> candidate.id == mediaId }
            val isMovie = item.media.kind == TrackingMediaKind.MOVIE || item.media.episode == null
            val targetProgress = if (isMovie) {
                1
            } else {
                maxOf(entry?.progress ?: 0, item.media.episode?.number ?: 1)
            }
            val targetStatus = if (isMovie) {
                AniListMediaListStatus.COMPLETED
            } else {
                AniListMediaListStatus.CURRENT
            }
            check(
                api.saveMediaListEntry(
                    mediaId = mediaId,
                    status = targetStatus,
                    progress = targetProgress,
                    score = entry?.score
                )
            ) { "AniList could not update watched history" }
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
        require(authStorage.state.value.isAuthenticated) { "AniList authentication is required" }
        val snapshot = syncRepository.state.value.snapshot
        var notFoundCount = 0
        items.forEach { reference ->
            val mediaId = reference.resolveAniListMediaId(snapshot)
                ?: run { notFoundCount++; return@forEach }
            val entry = snapshot.items.firstOrNull { candidate -> candidate.id == mediaId }
            val isMovie = reference.kind == TrackingMediaKind.MOVIE || reference.episode == null
            if (isMovie) {
                val entryId = entry?.entryId
                if (entryId != null) {
                    check(api.deleteMediaListEntry(entryId)) {
                        "AniList could not remove watched history"
                    }
                }
                syncRepository.commitMembershipChange(mediaId, null)
            } else {
                val current = entry?.progress ?: 0
                val targetProgress = (current - 1).coerceAtLeast(0)
                val targetStatus = entry?.status
                    ?.takeIf { targetProgress > 0 }
                    ?: AniListMediaListStatus.PLANNING
                check(
                    api.saveMediaListEntry(
                        mediaId = mediaId,
                        status = targetStatus,
                        progress = targetProgress,
                        score = entry?.score
                    )
                ) { "AniList could not remove watched history" }
                syncRepository.commitProgressChange(mediaId, targetProgress, targetStatus)
            }
        }
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = notFoundCount)
    }
}