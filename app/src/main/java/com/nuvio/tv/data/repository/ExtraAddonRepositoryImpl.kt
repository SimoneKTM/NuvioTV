package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.local.ExtraAddonPreferences
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.ExtraAddonRepository
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
class ExtraAddonRepositoryImpl @Inject constructor(
    private val api: AddonApi,
    private val preferences: ExtraAddonPreferences,
    @ApplicationContext private val context: Context
) : ExtraAddonRepository {

    companion object {
        private const val TAG = "ExtraAddonRepository"
        private const val MANIFEST_CACHE_PREFS = "extra_addon_manifest_cache"
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
            Log.d(TAG, "Loaded ${cached.size} cached extra manifests from disk")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load extra manifest cache from disk", e)
        }
    }

    private fun persistManifestCacheToDisk() {
        scope.launch {
            try {
                val snapshot = synchronized(manifestCacheLock) { manifestCache.toMap() }
                val prefs = context.getSharedPreferences(MANIFEST_CACHE_PREFS, Context.MODE_PRIVATE)
                prefs.edit().putString(MANIFEST_CACHE_KEY, gson.toJson(snapshot)).apply()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist extra manifest cache to disk", e)
            }
        }
    }

    override fun getInstalledExtraAddons(): Flow<List<Addon>> =
        combine(
            preferences.installedExtraAddonUrls,
            preferences.extraAddonEnabledStates,
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
                        ?: if (!enabled) placeholderAddon(canonical, enabled) else null
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
                                val manifest = getCachedManifest(canonical)
                                    ?: when (val result = fetchExtraAddon(url)) {
                                        is NetworkResult.Success -> result.data
                                        else -> null
                                    }
                                // Unreachable addons stay visible via a placeholder so they
                                // can still be disabled/removed from the extra manager.
                                manifest?.copy(enabled = enabled)
                                    ?: placeholderAddon(canonical, enabled)
                            }
                        }.awaitAll().filterNotNull()
                    }
                    if (fresh != cached) {
                        emit(fresh)
                    } else if (cached.isEmpty()) {
                        emit(emptyList())
                    }
                } else if (cached.isEmpty()) {
                    emit(emptyList())
                }
            }.flowOn(Dispatchers.IO)
        }

    override suspend fun fetchExtraAddon(baseUrl: String): NetworkResult<Addon> {
        val cleanBaseUrl = canonicalizeUrl(baseUrl)
        val queryStart = cleanBaseUrl.indexOf('?')
        val basePath = if (queryStart >= 0) cleanBaseUrl.substring(0, queryStart).trimEnd('/') else cleanBaseUrl
        val baseQuery = if (queryStart >= 0) cleanBaseUrl.substring(queryStart) else ""
        val manifestUrl = "$basePath/manifest.json$baseQuery"

        return when (val result = safeApiCall(context) { api.getManifest(manifestUrl) }) {
            is NetworkResult.Success -> {
                val addon = result.data.toDomain(cleanBaseUrl)
                if (putCachedManifestIfChanged(cleanBaseUrl, addon)) {
                    Log.d(TAG, "Updated extra addon manifest cache url=$cleanBaseUrl version=${addon.version}")
                }
                NetworkResult.Success(addon)
            }
            is NetworkResult.Error -> {
                Log.w(TAG, "Failed to fetch extra addon manifest for url=$manifestUrl code=${result.code} message=${result.message}")
                result
            }
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    override suspend fun addExtraAddon(url: String) {
        val cleanUrl = canonicalizeUrl(url)
        preferences.addExtraAddon(cleanUrl)
        fetchExtraAddon(cleanUrl)
    }

    override suspend fun removeExtraAddon(url: String) {
        val cleanUrl = canonicalizeUrl(url)
        if (removeCachedManifest(cleanUrl)) {
            persistManifestCacheToDisk()
            bumpManifestCacheRevision()
        }
        preferences.removeExtraAddon(cleanUrl)
    }

    override suspend fun setExtraAddonOrder(urls: List<String>) {
        preferences.setExtraAddonOrder(urls)
    }

    override suspend fun setExtraAddonEnabled(url: String, enabled: Boolean) {
        val cleanUrl = canonicalizeUrl(url)
        preferences.setExtraAddonEnabled(cleanUrl, enabled)
        if (enabled && getCachedManifest(cleanUrl) == null) {
            fetchExtraAddon(cleanUrl)
        }
    }

    override suspend fun refreshExtraAddons(): Int {
        val urls = preferences.currentUrls()
        if (urls.isEmpty()) return 0
        val results = coroutineScope {
            urls.map { url ->
                async { fetchExtraAddon(url) }
            }.awaitAll()
        }
        val successCount = results.count { it is NetworkResult.Success }
        Log.d(TAG, "Refreshed $successCount/${urls.size} extra addon manifests")
        if (successCount == 0) {
            throw IllegalStateException("All extra addon manifest fetches failed")
        }
        return successCount
    }

    suspend fun extraAddonExists(url: String): Boolean {
        val urls = preferences.currentUrls()
        val normalized = normalizeUrl(url)
        return urls.any { normalizeUrl(it) == normalized }
    }

    private fun getCachedManifest(url: String): Addon? =
        synchronized(manifestCacheLock) { manifestCache[url] }

    private fun placeholderAddon(url: String, enabled: Boolean): Addon {
        val canonical = canonicalizeUrl(url)
        val displayName = canonical.substringBefore("?").substringAfterLast("/").ifBlank { canonical }
        return Addon(
            id = canonical,
            name = displayName,
            displayName = displayName,
            version = "",
            description = null,
            logo = null,
            baseUrl = canonical,
            catalogs = emptyList(),
            types = emptyList(),
            rawTypes = emptyList(),
            resources = emptyList(),
            enabled = enabled
        )
    }

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
