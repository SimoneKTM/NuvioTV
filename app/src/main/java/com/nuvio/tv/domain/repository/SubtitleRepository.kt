package com.nuvio.tv.domain.repository

import com.nuvio.tv.domain.model.OpenSubtitlesManualSubtitle
import com.nuvio.tv.domain.model.Subtitle

interface SubtitleRepository {
    /**
     * Fetches subtitles from all installed addons that support subtitles
     * @param type Content type (movie, series, etc.)
     * @param id Content ID (IMDB ID, etc.)
     * @param videoId Optional video ID for series (e.g., tt1234567:1:1 for series episode)
     * @param videoHash Optional OpenSubtitles file hash
     * @param videoSize Optional video file size in bytes
     * @param filename Optional video filename
     * @param sourceAddonBaseUrl Optional base URL of the addon that provided the
     *        current stream; when it belongs to the anime group, anime subtitle
     *        addons are consulted first.
     * @return List of subtitles from all addons
     */
    suspend fun getSubtitles(
        type: String,
        id: String,
        videoId: String? = null,
        videoHash: String? = null,
        videoSize: Long? = null,
        filename: String? = null,
        sourceAddonBaseUrl: String? = null,
        onProgress: ((completed: Int, total: Int, addonName: String?) -> Unit)? = null
    ): List<Subtitle>

    /** Whether the direct OpenSubtitles API is configured (enabled + API key + languages). */
    suspend fun isOpenSubtitlesConfigured(): Boolean

    /**
     * Manually searches the direct OpenSubtitles API for the current media,
     * returning multiple results per language without downloading them.
     */
    suspend fun searchOpenSubtitles(
        type: String,
        id: String,
        videoId: String?
    ): List<OpenSubtitlesManualSubtitle>

    /** Downloads a single manual search result and returns the ready-to-attach subtitle. */
    suspend fun downloadOpenSubtitles(item: OpenSubtitlesManualSubtitle): Result<Subtitle>
}
