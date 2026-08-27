package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.OpenSubtitlesDirectDataStore
import com.nuvio.tv.data.remote.api.ArmApi
import com.nuvio.tv.data.remote.api.OpenSubtitlesApi
import com.nuvio.tv.domain.model.OpenSubtitlesManualSubtitle
import com.nuvio.tv.domain.model.Subtitle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Direct OpenSubtitles API integration (api.opensubtitles.com), mirroring the
 * mobile app flow: login with username/password when needed, search by IMDb id
 * + season/episode, download one subtitle per preferred language.
 */
@Singleton
class OpenSubtitlesDirectRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: OpenSubtitlesDirectDataStore,
    private val api: OpenSubtitlesApi,
    private val armApi: ArmApi,
    private val tmdbService: TmdbService
) {

    companion object {
        private const val TAG = "OpenSubtitlesDirect"
        private const val LOGIN_TIMEOUT_MS = 15_000L
        private const val SEARCH_TIMEOUT_MS = 20_000L
        private const val DOWNLOAD_TIMEOUT_MS = 20_000L
        private val SOURCE_PREFIXES = setOf("mal", "kitsu", "tmdb")
    }

    suspend fun isConfigured(): Boolean = withContext(Dispatchers.IO) {
        val settings = dataStore.settings.first()
        settings.enabled && settings.hasApiKey && settings.languages.isNotEmpty()
    }

    /**
     * Parses the Stremio-style id (e.g. "tt1234567:1:2") into the imdbId and
     * optional season/episode used by the OpenSubtitles API. Also recognizes
     * anime id prefixes (mal:/kitsu:/tmdb:) so the source id can be resolved
     * to an IMDb id via [resolveImdbId].
     */
    data class MediaRef(
        val imdbId: String?,
        val seasonNumber: Int?,
        val episodeNumber: Int?,
        val source: String? = null,
        val sourceId: String? = null
    )

    fun parseMediaRef(type: String, id: String, videoId: String?): MediaRef {
        val raw = videoId?.takeIf { it.isNotBlank() } ?: id
        val parts = raw.split(":")
        val base = parts.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: return MediaRef(null, null, null)

        if (base.startsWith("tt")) {
            val season = parts.getOrNull(1)?.toIntOrNull()
            val episode = parts.getOrNull(2)?.toIntOrNull()
            return MediaRef(base, season, episode)
        }

        val source = base.lowercase()
        if (source !in SOURCE_PREFIXES) return MediaRef(null, null, null)
        val sourceId = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: return MediaRef(null, null, null)
        val season = parts.getOrNull(2)?.toIntOrNull()
        val episode = parts.getOrNull(3)?.toIntOrNull()
        return MediaRef(null, season, episode, source, sourceId)
    }

    private suspend fun resolveImdbId(ref: MediaRef): String? {
        ref.imdbId?.let { return it }
        val sourceId = ref.sourceId ?: return null
        return try {
            when (ref.source) {
                "mal" -> armApi.resolveMalToImdb(malId = sourceId)
                    .takeIf { it.isSuccessful }?.body()?.imdb
                "kitsu" -> armApi.resolveKitsuToImdb(kitsuId = sourceId)
                    .takeIf { it.isSuccessful }?.body()?.imdb
                "tmdb" -> {
                    val numericId = sourceId.toIntOrNull() ?: return null
                    val mediaType = if (ref.seasonNumber != null) "tv" else "movie"
                    tmdbService.tmdbToImdb(numericId, mediaType)
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "IMDb resolution failed for source=${ref.source}: ${e.message}")
            null
        }
    }

    suspend fun searchAndPrepareSubtitles(
        type: String,
        id: String,
        videoId: String?
    ): List<Subtitle> {
        val settings = dataStore.settings.first()
        if (!settings.enabled || !settings.hasApiKey) return emptyList()
        if (settings.languages.isEmpty()) return emptyList()

        val ref = parseMediaRef(type, id, videoId)
        val imdbId = resolveImdbId(ref)
            ?: run {
                Log.w(TAG, "No IMDb id resolvable for id=$id videoId=$videoId")
                return emptyList()
            }

        Log.d(TAG, "Searching subtitles: imdb=$imdbId S${ref.seasonNumber ?: "-"}E${ref.episodeNumber ?: "-"} languages=${settings.languages}")

        val apiType = if (
            type.equals("tv", ignoreCase = true) ||
            type.equals("series", ignoreCase = true)
        ) {
            "episode"
        } else {
            "movie"
        }

        val searchResponse = try {
            api.searchSubtitles(
                apiKey = settings.apiKey,
                imdbId = imdbId,
                type = apiType,
                seasonNumber = ref.seasonNumber,
                episodeNumber = ref.episodeNumber,
                languages = settings.languages.sorted().joinToString(",")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Search request failed", e)
            return emptyList()
        }
        val searchBody = searchResponse.body()
        if (!searchResponse.isSuccessful || searchBody == null) {
            Log.w(TAG, "Search failed: code=${searchResponse.code()} message=${searchResponse.message()}")
            return emptyList()
        }
        if (searchBody.data.isEmpty()) {
            Log.d(TAG, "No subtitles found in search")
            return emptyList()
        }

        // Login is only required to download; search itself needs just the API key.
        var token = settings.userToken
        if (token.isBlank()) {
            val loginError = ensureLoggedIn(settings)
            if (loginError != null) {
                Log.w(TAG, "Login failed: $loginError")
                return emptyList()
            }
            token = dataStore.settings.first().userToken
        }
        if (token.isBlank()) {
            Log.w(TAG, "No user token available")
            return emptyList()
        }

        val langPriority = settings.languages.withIndex()
            .associate { (index, lang) -> lang.lowercase() to index }

        val sorted = searchBody.data
            .filter { it.attributes?.files?.isNotEmpty() == true }
            .sortedWith(
                compareByDescending<com.nuvio.tv.data.remote.dto.OpenSubtitlesSubtitleData> {
                    it.attributes?.fromTrusted == true
                }
                    .thenByDescending { it.attributes?.downloadCount ?: 0 }
                    .thenBy {
                        val code = it.attributes?.language?.take(2)?.lowercase().orEmpty()
                        langPriority[code] ?: Int.MAX_VALUE
                    }
            )

        val results = mutableListOf<Subtitle>()
        val seenLanguages = mutableSetOf<String>()

        for (sub in sorted) {
            val attrs = sub.attributes ?: continue
            val langCode = (attrs.language ?: "").take(2).lowercase()
            if (langCode.isBlank()) continue
            if (langCode in seenLanguages) continue
            seenLanguages.add(langCode)

            val file = attrs.files?.firstOrNull() ?: continue
            val fileId = file.fileId ?: continue

            try {
                val downloadResponse = api.downloadSubtitle(
                    apiKey = settings.apiKey,
                    authorization = "Bearer $token",
                    body = com.nuvio.tv.data.remote.dto.OpenSubtitlesDownloadRequest(fileId)
                )
                val downloadUrl = downloadResponse.body()?.link
                if (!downloadResponse.isSuccessful || downloadUrl == null) {
                    Log.w(TAG, "Download failed for lang=$langCode fileId=$fileId")
                    continue
                }
                val label = buildString {
                    append(attrs.language ?: langCode)
                    if (attrs.hearingImpaired == true) append(" [HI]")
                    if (attrs.fromTrusted == true) append(" ★")
                }
                Log.d(TAG, "Added subtitle lang=$langCode label='$label'")
                results.add(
                    Subtitle(
                        id = "opensubtitles-$fileId",
                        url = downloadUrl,
                        lang = langCode,
                        addonName = "OpenSubtitles",
                        addonLogo = null
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Download failed for lang=$langCode fileId=$fileId: ${e.message}")
            }
        }

        Log.d(TAG, "Returning ${results.size} subtitles")
        return results
    }

    /**
     * Lists all matching OpenSubtitles results (several per language) without
     * downloading them. Download happens on selection via [downloadManualItem]
     * to stay within the OpenSubtitles API rate limits.
     */
    suspend fun searchManual(type: String, id: String, videoId: String?): List<OpenSubtitlesManualSubtitle> {
        val settings = dataStore.settings.first()
        if (!settings.enabled || !settings.hasApiKey) return emptyList()
        if (settings.languages.isEmpty()) return emptyList()

        val ref = parseMediaRef(type, id, videoId)
        val imdbId = resolveImdbId(ref)
            ?: run {
                Log.w(TAG, "Manual search: no IMDb id resolvable for id=$id videoId=$videoId")
                return emptyList()
            }

        Log.d(TAG, "Manual search: imdb=$imdbId S${ref.seasonNumber ?: "-"}E${ref.episodeNumber ?: "-"} languages=${settings.languages}")

        val searchResponse = try {
            api.searchSubtitles(
                apiKey = settings.apiKey,
                imdbId = imdbId,
                type = if (
                    type.equals("tv", ignoreCase = true) ||
                    type.equals("series", ignoreCase = true)
                ) {
                    "episode"
                } else {
                    "movie"
                },
                seasonNumber = ref.seasonNumber,
                episodeNumber = ref.episodeNumber,
                languages = settings.languages.sorted().joinToString(",")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Manual search request failed", e)
            return emptyList()
        }
        val searchBody = searchResponse.body()
        if (!searchResponse.isSuccessful || searchBody == null) {
            Log.w(TAG, "Manual search failed: code=${searchResponse.code()} message=${searchResponse.message()}")
            return emptyList()
        }

        return searchBody.data
            .filter { it.attributes?.files?.isNotEmpty() == true }
            .sortedWith(
                compareByDescending<com.nuvio.tv.data.remote.dto.OpenSubtitlesSubtitleData> {
                    it.attributes?.fromTrusted == true
                }
                    .thenByDescending { it.attributes?.downloadCount ?: 0 }
            )
            .mapNotNull { sub ->
                val attrs = sub.attributes ?: return@mapNotNull null
                val file = attrs.files?.firstOrNull() ?: return@mapNotNull null
                val fileId = file.fileId ?: return@mapNotNull null
                OpenSubtitlesManualSubtitle(
                    fileId = fileId,
                    language = attrs.language ?: "",
                    languageCode = (attrs.language ?: "").take(2).lowercase(),
                    release = attrs.release,
                    fileName = file.fileName,
                    hearingImpaired = attrs.hearingImpaired == true,
                    fromTrusted = attrs.fromTrusted == true,
                    downloadCount = attrs.downloadCount ?: 0
                )
            }
    }

    /**
     * Downloads a single manual search result (mirroring the mobile app's
     * lazy download flow) and returns the ready-to-attach subtitle.
     */
    suspend fun downloadManualItem(item: OpenSubtitlesManualSubtitle): Result<Subtitle> {
        val settings = dataStore.settings.first()
        if (!settings.hasApiKey) {
            return Result.failure(IllegalStateException("missing_api_key"))
        }
        val token = settings.userToken
        if (token.isBlank()) {
            val loginError = ensureLoggedIn(settings)
            if (loginError != null) {
                return Result.failure(IllegalStateException(loginError))
            }
        }
        val currentToken = dataStore.settings.first().userToken
        if (currentToken.isBlank()) {
            return Result.failure(IllegalStateException("no_credentials"))
        }
        return try {
            val downloadResponse = api.downloadSubtitle(
                apiKey = settings.apiKey,
                authorization = "Bearer $currentToken",
                body = com.nuvio.tv.data.remote.dto.OpenSubtitlesDownloadRequest(item.fileId)
            )
            val downloadUrl = downloadResponse.body()?.link
            if (downloadResponse.isSuccessful && downloadUrl != null) {
                Result.success(
                    Subtitle(
                        id = "opensubtitles-${item.fileId}",
                        url = downloadUrl,
                        lang = item.languageCode,
                        addonName = "OpenSubtitles",
                        addonLogo = null
                    )
                )
            } else {
                Log.w(TAG, "Manual download failed: code=${downloadResponse.code()} message=${downloadResponse.message()}")
                Result.failure(IllegalStateException("no_link"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Manual download error: ${e.message}", e)
            Result.failure(e)
        }
    }

    private suspend fun ensureLoggedIn(
        settings: OpenSubtitlesDirectDataStore.OpenSubtitlesDirectSettings
    ): String? {
        if (settings.userToken.isNotBlank()) return null
        if (!settings.hasUserCredentials) {
            Log.w(TAG, "No username/password configured, cannot login")
            return "no_credentials"
        }
        return try {
            val response = api.login(
                apiKey = settings.apiKey,
                body = com.nuvio.tv.data.remote.dto.OpenSubtitlesLoginRequest(
                    username = settings.username,
                    password = settings.password
                )
            )
            val body = response.body()
            if (response.isSuccessful && body?.token != null) {
                dataStore.setUserToken(body.token)
                Log.d(TAG, "Login successful")
                null
            } else {
                Log.w(TAG, "Login returned no token: ${body?.message}")
                "login error: ${body?.message ?: response.code()}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Login error: ${e.message}")
            "login error: ${e.message ?: "unknown"}"
        }
    }
}