package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.AnimeAddonRepository
import com.nuvio.tv.domain.repository.ExtraAddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetaRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val addonRepository: AddonRepository,
    private val animeAddonRepository: AnimeAddonRepository,
    private val extraAddonRepository: ExtraAddonRepository
) : MetaRepository {
    companion object {
        private const val TAG = "MetaRepository"
    }

    private enum class MetaFailureKind {
        MISSING,
        REQUEST_FAILED
    }

    private data class MetaAttemptFailure(
        val addonName: String,
        val kind: MetaFailureKind,
        val detail: String
    )

    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // In-memory cache: "namespace:type:id" -> Meta (scoped per tab/addon)
    private val metaCache = ConcurrentHashMap<String, Meta>()
    // Separate cache for full meta fetched from addons (bypasses catalog-level cache)
    private val addonMetaCache = ConcurrentHashMap<String, Meta>()
    private val primaryAddonMetaCache = ConcurrentHashMap<String, Meta>()

    // In-flight deduplication: prevents concurrent coroutines from firing duplicate requests
    private val inFlightMeta = ConcurrentHashMap<String, Deferred<Meta?>>()
    private val inFlightAddonMeta = ConcurrentHashMap<String, Deferred<Meta?>>()
    private val inFlightPrimaryMeta = ConcurrentHashMap<String, Deferred<Meta?>>()

    override fun getMeta(
        addonBaseUrl: String,
        type: String,
        id: String,
        namespace: String
    ): Flow<NetworkResult<Meta>> = flow {
        val ctx = context
        val normalizedAddon = addonBaseUrl.trim().trimEnd('/').lowercase()
        val cacheKey = "$namespace:$normalizedAddon:$type:$id"
        metaCache[cacheKey]?.let { cached ->
            emit(NetworkResult.Success(cached))
            return@flow
        }

        emit(NetworkResult.Loading)

        val url = buildMetaUrl(addonBaseUrl, type, id)
        val deferred = inFlightMeta.getOrPut(cacheKey) {
            repositoryScope.async {
                try {
                    when (val result = safeApiCall(context) { api.getMeta(url) }) {
                        is NetworkResult.Success -> {
                            val metaDto = result.data.meta ?: return@async null
                            val meta = metaDto.toDomain(context.getString(R.string.episodes_episode))
                                .copy(sourceAddonBaseUrl = addonBaseUrl)
                            metaCache[cacheKey] = meta
                            meta
                        }
                        else -> null
                    }
                } finally {
                    inFlightMeta.remove(cacheKey)
                }
            }
        }

        val meta = deferred.await()
        if (meta != null) {
            emit(NetworkResult.Success(meta))
        } else {
            emit(NetworkResult.Error(context.getString(R.string.error_meta_not_found)))
        }
    }

    override fun getMetaFromAllAddons(
        type: String,
        id: String,
        sourceAddonBaseUrl: String?,
        rawId: String?,
        namespace: String,
        preferAnimeAddons: Boolean
    ): Flow<NetworkResult<Meta>> = flow {
        val ctx = context
        val cacheKey = "$namespace:$type:$id:${if (preferAnimeAddons) "anime" else "std"}"
        var bypassedCachedMeta: Meta? = null
        addonMetaCache[cacheKey]?.let { cached ->
            // When caller asks for anime preference, a cached entry without a
            // discovered source may have won a HOME-namespace race against a
            // non-anime addon — refetch so anime addons get priority.
            val cacheUsable = !preferAnimeAddons || !cached.sourceAddonBaseUrl.isNullOrBlank()
            if (cacheUsable) {
                emit(NetworkResult.Success(cached))
                return@flow
            }
            bypassedCachedMeta = cached
        }

        emit(NetworkResult.Loading)

        val regularAddons = addonRepository.getInstalledAddons().first()
        val animeAddons = animeAddonRepository.getInstalledAnimeAddons().first()
        val extraAddons = extraAddonRepository.getInstalledExtraAddons().first()
        // Strict namespace isolation: each tab only races its own addon pool.
        // Home -> regular only (TMDB), Anime -> anime only (TVDB),
        // Extra -> extra only, all -> every pool (global screens).
        val scoped: Triple<List<Addon>, List<Addon>, List<Addon>> = when (namespace) {
            com.nuvio.tv.domain.repository.MetaRepository.META_NAMESPACE_ANIME ->
                Triple(emptyList(), animeAddons, emptyList())
            com.nuvio.tv.domain.repository.MetaRepository.META_NAMESPACE_EXTRA ->
                Triple(emptyList(), emptyList(), extraAddons)
            com.nuvio.tv.domain.repository.MetaRepository.META_NAMESPACE_ALL ->
                Triple(regularAddons, animeAddons, extraAddons)
            else -> when {
                preferAnimeAddons -> Triple(regularAddons, animeAddons, emptyList())
                else -> Triple(regularAddons, emptyList(), emptyList())
            }
        }
        val (scopedRegular, scopedAnime, scopedExtra) = scoped
        val addons = scopedRegular + scopedAnime + scopedExtra

        val requestedType = type.trim()
        val inferredType = inferCanonicalType(requestedType, id)
        val attemptedFailures = mutableListOf<MetaAttemptFailure>()
        val attemptedAddonNames = linkedSetOf<String>()
        val metaResourceAddons = addons.filter { addon ->
            addon.resources.any { it.name == "meta" }
        }

        // Priority order:
        // 1) addons that explicitly support requested type
        // 2) addons that support inferred canonical type (for custom catalog types)
        // 3) top addon in installed order that exposes meta resource
        val prioritizedCandidates = linkedSetOf<Pair<Addon, String>>()
        addons.forEach { addon ->
            if (addon.supportsMetaType(requestedType)) {
                prioritizedCandidates.add(addon to requestedType)
            }
        }
        if (!inferredType.equals(requestedType, ignoreCase = true)) {
            addons.forEach { addon ->
                if (addon.supportsMetaType(inferredType)) {
                    prioritizedCandidates.add(addon to inferredType)
                }
            }
        }
        metaResourceAddons.firstOrNull()?.let { topMetaAddon ->
            val fallbackType = when {
                topMetaAddon.supportsMetaType(requestedType) -> requestedType
                topMetaAddon.supportsMetaType(inferredType) -> inferredType
                else -> inferredType.ifBlank { requestedType }
            }
            prioritizedCandidates.add(topMetaAddon to fallbackType)
        }

        // Anime addons may declare only type "anime" while the calendar asks for
        // "tv"/"series" — force them into the candidate set so preferAnimeAddons
        // can race them first and discover their source URL.
        if (preferAnimeAddons) {
            val alreadyCandidate = prioritizedCandidates.mapTo(hashSetOf()) { it.first.baseUrl }
            scopedAnime.forEach { addon ->
                if (addon.baseUrl in alreadyCandidate) return@forEach
                if (!addon.resources.any { it.name == "meta" }) return@forEach
                val candidateType = when {
                    addon.supportsMetaType(requestedType) -> requestedType
                    addon.supportsMetaType(inferredType) -> inferredType
                    addon.supportsMetaType("anime") -> "anime"
                    else -> requestedType
                }
                prioritizedCandidates.add(addon to candidateType)
            }
        }

        if (prioritizedCandidates.isEmpty()) {
            // Last resort: try addons that declare the raw type (legacy behavior).
            val fallbackAddons = addons.filter { addon ->
                addon.rawTypes.any { it.equals(requestedType, ignoreCase = true) } &&
                    addon.resources.any { it.name == "meta" }
            }

            for (addon in fallbackAddons) {
                attemptedAddonNames += addon.displayName
                val url = buildMetaUrl(addon.baseUrl, requestedType, id)
                when (val result = safeApiCall(context) { api.getMeta(url) }) {
                    is NetworkResult.Success -> {
                        val metaDto = result.data.meta
                        if (metaDto != null) {
                            val episodeLabel = context.getString(R.string.episodes_episode)
                            val meta = metaDto.toDomain(episodeLabel)
                                .copy(sourceAddonBaseUrl = addon.baseUrl)
                            addonMetaCache[cacheKey] = meta
                            emit(NetworkResult.Success(meta))
                            return@flow
                        } else {
                            attemptedFailures += buildMissingMetaFailure(addon)
                        }
                    }
                    is NetworkResult.Error -> {
                        attemptedFailures += buildAddonFailure(addon, result)
                    }
                    NetworkResult.Loading -> { /* Try next addon */ }
                }
            }

            val fallbackMessage = if (fallbackAddons.isEmpty()) {
                context.getString(R.string.error_meta_no_supported_addon, requestedType)
            } else {
                buildAggregateFailureMessage(
                    type = requestedType,
                    id = id,
                    attemptedAddonNames = attemptedAddonNames.toList(),
                    failures = attemptedFailures
                )
            }
            emit(NetworkResult.Error(fallbackMessage))
            return@flow
        }

        val deferred = inFlightAddonMeta.getOrPut(cacheKey) {
            repositoryScope.async {
                try {
                    val sourceUrl = sourceAddonBaseUrl?.trim()?.trimEnd('/')?.lowercase()

                    if (sourceUrl != null && sourceUrl.isNotEmpty()) {
                        // Source addon is known: prioritize it, fall back to others
                        val sourceCandidate = prioritizedCandidates.firstOrNull {
                            it.first.baseUrl.trimEnd('/').lowercase() == sourceUrl
                        }
                        val orderedCandidates = if (sourceCandidate != null) {
                            listOf(sourceCandidate) + prioritizedCandidates.filter { it != sourceCandidate }
                        } else {
                            prioritizedCandidates.toList()
                        }

                        for ((addon, candidateType) in orderedCandidates) {
                            val url = buildMetaUrl(addon.baseUrl, candidateType, id)
                            Log.d(TAG, "Trying meta (source-prioritized) addonId=${addon.id} addonName=${addon.name} type=$candidateType id=$id url=$url")
                            when (val result = safeApiCall(context) { api.getMeta(url) }) {
                                is NetworkResult.Success -> {
                                    val metaDto = result.data.meta
                                    if (metaDto != null) {
                                        val meta = metaDto.toDomain(context.getString(R.string.episodes_episode))
                                            .copy(sourceAddonBaseUrl = addon.baseUrl)
                                        addonMetaCache[cacheKey] = meta
                                        Log.d(TAG, "Meta fetch success addonId=${addon.id} type=$candidateType id=$id")
                                        return@async meta
                                    }
                                    Log.d(TAG, "Meta response was null addonId=${addon.id} type=$candidateType id=$id")
                                }
                                is NetworkResult.Error -> { /* try next */ }
                                NetworkResult.Loading -> { /* try next */ }
                            }
                        }
                        null
                    } else {
                        // No source addon known: query all matching addons in parallel,
                        // pick the one with the most complete videos (most seasons/episodes).
                        // This ensures TMDB doesn't override richer data from TVDB/anime addons.
                        // When rawId differs from id (e.g. resolved IMDB vs original TMDB numeric),
                        // also try the raw ID so addons that can't resolve IMDB still get a chance.
                        val episodeLabel = context.getString(R.string.episodes_episode)
                        val candidateIds = buildList {
                            add(id)
                            if (!rawId.isNullOrBlank() && rawId != id) {
                                add(rawId)
                            }
                        }

                        suspend fun raceMeta(candidates: List<Pair<Addon, String>>): Pair<Addon, Meta>? {
                            if (candidates.isEmpty()) return null
                            val results = coroutineScope {
                                candidates.map { (addon, candidateType) ->
                                    async {
                                        var bestForAddon: Meta? = null
                                        for (candidateId in candidateIds) {
                                            if (bestForAddon != null) break
                                            val url = buildMetaUrl(addon.baseUrl, candidateType, candidateId)
                                            Log.d(TAG, "Trying meta (parallel) addonId=${addon.id} addonName=${addon.name} type=$candidateType id=$candidateId url=$url")
                                            when (val result = safeApiCall(context) { api.getMeta(url) }) {
                                                is NetworkResult.Success -> {
                                                    result.data.meta?.toDomain(episodeLabel)
                                                        ?.let { bestForAddon = it }
                                                }
                                                else -> { /* try next ID */ }
                                            }
                                        }
                                        bestForAddon?.let { addon to it }
                                    }
                                }.awaitAll()
                            }

                            val validResults = results.filterNotNull()
                            if (validResults.isEmpty()) return null
                            // Pick the Meta with the most videos (episodes/seasons).
                            // For movies (no videos), pick the first one that has images.
                            return validResults.maxByOrNull { (_, meta) ->
                                val videoScore = meta.videos.size
                                val imageScore = listOf(meta.poster, meta.background, meta.logo)
                                    .count { it != null }
                                videoScore * 10 + imageScore
                            }
                        }

                        // When the caller asks for anime preference (e.g. Calendar),
                        // race anime addons first so their richer season/episode data wins.
                        val animeCandidates = if (preferAnimeAddons) {
                        val animeUrls = scopedAnime.mapTo(hashSetOf()) {
                            it.baseUrl.trim().trimEnd('/').lowercase()
                        }
                            prioritizedCandidates.partition { (addon, _) ->
                                addon.baseUrl.trim().trimEnd('/').lowercase() in animeUrls
                            }.let { (anime, rest) -> anime to rest }
                        } else {
                            emptyList<Pair<Addon, String>>() to prioritizedCandidates.toList()
                        }

                        val animeBest = raceMeta(animeCandidates.first)?.let { (addon, meta) ->
                            addon to meta.copy(sourceAddonBaseUrl = addon.baseUrl)
                        }
                        val best = animeBest ?: raceMeta(animeCandidates.second)?.let { (addon, meta) ->
                            addon to meta.copy(sourceAddonBaseUrl = addon.baseUrl)
                        }

                        if (best == null) {
                            null
                        } else {
                            val (winningAddon, winningMeta) = best
                            Log.d(TAG, "Best meta selected: addonId=${winningAddon.id} name=${winningMeta.name} videos=${winningMeta.videos.size} poster=${winningMeta.poster != null} bg=${winningMeta.background != null}")
                            addonMetaCache[cacheKey] = winningMeta
                            winningMeta
                        }
                    }
                } finally {
                    inFlightAddonMeta.remove(cacheKey)
                }
            }
        }

        val meta = deferred.await()
        if (meta != null) {
            emit(NetworkResult.Success(meta))
        } else if (bypassedCachedMeta != null) {
            emit(NetworkResult.Success(bypassedCachedMeta))
        } else {
            emit(
                NetworkResult.Error(
                    buildAggregateFailureMessage(
                        type = requestedType,
                        id = id,
                        attemptedAddonNames = attemptedAddonNames.toList(),
                        failures = attemptedFailures
                    )
                )
            )
        }
    }

    override fun getMetaFromPrimaryAddon(
        type: String,
        id: String,
        namespace: String
    ): Flow<NetworkResult<Meta>> = flow {
        val ctx = context
        val cacheKey = "$namespace:$type:$id"
        primaryAddonMetaCache[cacheKey]?.let { cached ->
            emit(NetworkResult.Success(cached))
            return@flow
        }

        emit(NetworkResult.Loading)

        val regularAddons = addonRepository.getInstalledAddons().first()
        val animeAddons = animeAddonRepository.getInstalledAnimeAddons().first()
        val extraAddons = extraAddonRepository.getInstalledExtraAddons().first()
        // Strict namespace isolation: each namespace only queries its own
        // addon pool with no cross-fallbacks (Home -> TMDB, Anime -> TVDB,
        // Extra -> extra addons). "all" unions every pool for global screens
        // (Library, Search, Discover, Calendar).
        val addons = when (namespace) {
            com.nuvio.tv.domain.repository.MetaRepository.META_NAMESPACE_ANIME ->
                animeAddons
            com.nuvio.tv.domain.repository.MetaRepository.META_NAMESPACE_EXTRA ->
                extraAddons
            com.nuvio.tv.domain.repository.MetaRepository.META_NAMESPACE_ALL ->
                regularAddons + animeAddons + extraAddons
            else -> regularAddons
        }
        val requestedType = type.trim()
        val inferredType = inferCanonicalType(requestedType, id)
        val candidates = buildOrderedPrimaryCandidates(
            addons = addons,
            requestedType = requestedType,
            inferredType = inferredType
        )

        if (candidates.isEmpty()) {
            emit(NetworkResult.Error(context.getString(R.string.error_meta_no_supported_addon, requestedType)))
            return@flow
        }

        val attemptedAddonNames = mutableListOf<String>()
        val attemptedFailures = mutableListOf<MetaAttemptFailure>()
        var successMeta: Meta? = null

        for ((addon, candidateType) in candidates) {
            val attemptKey = "$cacheKey:${addon.baseUrl}"
            val url = buildMetaUrl(addon.baseUrl, candidateType, id)
            Log.d(
                TAG,
                "Trying primary meta addonId=${addon.id} addonName=${addon.name} type=$candidateType id=$id url=$url"
            )

            val deferred = inFlightPrimaryMeta.getOrPut(attemptKey) {
                repositoryScope.async {
                    try {
                        when (val result = safeApiCall(context) { api.getMeta(url) }) {
                            is NetworkResult.Success -> {
                                val metaDto = result.data.meta ?: return@async null
                                metaDto.toDomain(context.getString(R.string.episodes_episode))
                                    .copy(sourceAddonBaseUrl = addon.baseUrl)
                            }
                            else -> null
                        }
                    } finally {
                        inFlightPrimaryMeta.remove(attemptKey)
                    }
                }
            }

            val meta = deferred.await()
            if (meta != null) {
                primaryAddonMetaCache[cacheKey] = meta
                successMeta = meta
                break
            }
            attemptedAddonNames += addon.displayName
            attemptedFailures += buildMissingMetaFailure(addon)
        }

        val resultMeta = successMeta
        if (resultMeta != null) {
            emit(NetworkResult.Success(resultMeta))
        } else {
            emit(NetworkResult.Error(buildAggregateFailureMessage(
                type = requestedType,
                id = id,
                attemptedAddonNames = attemptedAddonNames,
                failures = attemptedFailures
            )))
        }
    }

    private fun buildMetaUrl(baseUrl: String, type: String, id: String): String {
        val cleanBaseUrl = baseUrl.trimEnd('/')
        val queryStart = cleanBaseUrl.indexOf('?')
        val basePath = if (queryStart >= 0) cleanBaseUrl.substring(0, queryStart).trimEnd('/') else cleanBaseUrl
        val baseQuery = if (queryStart >= 0) cleanBaseUrl.substring(queryStart) else ""
        val encodedType = encodePathSegment(type)
        val encodedId = encodePathSegment(id)
        return "$basePath/meta/$encodedType/$encodedId.json$baseQuery"
    }

    private fun Addon.supportsMetaType(type: String): Boolean {
        val normalizedType = when (type.lowercase()) {
            "series", "tv", "show", "anime", "sport", "live" -> "tv"
            else -> type.lowercase()
        }
        val originalType = type.lowercase()
        val target = normalizedType
        if (target.isBlank()) return false
        return resources.any { resource ->
            resource.name == "meta" && (resource.supportsType(target) || resource.supportsType(originalType))
        }
    }

    private fun AddonResource.supportsType(type: String): Boolean {
        if (types.isEmpty()) return true
        return types.any { it.equals(type, ignoreCase = true) }
    }

    private fun inferCanonicalType(type: String, id: String): String {
        val normalizedType = type.trim()
        val known = setOf("movie", "series", "tv", "channel", "anime")
        if (normalizedType.lowercase() in known) return normalizedType

        val normalizedId = id.lowercase()
        return when {
            ":movie:" in normalizedId -> "movie"
            ":series:" in normalizedId -> "series"
            ":tv:" in normalizedId -> "tv"
            ":anime:" in normalizedId -> "anime"
            else -> normalizedType
        }
    }

    private fun buildOrderedPrimaryCandidates(
        addons: List<Addon>,
        requestedType: String,
        inferredType: String
    ): List<Pair<Addon, String>> {
        val ordered = linkedMapOf<String, Pair<Addon, String>>()
        fun addCandidate(addon: Addon, candidateType: String) {
            ordered.putIfAbsent(addon.baseUrl.trim().trimEnd('/').lowercase(), addon to candidateType)
        }
        addons.forEach { addon ->
            if (addon.supportsMetaType(requestedType)) {
                addCandidate(addon, requestedType)
            }
        }
        if (!inferredType.equals(requestedType, ignoreCase = true)) {
            addons.forEach { addon ->
                if (addon.supportsMetaType(inferredType)) {
                    addCandidate(addon, inferredType)
                }
            }
        }
        addons.firstOrNull { addon ->
            addon.resources.any { it.name == "meta" }
        }?.let { topMetaAddon ->
            val fallbackType = when {
                topMetaAddon.supportsMetaType(requestedType) -> requestedType
                topMetaAddon.supportsMetaType(inferredType) -> inferredType
                else -> inferredType.ifBlank { requestedType }
            }
            addCandidate(topMetaAddon, fallbackType)
        }
        return ordered.values.toList()
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun buildMissingMetaFailure(addon: Addon): MetaAttemptFailure {
        return MetaAttemptFailure(
            addonName = addon.displayName,
            kind = MetaFailureKind.MISSING,
            detail = context.getString(com.nuvio.tv.R.string.meta_error_detail_no_metadata_for_id)
        )
    }

    private fun buildAddonFailure(addon: Addon, error: NetworkResult.Error): MetaAttemptFailure {
        if (error.code == 404 || error.message.equals("Not Found", ignoreCase = true)) {
            return buildMissingMetaFailure(addon)
        }
        val normalizedReason = when {
            error.message.contains("Unable to resolve host", ignoreCase = true) ->
                context.getString(com.nuvio.tv.R.string.meta_error_detail_addon_unreachable)
            error.message.contains("Failed to connect", ignoreCase = true) ->
                context.getString(com.nuvio.tv.R.string.meta_error_detail_addon_connection_failed)
            error.message.contains("timeout", ignoreCase = true) ->
                context.getString(com.nuvio.tv.R.string.meta_error_detail_addon_timeout)
            error.message.contains("CLEARTEXT communication", ignoreCase = true) ->
                context.getString(com.nuvio.tv.R.string.meta_error_detail_addon_cleartext_blocked)
            error.message.isBlank() ->
                context.getString(com.nuvio.tv.R.string.meta_error_detail_addon_request_failed)
            else -> error.message.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        }
        val httpSuffix = error.code?.let { " (HTTP $it)" } ?: ""
        return MetaAttemptFailure(
            addonName = addon.displayName,
            kind = MetaFailureKind.REQUEST_FAILED,
            detail = "$normalizedReason$httpSuffix"
        )
    }

    private fun buildAggregateFailureMessage(
        type: String,
        id: String,
        attemptedAddonNames: List<String>,
        failures: List<MetaAttemptFailure>
    ): String {
        if (attemptedAddonNames.isEmpty()) {
            return context.getString(R.string.error_meta_no_addon_for_id, id, type)
        }

        val triedAddons = attemptedAddonNames.joinToString(", ")
        val missingOnly = failures.isNotEmpty() && failures.all { it.kind == MetaFailureKind.MISSING }

        return if (missingOnly) {
            context.getString(R.string.error_meta_tried_none, triedAddons, id, type)
        } else {
            val issueSummary = failures
                .filter { it.kind == MetaFailureKind.REQUEST_FAILED }
                .distinctBy { it.addonName to it.detail }
                .take(3)
                .joinToString("; ") { "${it.addonName}: ${it.detail}" }
            if (issueSummary.isBlank()) {
                context.getString(R.string.error_meta_tried_generic, triedAddons, id, type)
            } else {
                context.getString(R.string.error_meta_tried_issues, triedAddons, id, type, issueSummary)
            }
        }
    }
    
    override fun clearCache() {
        metaCache.clear()
        addonMetaCache.clear()
        primaryAddonMetaCache.clear()
        inFlightMeta.clear()
        inFlightAddonMeta.clear()
        inFlightPrimaryMeta.clear()
    }
}
