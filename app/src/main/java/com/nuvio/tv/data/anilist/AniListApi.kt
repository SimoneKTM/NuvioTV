package com.nuvio.tv.data.anilist

import com.nuvio.tv.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

const val ANILIST_AUTHORIZE_URL = "https://anilist.co/api/v2/oauth/authorize"
const val ANILIST_OOB_REDIRECT_URI = "urn:ietf:wg:oauth:2.0:oob"

data class AniListApiConfiguration(
    val clientId: String,
    val redirectUri: String,
    val baseUrl: String = "https://graphql.anilist.co"
)

fun defaultAniListApiConfiguration(): AniListApiConfiguration = AniListApiConfiguration(
    clientId = BuildConfig.ANILIST_CLIENT_ID,
    redirectUri = BuildConfig.ANILIST_REDIRECT_URI.ifBlank { ANILIST_OOB_REDIRECT_URI }
)

fun buildAniListAuthorizeUrl(configuration: AniListApiConfiguration): String =
    ANILIST_AUTHORIZE_URL.toHttpUrl().newBuilder()
        .addQueryParameter("client_id", configuration.clientId)
        .addQueryParameter("response_type", "token")
        .addQueryParameter("redirect_uri", configuration.redirectUri)
        .build()
        .toString()

data class AniListHttpResponse(
    val status: Int,
    val body: String
)

@Serializable
internal data class AniListTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("expires_in") val expiresIn: Long? = null
)

data class AniListAuthorizePayload(
    val accessToken: String,
    val expiresInSeconds: Long? = null
)

class AniListApiException(
    val status: Int?,
    override val message: String,
    cause: Throwable? = null
) : Exception(message, cause)

fun interface AniListHttpEngine {
    suspend fun post(request: AniListHttpRequest): AniListHttpResponse
}

data class AniListHttpRequest(
    val url: String,
    val headers: Map<String, String>,
    val body: String
)

class OkHttpAniListEngine(private val client: OkHttpClient) : AniListHttpEngine {
    override suspend fun post(request: AniListHttpRequest): AniListHttpResponse =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder().url(request.url)
            request.headers.forEach(builder::header)
            val requestBody = request.body.toRequestBody(JSON_MEDIA_TYPE)
            client.newCall(builder.post(requestBody).build()).execute().use { response ->
                AniListHttpResponse(
                    status = response.code,
                    body = response.body?.string().orEmpty()
                )
            }
        }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

class AniListApi(
    private val configuration: AniListApiConfiguration,
    private val engine: AniListHttpEngine,
    private val accessToken: () -> String?,
    private val onUnauthorized: () -> Unit
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Volatile
    private var lastGraphQLAtEpochMs = 0L

    fun authorizeUrl(): String = buildAniListAuthorizeUrl(configuration)

    fun hasRequiredCredentials(): Boolean = configuration.clientId.isNotBlank()

    /**
     * Returns the [AniListAuthorizePayload] parsed from whatever the user pasted or the QR login
     * relay delivered. Accepts the raw JSON token response, an implicit access-token URL
     * (`...#access_token=...&expires_in=...`), or a bare access token.
     */
    fun parseAuthorizePayload(rawToken: String): AniListAuthorizePayload {
        val trimmed = rawToken.trim()
        val parsedJson = runCatching { json.decodeFromString<AniListTokenResponse>(trimmed) }.getOrNull()
        if (parsedJson != null && parsedJson.accessToken.isNotBlank()) {
            return AniListAuthorizePayload(
                accessToken = parsedJson.accessToken,
                expiresInSeconds = parsedJson.expiresIn
            )
        }
        val implicitToken = extractQueryValue(trimmed, "access_token")
        if (!implicitToken.isNullOrBlank()) {
            return AniListAuthorizePayload(
                accessToken = implicitToken,
                expiresInSeconds = extractQueryValue(trimmed, "expires_in")?.toLongOrNull()
            )
        }
        return AniListAuthorizePayload(accessToken = trimmed)
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

    suspend fun fetchViewer(token: String): AniListViewer? {
        val query = """
            query {
                Viewer {
                    id
                    name
                    avatar {
                        large
                        medium
                    }
                }
            }
        """.trimIndent()
        val response = executeGraphQL(query = query, variables = null, token = token)
            ?: return null
        return runCatching { json.decodeFromString<AniListUserResponse>(response) }
            .getOrNull()
            ?.data
            ?.viewer
    }

    suspend fun fetchMediaListCollection(userId: Long, token: String): List<AniListLibraryItem> {
        val query = """
            query (${'$'}userId: Int, ${'$'}type: MediaType) {
                MediaListCollection(userId: ${'$'}userId, type: ${'$'}type) {
                    lists {
                        status
                        entries {
                            id
                            status
                            progress
                            score
                            updatedAt
                            media {
                                id
                                idMal
                                title {
                                    english
                                    romaji
                                    userPreferred
                                }
                                episodes
                                format
                                bannerImage
                                coverImage {
                                    extraLarge
                                    large
                                    medium
                                }
                                startDate {
                                    year
                                    month
                                    day
                                }
                            }
                        }
                    }
                }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            put("userId", userId)
            put("type", AniListMediaType.ANIME.wireValue)
        }
        val response = executeGraphQL(query, variables, token)
            ?: return emptyList()
        val parsed = runCatching { json.decodeFromString<AniListCollectionResponse>(response) }
            .getOrNull()
        val lists = parsed?.data?.collection?.lists.orEmpty()
        return lists.flatMap { group ->
            val groupStatus = AniListMediaListStatus.fromWireValue(group.status) ?: return@flatMap emptyList()
            group.entries.orEmpty().mapNotNull { entry ->
                val media = entry.media ?: return@mapNotNull null
                val entryStatus = AniListMediaListStatus.fromWireValue(entry.status) ?: groupStatus
                AniListLibraryItem(
                    id = media.id,
                    entryId = entry.id,
                    title = media.title?.userPreferred
                        ?: media.title?.english
                        ?: media.title?.romaji
                        ?: media.id.toString(),
                    posterUrl = media.coverImage?.extraLarge
                        ?: media.coverImage?.large
                        ?: media.coverImage?.medium,
                    bannerUrl = media.bannerImage?.takeIf(String::isNotBlank),
                    totalEpisodes = media.episodes,
                    score = entry.score,
                    status = entryStatus,
                    updatedAt = entry.updatedAt,
                    format = media.format
                )
            }
        }
    }

    suspend fun saveMediaListEntry(
        mediaId: Long,
        status: AniListMediaListStatus,
        progress: Int,
        score: Int?
    ): Boolean {
        val query = """
            mutation (${'$'}mediaId: Int, ${'$'}status: MediaListStatus, ${'$'}progress: Int, ${'$'}scoreRaw: Int) {
                SaveMediaListEntry(mediaId: ${'$'}mediaId, status: ${'$'}status, progress: ${'$'}progress, scoreRaw: ${'$'}scoreRaw) {
                    id
                    status
                    progress
                }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            put("mediaId", mediaId)
            put("status", status.wireValue)
            put("progress", progress)
            score?.takeIf { value -> value > 0 }?.let { put("scoreRaw", it) }
        }
        return executeGraphQL(query, variables) != null
    }

    /** Deletes an AniList MediaListEntry (the library entry), removing watched progress as well. */
    suspend fun deleteMediaListEntry(entryId: Long): Boolean {
        val query = """
            mutation (${'$'}id: Int) {
                DeleteMediaListEntry(id: ${'$'}id) {
                    deleted
                }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            put("id", entryId)
        }
        return executeGraphQL(query, variables) != null
    }

    /**
     * Executes a GraphQL request against the AniList API, enforcing a minimum interval between
     * requests so the API rate limits are respected. Returns the raw JSON body on success or null
     * when the request cannot be completed (including authentication failures).
     */
    private suspend fun executeGraphQL(
        query: String,
        variables: JsonObject?,
        token: String? = accessToken()
    ): String? {
        val authorization = token?.takeIf(String::isNotBlank)
            ?: throw AniListApiException(401, "AniList authentication is required")
        ensureRateRequestInterval()
        val payload = json.encodeToString(
            AniListGraphQLRequest(query = query, variables = variables)
        )
        var attempt = 0
        while (true) {
            attempt++
            val response = try {
                lastGraphQLAtEpochMs = System.currentTimeMillis()
                engine.post(
                    AniListHttpRequest(
                        url = configuration.baseUrl,
                        headers = mapOf(
                            "Accept" to "application/json",
                            "Content-Type" to "application/json",
                            "Authorization" to "Bearer $authorization"
                        ),
                        body = payload
                    )
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw AniListApiException(null, "AniList request failed", error)
                }
                delay(retryDelayMs(attempt, isRateLimit = false))
                continue
            }
            when (response.status) {
                in 200..299 -> return response.body
                401 -> {
                    onUnauthorized()
                    throw AniListApiException(401, "AniList authentication failed")
                }
                else -> {
                    if (attempt >= MAX_ATTEMPTS) {
                        val message = graphQLErrorMessage(response.body)
                            ?: "AniList request failed with HTTP ${response.status}"
                        throw AniListApiException(response.status, message)
                    }
                    delay(retryDelayMs(attempt, isRateLimit = response.status == 429))
                }
            }
        }
    }

    private suspend fun ensureRateRequestInterval() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastGraphQLAtEpochMs
        if (elapsed < MIN_REQUEST_INTERVAL_MS) {
            delay(MIN_REQUEST_INTERVAL_MS - elapsed)
        }
    }

    private fun retryDelayMs(attempt: Int, isRateLimit: Boolean): Long {
        val baseMs = if (isRateLimit) RATE_LIMIT_BASE_DELAY_MS else 1_000L
        return (baseMs * attempt).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private fun graphQLErrorMessage(body: String): String? =
        runCatching { json.decodeFromString<AniListErrorEnvelope>(body) }
            .getOrNull()
            ?.errors
            ?.firstOrNull()
            ?.message
            ?.takeIf(String::isNotBlank)

    private companion object {
        const val MIN_REQUEST_INTERVAL_MS = 1_600L
        const val RATE_LIMIT_BASE_DELAY_MS = 5_000L
        const val MAX_RETRY_DELAY_MS = 60_000L
        const val MAX_ATTEMPTS = 4
    }
}