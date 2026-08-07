package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import kotlinx.coroutines.flow.Flow

/**
 * Manages a separate set of "anime" addons (Stremio-style manifests) that feed
 * the Anime tab independently of the general Home addons.
 */
interface AnimeAddonRepository {
    fun getInstalledAnimeAddons(): Flow<List<Addon>>
    suspend fun fetchAnimeAddon(baseUrl: String): NetworkResult<Addon>
    suspend fun addAnimeAddon(url: String)
    suspend fun removeAnimeAddon(url: String)
    suspend fun setAnimeAddonOrder(urls: List<String>)
    suspend fun setAnimeAddonEnabled(url: String, enabled: Boolean)
    suspend fun refreshAnimeAddons()
}
