package com.nuvio.tv.data.kitsu

import com.nuvio.tv.core.tracking.TrackingProgressProvider
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.WatchProgress
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@Singleton
class KitsuTrackingProgressProvider @Inject constructor(
    private val syncRepository: KitsuSyncRepository,
    private val api: KitsuApi,
    private val authStorage: KitsuAuthStorage,
    private val layoutPreferences: LayoutPreferenceDataStore
) : TrackingProgressProvider {
    override val providerId = TrackingProviderId.KITSU
    override val isAuthenticated = authStorage.state.map { state -> state.isAuthenticated }
        .distinctUntilChanged()
    override val allProgress = syncRepository.projection.map { projection -> projection.progress }
        .onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()
    override val remoteProgressLoaded = syncRepository.state.map { state ->
        state.hasLoaded && state.errorMessage == null
    }.distinctUntilChanged()
    override val nextUpSeeds = combine(
        syncRepository.projection,
        layoutPreferences.nextUpFromFurthestEpisode
    ) { projection, preferFurthestEpisode ->
        projection.nextUp(preferFurthestEpisode)
    }.distinctUntilChanged()
    override val watchedMovieIds = syncRepository.projection.map { projection ->
        projection.watchedMovieIds
    }.distinctUntilChanged()

    override fun episodeProgress(contentId: String): Flow<Map<Pair<Int, Int>, WatchProgress>> =
        syncRepository.projection.map { projection ->
            projection.episodeProgress(contentId)
        }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
            .distinctUntilChanged()

    override fun airedEpisodeOrder(contentId: String): Flow<List<Pair<Int, Int>>> = flowOf(emptyList())

    override fun isWatched(
        contentId: String,
        videoId: String?,
        season: Int?,
        episode: Int?
    ): Flow<Boolean> = syncRepository.projection.map { projection ->
        projection.isWatched(contentId, season, episode)
    }.onStart { syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC) }
        .distinctUntilChanged()

    override suspend fun watchedShowEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        syncRepository.refresh(TrackingRefreshIntent.AUTOMATIC)
        return syncRepository.projection.value.watchedShowEpisodes
    }

    override suspend fun showIdSiblings(): Map<String, Set<String>> {
        syncRepository.ensureLoaded()
        return emptyMap()
    }

    override suspend fun refresh(intent: TrackingRefreshIntent) = syncRepository.refresh(intent)

    override suspend fun removeProgress(contentId: String, season: Int?, episode: Int?) {
        syncRepository.ensureLoaded()
        val mediaId = parseKitsuContentId(contentId) ?: return
        val entry = syncRepository.state.value.snapshot.items
            .firstOrNull { candidate -> candidate.id == mediaId } ?: return
        if (entry.progress <= 0) return
        require(authStorage.state.value.isAuthenticated) { "Kitsu authentication is required" }
        val targetProgress = (entry.progress - 1).coerceAtLeast(0)
        if (entry.isKitsuMovieEntry() && targetProgress <= 0) {
            val entryId = entry.entryId
            if (entryId != null) {
                check(api.deleteLibraryEntry(entryId)) {
                    "Kitsu could not remove watched progress"
                }
                syncRepository.commitMembershipChange(mediaId, null)
            }
        } else {
            val token = authStorage.accessToken().orEmpty()
            val success = entry.entryId?.let { entryId ->
                api.updateLibraryEntry(
                    token = token,
                    entryId = entryId,
                    status = entry.status.wireValue,
                    progress = targetProgress,
                    rating = entry.rating
                )
            } ?: run {
                val userId = authStorage.state.value.userId
                    ?: throw IllegalArgumentException("Kitsu user id is unavailable")
                api.saveLibraryEntry(
                    token = token,
                    kitsuMediaId = mediaId,
                    userId = userId,
                    status = entry.status.wireValue,
                    progress = targetProgress,
                    rating = entry.rating
                )
            }
            check(success) { "Kitsu could not remove watched progress" }
            syncRepository.commitProgressChange(mediaId, targetProgress)
        }
    }

    override fun applyOptimisticProgress(progress: WatchProgress, quiet: Boolean) = Unit

    override fun applyOptimisticRemoval(contentId: String, season: Int?, episode: Int?) = Unit

    override fun clearOptimistic() = Unit

    override fun isHiddenFromProgress(contentId: String): Boolean = false

    override suspend fun prepareNextUpSeed(progress: WatchProgress): WatchProgress = progress
}