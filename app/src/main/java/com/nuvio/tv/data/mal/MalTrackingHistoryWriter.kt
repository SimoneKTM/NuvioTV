package com.nuvio.tv.data.mal

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
class MalTrackingHistoryWriter @Inject constructor(
    private val api: MalApi,
    private val syncRepository: MalSyncRepository,
    private val authRepository: MalAuthRepository,
    private val authStorage: MalAuthStorage,
    private val profileManager: ProfileManager
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.MAL

    override suspend fun addToHistory(
        profileId: Int,
        items: Collection<TrackingHistoryItem>
    ): TrackingMutationResult {
        if (profileId != profileManager.activeProfileId.value) return TrackingMutationResult(0)
        syncRepository.ensureLoaded()
        require(authStorage.state.value.isAuthenticated) { "MyAnimeList authentication is required" }
        val token = authRepository.currentAccessToken()
            ?: throw IllegalArgumentException("MyAnimeList access token is unavailable")
        val snapshot = syncRepository.state.value.snapshot
        var notFoundCount = 0
        items.forEach { item ->
            val mediaId = item.media.resolveMalMediaId(snapshot)
                ?: run { notFoundCount++; return@forEach }
            val entry = snapshot.items.firstOrNull { candidate -> candidate.id == mediaId }
            val isMovie = item.media.kind == TrackingMediaKind.MOVIE || item.media.episode == null
            val targetWatched = if (isMovie) {
                1
            } else {
                maxOf(entry?.progress ?: 0, item.media.episode?.number ?: 1)
            }
            val targetStatus = if (isMovie) {
                MalMediaListStatus.COMPLETED
            } else {
                MalMediaListStatus.WATCHING
            }
            check(
                api.updateMyAnimeListStatus(
                    token = token,
                    animeId = mediaId,
                    status = targetStatus.wireValue,
                    score = entry?.score,
                    numWatchedEpisodes = targetWatched
                )
            ) { "MyAnimeList could not update watched history" }
            syncRepository.commitProgressChange(mediaId, targetWatched, targetStatus)
        }
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = notFoundCount)
    }

    override suspend fun removeFromHistory(
        profileId: Int,
        items: Collection<TrackingMediaReference>
    ): TrackingMutationResult {
        if (profileId != profileManager.activeProfileId.value) return TrackingMutationResult(0)
        syncRepository.ensureLoaded()
        require(authStorage.state.value.isAuthenticated) { "MyAnimeList authentication is required" }
        val token = authRepository.currentAccessToken()
            ?: throw IllegalArgumentException("MyAnimeList access token is unavailable")
        val snapshot = syncRepository.state.value.snapshot
        var notFoundCount = 0
        items.forEach { reference ->
            val mediaId = reference.resolveMalMediaId(snapshot)
                ?: run { notFoundCount++; return@forEach }
            val entry = snapshot.items.firstOrNull { candidate -> candidate.id == mediaId }
            val isMovie = reference.kind == TrackingMediaKind.MOVIE || reference.episode == null
            if (isMovie) {
                check(api.deleteMyAnimeListEntry(token, mediaId)) {
                    "MyAnimeList could not remove watched history"
                }
                syncRepository.commitMembershipChange(mediaId, null)
            } else {
                val current = entry?.progress ?: 0
                val targetWatched = (current - 1).coerceAtLeast(0)
                val targetStatus = entry?.status
                    ?.takeIf { targetWatched > 0 }
                    ?: MalMediaListStatus.PLAN_TO_WATCH
                check(
                    api.updateMyAnimeListStatus(
                        token = token,
                        animeId = mediaId,
                        status = targetStatus.wireValue,
                        score = entry?.score,
                        numWatchedEpisodes = targetWatched
                    )
                ) { "MyAnimeList could not remove watched history" }
                syncRepository.commitProgressChange(mediaId, targetWatched, targetStatus)
            }
        }
        return TrackingMutationResult(attemptedCount = items.size, notFoundCount = notFoundCount)
    }
}