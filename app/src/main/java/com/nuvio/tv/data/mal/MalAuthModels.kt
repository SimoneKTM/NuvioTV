package com.nuvio.tv.data.mal

import kotlinx.serialization.Serializable

enum class MalConnectionMode {
    DISCONNECTED,
    LOADING,
    CONNECTED
}

enum class MalAuthError {
    MISSING_CLIENT_ID,
    INVALID_TOKEN,
    AUTHORIZATION_REVOKED,
    NETWORK
}

data class MalAuthState(
    val isAuthenticated: Boolean = false,
    val isLoading: Boolean = false,
    val username: String? = null,
    val userId: Long? = null,
    val tokenExpiresAtEpochMs: Long? = null,
    val error: MalAuthError? = null
) {
    val mode: MalConnectionMode
        get() = when {
            isAuthenticated -> MalConnectionMode.CONNECTED
            isLoading -> MalConnectionMode.LOADING
            else -> MalConnectionMode.DISCONNECTED
        }
}

sealed interface MalConnectResult {
    data object Connected : MalConnectResult
    data class Failed(val error: MalAuthError) : MalConnectResult
}

class MalAuthException(val error: MalAuthError, cause: Throwable? = null) :
    Exception(error.name, cause)

@Serializable
internal data class MalStoredAuthMetadata(
    val username: String? = null,
    val userId: Long? = null,
    val tokenExpiresAtEpochMs: Long? = null
)