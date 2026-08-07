package com.nuvio.tv.data.kitsu

import kotlinx.serialization.Serializable

enum class KitsuConnectionMode {
    DISCONNECTED,
    LOADING,
    CONNECTED
}

enum class KitsuAuthError {
    MISSING_CLIENT_ID,
    INVALID_TOKEN,
    AUTHORIZATION_REVOKED,
    NETWORK
}

data class KitsuAuthState(
    val isAuthenticated: Boolean = false,
    val isLoading: Boolean = false,
    val username: String? = null,
    val userId: Long? = null,
    val tokenExpiresAtEpochMs: Long? = null,
    val error: KitsuAuthError? = null
) {
    val mode: KitsuConnectionMode
        get() = when {
            isAuthenticated -> KitsuConnectionMode.CONNECTED
            isLoading -> KitsuConnectionMode.LOADING
            else -> KitsuConnectionMode.DISCONNECTED
        }
}

sealed interface KitsuConnectResult {
    data object Connected : KitsuConnectResult
    data class Failed(val error: KitsuAuthError) : KitsuConnectResult
}

class KitsuAuthException(val error: KitsuAuthError, cause: Throwable? = null) :
    Exception(error.name, cause)

@Serializable
internal data class KitsuStoredAuthMetadata(
    val username: String? = null,
    val userId: Long? = null,
    val tokenExpiresAtEpochMs: Long? = null
)