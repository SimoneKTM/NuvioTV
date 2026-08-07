package com.nuvio.tv.data.kitsu

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KitsuAuthRepository @Inject constructor(
    private val apiClient: KitsuApi,
    private val configuration: KitsuApiConfiguration,
    private val storage: KitsuAuthStorage
) {
    private val mutex = Mutex()

    val state: StateFlow<KitsuAuthState> = storage.state

    fun hasRequiredCredentials(): Boolean = configuration.clientId.isNotBlank()

    fun authorizeUrl(): String? = if (hasRequiredCredentials()) {
        buildKitsuAuthorizeUrl(configuration)
    } else {
        null
    }

    fun beginAuthorization(): Boolean = storage.beginLoading()

    fun cancelAuthorization() {
        storage.cancelLoading()
    }

    /**
     * Completes the OAuth flow with the value the user pasted from the Kitsu authorization page.
     * Accepts a completed auth-code callback URL, an implicit access-token URL, or a bare access
     * token. The token is validated against the current-user endpoint and, when valid, persisted
     * together with the identified profile.
     */
    suspend fun connectToken(rawToken: String): KitsuConnectResult = mutex.withLock {
        if (!hasRequiredCredentials()) {
            return@withLock KitsuConnectResult.Failed(KitsuAuthError.MISSING_CLIENT_ID)
        }
        val payload = runCatching { apiClient.parseAuthorizePayload(rawToken) }.getOrNull()
            ?: return@withLock KitsuConnectResult.Failed(KitsuAuthError.INVALID_TOKEN)
        val code = payload.code?.trim()?.takeIf(String::isNotBlank)
        val pastedToken = payload.accessToken?.trim()?.takeIf(String::isNotBlank)
        if (code == null && pastedToken == null) {
            return@withLock KitsuConnectResult.Failed(KitsuAuthError.INVALID_TOKEN)
        }
        storage.beginLoading()
        var currentAccessToken: String? = null
        return@withLock try {
            val activeToken = if (code != null) {
                val exchanged = apiClient.exchangeCodeForToken(code)
                exchanged?.accessToken?.trim()?.takeIf(String::isNotBlank)
                    ?: run {
                        storage.cancelLoading()
                        return@withLock KitsuConnectResult.Failed(KitsuAuthError.INVALID_TOKEN)
                    }
            } else {
                pastedToken!!
            }
            currentAccessToken = activeToken
            val authScope = storage.currentScope()
            if (!storage.completeTokenAuthorization(activeToken, authScope)) {
                storage.cancelLoading()
                return@withLock KitsuConnectResult.Failed(KitsuAuthError.INVALID_TOKEN)
            }
            val expiresAt = payload.expiresInSeconds
                ?.takeIf { it > 0L }
                ?.let { seconds -> System.currentTimeMillis() + seconds * 1_000L }
            val user = apiClient.fetchCurrentUser(activeToken)
            if (user == null || user.attributes == null) {
                storage.clearAuth(
                    error = KitsuAuthError.INVALID_TOKEN,
                    scope = authScope,
                    expectedAccessToken = activeToken
                )
                return@withLock KitsuConnectResult.Failed(KitsuAuthError.INVALID_TOKEN)
            }
            storage.saveIdentity(
                username = user.attributes?.name
                    ?: user.attributes?.slug,
                userId = user.id?.toLongOrNull(),
                tokenExpiresAtEpochMs = expiresAt,
                scope = authScope
            )
            KitsuConnectResult.Connected
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            storage.clearAuth(
                error = KitsuAuthError.NETWORK,
                scope = storage.currentScope(),
                expectedAccessToken = currentAccessToken
            )
            KitsuConnectResult.Failed(KitsuAuthError.NETWORK)
        }
    }

    fun disconnect() {
        storage.clearAuth()
    }
}