package com.nuvio.tv.data.trackerqr

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.core.network.IPv4FirstDns
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

data class TrackerQrSession(
    val userCode: String,
    val url: String,
    val expiresAtEpochMs: Long?,
    val pollIntervalSeconds: Int
)

sealed interface TrackerQrPollResult {
    data object Pending : TrackerQrPollResult
    data class Approved(val payload: String?) : TrackerQrPollResult
    data object Expired : TrackerQrPollResult
    data class Failed(val message: String) : TrackerQrPollResult
}

@Singleton
class TrackerQrApi @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val client = OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    val isConfigured: Boolean
        get() = BuildConfig.TRACKER_LOGIN_RELAY_BASE_URL.isNotBlank()

    suspend fun startSession(provider: String): Result<TrackerQrSession> =
        withContext(Dispatchers.IO) {
            val response = post(
                path = "/start",
                requestSerializer = TrackerQrStartRequest.serializer(),
                request = TrackerQrStartRequest(provider = provider),
                responseSerializer = TrackerQrStartResponse.serializer()
            ) ?: return@withContext Result.failure(IOException("Empty response from QR login relay"))
            if (!response.isUsable) {
                return@withContext Result.failure(IOException("QR login relay returned incomplete data"))
            }
            Result.success(
                TrackerQrSession(
                    userCode = response.userCode!!,
                    url = response.url!!,
                    expiresAtEpochMs = response.expiresAtEpochMs,
                    pollIntervalSeconds = response.pollIntervalSeconds?.coerceAtLeast(2) ?: 5
                )
            )
        }

    suspend fun pollSession(userCode: String, provider: String): TrackerQrPollResult =
        withContext(Dispatchers.IO) {
            val response = post(
                path = "/poll",
                requestSerializer = TrackerQrPollRequest.serializer(),
                request = TrackerQrPollRequest(userCode = userCode, provider = provider),
                responseSerializer = TrackerQrPollResponse.serializer()
            ) ?: return@withContext TrackerQrPollResult.Failed("Empty response from QR login relay")
            when {
                response.isApproved -> TrackerQrPollResult.Approved(response.payload)
                response.isExpired -> TrackerQrPollResult.Expired
                else -> TrackerQrPollResult.Pending
            }
        }

    private suspend fun <T, R> post(
        path: String,
        requestSerializer: KSerializer<T>,
        request: T,
        responseSerializer: KSerializer<R>
    ): R? {
        val baseUrl = BuildConfig.TRACKER_LOGIN_RELAY_BASE_URL.trimEnd('/')
        if (baseUrl.isBlank()) return null
        return try {
            val requestBody = json.encodeToString(requestSerializer, request).toRequestBody(JSON_TYPE)
            val call = Request.Builder()
                .url("$baseUrl$path")
                .header("Content-Type", JSON_TYPE.toString())
                .post(requestBody)
                .build()
            client.newCall(call).execute().use { response ->
                if (!response.isSuccessful) return null
                val responseBody = response.body?.string() ?: return null
                runCatching { json.decodeFromString(responseSerializer, responseBody) }.getOrNull()
            }
        } catch (error: Exception) {
            null
        }
    }

    companion object {
        private val JSON_TYPE = "application/json".toMediaType()
    }
}
