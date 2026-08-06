package com.nuvio.tv.data.anilist

import kotlinx.serialization.Serializable

enum class AniListConnectionMode {
    DISCONNECTED,
    LOADING,
    CONNECTED
}

enum class AniListAuthError {
    MISSING_CLIENT_ID,
    INVALID_TOKEN,
    AUTHORIZATION_REVOKED,
    NETWORK
}

data class AniListAuthState(
    val isAuthenticated: Boolean = false,
    val isLoading: Boolean = false,
    val username: String? = null,
    val userId: Long? = null,
    val tokenExpiresAtEpochMs: Long? = null,
    val error: AniListAuthError? = null
) {
    val mode: AniListConnectionMode
        get() = when {
            isAuthenticated -> AniListConnectionMode.CONNECTED
            isLoading -> AniListConnectionMode.LOADING
            else -> AniListConnectionMode.DISCONNECTED
        }
}

sealed interface AniListConnectResult {
    data object Connected : AniListConnectResult
    data class Failed(val error: AniListAuthError) : AniListConnectResult
}

class AniListAuthException(val error: AniListAuthError, cause: Throwable? = null) :
    Exception(error.name, cause)

@Serializable
internal data class AniListStoredAuthMetadata(
    val username: String? = null,
    val userId: Long? = null,
    val tokenExpiresAtEpochMs: Long? = null
)