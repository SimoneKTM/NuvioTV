package com.nuvio.tv.data.mal

import com.nuvio.tv.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

const val MAL_DEFAULT_REDIRECT_URI = "nuvio://auth/mal"
const val MAL_SCOPE = "read+write:users:read+write:anime"

data class MalApiConfiguration(
    val clientId: String,
    val redirectUri: String,
    val clientSecret: String = "",
    val baseUrl: String = "https://api.myanimelist.net/v2",
    val authUrl: String = "https://myanimelist.net/v1/oauth2"
)

fun defaultMalApiConfiguration(): MalApiConfiguration = MalApiConfiguration(
    clientId = BuildConfig.MAL_CLIENT_ID,
    redirectUri = BuildConfig.MAL_REDIRECT_URI.ifBlank { MAL_DEFAULT_REDIRECT_URI },
    clientSecret = BuildConfig.MAL_CLIENT_SECRET
)

fun buildMalAuthorizeUrl(
    configuration: MalApiConfiguration,
    codeChallenge: String,
    state: String
): String = "${configuration.authUrl}/authorize".toHttpUrl().newBuilder()
    .addQueryParameter("response_type", "code")
    .addQueryParameter("client_id", configuration.clientId)
    .addQueryParameter("redirect_uri", configuration.redirectUri)
    .addQueryParameter("state", state)
    .addQueryParameter("code_challenge", codeChallenge)
    .addQueryParameter("code_challenge_method", "plain")
    .addQueryParameter("scope", MAL_SCOPE)
    .build()
    .toString()

data class MalHttpResponse(
    val status: Int,
    val body: String
)

class MalApiException(
    val status: Int?,
    override val message: String,
    cause: Throwable? = null
) : Exception(message, cause)

fun interface MalHttpEngine {
    suspend fun execute(request: MalHttpRequest): MalHttpResponse
}

data class MalHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String = "",
    val form: Boolean = false
)

class OkHttpMalEngine(private val client: OkHttpClient) : MalHttpEngine {
    override suspend fun execute(request: MalHttpRequest): MalHttpResponse =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(request.url)
            request.headers.forEach(builder::header)
            val reqBody = if (request.body.isNotEmpty()) {
                val mediaType = if (request.form) FORM_MEDIA_TYPE else JSON_MEDIA_TYPE
                request.body.toRequestBody(mediaType)
            } else {
                null
            }
            builder.method(request.method, reqBody)
            client.newCall(builder.build()).execute().use { response ->
                MalHttpResponse(
                    status = response.code,
                    body = response.body?.string().orEmpty()
                )
            }
        }

    private companion object {
        val FORM_MEDIA_TYPE = "application/x-www-form-urlencoded".toMediaType()
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

data class MalAuthorizePayload(
    val code: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresInSeconds: Long? = null
)

class MalApi(
    private val configuration: MalApiConfiguration,
    private val engine: MalHttpEngine,
    private val accessToken: () -> String?,
    private val onUnauthorized: () -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Volatile
    private var lastRequestAtEpochMs = 0L

    fun hasRequiredCredentials(): Boolean = configuration.clientId.isNotBlank()

    private fun publicHeaders(): Map<String, String> = mapOf(
        "X-MAL-CLIENT-ID" to configuration.clientId
    )

    /** Searches anime by name using the public X-MAL-CLIENT-ID endpoint (no token needed). */
    suspend fun searchAnime(
        query: String,
        limit: Int = 10,
        offset: Int = 0
    ): MalSearchResponse {
        val endpoint = "anime".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("fields", PUBLIC_FIELDS)
            .build()
            .toString()
        val response = executePublic(endpoint = endpoint)
        return runCatching { json.decodeFromString<MalSearchResponse>(response) }
            .getOrElse { MalSearchResponse() }
    }

    /** Fetches details for a single anime using the public endpoint (no token needed). */
    suspend fun getAnimeDetails(animeId: Long): MalSearchAnime? {
        val response = executePublic(endpoint = "anime/$animeId?fields=$DETAILS_FIELDS")
        return runCatching { json.decodeFromString<MalSearchAnime>(response) }.getOrNull()
    }

    /** Fetches the anime ranking using the public endpoint (no token needed). */
    suspend fun getRanking(
        rankingType: String = "all",
        limit: Int = 10,
        offset: Int = 0
    ): MalRankingResponse {
        val url = buildString {
            append("anime/ranking?ranking_type=").append(rankingType)
            append("&limit=").append(limit)
            append("&offset=").append(offset)
            append("&fields=").append(PUBLIC_FIELDS)
        }
        val response = executePublic(endpoint = url)
        return runCatching { json.decodeFromString<MalRankingResponse>(response) }
            .getOrElse { MalRankingResponse() }
    }

    private suspend fun executePublic(endpoint: String): String {
        var attempt = 0
        while (true) {
            attempt++
            val response = try {
                lastRequestAtEpochMs = System.currentTimeMillis()
                engine.execute(
                    MalHttpRequest(
                        method = "GET",
                        url = "${configuration.baseUrl}/$endpoint",
                        headers = publicHeaders() + mapOf("Accept" to "application/json"),
                        body = ""
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw MalApiException(null, "MyAnimeList request failed", error)
                }
                delay(retryDelayMs(attempt, isRateLimit = false))
                continue
            }
            when (response.status) {
                in 200..299 -> return response.body
                in 408..429 -> {
                    if (attempt >= MAX_ATTEMPTS) {
                        throw MalApiException(response.status, "MyAnimeList request failed with HTTP ${response.status}")
                    }
                    delay(retryDelayMs(attempt, isRateLimit = response.status == 429))
                }
                else -> throw MalApiException(
                    response.status,
                    malErrorMessage(response.body) ?: "MyAnimeList request failed with HTTP ${response.status}"
                )
            }
        }
    }

    fun authorizeUrl(codeChallenge: String, state: String): String? =
        if (hasRequiredCredentials()) buildMalAuthorizeUrl(configuration, codeChallenge, state) else null

    /**
     * Returns the [MalAuthorizePayload] parsed from whatever the user pasted. Accepts a completed
     * auth-code URL (`...?code=...`), the raw JSON token response, or a bare access token.
     */
    fun parseAuthorizePayload(rawToken: String): MalAuthorizePayload {
        val trimmed = rawToken.trim()
        val parsedJson = runCatching { json.decodeFromString<MalTokenResponse>(trimmed) }.getOrNull()
        if (parsedJson != null) {
            return MalAuthorizePayload(
                accessToken = parsedJson.accessToken,
                refreshToken = parsedJson.refreshToken,
                expiresInSeconds = parsedJson.expiresIn
            )
        }
        val code = extractQueryValue(trimmed, "code")
        if (!code.isNullOrBlank()) {
            return MalAuthorizePayload(code = code)
        }
        val implicitToken = extractQueryValue(trimmed, "access_token")
        if (!implicitToken.isNullOrBlank()) {
            return MalAuthorizePayload(accessToken = implicitToken)
        }
        return MalAuthorizePayload(accessToken = trimmed)
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

    /** Exchanges an authorization code for a bearer access token using the PKCE verifier. */
    internal suspend fun exchangeCodeForToken(code: String, codeVerifier: String): MalTokenResponse? {
        val body = buildString {
            append("grant_type=authorization_code")
            append("&client_id=").append(configuration.clientId)
            append("&code=").append(code)
            append("&code_verifier=").append(codeVerifier)
            append("&redirect_uri=").append(configuration.redirectUri)
            if (configuration.clientSecret.isNotBlank()) {
                append("&client_secret=").append(configuration.clientSecret)
            }
        }
        return executeTokenRequest(body)
    }

    /** Refreshes an expired access token using the stored refresh token. */
    internal suspend fun refreshAccessToken(refreshToken: String): MalTokenResponse? {
        val body = buildString {
            append("grant_type=refresh_token")
            append("&client_id=").append(configuration.clientId)
            append("&refresh_token=").append(refreshToken)
            if (configuration.clientSecret.isNotBlank()) {
                append("&client_secret=").append(configuration.clientSecret)
            }
        }
        return executeTokenRequest(body)
    }

    private suspend fun executeTokenRequest(body: String): MalTokenResponse? {
        return try {
            val response = engine.execute(
                MalHttpRequest(
                    method = "POST",
                    url = "${configuration.authUrl}/token",
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Content-Type" to "application/x-www-form-urlencoded"
                    ),
                    body = body,
                    form = true
                )
            )
            if (response.status !in 200..299) return null
            runCatching { json.decodeFromString<MalTokenResponse>(response.body) }.getOrNull()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            null
        }
    }

    suspend fun fetchCurrentUser(token: String): MalUserResponse? {
        val response = executeAuthenticated(
            method = "GET",
            endpoint = "users/@me",
            token = token
        ) ?: return null
        return runCatching { json.decodeFromString<MalUserResponse>(response) }
            .getOrNull()
    }

    internal suspend fun fetchMyAnimeList(
        token: String,
        userName: String,
        limit: Int = LIBRARY_PAGE_LIMIT,
        offset: Int = 0
    ): MalUserAnimeListResponse {
        val url = buildString {
            append("users/$userName/animelist")
            append("?limit=").append(limit)
            append("&offset=").append(offset)
            append("&fields=").append(LIBRARY_FIELDS)
        }
        val response = executeAuthenticated(method = "GET", endpoint = url, token = token)
        return runCatching { json.decodeFromString<MalUserAnimeListResponse>(response ?: "") }
            .getOrElse { MalUserAnimeListResponse() }
    }

    /** Updates (or creates) the entry for the anime on the user's MyAnimeList. */
    suspend fun updateMyAnimeListStatus(
        token: String,
        animeId: Long,
        status: String,
        score: Int? = null,
        numWatchedEpisodes: Int? = null
    ): Boolean {
        val body = buildString {
            append("status=").append(status)
            numWatchedEpisodes?.let { append("&num_watched_episodes=$it") }
            score?.takeIf { it > 0 }?.let { append("&score=$it") }
        }
        return executeMutation(
            method = "PATCH",
            endpoint = "anime/$animeId/my_list_status",
            body = body
        )
    }

    /** Deletes the entry for the anime from the user's MyAnimeList. */
    suspend fun deleteMyAnimeListEntry(token: String, animeId: Long): Boolean =
        executeMutation(
            method = "DELETE",
            endpoint = "anime/$animeId/my_list_status",
            body = ""
        )

    /**
     * Executes a MyAnimeList REST request, enforcing a minimum interval between requests so the
     * API rate limits are respected. Returns the raw JSON body on success or throws the
     * [MalApiException] when the request cannot be completed.
     */
    private suspend fun executeAuthenticated(
        method: String,
        endpoint: String,
        token: String? = accessToken(),
        payload: String = ""
    ): String? {
        val authorization = token?.takeIf(String::isNotBlank)
            ?: throw MalApiException(401, "MyAnimeList authentication is required")
        ensureRateLimitInterval()
        var attempt = 0
        while (true) {
            attempt++
            val response = try {
                lastRequestAtEpochMs = System.currentTimeMillis()
                engine.execute(
                    MalHttpRequest(
                        method = method,
                        url = "${configuration.baseUrl}/$endpoint",
                        headers = mapOf(
                            "Authorization" to "Bearer $authorization",
                            "Accept" to "application/json",
                            "Content-Type" to "application/x-www-form-urlencoded"
                        ),
                        body = payload
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw MalApiException(null, "MyAnimeList request failed", error)
                }
                delay(retryDelayMs(attempt, isRateLimit = false))
                continue
            }
            when (response.status) {
                in 200..299 -> return response.body
                401 -> {
                    onUnauthorized()
                    throw MalApiException(401, "MyAnimeList authentication failed")
                }
                in 408..429 -> {
                    if (attempt >= MAX_ATTEMPTS) {
                        throw MalApiException(response.status, "MyAnimeList request failed with HTTP ${response.status}")
                    }
                    delay(retryDelayMs(attempt, isRateLimit = response.status == 429))
                }
                else -> throw MalApiException(
                    response.status,
                    malErrorMessage(response.body) ?: "MyAnimeList request failed with HTTP ${response.status}"
                )
            }
        }
    }

    private suspend fun executeMutation(
        method: String,
        endpoint: String,
        body: String
    ): Boolean {
        ensureRateLimitInterval()
        var attempt = 0
        while (true) {
            attempt++
            val response = try {
                lastRequestAtEpochMs = System.currentTimeMillis()
                engine.execute(
                    MalHttpRequest(
                        method = method,
                        url = "${configuration.baseUrl}/$endpoint",
                        headers = mapOf(
                            "Authorization" to "Bearer ${accessToken().orEmpty()}",
                            "Accept" to "application/json",
                            "Content-Type" to "application/x-www-form-urlencoded"
                        ),
                        body = body,
                        form = true
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

    private fun malErrorMessage(body: String): String? =
        runCatching { json.decodeFromString<MalTokenErrorResponse>(body) }
            .getOrNull()
            ?.error
            ?.takeIf(String::isNotBlank)

    private companion object {
        const val LIBRARY_PAGE_LIMIT = 1_000
        const val LIBRARY_FIELDS = "id,title,main_picture,num_episodes,status,media_type,start_date,list_status"
        const val PUBLIC_FIELDS = "id,title,main_picture,synopsis,mean,rank,popularity,num_episodes,status,media_type,genres,start_date"
        const val DETAILS_FIELDS = "id,title,main_picture,synopsis,mean,rank,popularity,num_episodes,status,media_type,genres,start_date,average_episode_duration,studios"
        const val MIN_REQUEST_INTERVAL_MS = 800L
        const val RATE_LIMIT_BASE_DELAY_MS = 5_000L
        const val MAX_RETRY_DELAY_MS = 60_000L
        const val MAX_ATTEMPTS = 4
    }
}