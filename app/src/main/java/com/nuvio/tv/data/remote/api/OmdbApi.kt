package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.omdb.OmdbResponseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface OmdbApi {
    @GET(".")
    suspend fun getTitle(
        @Query("apikey") apiKey: String,
        @Query("i") imdbId: String
    ): Response<OmdbResponseDto>
}
