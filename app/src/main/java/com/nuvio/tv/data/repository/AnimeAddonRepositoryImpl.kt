package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.local.AnimeAddonPreferences
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AnimeAddonRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class AnimeAddonRepositoryImpl @Inject constructor(
    private val api: AddonApi,
    private val preferences: AnimeAddonPreferences,
    @ApplicationContext private val context: Context
) : AnimeAddonRepository {

    companion object {
        private const val TAG = "AnimeAddonRepository"
        private const val MANIFEST_CACHE_PREFS = "anime_addon_manifest_cache"
        private const val MANIFEST_CACHE_KEY = "manifests_v1"
        private const val MANIFEST_SUFFIX = "/manifest.json"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = Gson()
    private val manifestCache = mutableMapOf<String, Addon>()
    private val manifestCacheLock = Any()
    private val manifestCacheRevision = MutableStateFlow(0L)

    init {
        scope.launch { loadManifestCacheFromDisk() }
    }

    private fun canonicalizeUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        val queryStart = trimmed.indexOf('?')
        val path = if (queryStart >= 0) trimmed.substring(0, queryStart) else trimmed
        val query = if (queryStart >= 0) trimmed.substring(queryStart) else ""
        val cleanPath = if (path.endsWith(MANIFEST_SUFFIX, ignoreCase = true)) {
            path.dropLast(MANIFEST_SUFFIX.length).trimEnd('/')
        } else {
            path.trimEnd('/')
        }
        return cleanPath + query
    }

    private fun normalizeUrl(url: String): String = canonicalizeUrl(url).lowercase()

    private suspend fun loadManifestCacheFromDisk() = withContext(Dispatchers.IO) {
        try {
            val prefs = context.getSharedPreferences(MANIFEST_CACHE_PREFS, Context.MODE_PRIVATE)
            val json = prefs.getString(MANIFEST_CACHE_KEY, null) ?: return@withContext
            val type = object : TypeToken<Map<String, Addon>>() {}.type
            val cached: Map<String, Addon> = gson.fromJson(json, type) ?: return@withContext
            synchronized(manifestCacheLock) {
                manifestCache.putAll(cached)
            }
            bumpManifestCacheRevision()
            Log.d(TAG, "Loaded ${cached.size} cached anime manifests from disk")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load anime manifest cache from disk", e)
        }
    }

    private fun persistManifestCacheToDisk() {
        scope.launch {
            try {
                val snapshot = synchronized(manifestCacheLock) { manifestCache.toMap() }
                val prefs = context.getSharedPreferences(MANIFEST_CACHE_PREFS, Context.MODE_PRIVATE)
                prefs.edit().putString(MANIFEST_CACHE_KEY, gson.toJson(snapshot)).apply()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist anime manifest cache to disk", e)
            }
        }
    }

    override fun getInstalledAnimeAddons(): Flow<List<Addon>> =
        combine(
            preferences.installedAnimeAddonUrls,
            preferences.animeAddonEnabledStates,
            manifestCacheRevision
        ) { urls, enabledStates, _ -> urls to enabledStates }
        .flatMapLatest { (urls, enabledStates) ->
            flow {
                if (urls.isEmpty()) {
                    emit(emptyList())
                    return@flow
                }

                val enabledByUrl = enabledStates.mapKeys { (url, _) -> canonicalizeUrl(url) }
                val cached = urls.mapNotNull { url ->
                    val canonical = canonicalizeUrl(url)
                    val enabled = enabledByUrl[canonical] ?: true
                    getCachedManifest(canonical)?.copy(enabled = enabled)
                }
                if (cached.isNotEmpty()) {
                    emit(cached)
                }

                val hasCacheMiss = urls.any { url ->
                    val canonical = canonicalizeUrl(url)
                    (enabledByUrl[canonical] ?: true) && getCachedManifest(canonical) == null
                }
                if (hasCacheMiss) {
                    val fresh = coroutineScope {
                        urls.map { url ->
                            async {
                                val canonical = canonicalizeUrl(url)
                                val enabled = enabledByUrl[canonical] ?: true
                                (getCachedManifest(canonical) ?: when (val result = fetchAnimeAddon(url)) {
                                    is NetworkResult.Success -> result.data
                                    else -> null
                                })?.copy(enabled = enabled)
                            }
                        }.awaitAll().filterNotNull()
                    }
                    if (fresh != cached) {
                        emit(fresh)
                    }
                }
            }.flowOn(Dispatchers.IO)
        }

    override suspend fun fetchAnimeAddon(baseUrl: String): NetworkResult<Addon> {
        val cleanBaseUrl = canonicalizeUrl(baseUrl)
        val queryStart = cleanBaseUrl.indexOf('?')
        val basePath = if (queryStart >= 0) cleanBaseUrl.substring(0, queryStart).trimEnd('/') else cleanBaseUrl
        val baseQuery = if (queryStart >= 0) cleanBaseUrl.substring(queryStart) else ""
        val manifestUrl = "$basePath/manifest.json$baseQuery"

        return when (val result = safeApiCall(context) { api.getManifest(manifestUrl) }) {
            is NetworkResult.Success -> {
                val addon = result.data.toDomain(cleanBaseUrl)
                if (putCachedManifestIfChanged(cleanBaseUrl, addon)) {
                    Log.d(TAG, "Updated anime addon manifest cache url=$cleanBaseUrl version=${addon.version}")
                }
                NetworkResult.Success(addon)
            }
            is NetworkResult.Error -> {
                Log.w(TAG, "Failed to fetch anime addon manifest for url=$manifestUrl code=${result.code} message=${result.message}")
                result
            }
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    override suspend fun addAnimeAddon(url: String) {
        val cleanUrl = canonicalizeUrl(url)
        preferences.addAnimeAddon(cleanUrl)
        fetchAnimeAddon(cleanUrl)
    }

    override suspend fun removeAnimeAddon(url: String) {
        val cleanUrl = canonicalizeUrl(url)
        if (removeCachedManifest(cleanUrl)) {
            persistManifestCacheToDisk()
            bumpManifestCacheRevision()
        }
        preferences.removeAnimeAddon(cleanUrl)
    }

    override suspend fun setAnimeAddonOrder(urls: List<String>) {
        preferences.setAnimeAddonOrder(urls)
    }

    override suspend fun setAnimeAddonEnabled(url: String, enabled: Boolean) {
        val cleanUrl = canonicalizeUrl(url)
        preferences.setAnimeAddonEnabled(cleanUrl, enabled)
        if (enabled && getCachedManifest(cleanUrl) == null) {
            fetchAnimeAddon(cleanUrl)
        }
    }

    override suspend fun refreshAnimeAddons() {
        val urls = preferences.currentUrls()
        if (urls.isEmpty()) return
        coroutineScope {
            urls.map { url ->
                async { fetchAnimeAddon(url) }
            }.awaitAll()
        }
        Log.d(TAG, "Refreshed ${urls.size} anime addon manifests")
    }

    suspend fun animeAddonExists(url: String): Boolean {
        val urls = preferences.currentUrls()
        val normalized = normalizeUrl(url)
        return urls.any { normalizeUrl(it) == normalized }
    }

    private fun getCachedManifest(url: String): Addon? =
        synchronized(manifestCacheLock) { manifestCache[url] }

    private fun putCachedManifestIfChanged(url: String, addon: Addon): Boolean {
        val changed = synchronized(manifestCacheLock) {
            val existing = manifestCache[url]
            if (existing == null || hasManifestChanged(existing, addon)) {
                manifestCache[url] = addon
                true
            } else {
                false
            }
        }
        if (changed) {
            persistManifestCacheToDisk()
            bumpManifestCacheRevision()
        }
        return changed
    }

    private fun removeCachedManifest(url: String): Boolean =
        synchronized(manifestCacheLock) {
            manifestCache.remove(url) != null
        }

    private fun bumpManifestCacheRevision() {
        manifestCacheRevision.value = manifestCacheRevision.value + 1
    }

    private fun hasManifestChanged(existing: Addon, incoming: Addon): Boolean =
        existing.id != incoming.id ||
            existing.name != incoming.name ||
            existing.version != incoming.version ||
            existing.description != incoming.description ||
            existing.logo != incoming.logo ||
            existing.background != incoming.background ||
            existing.baseUrl != incoming.baseUrl ||
            existing.catalogs != incoming.catalogs ||
            existing.types != incoming.types ||
            existing.rawTypes != incoming.rawTypes ||
            existing.resources != incoming.resources ||
            existing.idPrefixes != incoming.idPrefixes ||
            existing.behaviorHints != incoming.behaviorHints ||
            existing.stremioAddonsConfig != incoming.stremioAddonsConfig ||
            existing.manifestLanguage != incoming.manifestLanguage ||
            existing.configVersion != incoming.configVersion
}
