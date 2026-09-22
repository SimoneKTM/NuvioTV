package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.repository.CalendarRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.core.network.NetworkResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CalendarRepositoryImpl @Inject constructor(
    private val traktApi: TraktApi,
    private val metaRepository: MetaRepository
) : CalendarRepository {

    companion object {
        private const val TAG = "CalendarRepo"
        // Long enough for the anime-first race + backup race (each addon capped at 5s).
        private const val ADDON_ENRICHMENT_TIMEOUT_MS = 12_000L
        private const val ADDON_ENRICHMENT_CONCURRENCY = 4
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cachedItems = MutableStateFlow<List<CalendarItem>>(emptyList())
    // Attempted-once set (like LibraryRepositoryImpl.enrichedAddonIds) so we
    // don't re-query addons for items that already succeeded or failed.
    private val enrichedAddonIds = ConcurrentHashMap.newKeySet<String>()

    override fun warmUp() {
        scope.launch {
            try {
                getCalendarItems().collect { items ->
                    cachedItems.value = items
                }
            } catch (_: Exception) {}
        }
    }

    override fun getCalendarItems(): Flow<List<CalendarItem>> = flow {
        val cached = cachedItems.value
        if (cached.isNotEmpty()) {
            emit(cached)
        }
        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val daysAhead = 60

        val allItems = mutableListOf<CalendarItem>()

        try {
            val response = traktApi.getCalendarMedia(
                target = "all",
                startDate = todayStr,
                days = daysAhead
            )

            if (response.isSuccessful) {
                val body = response.body() ?: emptyList()
                Log.d(TAG, "Trakt calendar media: ${body.size} items")

                for (item in body) {
                    val calendarItem = item.toCalendarItem(today) ?: continue
                    allItems.add(calendarItem)
                }
            } else {
                Log.w(TAG, "Trakt calendar media failed: ${response.code()} ${response.message()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch Trakt calendar media", e)
        }

        val filteredItems = allItems
            .filter { it.releaseDate != null && !it.releaseDate.isBefore(today) }
            .distinctBy { it.meta.id }
            .sortedBy { it.releaseDate }

        Log.d(TAG, "Calendar: ${filteredItems.size} items after filtering (${allItems.size} raw)")

        emit(filteredItems)

        // Addon metadata only — same source as Library / Home / Anime / Extra.
        // Uses MetaRepository's shared addonMetaCache so data already saved
        // while browsing other tabs is returned instantly. No TMDB here.
        val addonEnrichedItems = enrichItemsWithAddonData(filteredItems)
        emit(addonEnrichedItems)
    }

    private suspend fun enrichItemsWithAddonData(items: List<CalendarItem>): List<CalendarItem> {
        // Attempt every item once (not gated on "missing fields") so addon
        // metadata is the primary source, matching Library/Collections behavior.
        // enrichedAddonIds prevents repeat network work on subsequent emissions.
        val pending = items.filter { it.meta.id !in enrichedAddonIds }
        if (pending.isEmpty()) {
            Log.d(TAG, "All items already attempted addon enrichment, skipping")
            return items
        }
        Log.d(TAG, "Addon enrichment: ${pending.size}/${items.size} items (primary source)")
        val semaphore = Semaphore(ADDON_ENRICHMENT_CONCURRENCY)
        return coroutineScope {
            items.map { item ->
                async {
                    if (item.meta.id in enrichedAddonIds) return@async item
                    semaphore.withPermit {
                        try {
                            val enriched = resolveAddonMetaForItem(item)
                            // Success returns a copied item; failures return the
                            // original reference and stay eligible for a later load.
                            if (enriched !== item) {
                                enrichedAddonIds.add(item.meta.id)
                            }
                            enriched
                        } catch (e: Exception) {
                            Log.w(TAG, "Addon enrichment failed for ${item.meta.name}: ${e.message}")
                            item
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun resolveAddonMetaForItem(item: CalendarItem): CalendarItem {
        val candidates = buildAddonIdCandidates(item.meta.id, item.meta.rawType)
        if (candidates.isEmpty()) return item
        val rawNumericId = extractRawNumericId(item.meta.id)

        for ((candidateType, candidateId) in candidates) {
            val result = withTimeoutOrNull(ADDON_ENRICHMENT_TIMEOUT_MS) {
                metaRepository.getMetaFromAllAddons(
                    type = candidateType,
                    id = candidateId,
                    sourceAddonBaseUrl = item.meta.sourceAddonBaseUrl,
                    rawId = rawNumericId,
                    namespace = com.nuvio.tv.domain.repository.MetaRepository.META_NAMESPACE_ALL,
                    preferAnimeAddons = true
                ).first { it !is NetworkResult.Loading }
            } ?: continue

            if (result is NetworkResult.Success) {
                val addonMeta = result.data
                // Addon is the primary source: prefer its values when present.
                // Description keeps Trakt's episode label ("S1E5") when set —
                // it is calendar context addons don't provide.
                val updatedMeta = item.meta.copy(
                    name = addonMeta.name.ifBlank { item.meta.name },
                    poster = addonMeta.poster ?: item.meta.poster,
                    background = addonMeta.background ?: item.meta.background,
                    logo = addonMeta.logo ?: item.meta.logo,
                    description = item.meta.description ?: addonMeta.description,
                    imdbRating = addonMeta.imdbRating ?: item.meta.imdbRating,
                    genres = if (addonMeta.genres.isNotEmpty()) addonMeta.genres else item.meta.genres,
                    releaseInfo = addonMeta.releaseInfo ?: item.meta.releaseInfo,
                    sourceAddonBaseUrl = addonMeta.sourceAddonBaseUrl ?: item.meta.sourceAddonBaseUrl
                )
                if (updatedMeta != item.meta) {
                    Log.d(TAG, "Addon enriched: ${item.meta.name} name=${updatedMeta.name != item.meta.name} poster=${updatedMeta.poster != item.meta.poster} bg=${updatedMeta.background != item.meta.background} logo=${updatedMeta.logo != item.meta.logo}")
                }
                return item.copy(meta = updatedMeta)
            }
        }
        return item
    }

    private fun extractRawNumericId(metaId: String): String? {
        val raw = metaId.trim()
        val numericId = when {
            raw.startsWith("tmdb_tv_", ignoreCase = true) ->
                raw.removePrefix("tmdb_tv_").removePrefix("tmdb_Tv_")
            raw.startsWith("tmdb_movie_", ignoreCase = true) ->
                raw.removePrefix("tmdb_movie_").removePrefix("tmdb_Movie_")
            raw.startsWith("tmdb:", ignoreCase = true) ->
                raw.substringAfter(':', missingDelimiterValue = "").substringBefore(':')
            else -> null
        }
        return numericId?.takeIf { it.all { c -> c.isDigit() } }
    }

    private fun com.nuvio.tv.data.remote.dto.trakt.TraktCalendarMediaItemDto.toCalendarItem(
        today: LocalDate
    ): CalendarItem? {
        val movie = movie
        if (movie != null) {
            val tmdbId = movie.ids?.tmdb
            val id = if (tmdbId != null) "tmdb_movie_$tmdbId" else "trakt_movie_${movie.ids?.trakt ?: 0}"
            val releaseDate = parseDate(released)
            if (releaseDate == null || releaseDate.isBefore(today)) return null

            return CalendarItem(
                meta = MetaPreview(
                    id = id,
                    type = ContentType.MOVIE,
                    rawType = "movie",
                    name = movie.title ?: "",
                    poster = null,
                    posterShape = PosterShape.POSTER,
                    background = null,
                    logo = null,
                    description = null,
                    releaseInfo = null,
                    imdbRating = null,
                    genres = emptyList(),
                    sourceAddonBaseUrl = null
                ),
                releaseDate = releaseDate
            )
        }

        val show = show
        val episode = episode
        if (show != null) {
            val tmdbId = show.ids?.tmdb
            val id = if (tmdbId != null) "tmdb_tv_$tmdbId" else "trakt_tv_${show.ids?.trakt ?: 0}"
            val airDate = parseDate(firstAired ?: episode?.firstAired)
            if (airDate == null || airDate.isBefore(today)) return null

            val episodeLabel = if (episode != null) {
                val season = episode.season ?: 0
                val number = episode.number ?: 0
                "S${season}E$number"
            } else null

            return CalendarItem(
                meta = MetaPreview(
                    id = id,
                    type = ContentType.SERIES,
                    rawType = "tv",
                    name = show.title ?: "",
                    poster = null,
                    posterShape = PosterShape.POSTER,
                    background = null,
                    logo = null,
                    description = episodeLabel,
                    releaseInfo = null,
                    imdbRating = null,
                    genres = emptyList(),
                    sourceAddonBaseUrl = null
                ),
                releaseDate = airDate
            )
        }

        return null
    }

    private fun parseDate(dateStr: String?): LocalDate? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            LocalDate.parse(dateStr.take(10))
        } catch (e: Exception) {
            null
        }
    }
}
