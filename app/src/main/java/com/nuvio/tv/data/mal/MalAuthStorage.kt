package com.nuvio.tv.data.mal

import kotlinx.coroutines.flow.StateFlow

data class MalAuthScope(
    val profileId: Int,
    val generation: Long
)

data class MalAuthorization(
    val scope: MalAuthScope,
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochMs: Long? = null
)

interface MalAuthStorage {
    val state: StateFlow<MalAuthState>

    fun currentScope(): MalAuthScope
    fun isCurrent(scope: MalAuthScope): Boolean
    fun authorization(): MalAuthorization?
    fun accessToken(): String? = authorization()?.accessToken
    fun refreshToken(): String? = authorization()?.refreshToken

    fun beginLoading(): Boolean
    fun cancelLoading(): Boolean
    fun completeAuthorization(
        token: String,
        refreshToken: String? = null,
        tokenExpiresAtEpochMs: Long? = null,
        scope: MalAuthScope = currentScope()
    ): Boolean
    fun saveIdentity(
        username: String?,
        userId: Long?,
        tokenExpiresAtEpochMs: Long? = null,
        scope: MalAuthScope = currentScope()
    ): Boolean
    fun clearAuth(
        error: MalAuthError? = null,
        scope: MalAuthScope = currentScope(),
        expectedAccessToken: String? = null
    ): Boolean
    fun removeProfile(profileId: Int)
    fun clearAllProfiles()
}