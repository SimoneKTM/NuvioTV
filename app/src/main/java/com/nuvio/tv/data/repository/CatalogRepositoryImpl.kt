package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.AnimeAddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.ExtraAddonRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val addonRepository: AddonRepository,
    private val animeAddonRepository: AnimeAddonRepository,
    private val extraAddonRepository: ExtraAddonRepository,
    private val profileManager: ProfileManager
) : CatalogRepository {
    companion object {
        private const val TAG = "CatalogRepository"
        private const val FIRST_PAGE_CACHE_TTL_MS = 3 * 60 * 1000L
        private const val WARM_CONCURRENCY = 4
    }

    private data class CacheEntry(
        val row: CatalogRow,
        val cachedAtMs: Long
    )

    private val warmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var warmJob: Job? = null
    private var profileWatchJob: Job? = null
    private val catalogCache = ConcurrentHashMap<String, CacheEntry>()

    init {
        profileWatchJob = warmScope.launch {
            var lastProfileId = profileManager.activeProfileId.value
            profileManager.activeProfileId.collect { profileId ->
                if (profileId != lastProfileId) {
                    lastProfileId = profileId
                    catalogCache.clear()
                    Log.d(TAG, "Active profile changed to $profileId — catalog cache cleared, rewarming")
                    warmJob?.cancel()
                    warmJob = null
                    warmUp()
                }
            }
        }
    }

    override fun warmUp() {
        if (warmJob?.isActive == true) return
        warmJob = warmScope.launch {
            try {
                profileManager.activeProfileReady.first { it }
                val regular = addonRepository.getInstalledAddons().first().enabledAddons()
                val anime = animeAddonRepository.getInstalledAnimeAddons().first().filter { it.enabled }
                val extra = extraAddonRepository.getInstalledExtraAddons().first().filter { it.enabled }

                val targets = buildList {
                    addAll(warmTargets(regular))
                    addAll(warmTargets(anime))
                    addAll(warmTargets(extra))
                }
                if (targets.isEmpty()) {
                    Log.d(TAG, "Catalog warm-up: no show-in-home catalogs")
                    return@launch
                }
                Log.d(TAG, "Catalog warm-up: ${targets.size} first-page catalogs (home=${regular.size} anime=${anime.size} extra=${extra.size})")

                val semaphore = Semaphore(WARM_CONCURRENCY)
                targets.forEach { (addon, catalog) ->
                    launch {
                        semaphore.withPermit {
                            warmCatalogPage(addon, catalog)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Catalog warm-up failed: ${e.message}")
            }
        }
    }

    override fun clearCache() {
        catalogCache.clear()
    }

    private fun warmTargets(addons: List<Addon>): List<Pair<Addon, CatalogDescriptor>> =
        addons.flatMap { addon ->
            addon.catalogs
                .filter { catalog -> shouldWarmCatalog(catalog) }
                .map { catalog -> addon to catalog }
        }

    private fun shouldWarmCatalog(catalog: CatalogDescriptor): Boolean {
        val isSearchOnly = catalog.extra.any {
            it.name.equals("search", ignoreCase = true) && it.isRequired
        }
        if (isSearchOnly) return false
        return !catalog.hasExplicitShowInHome || catalog.showInHome
    }

    private suspend fun warmCatalogPage(addon: Addon, catalog: CatalogDescriptor) {
        val url = buildCatalogUrl(
            baseUrl = addon.baseUrl,
            type = catalog.apiType,
            catalogId = catalog.id,
            skip = 0,
            extraArgs = emptyMap()
        )
        if (catalogCache.containsKey(url)) return
        when (val result = safeApiCall(context) { api.getCatalog(url) }) {
            is NetworkResult.Success -> {
                val row = result.data.metas
                    .map { it.toDomain(catalog.apiType, addon.baseUrl) }
                    .distinctBy { it.id }
                    .let { items ->
                        CatalogRow(
                            addonId = addon.id,
                            addonName = addon.displayName,
                            addonBaseUrl = addon.baseUrl,
                            catalogId = catalog.id,
                            catalogName = catalog.name,
                            type = ContentType.fromString(catalog.apiType),
                            rawType = catalog.apiType,
                            items = items,
                            isLoading = false,
                            hasMore = catalog.supportsExtra("skip") && items.isNotEmpty(),
                            currentPage = 0,
                            supportsSkip = catalog.supportsExtra("skip"),
                            skipStep = catalog.skipStep(),
                            nextSkip = if (catalog.supportsExtra("skip") && items.isNotEmpty()) items.size else 0,
                            extraArgs = emptyMap()
                        )
                    }
                catalogCache[url] = CacheEntry(row, System.currentTimeMillis())
                Log.d(TAG, "Warmed catalog addonId=${addon.id} type=${catalog.apiType} catalogId=${catalog.id} items=${row.items.size}")
            }
            is NetworkResult.Error -> {
                Log.d(TAG, "Warm skipped addonId=${addon.id} catalogId=${catalog.id} code=${result.code}")
            }
            NetworkResult.Loading -> Unit
        }
    }

    override fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean
    ): Flow<NetworkResult<CatalogRow>> = flow {
        emit(NetworkResult.Loading)

        val url = buildCatalogUrl(addonBaseUrl, type, catalogId, skip, extraArgs)
        val cacheable = skip == 0 && extraArgs.isEmpty()

        if (cacheable) {
            catalogCache[url]?.let { entry ->
                val age = System.currentTimeMillis() - entry.cachedAtMs
                if (age in 0 until FIRST_PAGE_CACHE_TTL_MS) {
                    Log.d(TAG, "Catalog cache hit addonId=$addonId type=$type catalogId=$catalogId ageMs=$age")
                    emit(NetworkResult.Success(entry.row))
                    return@flow
                }
            }
        }

        Log.d(
            TAG,
            "Fetching catalog addonId=$addonId addonName=$addonName type=$type catalogId=$catalogId skip=$skip skipStep=$skipStep supportsSkip=$supportsSkip url=$url"
        )

        when (val result = safeApiCall(context) { api.getCatalog(url) }) {
            is NetworkResult.Success -> {
                val items = result.data.metas.map { it.toDomain(type, addonBaseUrl) }.distinctBy { it.id }
                Log.d(
                    TAG,
                    "Catalog fetch success addonId=$addonId type=$type catalogId=$catalogId items=${items.size}"
                )

                val catalogRow = CatalogRow(
                    addonId = addonId,
                    addonName = addonName,
                    addonBaseUrl = addonBaseUrl,
                    catalogId = catalogId,
                    catalogName = catalogName,
                    type = ContentType.fromString(type),
                    rawType = type,
                    items = items,
                    isLoading = false,
                    hasMore = supportsSkip && items.isNotEmpty(),
                    currentPage = if (skipStep > 0) skip / skipStep else 0,
                    supportsSkip = supportsSkip,
                    skipStep = skipStep,
                    nextSkip = if (supportsSkip && items.isNotEmpty()) skip + items.size else skip,
                    extraArgs = extraArgs
                )
                if (cacheable) {
                    catalogCache[url] = CacheEntry(catalogRow, System.currentTimeMillis())
                }
                emit(NetworkResult.Success(catalogRow))
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "Catalog fetch failed addonId=$addonId type=$type catalogId=$catalogId code=${result.code} message=${result.message} url=$url"
                )
                // Serve a stale first page if the network is down but we warmed earlier.
                if (cacheable) {
                    catalogCache[url]?.let { entry ->
                        Log.d(TAG, "Catalog cache fallback addonId=$addonId catalogId=$catalogId")
                        emit(NetworkResult.Success(entry.row))
                        return@flow
                    }
                }
                emit(result)
            }
            NetworkResult.Loading -> { /* Already emitted */ }
        }
    }

    private fun buildCatalogUrl(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int,
        extraArgs: Map<String, String>
    ): String {
        val trimmedBase = baseUrl.trimEnd('/')
        val queryStart = trimmedBase.indexOf('?')
        val basePath = if (queryStart >= 0) trimmedBase.substring(0, queryStart).trimEnd('/') else trimmedBase
        val baseQuery = if (queryStart >= 0) trimmedBase.substring(queryStart) else ""

        val catalogPath = if (extraArgs.isEmpty()) {
            if (skip > 0) {
                "$basePath/catalog/$type/$catalogId/skip=$skip.json"
            } else {
                "$basePath/catalog/$type/$catalogId.json"
            }
        } else {
            val allArgs = LinkedHashMap<String, String>()
            allArgs.putAll(extraArgs)

            if (!allArgs.containsKey("skip") && skip > 0) {
                allArgs["skip"] = skip.toString()
            }

            val encodedArgs = allArgs.entries.joinToString("&") { (key, value) ->
                "${encodeArg(key)}=${encodeArg(value)}"
            }

            "$basePath/catalog/$type/$catalogId/$encodedArgs.json"
        }

        return catalogPath + baseQuery
    }

    private fun encodeArg(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
