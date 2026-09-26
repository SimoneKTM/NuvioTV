package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Meta
import kotlinx.coroutines.flow.Flow

interface MetaRepository {
    fun getMeta(
        addonBaseUrl: String,
        type: String,
        id: String,
        namespace: String = META_NAMESPACE_HOME
    ): Flow<NetworkResult<Meta>>

    fun getMetaFromAllAddons(
        type: String,
        id: String,
        sourceAddonBaseUrl: String? = null,
        rawId: String? = null,
        namespace: String = META_NAMESPACE_HOME,
        preferAnimeAddons: Boolean = false,
        originalId: String? = null
    ): Flow<NetworkResult<Meta>>

    fun getMetaFromPrimaryAddon(
        type: String,
        id: String,
        namespace: String = META_NAMESPACE_HOME
    ): Flow<NetworkResult<Meta>>

    fun clearCache()

    companion object {
        const val META_NAMESPACE_HOME = "home"
        const val META_NAMESPACE_ANIME = "anime"
        const val META_NAMESPACE_EXTRA = "extra"
        /** Global namespace: searches regular + anime + extra addon pools. */
        const val META_NAMESPACE_ALL = "all"
    }
}
