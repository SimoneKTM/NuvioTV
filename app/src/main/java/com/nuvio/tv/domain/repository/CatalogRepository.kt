package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.CatalogRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface CatalogRepository {
    fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int = 0,
        skipStep: Int = 100,
        extraArgs: Map<String, String> = emptyMap(),
        supportsSkip: Boolean = false
    ): Flow<NetworkResult<CatalogRow>>

    /** Preloads first-page Home/Anime/Extra catalogs into the in-memory cache. */
    fun warmUp()

    /**
     * True once the current warm-up pass has finished (or there is nothing to warm).
     * Resets to false when the active profile changes and warm-up restarts.
     */
    val warmComplete: StateFlow<Boolean>

    fun clearCache()
}
