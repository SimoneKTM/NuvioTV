package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class OpenSubtitlesLoginRequest(
    @Json(name = "username") val username: String,
    @Json(name = "password") val password: String
)

@JsonClass(generateAdapter = true)
data class OpenSubtitlesLoginResponse(
    @Json(name = "token") val token: String? = null,
    @Json(name = "status") val status: Int? = null,
    @Json(name = "message") val message: String? = null
)

@JsonClass(generateAdapter = true)
data class OpenSubtitlesSearchResponse(
    @Json(name = "total_count") val totalCount: Int? = null,
    @Json(name = "data") val data: List<OpenSubtitlesSubtitleData> = emptyList()
)

@JsonClass(generateAdapter = true)
data class OpenSubtitlesSubtitleData(
    @Json(name = "id") val id: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "attributes") val attributes: OpenSubtitlesSubtitleAttributes? = null
)

@JsonClass(generateAdapter = true)
data class OpenSubtitlesSubtitleAttributes(
    @Json(name = "subtitle_id") val subtitleId: String? = null,
    @Json(name = "language") val language: String? = null,
    @Json(name = "language_code") val languageCode: String? = null,
    @Json(name = "download_count") val downloadCount: Int? = null,
    @Json(name = "new_download_count") val newDownloadCount: Int? = null,
    @Json(name = "hearing_impaired") val hearingImpaired: Boolean? = null,
    @Json(name = "hd") val hd: Boolean? = null,
    @Json(name = "votes") val votes: Int? = null,
    @Json(name = "ratings") val ratings: Double? = null,
    @Json(name = "from_trusted") val fromTrusted: Boolean? = null,
    @Json(name = "foreign_parts_only") val foreignPartsOnly: Boolean? = null,
    @Json(name = "ai_translated") val aiTranslated: Boolean? = null,
    @Json(name = "machine_translated") val machineTranslated: Boolean? = null,
    @Json(name = "release") val release: String? = null,
    @Json(name = "uploader") val uploader: OpenSubtitlesUploader? = null,
    @Json(name = "files") val files: List<OpenSubtitlesFile>? = null,
    @Json(name = "feature_details") val featureDetails: OpenSubtitlesFeatureDetails? = null,
    @Json(name = "url") val url: String? = null
)

@JsonClass(generateAdapter = true)
data class OpenSubtitlesUploader(
    @Json(name = "uploader_id") val uploaderId: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "rank") val rank: String? = null
)

@JsonClass(generateAdapter = true)
data class OpenSubtitlesFile(
    @Json(name = "file_id") val fileId: Int? = null,
    @Json(name = "file_name") val fileName: String? = null
)

@JsonClass(generateAdapter = true)
data class OpenSubtitlesFeatureDetails(
    @Json(name = "feature_id") val featureId: Int? = null,
    @Json(name = "feature_type") val featureType: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "imdb_id") val imdbId: Int? = null,
    @Json(name = "tmdb_id") val tmdbId: Int? = null,
    @Json(name = "season_number") val seasonNumber: Int? = null,
    @Json(name = "episode_number") val episodeNumber: Int? = null,
    @Json(name = "parent_imdb_id") val parentImdbId: Int? = null,
    @Json(name = "parent_title") val parentTitle: String? = null,
    @Json(name = "parent_tmdb_id") val parentTmdbId: Int? = null
)

@JsonClass(generateAdapter = true)
data class OpenSubtitlesDownloadRequest(
    @Json(name = "file_id") val fileId: Int
)

@JsonClass(generateAdapter = true)
data class OpenSubtitlesDownloadResponse(
    @Json(name = "link") val link: String? = null,
    @Json(name = "file_name") val fileName: String? = null,
    @Json(name = "requests") val requests: Int? = null,
    @Json(name = "remaining") val remaining: Int? = null
)