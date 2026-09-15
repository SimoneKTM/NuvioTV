package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import kotlinx.coroutines.flow.Flow

/**
 * Manages a separate set of "extra" addons (Stremio-style manifests) that feed
 * the Anime tab independently of the general Home addons.
 */
interface ExtraAddonRepository {
    fun getInstalledExtraAddons(): Flow<List<Addon>>
    suspend fun fetchExtraAddon(baseUrl: String): NetworkResult<Addon>
    suspend fun addExtraAddon(url: String)
    suspend fun removeExtraAddon(url: String)
    suspend fun setExtraAddonOrder(urls: List<String>)
    suspend fun setExtraAddonEnabled(url: String, enabled: Boolean)
    /**
     * Refetches all installed extra addon manifests and refreshes the cache.
     * Returns the number of manifests that were successfully refreshed.
     * Throws if none of the fetches succeeded.
     */
    suspend fun refreshExtraAddons(): Int
}
