package com.nuvio.tv.data.mal

import android.util.Base64
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MalAuthRepository @Inject constructor(
    private val apiClient: MalApi,
    private val configuration: MalApiConfiguration,
    private val storage: MalAuthStorage
) {
    private val mutex = Mutex()

    @Volatile
    private var codeChallenge: String? = null

    val state: StateFlow<MalAuthState> = storage.state

    fun hasRequiredCredentials(): Boolean = configuration.clientId.isNotBlank()

    /**
     * Builds the MyAnimeList authorization URL while storing the PKCE challenge (used as the
     * plain code verifier) so the pasted authorization code can be exchanged later.
     */
    fun buildAuthorizeUrl(): String? = if (hasRequiredCredentials()) {
        val verifier = generateOAuthValue(64)
        codeChallenge = verifier
        apiClient.authorizeUrl(codeChallenge = verifier, state = generateOAuthValue(16))
    } else {
        null
    }

    fun beginAuthorization(): Boolean = storage.beginLoading()

    fun cancelAuthorization() {
        storage.cancelLoading()
        codeChallenge = null
    }

    /**
     * Completes the authorization flow with the value the user pasted from the MyAnimeList
     * authorization page. Accepts a completed auth-code callback URL, the raw JSON token response,
     * or a bare access token. The token is validated against the /users/@me endpoint and, when
     * valid, persisted together with the identified profile.
     */
    suspend fun connectToken(rawToken: String): MalConnectResult = mutex.withLock {
        if (!hasRequiredCredentials()) {
            return@withLock MalConnectResult.Failed(MalAuthError.MISSING_CLIENT_ID)
        }
        val payload = runCatching { apiClient.parseAuthorizePayload(rawToken) }.getOrNull()
            ?: return@withLock MalConnectResult.Failed(MalAuthError.INVALID_TOKEN)
        val code = payload.code?.trim()?.takeIf(String::isNotBlank)
        val pastedToken = payload.accessToken?.trim()?.takeIf(String::isNotBlank)
        if (code == null && pastedToken == null) {
            return@withLock MalConnectResult.Failed(MalAuthError.INVALID_TOKEN)
        }
        storage.beginLoading()
        var currentAccessToken: String? = null
        return@withLock try {
            val activeToken = if (code != null) {
                val verifier = codeChallenge
                if (verifier.isNullOrBlank()) {
                    storage.cancelLoading()
                    return@withLock MalConnectResult.Failed(MalAuthError.INVALID_TOKEN)
                }
                val exchanged = apiClient.exchangeCodeForToken(code, verifier)
                exchanged?.accessToken?.trim()?.takeIf(String::isNotBlank)
                    ?: run {
                        storage.cancelLoading()
                        return@withLock MalConnectResult.Failed(MalAuthError.INVALID_TOKEN)
                    }
            } else {
                pastedToken!!
            }
            currentAccessToken = activeToken
            val authScope = storage.currentScope()
            val expiresAt = payload.expiresInSeconds
                ?.takeIf { it > 0L }
                ?.let { seconds -> System.currentTimeMillis() + seconds * 1_000L }
            if (!storage.completeAuthorization(
                    token = activeToken,
                    refreshToken = payload.refreshToken,
                    tokenExpiresAtEpochMs = expiresAt,
                    scope = authScope
                )
            ) {
                storage.cancelLoading()
                return@withLock MalConnectResult.Failed(MalAuthError.INVALID_TOKEN)
            }
            val user = apiClient.fetchCurrentUser(activeToken)
            if (user == null || user.id == null) {
                storage.clearAuth(
                    error = MalAuthError.INVALID_TOKEN,
                    scope = authScope,
                    expectedAccessToken = activeToken
                )
                return@withLock MalConnectResult.Failed(MalAuthError.INVALID_TOKEN)
            }
            storage.saveIdentity(
                username = user.name,
                userId = user.id,
                tokenExpiresAtEpochMs = expiresAt,
                scope = authScope
            )
            codeChallenge = null
            MalConnectResult.Connected
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            storage.clearAuth(
                error = MalAuthError.NETWORK,
                scope = storage.currentScope(),
                expectedAccessToken = currentAccessToken
            )
            MalConnectResult.Failed(MalAuthError.NETWORK)
        }
    }

    /**
     * Returns the current access token, refreshing the stored credentials first when the token is
     * about to expire and a refresh token is available.
     */
    suspend fun currentAccessToken(): String? = mutex.withLock {
        refreshTokenIfNeeded()
        storage.accessToken()
    }

    private suspend fun refreshTokenIfNeeded(): Boolean {
        val authorization = storage.authorization() ?: return false
        val refreshToken = authorization.refreshToken?.takeIf(String::isNotBlank)
        if (refreshToken == null) {
            return authorization.accessToken.isNotBlank()
        }
        val expiresAt = authorization.expiresAtEpochMs
        val needsRefresh = expiresAt?.let {
            System.currentTimeMillis() >= it - REFRESH_EARLY_MARGIN_MS
        } ?: false
        if (!needsRefresh) return true
        val exchanged = try {
            apiClient.refreshAccessToken(refreshToken)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            null
        } ?: return false
        val scope = storage.currentScope()
        val newExpiry = exchanged.expiresIn
            ?.takeIf { it > 0L }
            ?.let { seconds -> System.currentTimeMillis() + seconds * 1_000L }
        return storage.completeAuthorization(
            token = exchanged.accessToken,
            refreshToken = exchanged.refreshToken,
            tokenExpiresAtEpochMs = newExpiry,
            scope = scope
        )
    }

    fun disconnect() {
        codeChallenge = null
        storage.clearAuth()
    }

    private fun generateOAuthValue(minLength: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = SecureRandom()
        val parts = mutableListOf<String>()
        var length = 0
        while (length < minLength) {
            val bytes = ByteArray(8)
            random.nextBytes(bytes)
            val token = Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
            parts.add(token)
            length += token.length
        }
        return parts.joinToString("").substring(0, minLength)
    }

    private companion object {
        const val REFRESH_EARLY_MARGIN_MS = 60 * 1_000L
    }
}