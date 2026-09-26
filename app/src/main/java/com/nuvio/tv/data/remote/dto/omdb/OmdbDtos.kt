package com.nuvio.tv.data.remote.dto.omdb

import com.squareup.moshi.Json

data class OmdbResponseDto(
    @Json(name = "Response") val response: String? = null,
    @Json(name = "Awards") val awards: String? = null,
    @Json(name = "Error") val error: String? = null
)
