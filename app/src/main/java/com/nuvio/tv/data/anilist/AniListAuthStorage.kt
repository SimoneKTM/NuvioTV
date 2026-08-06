package com.nuvio.tv.data.anilist

import kotlinx.coroutines.flow.StateFlow

data class AniListAuthScope(
    val profileId: Int,
    val generation: Long
)

data class AniListAuthorization(
    val scope: AniListAuthScope,
    val accessToken: String,
    val expiresAtEpochMs: Long? = null
)

interface AniListAuthStorage {
    val state: StateFlow<AniListAuthState>

    fun currentScope(): AniListAuthScope
    fun isCurrent(scope: AniListAuthScope): Boolean
    fun authorization(): AniListAuthorization?
    fun accessToken(): String? = authorization()?.accessToken

    fun beginLoading(): Boolean
    fun cancelLoading(): Boolean
    fun completeTokenAuthorization(token: String, scope: AniListAuthScope = currentScope()): Boolean
    fun saveIdentity(
        username: String?,
        userId: Long?,
        tokenExpiresAtEpochMs: Long? = null,
        scope: AniListAuthScope = currentScope()
    ): Boolean
    fun clearAuth(
        error: AniListAuthError? = null,
        scope: AniListAuthScope = currentScope(),
        expectedAccessToken: String? = null
    ): Boolean
    fun removeProfile(profileId: Int)
    fun clearAllProfiles()
}