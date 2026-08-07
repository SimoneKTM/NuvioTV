package com.nuvio.tv.data.kitsu

import com.nuvio.tv.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

const val KITSU_AUTHORIZE_URL = "https://kitsu.app/api/oauth/authorize"
const val KITSU_OAUTH_TOKEN_URL = "https://kitsu.app/api/oauth/token"

data class KitsuApiConfiguration(
    val clientId: String,
    val redirectUri: String,
    val clientSecret: String = "",
    val baseUrl: String = "https://kitsu.app/api/edge"
)

fun defaultKitsuApiConfiguration(): KitsuApiConfiguration = KitsuApiConfiguration(
    clientId = BuildConfig.KITSU_CLIENT_ID,
    redirectUri = BuildConfig.KITSU_REDIRECT_URI,
    clientSecret = BuildConfig.KITSU_CLIENT_SECRET
)

fun buildKitsuAuthorizeUrl(configuration: KitsuApiConfiguration): String =
    KITSU_AUTHORIZE_URL.toHttpUrl().newBuilder()
        .addQueryParameter("client_id", configuration.clientId)
        .addQueryParameter("redirect_uri", configuration.redirectUri)
        .addQueryParameter("response_type", "code")
        .build()
        .toString()

data class KitsuHttpResponse(
    val status: Int,
    val body: String
)

class KitsuApiException(
    val status: Int?,
    override val message: String,
    cause: Throwable? = null
) : Exception(message, cause)

fun interface KitsuHttpEngine {
    suspend fun execute(request: KitsuHttpRequest): KitsuHttpResponse
}

data class KitsuHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String = ""
)

class OkHttpKitsuEngine(private val client: OkHttpClient) : KitsuHttpEngine {
    override suspend fun execute(request: KitsuHttpRequest): KitsuHttpResponse =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(request.url)
            request.headers.forEach(builder::header)
            val reqBody = if (request.body.isNotEmpty()) {
                request.body.toRequestBody(JSON_API_MEDIA_TYPE)
            } else {
                null
            }
            builder.method(request.method, reqBody)
            client.newCall(builder.build()).execute().use { response ->
                KitsuHttpResponse(
                    status = response.code,
                    body = response.body?.string().orEmpty()
                )
            }
        }

    private companion object {
        val JSON_API_MEDIA_TYPE = "application/vnd.api+json".toMediaType()
    }
}

class KitsuApi(
    private val configuration: KitsuApiConfiguration,
    private val engine: KitsuHttpEngine,
    private val accessToken: () -> String?,
    private val onUnauthorized: () -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Volatile
    private var lastRequestAtEpochMs = 0L

    fun authorizeUrl(): String? = if (hasRequiredCredentials()) buildKitsuAuthorizeUrl(configuration) else null

    fun hasRequiredCredentials(): Boolean = configuration.clientId.isNotBlank()

    /**
     * Returns the [KitsuAuthorizePayload] parsed from whatever the user pasted. Accepts either a
     * completed auth-code URL (`.../callback?code=...`), an implicit access-token URL
     * (`...#access_token=...`), or a bare access token.
     */
    fun parseAuthorizePayload(rawToken: String): KitsuAuthorizePayload {
        val trimmed = rawToken.trim()
        val accessToken = extractQueryValue(trimmed, "access_token")
        if (!accessToken.isNullOrBlank()) {
            return KitsuAuthorizePayload(
                accessToken = accessToken,
                expiresInSeconds = extractQueryValue(trimmed, "expires_in")?.toLongOrNull()
            )
        }
        val code = extractQueryValue(trimmed, "code")
        if (!code.isNullOrBlank()) {
            return KitsuAuthorizePayload(code = code)
        }
        return KitsuAuthorizePayload(accessToken = trimmed)
    }

    private fun extractQueryValue(raw: String, key: String): String? {
        val tokenMarker = "${key}="
        val index = raw.indexOf(tokenMarker)
        if (index < 0) return null
        var end = raw.indexOf('&', index)
        val fragment = raw.indexOf('#')
        if (end < 0 || (fragment in index..end)) end = fragment
        if (end < 0) end = raw.length
        return raw.substring(index + tokenMarker.length, end).trim()
            .takeIf(String::isNotEmpty)
    }

    /** Exchanges an authorization code for a bearer access token. */
    suspend fun exchangeCodeForToken(code: String): KitsuOAuthTokenResponse? {
        val body = buildString {
            append("grant_type=authorization_code")
            append("&client_id=").append(configuration.clientId)
            if (configuration.clientSecret.isNotBlank()) {
                append("&client_secret=").append(configuration.clientSecret)
            }
            append("&redirect_uri=").append(configuration.redirectUri)
            append("&code=").append(code)
        }
        return try {
            val response = engine.execute(
                KitsuHttpRequest(
                    method = "POST",
                    url = KITSU_OAUTH_TOKEN_URL,
                    headers = mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                    body = body
                )
            )
            if (response.status !in 200..299) return null
            runCatching { json.decodeFromString<KitsuOAuthTokenResponse>(response.body) }.getOrNull()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            null
        }
    }

    suspend fun fetchCurrentUser(token: String): KitsuUserData? {
        val response = executeAuthenticated(
            method = "GET",
            endpoint = "users?filter[self]=true",
            token = token
        ) ?: return null
        return runCatching { json.decodeFromString<KitsuUserResponse>(response) }
            .getOrNull()
            ?.data
            ?.firstOrNull()
    }

    suspend fun fetchLibraryEntries(
        token: String,
        userId: Long,
        status: KitsuMediaListStatus? = null,
        pageLimit: Int = LIBRARY_PAGE_LIMIT,
        pageOffset: Int = 0
    ): KitsuLibraryEntriesResponse {
        val url = buildString {
            append("library-entries?filter[user_id]=").append(userId)
            append("&include=anime")
            append("&page[limit]=").append(pageLimit)
            append("&page[offset]=").append(pageOffset)
            status?.let { st -> append("&filter[status]=").append(st.wireValue) }
        }
        val response = executeAuthenticated(method = "GET", endpoint = url, token = token)
        return runCatching { json.decodeFromString<KitsuLibraryEntriesResponse>(response ?: "") }
            .getOrElse { KitsuLibraryEntriesResponse() }
    }

    suspend fun saveLibraryEntry(
        token: String,
        kitsuMediaId: Long,
        userId: Long,
        status: String,
        progress: Int,
        rating: Double? = null
    ): Boolean {
        val attributes = KitsuLibraryEntryAttributes(
            status = status,
            progress = progress,
            rating = rating
        )
        val body = json.encodeToString(
            KitsuCreateLibraryEntryRequest(
                data = KitsuCreateLibraryEntryData(
                    attributes = attributes,
                    relationships = KitsuCreateLibraryRelationships(
                        media = KitsuRelationship(
                            data = KitsuRelationshipData(id = kitsuMediaId.toString(), type = "anime")
                        ),
                        user = KitsuRelationship(
                            data = KitsuRelationshipData(id = userId.toString(), type = "users")
                        )
                    )
                )
            )
        )
        return executeMutation(
            method = "POST",
            endpoint = "library-entries",
            payload = body
        )
    }

    suspend fun updateLibraryEntry(
        token: String,
        entryId: String,
        status: String? = null,
        progress: Int? = null,
        rating: Double? = null
    ): Boolean {
        val attributes = KitsuLibraryEntryAttributes(
            status = status,
            progress = progress,
            rating = rating
        )
        val body = json.encodeToString(
            KitsuPatchLibraryEntryRequest(
                data = KitsuPatchLibraryEntryData(
                    id = entryId,
                    attributes = attributes
                )
            )
        )
        return executeAuthenticated(
            method = "PATCH",
            endpoint = "library-entries/$entryId",
            payload = body
        ) != null
    }

    suspend fun deleteLibraryEntry(entryId: String): Boolean =
        executeAuthenticated(
            method = "DELETE",
            endpoint = "library-entries/$entryId"
        ) != null

    /**
     * Executes a Kitsu JSON:API request, enforcing a minimum interval between requests so the
     * API rate limits are respected. Returns the raw JSON body on success or the thrown
     * [KitsuApiException] when the request cannot be completed.
     */
    private suspend fun executeAuthenticated(
        method: String,
        endpoint: String,
        token: String? = accessToken(),
        payload: String? = null
    ): String? {
        val authorization = token?.takeIf(String::isNotBlank)
            ?: throw KitsuApiException(401, "Kitsu authentication is required")
        ensureRateLimitInterval()
        var attempt = 0
        while (true) {
            attempt++
            val response = try {
                lastRequestAtEpochMs = System.currentTimeMillis()
                engine.execute(
                    KitsuHttpRequest(
                        method = method,
                        url = "${configuration.baseUrl}/$endpoint",
                        headers = mapOf(
                            "Authorization" to "Bearer $authorization",
                            "Accept" to "application/vnd.api+json",
                            "Content-Type" to "application/vnd.api+json"
                        ),
                        body = payload.orEmpty()
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw KitsuApiException(null, "Kitsu request failed", error)
                }
                delay(retryDelayMs(attempt, isRateLimit = false))
                continue
            }
            when (response.status) {
                in 200..299 -> return response.body
                401 -> {
                    onUnauthorized()
                    throw KitsuApiException(401, "Kitsu authentication failed")
                }
                in 408..429 -> {
                    if (attempt >= MAX_ATTEMPTS) {
                        throw KitsuApiException(response.status, "Kitsu request failed with HTTP ${response.status}")
                    }
                    delay(retryDelayMs(attempt, isRateLimit = response.status == 429))
                }
                else -> throw KitsuApiException(response.status, kitsuErrorMessage(response.body) ?: "Kitsu request failed with HTTP ${response.status}")
            }
        }
    }

    private suspend fun executeMutation(
        method: String,
        endpoint: String,
        payload: String
    ): Boolean {
        ensureRateLimitInterval()
        var attempt = 0
        while (true) {
            attempt++
            val response = try {
                lastRequestAtEpochMs = System.currentTimeMillis()
                engine.execute(
                    KitsuHttpRequest(
                        method = method,
                        url = "${configuration.baseUrl}/$endpoint",
                        headers = mapOf(
                            "Authorization" to "Bearer ${accessToken().orEmpty()}",
                            "Accept" to "application/vnd.api+json",
                            "Content-Type" to "application/vnd.api+json"
                        ),
                        body = payload
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (attempt >= MAX_ATTEMPTS) return false
                delay(retryDelayMs(attempt, isRateLimit = false))
                continue
            }
            when (response.status) {
                in 200..299 -> return true
                401 -> {
                    onUnauthorized()
                    return false
                }
                422 -> return false
                else -> {
                    if (attempt >= MAX_ATTEMPTS) return false
                    delay(retryDelayMs(attempt, isRateLimit = response.status == 429))
                }
            }
        }
    }

    private suspend fun ensureRateLimitInterval() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRequestAtEpochMs
        if (elapsed < MIN_REQUEST_INTERVAL_MS) {
            delay(MIN_REQUEST_INTERVAL_MS - elapsed)
        }
    }

    private fun retryDelayMs(attempt: Int, isRateLimit: Boolean): Long {
        val baseMs = if (isRateLimit) RATE_LIMIT_BASE_DELAY_MS else 1_000L
        return (baseMs * attempt).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private fun kitsuErrorMessage(body: String): String? =
        runCatching { json.decodeFromString<KitsuErrorResponse>(body) }
            .getOrNull()
            ?.errors
            ?.firstOrNull()
            ?.title
            ?.takeIf(String::isNotBlank)
            ?: runCatching { json.decodeFromString<KitsuErrorResponse>(body) }
                .getOrNull()
                ?.errors
                ?.firstOrNull()
                ?.detail
                ?.takeIf(String::isNotBlank)

    private companion object {
        const val LIBRARY_PAGE_LIMIT = 500
        const val MIN_REQUEST_INTERVAL_MS = 800L
        const val RATE_LIMIT_BASE_DELAY_MS = 5_000L
        const val MAX_RETRY_DELAY_MS = 60_000L
        const val MAX_ATTEMPTS = 4
    }
}

data class KitsuAuthorizePayload(
    val accessToken: String? = null,
    val code: String? = null,
    val expiresInSeconds: Long? = null
)

@kotlinx.serialization.Serializable
data class KitsuOAuthTokenResponse(
    @kotlinx.serialization.SerialName("access_token") val accessToken: String,
    @kotlinx.serialization.SerialName("expires_in") val expiresIn: Long? = null
)