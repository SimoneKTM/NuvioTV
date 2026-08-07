package com.nuvio.tv.data.kitsu

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
class KitsuTrackingScrobbler @Inject constructor(
    private val authRepository: KitsuAuthRepository,
    private val authStorage: KitsuAuthStorage,
    private val syncRepository: KitsuSyncRepository,
    private val api: KitsuApi
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.KITSU

    override suspend fun scrobble(
        action: TrackingScrobbleAction,
        event: TrackingScrobbleEvent
    ) {
        val authenticated = authRepository.state.value.isAuthenticated
        Log.d(
            TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
            "kitsu adapter received action=${action.wireValue} authenticated=$authenticated " +
                event.scrobbleDiagnosticSummary()
        )
        if (!authenticated) {
            Log.d(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "kitsu adapter skipped action=${action.wireValue} reason=not_authenticated"
            )
            return
        }
        val mediaId = event.media.resolveMediaId()
        if (mediaId == null) {
            Log.d(
                TRACKING_SCROBBLE_DIAGNOSTIC_TAG,
                "kitsu adapter skipped action=${action.wireValue} reason=unresolved_media"
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
            "kitsu adapter complete action=${action.wireValue} ${event.scrobbleDiagnosticSummary()}"
        )
    }

    private suspend fun scrobbleStart(
        mediaId: Long,
        event: TrackingScrobbleEvent,
        entry: KitsuLibraryItem?
    ) {
        val episode = event.media.episode?.number ?: return
        val status = KitsuMediaListStatus.CURRENT
        val target = maxOf(entry?.progress ?: 0, episode - 1)
        check(updateOrCreateEntry(mediaId, entry, status, target)) {
            "Kitsu could not update watched progress"
        }
        syncRepository.commitProgressChange(mediaId, target, status)
    }

    private suspend fun scrobbleStop(
        mediaId: Long,
        event: TrackingScrobbleEvent,
        entry: KitsuLibraryItem?
    ) {
        if (event.progressPercent < 90.0) return
        val isMovie = event.media.kind == TrackingMediaKind.MOVIE
        val status: KitsuMediaListStatus
        val target: Int
        if (isMovie) {
            status = KitsuMediaListStatus.COMPLETED
            target = 1
        } else {
            val episode = event.media.episode?.number ?: return
            target = maxOf(entry?.progress ?: 0, episode)
            val totalEpisodes = entry?.totalEpisodes
            status = if (totalEpisodes != null && target >= totalEpisodes) {
                KitsuMediaListStatus.COMPLETED
            } else {
                KitsuMediaListStatus.CURRENT
            }
        }
        check(updateOrCreateEntry(mediaId, entry, status, target)) {
            "Kitsu could not update watched progress"
        }
        syncRepository.commitProgressChange(mediaId, target, status)
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

    private fun TrackingMediaReference.resolveMediaId(): Long? {
        ids.kitsu?.let { return it }
        catalog?.contentId?.let { contentId ->
            parseKitsuContentId(contentId)?.let { return it }
        }
        catalog?.videoId?.let { videoId ->
            parseKitsuContentId(videoId)?.let { return it }
        }
        return null
    }
}
