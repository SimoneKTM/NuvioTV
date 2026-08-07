package com.nuvio.tv.data.anilist

import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.selectPreferredTrackingNextUpSeeds
import com.nuvio.tv.domain.model.WatchProgress

const val ANILIST_PROGRESS_SOURCE = "anilist_progress"

internal data class AniListProgressProjection(
    val progress: List<WatchProgress> = emptyList(),
    val watchedMovieIds: Set<String> = emptySet(),
    val watchedShowEpisodes: Map<String, Set<Pair<Int, Int>>> = emptyMap(),
    val watchedCounts: Map<Long, Int> = emptyMap(),
    val recentNextUp: List<WatchProgress> = emptyList(),
    val furthestNextUp: List<WatchProgress> = emptyList()
)

internal fun AniListSyncSnapshot.toAniListProgressProjection(): AniListProgressProjection {
    val progress = mutableListOf<WatchProgress>()
    val watchedMovieIds = linkedSetOf<String>()
    val watchedShowEpisodes = linkedMapOf<String, MutableSet<Pair<Int, Int>>>()
    val watchedCounts = linkedMapOf<Long, Int>()
    items.forEach { item ->
        if (item.progress <= 0) return@forEach
        watchedCounts[item.id] = item.progress
        val contentId = buildAniListContentId(item.id)
        if (item.isAniListMovieEntry()) {
            watchedMovieIds += contentId
            progress += item.toAniListWatchedProgress(contentId, season = null, episode = null)
        } else {
            val episodes = watchedShowEpisodes.getOrPut(contentId) { linkedSetOf() }
            (1..item.progress).forEach { episode ->
                episodes += 1 to episode
                progress += item.toAniListWatchedProgress(contentId, season = 1, episode = episode)
            }
        }
    }
    val nextUpCandidates = items.asSequence()
        .filter { item -> item.progress > 0 }
        .filter { item -> item.status != AniListMediaListStatus.COMPLETED }
        .filterNot { item -> item.isAniListMovieEntry() }
        .map { item -> item.toAniListNextUpSeed(buildAniListContentId(item.id)) }
        .toList()
    return AniListProgressProjection(
        progress = progress.sortedByDescending(WatchProgress::lastWatched),
        watchedMovieIds = watchedMovieIds,
        watchedShowEpisodes = watchedShowEpisodes.mapValues { (_, episodes) -> episodes.toSet() },
        watchedCounts = watchedCounts,
        recentNextUp = selectPreferredTrackingNextUpSeeds(nextUpCandidates, preferFurthestEpisode = false),
        furthestNextUp = selectPreferredTrackingNextUpSeeds(nextUpCandidates, preferFurthestEpisode = true)
    )
}

fun AniListLibraryItem.isAniListMovieEntry(): Boolean {
    if (format?.equals("MOVIE", ignoreCase = true) == true) return true
    return (totalEpisodes == null || totalEpisodes <= 1) && status == AniListMediaListStatus.COMPLETED
}

fun AniListLibraryProjection.episodeProgress(contentId: String): Map<Pair<Int, Int>, WatchProgress> {
    val mediaId = parseAniListContentId(contentId) ?: return emptyMap()
    return progress.asSequence()
        .filter { item -> item.season != null && item.episode != null }
        .filter { item -> parseAniListContentId(item.contentId) == mediaId }
        .associateBy { item -> requireNotNull(item.season) to requireNotNull(item.episode) }
}

fun AniListLibraryProjection.isWatched(contentId: String, season: Int?, episode: Int?): Boolean {
    val mediaId = parseAniListContentId(contentId) ?: return false
    val watched = watchedCounts[mediaId] ?: return false
    val normalizedSeason = if (season == null || season == 0) 1 else season
    val normalizedEpisode = if (episode == null) 1 else episode
    if (normalizedSeason != 1) return false
    return normalizedEpisode in 1..watched
}

internal fun TrackingMediaReference.resolveAniListMediaId(snapshot: AniListSyncSnapshot): Long? {
    ids.anilist?.let { return it }
    catalog?.contentId?.let { contentId ->
        parseAniListContentId(contentId)?.let { return it }
    }
    catalog?.videoId?.let { videoId ->
        parseAniListContentId(videoId)?.let { return it }
    }
    val contentId = catalog?.contentId
    if (!contentId.isNullOrBlank()) {
        snapshot.items.firstOrNull { item -> item.matchesAniListContentId(contentId) }?.let { return it.id }
    }
    return null
}

private fun AniListLibraryItem.toAniListWatchedProgress(
    contentId: String,
    season: Int?,
    episode: Int?
): WatchProgress = WatchProgress(
    contentId = contentId,
    contentType = if (season == null) "movie" else "series",
    name = title,
    poster = posterUrl,
    backdrop = bannerUrl,
    logo = null,
    videoId = if (season != null && episode != null) "$contentId:1:$episode" else contentId,
    season = season,
    episode = episode,
    episodeTitle = null,
    position = 1L,
    duration = 1L,
    lastWatched = updatedAt.coerceAtLeast(0L) * 1_000L,
    progressPercent = 100f,
    source = ANILIST_PROGRESS_SOURCE,
    trackingProviderId = TrackingProviderId.ANILIST.storageId,
    trackingProviderItemId = id.toString(),
    trackingSourceUrl = "https://anilist.co/anime/$id"
)

private fun AniListLibraryItem.toAniListNextUpSeed(contentId: String): WatchProgress = WatchProgress(
    contentId = contentId,
    contentType = "series",
    name = title,
    poster = posterUrl,
    backdrop = bannerUrl,
    logo = null,
    videoId = "$contentId:1:${progress + 1}",
    season = 1,
    episode = progress + 1,
    episodeTitle = null,
    position = 1L,
    duration = 1L,
    lastWatched = updatedAt.coerceAtLeast(0L) * 1_000L,
    progressPercent = 100f,
    source = ANILIST_PROGRESS_SOURCE,
    trackingProviderId = TrackingProviderId.ANILIST.storageId,
    trackingProviderItemId = id.toString(),
    trackingSourceUrl = "https://anilist.co/anime/$id"
)
