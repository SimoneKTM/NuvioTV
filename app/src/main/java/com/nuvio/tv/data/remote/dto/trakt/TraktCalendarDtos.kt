package com.nuvio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TraktCalendarShowItemDto(
    @Json(name = "first_aired") val firstAired: String? = null,
    @Json(name = "episode") val episode: TraktCalendarEpisodeDto? = null,
    @Json(name = "show") val show: TraktShowDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktCalendarEpisodeDto(
    @Json(name = "season") val season: Int? = null,
    @Json(name = "number") val number: Int? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktCalendarMovieItemDto(
    @Json(name = "movie") val movie: TraktCalendarMovieDto? = null,
    @Json(name = "released") val released: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktCalendarMovieDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "rating") val rating: Double? = null,
    @Json(name = "genres") val genres: List<String>? = null,
    @Json(name = "images") val images: TraktImagesDto? = null
)
