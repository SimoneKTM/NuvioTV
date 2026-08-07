package com.nuvio.tv.data.anilist

import android.util.Log
import com.nuvio.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.TrackingScrobbler
import com.nuvio.tv.core.tracking.scrobbleDiagnosticSummary
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AniListTrackingScrobbler @Inject constructor(
    private val authRepository: AniListAuthRepository,
    private val syncRepository: AniListSyncRepository,
    private val api: AniListApi
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.ANILIST

    override suspend fun scrobble(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ) {
        val authenticated = authRepository.state.value.isAuthenticated
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "anilist adapter received action=${action.wireValue} authenticated=$authenticated " +
                event.scrobbleDiagnosticSummary()
        )
        if (!authenticated) {
            Log.d(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "anilist adapter skipped action=${action.wireValue} reason=not_authenticated"
            )
            return
        }
        val mediaId = event.media.resolveMediaId()
        if (mediaId == null) {
            Log.d(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "anilist adapter skipped action=${action.wireValue} reason=unresolved_media"
            )
            return
        }
        syncRepository.ensureLoaded()
        val snapshot = syncRepository.state.value.snapshot
        val entry = snapshot.items.firstOrNull { item -> item.id == mediaId }
        when (action) {
            TrackingScrobbleAction.START -> scrobbleStart(mediaId, event, entry)
            TrackingScrobbleAction.PAUSE -> Unit
            TrackingScrobbleAction.STOP -> scrobbleStop(mediaId, event, entry)
        }
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "anilist adapter complete action=${action.wireValue} ${event.scrobbleDiagnosticSummary()}"
        )
    }

    private suspend fun scrobbleStart(
        mediaId: Long,
        event: TrackingScrobbleEvent,
        entry: AniListLibraryItem?
    ) {
        val episode = event.media.episode?.number ?: return
        val status = AniListMediaListStatus.CURRENT
        val target = maxOf(entry?.progress ?: 0, episode - 1)
        check(
            api.saveMediaListEntry(
                mediaId = mediaId,
                status = status,
                progress = target,
                score = entry?.score
            )
        ) { "AniList could not update watched progress" }
        syncRepository.commitProgressChange(mediaId, target, status)
    }

    private suspend fun scrobbleStop(
        mediaId: Long,
        event: TrackingScrobbleEvent,
        entry: AniListLibraryItem?
    ) {
        if (event.progressPercent < 90.0) return
        val isMovie = event.media.kind == TrackingMediaKind.MOVIE
        val status: AniListMediaListStatus
        val target: Int
        if (isMovie) {
            status = AniListMediaListStatus.COMPLETED
            target = 1
        } else {
            val episode = event.media.episode?.number ?: return
            target = maxOf(entry?.progress ?: 0, episode)
            val totalEpisodes = entry?.totalEpisodes
            status = if (totalEpisodes != null && target >= totalEpisodes) {
                AniListMediaListStatus.COMPLETED
            } else {
                AniListMediaListStatus.CURRENT
            }
        }
        check(
            api.saveMediaListEntry(
                mediaId = mediaId,
                status = status,
                progress = target,
                score = entry?.score
            )
        ) { "AniList could not update watched progress" }
        syncRepository.commitProgressChange(mediaId, target, status)
    }

    private fun TrackingMediaReference.resolveMediaId(): Long? {
        ids.anilist?.let { return it }
        catalog?.contentId?.let { contentId ->
            parseAniListContentId(contentId)?.let { return it }
        }
        catalog?.videoId?.let { videoId ->
            parseAniListContentId(videoId)?.let { return it }
        }
        return null
    }
}
