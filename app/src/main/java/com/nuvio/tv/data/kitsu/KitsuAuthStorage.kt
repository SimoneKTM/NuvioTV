package com.nuvio.tv.data.kitsu

import kotlinx.coroutines.flow.StateFlow

data class KitsuAuthScope(
    val profileId: Int,
    val generation: Long
)

data class KitsuAuthorization(
    val scope: KitsuAuthScope,
    val accessToken: String,
    val expiresAtEpochMs: Long? = null
)

interface KitsuAuthStorage {
    val state: StateFlow<KitsuAuthState>

    fun currentScope(): KitsuAuthScope
    fun isCurrent(scope: KitsuAuthScope): Boolean
    fun authorization(): KitsuAuthorization?
    fun accessToken(): String? = authorization()?.accessToken

    fun beginLoading(): Boolean
    fun cancelLoading(): Boolean
    fun completeTokenAuthorization(token: String, scope: KitsuAuthScope = currentScope()): Boolean
    fun saveIdentity(
        username: String?,
        userId: Long?,
        tokenExpiresAtEpochMs: Long? = null,
        scope: KitsuAuthScope = currentScope()
    ): Boolean
    fun clearAuth(
        error: KitsuAuthError? = null,
        scope: KitsuAuthScope = currentScope(),
        expectedAccessToken: String? = null
    ): Boolean
    fun removeProfile(profileId: Int)
    fun clearAllProfiles()
}