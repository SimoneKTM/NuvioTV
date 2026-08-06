package com.nuvio.tv.data.anilist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AniListAuthRepository @Inject constructor(
    private val apiClient: AniListApi,
    private val configuration: AniListApiConfiguration,
    private val storage: AniListAuthStorage
) {
    private val mutex = Mutex()

    val state: StateFlow<AniListAuthState> = storage.state

    fun hasRequiredCredentials(): Boolean = configuration.clientId.isNotBlank()

    fun authorizeUrl(): String? = if (hasRequiredCredentials()) {
        buildAniListAuthorizeUrl(configuration)
    } else {
        null
    }

    fun beginAuthorization(): Boolean = storage.beginLoading()

    fun cancelAuthorization() {
        storage.cancelLoading()
    }

    /**
     * Completes the implicit-grant flow with the access token the user pasted from the
     * AniList authorization page. The token is validated against the Viewer query and, when
     * valid, persisted together with the identified profile.
     */
    suspend fun connectToken(rawToken: String): AniListConnectResult = mutex.withLock {
        val token = rawToken.trim().takeIf(String::isNotBlank)
            ?: return@withLock AniListConnectResult.Failed(AniListAuthError.INVALID_TOKEN)
        if (!hasRequiredCredentials()) {
            return@withLock AniListConnectResult.Failed(AniListAuthError.MISSING_CLIENT_ID)
        }
        storage.beginLoading()
        return@withLock try {
            val authScope = storage.currentScope()
            if (!storage.completeTokenAuthorization(token, authScope)) {
                storage.cancelLoading()
                return@withLock AniListConnectResult.Failed(AniListAuthError.INVALID_TOKEN)
            }
            val viewer = apiClient.fetchViewer(token)
            if (viewer == null) {
                storage.clearAuth(
                    error = AniListAuthError.INVALID_TOKEN,
                    scope = authScope,
                    expectedAccessToken = token
                )
                return@withLock AniListConnectResult.Failed(AniListAuthError.INVALID_TOKEN)
            }
            storage.saveIdentity(
                username = viewer.name,
                userId = viewer.id,
                scope = authScope
            )
            AniListConnectResult.Connected
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            storage.clearAuth(
                error = AniListAuthError.NETWORK,
                scope = storage.currentScope(),
                expectedAccessToken = token
            )
            AniListConnectResult.Failed(AniListAuthError.NETWORK)
        }
    }

    fun disconnect() {
        storage.clearAuth()
    }
}