package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbEnrichment
import com.nuvio.tv.core.tmdb.TmdbMetadataService
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.AnimeTvdbSettingsDataStore
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.local.TvdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktCalendarMediaItemDto
import com.nuvio.tv.data.tvdb.TvdbMetadataService
import com.nuvio.tv.domain.model.CalendarItem
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MDBListSettings
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.domain.repository.CalendarRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.trakt.traktBestBackdropUrl
import com.nuvio.tv.core.trakt.traktBestLogoUrl
import com.nuvio.tv.core.trakt.traktBestPosterUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class CalendarRepositoryImpl @Inject constructor(
    private val traktApi: TraktApi,
    private val metaRepository: MetaRepository,
    private val tmdbService: TmdbService,
    private val tmdbMetadataService: TmdbMetadataService,
    private val mdbListRepository: MDBListRepository,
    private val tvdbMetadataService: TvdbMetadataService,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val mdbListSettingsDataStore: MDBListSettingsDataStore,
    private val tvdbSettingsDataStore: TvdbSettingsDataStore,
    @param:Named("anime_tmdb") private val animeTmdbSettingsDataStore: TmdbSettingsDataStore,
    @param:Named("anime_mdblist") private val animeMdbListSettingsDataStore: MDBListSettingsDataStore,
    private val animeTvdbSettingsDataStore: AnimeTvdbSettingsDataStore
) : CalendarRepository {

    companion object {
        private const val TAG = "CalendarRepo"
        // Anime-first race + Home/Extra backup + external TMDB/MDBList/TVDB.
        private const val ADDON_ENRICHMENT_TIMEOUT_MS = 12_000L
        private const val ADDON_ENRICHMENT_CONCURRENCY = 4
        private const val EXTERNAL_ENRICHMENT_TIMEOUT_MS = 6_000L
        // Trakt calendars documented maximum is 33 days.
        private const val TRAKT_MAX_DAYS = 33
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cachedItems = MutableStateFlow<List<CalendarItem>>(emptyList())
    // Attempted-once set (like LibraryRepositoryImpl.enrichedAddonIds) so we
    // don't re-query addons for items that already succeeded or failed.
    private val enrichedAddonIds = ConcurrentHashMap.newKeySet<String>()
    private var warmUpJob: Job? = null

    override fun warmUp() {
        if (warmUpJob?.isActive == true) return
        warmUpJob = scope.launch {
            try {
                getCalendarItems().collect { items ->
                    cachedItems.value = items
                }
            } catch (_: CancellationException) {
            } catch (e: Exception) {
                Log.w(TAG, "warmUp failed: ${e.message}")
            }
        }
    }

    override fun getCalendarItems(): Flow<List<CalendarItem>> = flow {
        val cached = cachedItems.value
        if (cached.isNotEmpty()) {
            emit(cached)
        }

        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)

        val allItems = mutableListOf<CalendarItem>()
        var fetchFailed = false

        try {
            // extended=full returns images on /calendars/*/media (fullimages
            // returns none). Trakt image URLs are scheme-less media.trakt.tv
            // paths — traktBest* helpers normalize them to https.
            val response = traktApi.getCalendarMedia(
                target = "all",
                startDate = todayStr,
                days = TRAKT_MAX_DAYS,
                extended = "full"
            )

            if (response.isSuccessful) {
                val body = response.body() ?: emptyList()
                Log.d(TAG, "Trakt calendar media: ${body.size} items")

                for (item in body) {
                    val calendarItem = item.toCalendarItem() ?: continue
                    allItems.add(calendarItem)
                }
            } else {
                fetchFailed = true
                Log.w(TAG, "Trakt calendar media failed: ${response.code()} ${response.message()}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fetchFailed = true
            Log.e(TAG, "Failed to fetch Trakt calendar media", e)
        }

        if (fetchFailed) {
            // Keep whatever cache the UI is already showing instead of
            // overwriting it with an empty list (which forced an infinite spinner).
            if (cached.isNotEmpty()) {
                return@flow
            }
            throw IllegalStateException("Trakt calendar request failed")
        }

        val filteredItems = allItems
            .filter { item ->
                val date = item.releaseDate ?: return@filter false
                // Trakt dates are UTC; keep yesterday-UTC items so Italian
                // evenings around midnight are not dropped.
                !date.isBefore(today.minusDays(1))
            }
            .distinctBy { "${it.meta.id}:${it.releaseDate}" }
            .sortedBy { it.releaseDate }

        Log.d(TAG, "Calendar: ${filteredItems.size} items after filtering (${allItems.size} raw)")

        cachedItems.value = filteredItems
        emit(filteredItems)

        // Addon metadata only — same source as Library / Home / Anime / Extra.
        // Uses MetaRepository's shared addonMetaCache so data already saved
        // while browsing other tabs is returned instantly. Then external
        // TMDB/MDBList/TVDB like the Anime tab (Trakt still owns dates).
        val addonEnrichedItems = enrichItemsWithAddonData(filteredItems)
        cachedItems.value = addonEnrichedItems
        emit(addonEnrichedItems)
    }.flowOn(Dispatchers.IO)

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
                            // Mark only when enrichment added artwork, a source
                            // URL, or a rating — a no-op stays eligible so
                            // letter-cards can still pick up images later.
                            if (enriched !== item) {
                                val gained = enriched.meta.poster != item.meta.poster ||
                                    enriched.meta.background != item.meta.background ||
                                    enriched.meta.logo != item.meta.logo ||
                                    enriched.meta.imdbRating != item.meta.imdbRating ||
                                    !enriched.meta.sourceAddonBaseUrl.isNullOrBlank()
                                if (gained || enriched.notInCatalog) {
                                    enrichedAddonIds.add(item.meta.id)
                                }
                            }
                            enriched
                        } catch (e: CancellationException) {
                            throw e
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
        if (candidates.isEmpty()) return applyExternalEnrichment(item, MetaRepository.META_NAMESPACE_HOME)
        val rawNumericId = extractRawNumericId(item.meta.id)

        // Anime first (same as the Anime tab), then Home/Extra. A hit with
        // artwork or a source URL wins immediately so Detail opens with the
        // Anime layout for anime-sourced titles (4 seasons, not the generic 1).
        val animeWinner = queryNamespace(
            namespace = MetaRepository.META_NAMESPACE_ANIME,
            candidates = candidates,
            item = item,
            rawNumericId = rawNumericId,
            requireStrongFields = true
        )
        if (animeWinner != null) {
            return applyExternalEnrichment(
                mergeAddonMeta(item, animeWinner, MetaRepository.META_NAMESPACE_ANIME),
                MetaRepository.META_NAMESPACE_ANIME
            )
        }

        val fallbackNamespaces = listOf(
            MetaRepository.META_NAMESPACE_HOME,
            MetaRepository.META_NAMESPACE_EXTRA
        )
        var bestNamespace: String? = null
        var bestMeta: Meta? = null
        var bestScore = -1

        for (namespace in fallbackNamespaces) {
            val result = queryNamespace(
                namespace = namespace,
                candidates = candidates,
                item = item,
                rawNumericId = rawNumericId,
                requireStrongFields = false
            ) ?: continue
            val score = metaRichnessScore(result)
            if (score > bestScore) {
                bestScore = score
                bestNamespace = namespace
                bestMeta = result
            }
        }

        val winner = bestMeta
        val afterAddon = if (winner != null) {
            mergeAddonMeta(item, winner, bestNamespace ?: MetaRepository.META_NAMESPACE_HOME)
        } else {
            // No tab knows this title: keep Trakt data and flag the card.
            item.copy(notInCatalog = true)
        }
        return applyExternalEnrichment(afterAddon, bestNamespace ?: MetaRepository.META_NAMESPACE_HOME)
    }

    private suspend fun queryNamespace(
        namespace: String,
        candidates: List<Pair<String, String>>,
        item: CalendarItem,
        rawNumericId: String?,
        requireStrongFields: Boolean
    ): Meta? {
        for ((candidateType, candidateId) in candidates) {
            val result = withTimeoutOrNull(ADDON_ENRICHMENT_TIMEOUT_MS) {
                metaRepository.getMetaFromAllAddons(
                    type = candidateType,
                    id = candidateId,
                    sourceAddonBaseUrl = item.meta.sourceAddonBaseUrl,
                    rawId = rawNumericId,
                    namespace = namespace,
                    preferAnimeAddons = namespace == MetaRepository.META_NAMESPACE_ANIME
                ).first { it !is NetworkResult.Loading }
            } ?: continue

            if (result is NetworkResult.Success) {
                val meta = result.data
                if (!requireStrongFields || hasStrongAddonFields(meta)) {
                    return meta
                }
            }
        }
        return null
    }

    private fun hasStrongAddonFields(meta: Meta): Boolean {
        return !meta.poster.isNullOrBlank() ||
            !meta.background.isNullOrBlank() ||
            !meta.logo.isNullOrBlank() ||
            !meta.sourceAddonBaseUrl.isNullOrBlank()
    }

    /**
     * External posters/ratings like the Anime tab (TMDB + MDBList + TVDB).
     * Never touches [CalendarItem.releaseDate] (Trakt owns dates) and never
     * overwrites a non-blank description (Trakt episode label "S1E5").
     * Uses anime-scoped settings when the Anime pool won, else global.
     */
    private suspend fun applyExternalEnrichment(
        item: CalendarItem,
        namespace: String
    ): CalendarItem = withContext(Dispatchers.IO) {
        val useAnimeSettings = namespace == MetaRepository.META_NAMESPACE_ANIME
        val tmdbSettings = if (useAnimeSettings) {
            animeTmdbSettingsDataStore.settings.first()
        } else {
            tmdbSettingsDataStore.settings.first()
        }
        val mdbSettings = if (useAnimeSettings) {
            animeMdbListSettingsDataStore.settings.first()
        } else {
            mdbListSettingsDataStore.settings.first()
        }
        val tvdbSettings = if (useAnimeSettings) {
            animeTvdbSettingsDataStore.settings.first()
        } else {
            tvdbSettingsDataStore.settings.first()
        }
        val tvdbEnabled = tvdbSettings.enabled && tvdbSettings.hasApiKey
        if (!tmdbSettings.enabled &&
            !(mdbSettings.enabled && mdbSettings.apiKey.isNotBlank()) &&
            !tvdbEnabled
        ) {
            return@withContext item
        }

        val (enrichment, mdbRating) = fetchExternalEnrichment(
            itemId = item.meta.id,
            itemType = item.meta.apiType,
            contentType = item.meta.type,
            tmdbSettings = tmdbSettings,
            mdbSettings = mdbSettings
        )
        var meta = applyTmdbToCalendarPreview(
            meta = item.meta,
            enrichment = enrichment,
            settings = tmdbSettings
        ).let { m ->
            if (mdbRating != null) m.copy(imdbRating = mdbRating.toFloat()) else m
        }
        // TVDB fills blanks after TMDB (TMDB wins). Movies return null.
        if (tvdbEnabled && item.meta.apiType in listOf("series", "tv")) {
            val tvdbPreview = runCatching {
                withTimeoutOrNull(EXTERNAL_ENRICHMENT_TIMEOUT_MS) {
                    tvdbMetadataService.enrichPreview(
                        itemId = item.meta.id,
                        name = item.meta.name,
                        apiType = item.meta.apiType,
                        settings = tvdbSettings
                    )
                }
            }.getOrNull()
            // applyPreviewToItem only fills blank description/background.
            meta = tvdbMetadataService.applyPreviewToItem(meta, tvdbPreview)
        }
        if (meta != item.meta) {
            Log.d(
                TAG,
                "External enriched ($namespace): ${item.meta.name} " +
                    "poster=${meta.poster != item.meta.poster} " +
                    "bg=${meta.background != item.meta.background} " +
                    "logo=${meta.logo != item.meta.logo} " +
                    "rating=${meta.imdbRating != item.meta.imdbRating}"
            )
        }
        item.copy(meta = meta)
    }

    private suspend fun fetchExternalEnrichment(
        itemId: String,
        itemType: String,
        contentType: ContentType,
        tmdbSettings: TmdbSettings,
        mdbSettings: MDBListSettings
    ): Pair<TmdbEnrichment?, Double?> {
        val tmdbEnabled = tmdbSettings.enabled
        val mdbEnabled = mdbSettings.enabled && mdbSettings.apiKey.isNotBlank()
        if (!tmdbEnabled && !mdbEnabled) return null to null

        val tmdbEnrichment = if (tmdbEnabled) {
            val tmdbId = runCatching {
                withTimeoutOrNull(EXTERNAL_ENRICHMENT_TIMEOUT_MS) {
                    tmdbService.ensureTmdbId(itemId, itemType)
                }
            }.getOrNull()
            if (tmdbId != null) {
                runCatching {
                    withTimeoutOrNull(EXTERNAL_ENRICHMENT_TIMEOUT_MS) {
                        tmdbMetadataService.fetchEnrichment(
                            tmdbId = tmdbId,
                            contentType = contentType,
                            language = tmdbSettings.language
                        )
                    }
                }.getOrNull()
            } else {
                null
            }
        } else {
            null
        }

        val mdbRating = if (mdbEnabled) {
            runCatching {
                withTimeoutOrNull(EXTERNAL_ENRICHMENT_TIMEOUT_MS) {
                    mdbListRepository.getImdbRatingForItemWithSettings(itemId, itemType, mdbSettings)
                }
            }.getOrNull()
        } else {
            null
        }

        return tmdbEnrichment to mdbRating
    }

    private fun applyTmdbToCalendarPreview(
        meta: MetaPreview,
        enrichment: TmdbEnrichment?,
        settings: TmdbSettings
    ): MetaPreview {
        var enriched = meta
        if (enrichment == null) return enriched
        if (settings.useArtwork) {
            enriched = enriched.copy(
                background = enrichment.backdrop ?: enriched.background,
                logo = enrichment.logo ?: enriched.logo,
                poster = enrichment.poster ?: enriched.poster
            )
        }
        if (settings.useBasicInfo) {
            // Never replace Trakt's episode label / overview when present.
            enriched = enriched.copy(
                description = enriched.description ?: enrichment.description,
                genres = if (enrichment.genres.isNotEmpty()) enrichment.genres else enriched.genres
            )
        }
        if (settings.useDetails) {
            enriched = enriched.copy(
                runtime = enrichment.runtimeMinutes?.toString() ?: enriched.runtime,
                status = enrichment.status ?: enriched.status,
                ageRating = enrichment.ageRating ?: enriched.ageRating,
                country = enrichment.countries?.joinToString(", ") ?: enriched.country,
                language = enrichment.language ?: enriched.language
            )
        }
        if (settings.useReleaseDates) {
            // Year only — CalendarItem.releaseDate stays Trakt-owned.
            enriched = enriched.copy(
                releaseInfo = enrichment.releaseInfo ?: enriched.releaseInfo
            )
        }
        return enriched
    }

    private fun metaRichnessScore(meta: Meta): Int {
        var score = 0
        if (!meta.poster.isNullOrBlank()) score++
        if (!meta.background.isNullOrBlank()) score++
        if (!meta.logo.isNullOrBlank()) score++
        if (!meta.sourceAddonBaseUrl.isNullOrBlank()) score++
        if (meta.genres.isNotEmpty()) score++
        if (meta.imdbRating != null) score++
        if (!meta.releaseInfo.isNullOrBlank()) score++
        return score
    }

    private fun mergeAddonMeta(
        item: CalendarItem,
        addonMeta: Meta,
        namespace: String
    ): CalendarItem {
        // Addon is the primary source: prefer its values when present.
        // Description keeps Trakt's episode label ("S1E5") when set —
        // it is calendar context addons don't provide. releaseDate is
        // never copied here (Trakt owns the calendar dates).
        val updatedMeta = item.meta.copy(
            name = addonMeta.name.ifBlank { item.meta.name },
            poster = addonMeta.poster ?: item.meta.poster,
            background = addonMeta.background ?: item.meta.background,
            logo = addonMeta.logo ?: item.meta.logo,
            landscapePoster = addonMeta.landscapePoster ?: item.meta.landscapePoster,
            description = item.meta.description ?: addonMeta.description,
            imdbRating = addonMeta.imdbRating ?: item.meta.imdbRating,
            genres = if (addonMeta.genres.isNotEmpty()) addonMeta.genres else item.meta.genres,
            releaseInfo = addonMeta.releaseInfo ?: item.meta.releaseInfo,
            runtime = addonMeta.runtime ?: item.meta.runtime,
            status = addonMeta.status ?: item.meta.status,
            ageRating = addonMeta.ageRating ?: item.meta.ageRating,
            language = addonMeta.language ?: item.meta.language,
            country = addonMeta.country ?: item.meta.country,
            sourceAddonBaseUrl = addonMeta.sourceAddonBaseUrl ?: item.meta.sourceAddonBaseUrl
        )
        if (updatedMeta != item.meta) {
            Log.d(
                TAG,
                "Addon enriched ($namespace): ${item.meta.name} " +
                    "name=${updatedMeta.name != item.meta.name} " +
                    "poster=${updatedMeta.poster != item.meta.poster} " +
                    "bg=${updatedMeta.background != item.meta.background} " +
                    "logo=${updatedMeta.logo != item.meta.logo}"
            )
        }
        return item.copy(meta = updatedMeta)
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

    /**
     * Prefer IMDB (best addon compatibility), then TMDB, then Trakt.
     * Bare numeric fallbacks are avoided so addons can resolve the title.
     */
    private fun buildContentId(
        imdb: String?,
        tmdb: Int?,
        trakt: Int?,
        kind: String
    ): String {
        val imdbId = imdb?.trim()?.takeIf { it.isNotBlank() }
        if (imdbId != null) return imdbId
        if (tmdb != null) return "tmdb_${kind}_$tmdb"
        return "trakt_${kind}_${trakt ?: 0}"
    }

    private fun TraktCalendarMediaItemDto.toCalendarItem(): CalendarItem? {
        val movie = movie
        if (movie != null) {
            val id = buildContentId(
                imdb = movie.ids?.imdb,
                tmdb = movie.ids?.tmdb,
                trakt = movie.ids?.trakt,
                kind = "movie"
            )
            val releaseDate = parseDate(released)
            // Keep undated items only when we have a title; drop long-past dates.
            if (releaseDate != null && releaseDate.isBefore(LocalDate.now(ZoneOffset.UTC).minusDays(1))) {
                return null
            }

            val images = movie.images
            return CalendarItem(
                meta = MetaPreview(
                    id = id,
                    type = ContentType.MOVIE,
                    rawType = "movie",
                    name = movie.title ?: "",
                    poster = images.traktBestPosterUrl(),
                    posterShape = PosterShape.POSTER,
                    background = images.traktBestBackdropUrl(),
                    logo = images.traktBestLogoUrl(),
                    description = movie.overview,
                    releaseInfo = movie.year?.toString(),
                    imdbRating = movie.rating?.toFloat(),
                    genres = movie.genres.orEmpty(),
                    imdbId = movie.ids?.imdb,
                    sourceAddonBaseUrl = null
                ),
                releaseDate = releaseDate
            )
        }

        val show = show
        val episode = episode
        if (show != null) {
            val id = buildContentId(
                imdb = show.ids?.imdb,
                tmdb = show.ids?.tmdb,
                trakt = show.ids?.trakt,
                kind = "tv"
            )
            val airDate = parseDate(firstAired ?: episode?.firstAired)
            if (airDate != null && airDate.isBefore(LocalDate.now(ZoneOffset.UTC).minusDays(1))) {
                return null
            }

            val episodeLabel = if (episode != null) {
                val season = episode.season ?: 0
                val number = episode.number ?: 0
                "S${season}E$number"
            } else null

            val images = show.images
            return CalendarItem(
                meta = MetaPreview(
                    id = id,
                    type = ContentType.SERIES,
                    rawType = "tv",
                    name = show.title ?: "",
                    poster = images.traktBestPosterUrl(),
                    posterShape = PosterShape.POSTER,
                    background = images.traktBestBackdropUrl(),
                    logo = images.traktBestLogoUrl(),
                    description = episodeLabel,
                    releaseInfo = show.year?.toString(),
                    imdbRating = show.rating?.toFloat(),
                    genres = show.genres.orEmpty(),
                    imdbId = show.ids?.imdb,
                    sourceAddonBaseUrl = null
                ),
                releaseDate = airDate
            )
        }

        return null
    }

    private fun parseDate(dateStr: String?): LocalDate? {
        if (dateStr.isNullOrBlank()) return null
        val raw = dateStr.trim()
        // Full timestamp (Trakt UTC) → local calendar date so Italian evenings
        // around midnight keep the correct release day.
        if (raw.length > 10 && (raw.contains('T') || raw.endsWith('Z'))) {
            val normalized = when {
                raw.endsWith("Z") || raw.contains('+') -> raw
                else -> raw + "Z"
            }
            runCatching {
                Instant.parse(normalized)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()
            }.getOrNull()?.let { return it }
        }
        return runCatching { LocalDate.parse(raw.take(10)) }.getOrNull()
    }
}
