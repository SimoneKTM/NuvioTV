package com.nuvio.tv.data.mal

import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.selectPreferredTrackingNextUpSeeds
import com.nuvio.tv.domain.model.WatchProgress

const val MAL_PROGRESS_SOURCE = "mal_progress"

internal data class MalProgressProjection(
    val progress: List<WatchProgress> = emptyList(),
    val watchedMovieIds: Set<String> = emptySet(),
    val watchedShowEpisodes: Map<String, Set<Pair<Int, Int>>> = emptyMap(),
    val watchedCounts: Map<Long, Int> = emptyMap(),
    val recentNextUp: List<WatchProgress> = emptyList(),
    val furthestNextUp: List<WatchProgress> = emptyList()
)

internal fun MalSyncSnapshot.toMalProgressProjection(): MalProgressProjection {
    val progress = mutableListOf<WatchProgress>()
    val watchedMovieIds = linkedSetOf<String>()
    val watchedShowEpisodes = linkedMapOf<String, MutableSet<Pair<Int, Int>>>()
    val watchedCounts = linkedMapOf<Long, Int>()
    items.forEach { item ->
        if (item.progress <= 0) return@forEach
        watchedCounts[item.id] = item.progress
        val contentId = buildMalContentId(item.id)
        if (item.isMalMovieEntry()) {
            watchedMovieIds += contentId
            progress += item.toMalWatchedProgress(contentId, season = null, episode = null)
        } else {
            val episodes = watchedShowEpisodes.getOrPut(contentId) { linkedSetOf() }
            (1..item.progress).forEach { episode ->
                episodes += 1 to episode
                progress += item.toMalWatchedProgress(contentId, season = 1, episode = episode)
            }
        }
    }
    val nextUpCandidates = items.asSequence()
        .filter { item -> item.progress > 0 }
        .filter { item -> item.status != MalMediaListStatus.COMPLETED }
        .filterNot { item -> item.isMalMovieEntry() }
        .map { item -> item.toMalNextUpSeed(buildMalContentId(item.id)) }
        .toList()
    return MalProgressProjection(
        progress = progress.sortedByDescending(WatchProgress::lastWatched),
        watchedMovieIds = watchedMovieIds,
        watchedShowEpisodes = watchedShowEpisodes.mapValues { (_, episodes) -> episodes.toSet() },
        watchedCounts = watchedCounts,
        recentNextUp = selectPreferredTrackingNextUpSeeds(nextUpCandidates, preferFurthestEpisode = false),
        furthestNextUp = selectPreferredTrackingNextUpSeeds(nextUpCandidates, preferFurthestEpisode = true)
    )
}

fun MalLibraryItem.isMalMovieEntry(): Boolean {
    if (mediaType.equals("movie", ignoreCase = true)) return true
    return (totalEpisodes == null || totalEpisodes <= 1) && status == MalMediaListStatus.COMPLETED
}

fun MalLibraryProjection.episodeProgress(contentId: String): Map<Pair<Int, Int>, WatchProgress> {
    val mediaId = parseMalContentId(contentId) ?: return emptyMap()
    return progress.asSequence()
        .filter { item -> item.season != null && item.episode != null }
        .filter { item -> parseMalContentId(item.contentId) == mediaId }
        .associateBy { item -> requireNotNull(item.season) to requireNotNull(item.episode) }
}

fun MalLibraryProjection.isWatched(contentId: String, season: Int?, episode: Int?): Boolean {
    val mediaId = parseMalContentId(contentId) ?: return false
    val watched = watchedCounts[mediaId] ?: return false
    val normalizedSeason = if (season == null || season == 0) 1 else season
    val normalizedEpisode = if (episode == null) 1 else episode
    if (normalizedSeason != 1) return false
    return normalizedEpisode in 1..watched
}

internal fun TrackingMediaReference.resolveMalMediaId(snapshot: MalSyncSnapshot): Long? {
    ids.mal?.let { return it }
    catalog?.contentId?.let { contentId ->
        parseMalContentId(contentId)?.let { return it }
    }
    catalog?.videoId?.let { videoId ->
        parseMalContentId(videoId)?.let { return it }
    }
    val contentId = catalog?.contentId
    if (!contentId.isNullOrBlank()) {
        snapshot.items.firstOrNull { item -> item.matchesMalContentId(contentId) }?.let { return it.id }
    }
    return null
}

private fun MalLibraryItem.toMalWatchedProgress(
    contentId: String,
    season: Int?,
    episode: Int?
): WatchProgress = WatchProgress(
    contentId = contentId,
    contentType = if (season == null) "movie" else "series",
    name = title,
    poster = posterUrl,
    backdrop = null,
    logo = null,
    videoId = if (season != null && episode != null) "$contentId:1:$episode" else contentId,
    season = season,
    episode = episode,
    episodeTitle = null,
    position = 1L,
    duration = 1L,
    lastWatched = updatedAt,
    progressPercent = 100f,
    source = MAL_PROGRESS_SOURCE,
    trackingProviderId = TrackingProviderId.MAL.storageId,
    trackingProviderItemId = id.toString(),
    trackingSourceUrl = "https://myanimelist.net/anime/$id"
)

private fun MalLibraryItem.toMalNextUpSeed(contentId: String): WatchProgress = WatchProgress(
    contentId = contentId,
    contentType = "series",
    name = title,
    poster = posterUrl,
    backdrop = null,
    logo = null,
    videoId = "$contentId:1:${progress + 1}",
    season = 1,
    episode = progress + 1,
    episodeTitle = null,
    position = 1L,
    duration = 1L,
    lastWatched = updatedAt,
    progressPercent = 100f,
    source = MAL_PROGRESS_SOURCE,
    trackingProviderId = TrackingProviderId.MAL.storageId,
    trackingProviderItemId = id.toString(),
    trackingSourceUrl = "https://myanimelist.net/anime/$id"
)