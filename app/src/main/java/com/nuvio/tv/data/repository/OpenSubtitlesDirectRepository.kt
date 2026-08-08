package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.local.OpenSubtitlesDirectDataStore
import com.nuvio.tv.data.remote.api.OpenSubtitlesApi
import com.nuvio.tv.domain.model.Subtitle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import android.content.Context

/**
 * Direct OpenSubtitles API integration (api.opensubtitles.com), mirroring the
 * mobile app flow: login with username/password when needed, search by IMDb id
 * + season/episode, download one subtitle per preferred language.
 */
@Singleton
class OpenSubtitlesDirectRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: OpenSubtitlesDirectDataStore,
    private val api: OpenSubtitlesApi
) {

    companion object {
        private const val TAG = "OpenSubtitlesDirect"
        private const val LOGIN_TIMEOUT_MS = 15_000L
        private const val SEARCH_TIMEOUT_MS = 20_000L
        private const val DOWNLOAD_TIMEOUT_MS = 20_000L
    }

    suspend fun isConfigured(): Boolean = withContext(Dispatchers.IO) {
        val settings = dataStore.settings.first()
        settings.enabled && settings.hasApiKey && settings.languages.isNotEmpty()
    }

    /**
     * Parses the Stremio-style id (e.g. "tt1234567" or "tt1234567:1:2") into
     * the imdbId and optional season/episode used by the OpenSubtitles API.
     */
    data class MediaRef(
        val imdbId: String?,
        val seasonNumber: Int?,
        val episodeNumber: Int?
    )

    fun parseMediaRef(type: String, id: String, videoId: String?): MediaRef {
        val raw = videoId?.takeIf { it.isNotBlank() } ?: id
        val parts = raw.split(":")
        val base = parts.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        if (base == null || !base.startsWith("tt")) return MediaRef(null, null, null)

        val isSeries = type.equals("tv", ignoreCase = true) ||
            type.equals("series", ignoreCase = true)
        if (!isSeries || parts.size < 3) {
            return MediaRef(base, null, null)
        }
        val season = parts.getOrNull(1)?.toIntOrNull()
        val episode = parts.getOrNull(2)?.toIntOrNull()
        return MediaRef(base, season, episode)
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
        val imdbId = ref.imdbId ?: return emptyList()

        Log.d(TAG, "Searching subtitles: imdb=$imdbId S${ref.seasonNumber ?: "-"}E${ref.episodeNumber ?: "-"} languages=${settings.languages}")

        val loginError = ensureLoggedIn(settings)
        if (loginError != null) {
            Log.w(TAG, "Login failed: $loginError")
            return emptyList()
        }

        val token = dataStore.settings.first().userToken
        if (token.isBlank()) {
            Log.w(TAG, "No user token available")
            return emptyList()
        }

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