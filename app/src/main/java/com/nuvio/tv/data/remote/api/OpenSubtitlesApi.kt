package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.OpenSubtitlesDownloadRequest
import com.nuvio.tv.data.remote.dto.OpenSubtitlesDownloadResponse
import com.nuvio.tv.data.remote.dto.OpenSubtitlesLoginRequest
import com.nuvio.tv.data.remote.dto.OpenSubtitlesLoginResponse
import com.nuvio.tv.data.remote.dto.OpenSubtitlesSearchResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

interface OpenSubtitlesApi {

    @POST("login")
    suspend fun login(
        @Header("Api-Key") apiKey: String,
        @Body body: OpenSubtitlesLoginRequest
    ): Response<OpenSubtitlesLoginResponse>

    @GET("subtitles")
    suspend fun searchSubtitles(
        @Header("Api-Key") apiKey: String,
        @Query("imdb_id") imdbId: String?,
        @Query("type") type: String?,
        @Query("season_number") seasonNumber: Int?,
        @Query("episode_number") episodeNumber: Int?,
        @Query("languages") languages: String?,
        @Query("page") page: Int = 1
    ): Response<OpenSubtitlesSearchResponse>

    @POST("download")
    suspend fun downloadSubtitle(
        @Header("Api-Key") apiKey: String,
        @Header("Authorization") authorization: String?,
        @Body body: OpenSubtitlesDownloadRequest
    ): Response<OpenSubtitlesDownloadResponse>
}