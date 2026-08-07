package com.nuvio.tv.data.mal

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
class MalTrackingScrobbler @Inject constructor(
    private val authRepository: MalAuthRepository,
    private val syncRepository: MalSyncRepository,
    private val api: MalApi
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.MAL

    override suspend fun scrobble(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ) {
        val authenticated = authRepository.state.value.isAuthenticated
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "mal adapter received action=${action.wireValue} authenticated=$authenticated " +
                event.scrobbleDiagnosticSummary()
        )
        if (!authenticated) {
            Log.d(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "mal adapter skipped action=${action.wireValue} reason=not_authenticated"
            )
            return
        }
        val mediaId = event.media.resolveMediaId()
        if (mediaId == null) {
            Log.d(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "mal adapter skipped action=${action.wireValue} reason=unresolved_media"
            )
            return
        }
        syncRepository.ensureLoaded()
        val token = authRepository.currentAccessToken()
            ?: run {
                Log.d(
                    TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                    "mal adapter skipped action=${action.wireValue} reason=missing_token"
                )
                return
            }
        val snapshot = syncRepository.state.value.snapshot
        val entry = snapshot.items.firstOrNull { item -> item.id == mediaId }
        when (action) {
            TrackingScrobbleAction.START -> scrobbleStart(
                token = token,
                mediaId = mediaId,
                event = event,
                entry = entry
            )
            TrackingScrobbleAction.PAUSE -> Unit
            TrackingScrobbleAction.STOP -> scrobbleStop(
                token = token,
                mediaId = mediaId,
                event = event,
                entry = entry
            )
        }
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "mal adapter complete action=${action.wireValue} ${event.scrobbleDiagnosticSummary()}"
        )
    }

    private suspend fun scrobbleStart(
        token: String,
        mediaId: Long,
        event: TrackingScrobbleEvent,
        entry: MalLibraryItem?
    ) {
        val episode = event.media.episode?.number ?: return
        val status = MalMediaListStatus.WATCHING
        val target = maxOf(entry?.progress ?: 0, episode - 1)
        check(
            api.updateMyAnimeListStatus(
                token = token,
                animeId = mediaId,
                status = status.wireValue,
                score = entry?.score,
                numWatchedEpisodes = target
            )
        ) { "MyAnimeList could not update watched progress" }
        syncRepository.commitProgressChange(mediaId, target, status)
    }

    private suspend fun scrobbleStop(
        token: String,
        mediaId: Long,
        event: TrackingScrobbleEvent,
        entry: MalLibraryItem?
    ) {
        if (event.progressPercent < 90.0) return
        val isMovie = event.media.kind == TrackingMediaKind.MOVIE
        val status: MalMediaListStatus
        val target: Int
        if (isMovie) {
            status = MalMediaListStatus.COMPLETED
            target = 1
        } else {
            val episode = event.media.episode?.number ?: return
            target = maxOf(entry?.progress ?: 0, episode)
            val totalEpisodes = entry?.totalEpisodes
            status = if (totalEpisodes != null && target >= totalEpisodes) {
                MalMediaListStatus.COMPLETED
            } else {
                MalMediaListStatus.WATCHING
            }
        }
        check(
            api.updateMyAnimeListStatus(
                token = token,
                animeId = mediaId,
                status = status.wireValue,
                score = entry?.score,
                numWatchedEpisodes = target
            )
        ) { "MyAnimeList could not update watched progress" }
        syncRepository.commitProgressChange(mediaId, target, status)
    }

    private fun TrackingMediaReference.resolveMediaId(): Long? {
        ids.mal?.let { return it }
        catalog?.contentId?.let { contentId ->
            parseMalContentId(contentId)?.let { return it }
        }
        catalog?.videoId?.let { videoId ->
            parseMalContentId(videoId)?.let { return it }
        }
        return null
    }
}