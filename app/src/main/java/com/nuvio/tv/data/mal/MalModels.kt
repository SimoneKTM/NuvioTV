package com.nuvio.tv.data.mal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class MalMediaListStatus(val wireValue: String) {
    WATCHING("watching"),
    COMPLETED("completed"),
    ON_HOLD("on_hold"),
    DROPPED("dropped"),
    PLAN_TO_WATCH("plan_to_watch");

    companion object {
        fun fromWireValue(value: String?): MalMediaListStatus? =
            entries.firstOrNull { status -> status.wireValue.equals(value, ignoreCase = true) }
    }
}

/** A single anime entry from the user's MyAnimeList library. */
@Serializable
data class MalLibraryItem(
    val id: Long,
    val title: String,
    val posterUrl: String? = null,
    val bannerUrl: String? = null,
    val progress: Int = 0,
    val totalEpisodes: Int? = null,
    val score: Int? = null,
    val status: MalMediaListStatus,
    val updatedAt: Long = 0L,
    val mediaType: String? = null
)

@Serializable
data class MalUserResponse(
    val id: Long? = null,
    val name: String? = null
)

@Serializable
internal data class MalUserAnimeListResponse(
    val data: List<MalUserAnimeEntry> = emptyList(),
    val paging: MalPaging? = null
)

@Serializable
internal data class MalUserAnimeEntry(
    val node: MalAnimeNode? = null,
    @SerialName("list_status") val listStatus: MalListStatus? = null
)

@Serializable
internal data class MalAnimeNode(
    val id: Long? = null,
    val title: String? = null,
    @SerialName("main_picture") val mainPicture: MalPicture? = null,
    @SerialName("num_episodes") val numEpisodes: Int? = null,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("media_type") val mediaType: String? = null
)

@Serializable
data class MalPicture(
    val medium: String? = null,
    val large: String? = null
)

@Serializable
data class MalSearchResponse(
    val data: List<MalSearchNode> = emptyList(),
    val paging: MalPaging? = null
)

@Serializable
data class MalSearchNode(
    val node: MalSearchAnime? = null
)

@Serializable
data class MalSearchAnime(
    val id: Long? = null,
    val title: String? = null,
    @SerialName("main_picture") val mainPicture: MalPicture? = null,
    val synopsis: String? = null,
    val mean: Double? = null,
    val rank: Int? = null,
    val popularity: Int? = null,
    @SerialName("num_episodes") val numEpisodes: Int? = null,
    val status: String? = null,
    val genres: List<MalGenre>? = null,
    @SerialName("media_type") val mediaType: String? = null,
    @SerialName("start_date") val startDate: String? = null
)

@Serializable
data class MalRankingResponse(
    val data: List<MalRankingNode> = emptyList(),
    val paging: MalPaging? = null
)

@Serializable
data class MalRankingNode(
    val node: MalSearchAnime? = null
)

@Serializable
data class MalGenre(
    val id: Int? = null,
    val name: String? = null
)

@Serializable
data class MalPaging(
    val next: String? = null,
    val previous: String? = null
)

@Serializable
internal data class MalListStatus(
    val status: String? = null,
    val score: Int? = null,
    @SerialName("num_episodes_watched") val numEpisodesWatched: Int? = null,
    @SerialName("is_rewatching") val isRewatching: Boolean? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
internal data class MalTokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Long? = null
)

@Serializable
internal data class MalTokenErrorResponse(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null
)