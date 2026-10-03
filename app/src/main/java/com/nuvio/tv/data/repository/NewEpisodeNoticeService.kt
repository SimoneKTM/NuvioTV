package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktCalendarMediaItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.domain.model.LibraryEntry
import com.nuvio.tv.domain.model.LibrarySourceMode
import com.nuvio.tv.domain.model.NewEpisodeNotice
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Detects shows saved in the user's library that got a new aired episode.
 *
 * Air dates come from the Trakt calendar (public endpoint, 7 day look-back),
 * the candidate set comes from the local/remote library and "already watched"
 * is resolved against the Trakt watched-episodes snapshot with a local
 * watch-progress fallback.
 *
 * Display numbers are remapped to the addon catalog's season/episode
 * numbering (via [TraktEpisodeMappingService]) so the banner shows exactly
 * what the detail screen shows. The watched check above always uses the
 * Trakt numbers, which is what the watched snapshot is keyed on.
 */
@Singleton
class NewEpisodeNoticeService @Inject constructor(
    private val libraryRepository: LibraryRepository,
    private val libraryPreferences: LibraryPreferences,
    private val watchProgressRepository: WatchProgressRepository,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktApi: TraktApi,
    private val episodeMappingService: TraktEpisodeMappingService
) {

    companion object {
        private const val TAG = "NewEpisodeNotice"
        /** Look-back window: episodes aired in the last [WINDOW_DAYS] days. */
        private const val WINDOW_DAYS = 7
        private const val MAX_NOTICES = 6
        private const val LIBRARY_TIMEOUT_MS = 10_000L
        private const val PROGRESS_TIMEOUT_MS = 6_000L
        private const val MAPPING_TIMEOUT_MS = 10_000L

        private val SERIES_TYPES = setOf("series", "tv", "show", "anime")
    }

    suspend fun computeNotices(): List<NewEpisodeNotice> = withContext(Dispatchers.IO) {
        val libraryShows = loadLibraryShows().filter { entry ->
            entry.type.trim().lowercase(Locale.ROOT) in SERIES_TYPES
        }
        if (libraryShows.isEmpty()) return@withContext emptyList()

        val calendar = fetchRecentEpisodes() ?: return@withContext emptyList()
        if (calendar.isEmpty()) return@withContext emptyList()

        val libraryIndex = buildLibraryIndex(libraryShows)
        if (libraryIndex.isEmpty()) return@withContext emptyList()

        val siblings = runCatching { watchProgressRepository.getShowIdSiblings() }
            .getOrDefault(emptyMap())
        val watchedEpisodes = resolveWatchedEpisodes()
        val progressIndex = resolveProgressIndex()

        val now = System.currentTimeMillis()
        val pending = mutableListOf<NoticeWorkItem>()
        val seenContentIds = mutableSetOf<String>()

        for (item in calendar) {
            val show = item.show ?: continue
            val episode = item.episode ?: continue
            val season = episode.season ?: continue
            val number = episode.number ?: continue
            if (season <= 0 || number <= 0) continue

            val showKeys = showIdKeys(show.ids)
            val entry = showKeys.firstNotNullOfOrNull { libraryIndex[it] } ?: continue
            if (!seenContentIds.add(entry.id)) continue

            val airMillis = resolveAirMillis(item.firstAired, item.released) ?: continue
            if (airMillis > now) continue

            val entryKeys = entryMatchKeys(entry)
            if (isEpisodeWatched(
                    entryKeys = entryKeys,
                    siblings = siblings,
                    watchedEpisodes = watchedEpisodes,
                    progressIndex = progressIndex,
                    season = season,
                    episode = number,
                    airMillis = airMillis
                )
            ) {
                continue
            }

            val notice = NewEpisodeNotice(
                contentId = entry.id,
                title = entry.name,
                season = season,
                episode = number,
                episodeTitle = episode.title?.takeIf { it.isNotBlank() },
                poster = entry.poster,
                logo = entry.logo,
                airedLabel = formatAiredLabel(airMillis),
                airedAtMs = airMillis
            )
            val idCandidates = linkedSetOf<String>()
            showIdKeys(show.ids).forEach { idCandidates.add(it) }
            entryKeys.forEach { idCandidates.add(it) }
            pending += NoticeWorkItem(notice, idCandidates.toList(), entry.addonBaseUrl)
            if (pending.size >= MAX_NOTICES) break
        }

        pending.sortByDescending { it.notice.airedAtMs }
        remapNoticesToAddonNumbering(pending)
    }

    /** One notice plus the ids and library source used to remap its numbering. */
    private data class NoticeWorkItem(
        val notice: NewEpisodeNotice,
        val idCandidates: List<String>,
        val sourceAddonBaseUrl: String?
    )

    /**
     * Rewrites [NewEpisodeNotice.season]/[NewEpisodeNotice.episode] from the Trakt
     * calendar numbers to the numbering used by the addon catalog meta, in parallel.
     * Several id candidates are tried (calendar ids first, then library entry ids)
     * because a bare `tmdb_tv_xxx` entry id cannot be resolved against the Trakt
     * seasons endpoint. The library card's own addon source is passed along so the
     * remap targets the same addon the detail screen opens with.
     * Any failure (no Trakt auth, addon without episode list,
     * timeout) leaves the original Trakt numbers untouched.
     */
    private suspend fun remapNoticesToAddonNumbering(
        pending: List<NoticeWorkItem>
    ): List<NewEpisodeNotice> {
        if (pending.isEmpty()) return emptyList()
        return coroutineScope {
            pending.map { item ->
                async {
                    val notice = item.notice
                    var mapped: EpisodeMappingEntry? = null
                    for (contentId in item.idCandidates) {
                        mapped = try {
                            withTimeoutOrNull(MAPPING_TIMEOUT_MS) {
                                episodeMappingService.resolveAddonEpisodeMapping(
                                    contentId = contentId,
                                    contentType = "series",
                                    season = notice.season,
                                    episode = notice.episode,
                                    episodeTitle = notice.episodeTitle,
                                    preferredSourceBaseUrl = item.sourceAddonBaseUrl
                                )
                            }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "remap failed for $contentId: ${e.message}")
                            null
                        }
                        if (mapped != null) break
                    }
                    if (mapped != null && mapped.season > 0 && mapped.episode > 0) {
                        Log.d(
                            TAG,
                            "remap ${notice.contentId} s${notice.season}e${notice.episode}" +
                                " -> s${mapped.season}e${mapped.episode}"
                        )
                        notice.copy(season = mapped.season, episode = mapped.episode)
                    } else {
                        Log.d(TAG, "remap ${notice.contentId} keep s${notice.season}e${notice.episode} ids=${item.idCandidates}")
                        notice
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun loadLibraryShows(): List<LibraryEntry> {
        val mode = runCatching { libraryRepository.sourceMode.first() }
            .getOrDefault(LibrarySourceMode.LOCAL)
        if (mode == LibrarySourceMode.LOCAL) {
            val saved = runCatching { libraryPreferences.getAllItems() }.getOrDefault(emptyList())
            if (saved.isNotEmpty()) return saved.map { it.toLibraryEntry() }
        }
        return withTimeoutOrNull(LIBRARY_TIMEOUT_MS) {
            runCatching { libraryRepository.libraryItems.first() }.getOrNull()
        } ?: emptyList()
    }

    private suspend fun fetchRecentEpisodes(): List<com.nuvio.tv.data.remote.dto.trakt.TraktCalendarMediaItemDto>? {
        val today = LocalDate.now()
        val startDate = today.minusDays(WINDOW_DAYS - 1L)
        return try {
            val response = traktApi.getCalendarMedia(
                target = "all",
                startDate = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                days = WINDOW_DAYS,
                extended = "full"
            )
            if (!response.isSuccessful) {
                Log.w(TAG, "Trakt calendar failed: ${response.code()}")
                return null
            }
            response.body().orEmpty().filter { it.show != null && it.episode != null }
        } catch (e: Exception) {
            Log.w(TAG, "Trakt calendar error: ${e.message}")
            null
        }
    }

    private suspend fun resolveWatchedEpisodes(): Map<String, Set<Pair<Int, Int>>> {
        val authenticated = runCatching { traktAuthDataStore.isEffectivelyAuthenticated.first() }
            .getOrDefault(false)
        if (authenticated) {
            withTimeoutOrNull(PROGRESS_TIMEOUT_MS) {
                runCatching { watchProgressRepository.observeRemoteProgressLoaded().first { it } }
            }
        }
        return runCatching { watchProgressRepository.getWatchedShowEpisodes() }
            .getOrDefault(emptyMap())
    }

    private suspend fun resolveProgressIndex(): Map<String, WatchProgress> {
        val progress = withTimeoutOrNull(PROGRESS_TIMEOUT_MS) {
            runCatching { watchProgressRepository.allProgress.first() }.getOrNull()
        } ?: return emptyMap()

        val index = HashMap<String, WatchProgress>()
        progress.filter { entry ->
            !entry.contentType.equals("movie", ignoreCase = true)
        }.forEach { entry ->
            val best = index[entry.contentId]
            if (best == null || entry.lastWatched > best.lastWatched) {
                index[entry.contentId] = entry
            }
            entry.traktShowId?.let { traktId ->
                val key = "trakt:$traktId"
                val current = index[key]
                if (current == null || entry.lastWatched > current.lastWatched) {
                    index[key] = entry
                }
            }
        }
        return index
    }

    private fun buildLibraryIndex(entries: List<LibraryEntry>): Map<String, LibraryEntry> {
        val index = HashMap<String, LibraryEntry>()
        entries.forEach { entry ->
            entryMatchKeys(entry).forEach { key -> index.putIfAbsent(key, entry) }
        }
        return index
    }

    private fun entryMatchKeys(entry: LibraryEntry): Set<String> {
        val keys = linkedSetOf<String>()
        entry.id.trim().takeIf { it.isNotBlank() }?.let { raw -> keys.addAll(idKeyCandidates(raw)) }
        entry.imdbId?.trim()?.takeIf { it.isNotBlank() }?.let { keys.add(it) }
        entry.tmdbId?.let { keys.add("tmdb:$it") }
        entry.traktId?.let { keys.add("trakt:$it") }
        return keys
    }

    private fun idKeyCandidates(raw: String): Set<String> {
        val keys = linkedSetOf<String>()
        keys.add(raw)
        val lower = raw.lowercase(Locale.ROOT)
        val prefixes = listOf("tmdb_tv_", "tmdb_movie_", "tmdb_", "trakt_tv_", "trakt_movie_", "trakt_")
        prefixes.forEach { prefix ->
            if (lower.startsWith(prefix)) {
                val numeric = raw.substring(prefix.length).toIntOrNull() ?: return@forEach
                if (prefix.startsWith("tmdb")) {
                    keys.add("tmdb:$numeric")
                } else {
                    keys.add("trakt:$numeric")
                }
                return@forEach
            }
        }
        val parsed = parseContentIds(raw)
        parsed.imdb?.takeIf { it.isNotBlank() }?.let { keys.add(it) }
        parsed.tmdb?.let { keys.add("tmdb:$it") }
        parsed.trakt?.let { keys.add("trakt:$it") }
        return keys
    }

    private fun showIdKeys(ids: TraktIdsDto?): Set<String> {
        if (ids == null) return emptySet()
        val keys = linkedSetOf<String>()
        ids.imdb?.takeIf { it.isNotBlank() }?.let { keys.add(it) }
        ids.tmdb?.let { keys.add("tmdb:$it") }
        ids.trakt?.let { keys.add("trakt:$it") }
        return keys
    }

    private fun isEpisodeWatched(
        entryKeys: Set<String>,
        siblings: Map<String, Set<String>>,
        watchedEpisodes: Map<String, Set<Pair<Int, Int>>>,
        progressIndex: Map<String, WatchProgress>,
        season: Int,
        episode: Int,
        airMillis: Long
    ): Boolean {
        val lookupKeys = linkedSetOf<String>()
        entryKeys.forEach { key ->
            lookupKeys.add(key)
            siblings[key]?.let { siblingKeys ->
                if ("__ambiguous__" !in siblingKeys) lookupKeys.addAll(siblingKeys)
            }
        }

        lookupKeys.forEach { key ->
            if (watchedEpisodes[key]?.contains(season to episode) == true) return true
        }
        // Fallback: the show-level "last watched" timestamp is already newer than
        // the episode air date, so the episode was (most likely) watched.
        lookupKeys.forEach { key ->
            val progress = progressIndex[key] ?: return@forEach
            if (progress.lastWatched >= airMillis) return true
        }
        return false
    }

    private fun resolveAirMillis(firstAired: String?, released: String?): Long? {
        firstAired?.takeIf { it.isNotBlank() }?.let { value ->
            runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()?.let { return it }
        }
        released?.takeIf { it.isNotBlank() }?.let { value ->
            runCatching { LocalDate.parse(value) }.getOrNull()?.let { date ->
                return date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }
        }
        return null
    }

    private fun formatAiredLabel(airedAtMs: Long): String? {
        return runCatching {
            val date = Instant.ofEpochMilli(airedAtMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(Locale.getDefault())
                .format(date)
        }.getOrNull()
    }

    private fun SavedLibraryItem.toLibraryEntry(): LibraryEntry {
        return LibraryEntry(
            id = id,
            type = type,
            name = name,
            poster = poster,
            posterShape = posterShape,
            background = background,
            logo = logo,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres,
            addonBaseUrl = addonBaseUrl,
            listedAt = addedAt
        )
    }
}
