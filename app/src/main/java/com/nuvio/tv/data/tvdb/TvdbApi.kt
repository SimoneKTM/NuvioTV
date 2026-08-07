package com.nuvio.tv.data.tvdb

import android.util.Log
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TvdbApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val moshi: Moshi
) {
    private val tag = "TvdbApi"
    private val jsonMediaType = "application/json".toMediaType()

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenApiKey: String? = null

    private val authLock = Any()

    suspend fun ensureAuthenticated(apiKey: String?): String? {
        val resolvedKey = apiKey?.trim()?.takeIf { it.isNotBlank() } ?: return null
        synchronized(authLock) {
            if (cachedToken != null && tokenApiKey == resolvedKey) return cachedToken
        }
        val response = login(resolvedKey) ?: return null
        synchronized(authLock) {
            cachedToken = response.data.token
            tokenApiKey = resolvedKey
        }
        return cachedToken
    }

    fun clearAuth() {
        synchronized(authLock) {
            cachedToken = null
            tokenApiKey = null
        }
    }

    suspend fun searchSeries(apiKey: String, query: String): List<TvdbSearchResult> {
        val token = ensureAuthenticated(apiKey) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/search?query=${encodeQuery(query)}&type=series"
                val body = get(url, authHeaders(token))
                moshi.adapter(TvdbSearchResponse::class.java)
                    .fromJson(body)?.data.orEmpty()
            } catch (e: Exception) {
                Log.w(tag, "TVDB search failed: ${e.message}")
                emptyList()
            }
        }
    }

    suspend fun searchByRemoteId(apiKey: String, remoteId: String): List<TvdbSearchResult> {
        val token = ensureAuthenticated(apiKey) ?: return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/search/remoteid/${encodeQuery(remoteId)}"
                val body = get(url, authHeaders(token))
                moshi.adapter(TvdbSearchResponse::class.java)
                    .fromJson(body)?.data.orEmpty()
            } catch (e: Exception) {
                Log.w(tag, "TVDB remote search failed: ${e.message}")
                emptyList()
            }
        }
    }

    suspend fun getSeriesExtended(apiKey: String, id: String, language: String? = null): TvdbSeriesExtended? {
        val token = ensureAuthenticated(apiKey) ?: return null
        val numericId = id.removePrefix("series-").removePrefix("movie-")
        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/series/$numericId/extended"
                val body = get(url, buildHeaders(token, language))
                moshi.adapter(TvdbSeriesExtendedResponse::class.java)
                    .fromJson(body)?.data
            } catch (e: Exception) {
                Log.w(tag, "TVDB series $id failed: ${e.message}")
                null
            }
        }
    }

    suspend fun getSeriesEpisodes(
        apiKey: String,
        id: Int,
        page: Int = 0,
        language: String? = null
    ): TvdbEpisodesResponse? {
        val token = ensureAuthenticated(apiKey) ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/series/$id/episodes${if (page > 0) "?page=$page" else ""}"
                val body = get(url, buildHeaders(token, language))
                moshi.adapter(TvdbEpisodesResponse::class.java).fromJson(body)
            } catch (e: Exception) {
                Log.w(tag, "TVDB episodes for $id failed: ${e.message}")
                null
            }
        }
    }

    private fun get(url: String, headers: Map<String, String>): String {
        val builder = Request.Builder().url(url)
        headers.forEach(builder::header)
        val response = okHttpClient.newCall(builder.get().build()).execute()
        response.use {
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    private suspend fun login(apiKey: String): TvdbLoginResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val body = """{"apikey":"$apiKey"}""".toRequestBody(jsonMediaType)
                val request = Request.Builder()
                    .url("$BASE_URL/login")
                    .post(body)
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(tag, "TVDB login failed: ${response.code}")
                        return@withContext null
                    }
                    moshi.adapter(TvdbLoginResponse::class.java)
                        .fromJson(response.body?.string().orEmpty())
                }
            } catch (e: Exception) {
                Log.w(tag, "TVDB login failed: ${e.message}")
                null
            }
        }
    }

    private fun authHeaders(token: String): Map<String, String> =
        buildHeaders(token, language = null)

    private fun buildHeaders(token: String, language: String?): Map<String, String> {
        val headers = mutableMapOf("Authorization" to "Bearer $token")
        if (!language.isNullOrBlank() && language != "en") {
            headers["Accept-Language"] = language
        }
        return headers
    }

    private fun encodeQuery(query: String): String =
        URLEncoder.encode(query, "UTF-8").replace("+", "%20")

    private companion object {
        const val BASE_URL = "https://api4.thetvdb.com/v4"
    }

    @JsonClass(generateAdapter = true)
    data class TvdbLoginData(
        val token: String = ""
    )

    @JsonClass(generateAdapter = true)
    data class TvdbLoginResponse(
        val data: TvdbLoginData = TvdbLoginData()
    )

    @JsonClass(generateAdapter = true)
    data class TvdbSearchResponse(
        val data: List<TvdbSearchResult>? = null
    )

    @JsonClass(generateAdapter = true)
    data class TvdbSearchResult(
        val id: String = "",
        val name: String = "",
        @Json(name = "aliases") val aliases: List<String> = emptyList(),
        @Json(name = "first_air_time") val firstAirTime: String? = null,
        val image: String? = null,
        @Json(name = "image_type") val imageType: Int? = null,
        @Json(name = "is_official") val isOfficial: Boolean = true,
        val nameTranslations: List<String> = emptyList(),
        val overviewTranslations: List<String> = emptyList(),
        @Json(name = "remote_ids") val remoteIds: List<TvdbRemoteId> = emptyList(),
        val slug: String? = null,
        val status: String? = null,
        val year: String? = null
    )

    @JsonClass(generateAdapter = true)
    data class TvdbRemoteId(
        val id: String = "",
        val type: Int = 0,
        @Json(name = "sourceName") val sourceName: String = ""
    )

    @JsonClass(generateAdapter = true)
    data class TvdbStatus(
        val id: Int = 0,
        val name: String = "",
        @Json(name = "recordType") val recordType: String = ""
    )

    @JsonClass(generateAdapter = true)
    data class TvdbSeriesExtendedResponse(
        val data: TvdbSeriesExtended? = null
    )

    @JsonClass(generateAdapter = true)
    data class TvdbAlias(
        val name: String = "",
        val language: String = ""
    )

    @JsonClass(generateAdapter = true)
    data class TvdbSeriesExtended(
        val id: Int = 0,
        val name: String = "",
        val slug: String? = null,
        val overview: String? = null,
        val year: String? = null,
        val image: String? = null,
        val status: TvdbStatus? = null,
        val artwork: List<TvdbArtwork> = emptyList(),
        val seasons: List<TvdbSeason> = emptyList(),
        @Json(name = "remote_ids") val remoteIds: List<TvdbRemoteId> = emptyList(),
        @Json(name = "first_air_time") val firstAirTime: String? = null,
        val trailers: List<TvdbTrailer> = emptyList(),
        val companies: List<TvdbCompany> = emptyList(),
        @Json(name = "content_ratings") val contentRatings: List<TvdbContentRating> = emptyList(),
        val tags: List<TvdbTag> = emptyList(),
        val characters: List<TvdbCharacter> = emptyList(),
        val lists: List<TvdbList> = emptyList(),
        @Json(name = "season_types") val seasonTypes: List<TvdbSeasonType> = emptyList(),
        val aliases: List<TvdbAlias> = emptyList()
    )

    @JsonClass(generateAdapter = true)
    data class TvdbArtwork(
        val id: Int = 0,
        val image: String = "",
        val type: Int = 0,
        @Json(name = "thumbnail") val thumbnail: String? = null,
        @Json(name = "language") val language: String? = null,
        val score: Int = 0,
        val width: Int = 0,
        val height: Int = 0,
        val includesText: Boolean = false
    )

    @JsonClass(generateAdapter = true)
    data class TvdbSeasonCompanies(
        val studio: TvdbCompany? = null,
        val network: TvdbCompany? = null
    )

    @JsonClass(generateAdapter = true)
    data class TvdbSeason(
        val id: Int = 0,
        val number: Int = 0,
        val name: String? = null,
        @Json(name = "image") val image: String? = null,
        @Json(name = "image_type") val imageType: Int? = null,
        val overview: String? = null,
        val companies: TvdbSeasonCompanies? = null,
        val seasons: List<TvdbSeason>? = null,
        val trailers: List<TvdbTrailer>? = null,
        val artwork: List<TvdbArtwork>? = null,
        val episodeCount: Int = 0
    )

    @JsonClass(generateAdapter = true)
    data class TvdbTrailer(
        val id: Int = 0,
        val name: String? = null,
        val url: String? = null,
        val language: String? = null,
        val runtime: Int = 0
    )

    @JsonClass(generateAdapter = true)
    data class TvdbCompany(
        val id: Int? = null,
        val name: String = "",
        val slug: String? = null,
        @Json(name = "primary_company_type") val primaryCompanyType: Int? = null,
        val activeDate: String? = null,
        val inactiveDate: String? = null,
        val description: String? = null,
        val country: String? = null,
        val parentCompany: TvdbCompany? = null
    )

    @JsonClass(generateAdapter = true)
    data class TvdbContentRating(
        val id: Int = 0,
        val name: String = "",
        val country: String = "",
        @Json(name = "content_type") val contentType: String = "",
        val rating: Float = 0f,
        val description: String? = null
    )

    @JsonClass(generateAdapter = true)
    data class TvdbTag(
        val id: Int = 0,
        val name: String = "",
        val tag: Int = 0,
        val helpText: String? = null
    )

    @JsonClass(generateAdapter = true)
    data class TvdbCharacter(
        val id: Int = 0,
        val name: String = "",
        val image: String? = null,
        val episode: TvdbCharacterEpisode? = null,
        val peopleId: Int = 0,
        val seriesId: Int = 0,
        val sort: Int = 0,
        val tagOptions: List<TvdbTagOption> = emptyList(),
        val type: Int = 0,
        val url: String? = null,
        val personName: String? = null
    )

    @JsonClass(generateAdapter = true)
    data class TvdbCharacterEpisode(
        val episodeId: Int = 0,
        val image: String? = null,
        val name: String = "",
        val number: Int = 0,
        val seasonNumber: Int = 0,
        val absoluteNumber: Int? = null,
        val overview: String? = null
    )

    @JsonClass(generateAdapter = true)
    data class TvdbTagOption(
        @Json(name = "help_text") val helpText: String? = null,
        val id: Int = 0,
        val name: String = "",
        val tag: Int = 0,
        val tagName: String? = null
    )

    @JsonClass(generateAdapter = true)
    data class TvdbList(
        val id: Int = 0,
        val name: String = "",
        val overview: String? = null,
        val url: String? = null,
        val isOfficial: Boolean = false,
        val nameTranslations: List<String> = emptyList(),
        val overviewTranslations: List<String> = emptyList(),
        val image: String? = null,
        val score: Int = 0
    )

    @JsonClass(generateAdapter = true)
    data class TvdbSeasonType(
        val id: Int = 0,
        val name: String = "",
        val type: String = "",
        val seasons: List<TvdbSeason> = emptyList(),
        @Json(name = "alternate_name") val alternateName: String? = null
    )

    @JsonClass(generateAdapter = true)
    data class TvdbEpisodesResponse(
        val data: List<TvdbEpisode> = emptyList(),
        val status: TvdbResponseStatus? = null,
        val links: TvdbLinks? = null
    )

    @JsonClass(generateAdapter = true)
    data class TvdbEpisode(
        val id: Int = 0,
        val name: String = "",
        val overview: String? = null,
        val number: Int = 0,
        @Json(name = "season_number") val seasonNumber: Int = 0,
        @Json(name = "absolute_number") val absoluteNumber: Int? = null,
        val image: String? = null,
        val thumbnail: String? = null,
        val airDate: String? = null,
        val runtime: Int? = null,
        val characters: List<TvdbCharacter> = emptyList(),
        val companies: List<TvdbCompany> = emptyList(),
        val trailers: List<TvdbTrailer> = emptyList(),
        val artwork: List<TvdbArtwork>? = null,
        val remoteIds: List<TvdbRemoteId> = emptyList()
    )

    @JsonClass(generateAdapter = true)
    data class TvdbResponseStatus(
        val type: String = "",
        val detail: String = ""
    )

    @JsonClass(generateAdapter = true)
    data class TvdbLinks(
        val previous: Int? = null,
        val current: Int = 0,
        val next: Int? = null,
        val totalPages: Int = 0
    )
}
