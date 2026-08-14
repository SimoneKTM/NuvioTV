package com.nuvio.tv.data.trackerqr

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TrackerQrStartRequest(
    val provider: String
)

@Serializable
data class TrackerQrStartResponse(
    @SerialName("user_code") val userCode: String? = null,
    val url: String? = null,
    @SerialName("expires_at") val expiresAtEpochMs: Long? = null,
    @SerialName("poll_interval_seconds") val pollIntervalSeconds: Int? = null
) {
    val isUsable: Boolean
        get() = !userCode.isNullOrBlank() && !url.isNullOrBlank()
}

@Serializable
data class TrackerQrPollRequest(
    @SerialName("user_code") val userCode: String,
    val provider: String
)

@Serializable
data class TrackerQrPollResponse(
    val status: String,
    val payload: String? = null,
    @SerialName("username") val username: String? = null,
    val error: String? = null
) {
    val isApproved: Boolean
        get() = status.equals("approved", ignoreCase = true)
    val isRejected: Boolean
        get() = status.equals("rejected", ignoreCase = true)
    val isExpired: Boolean
        get() = status.equals("expired", ignoreCase = true) ||
            status.equals("cancelled", ignoreCase = true) ||
            status.equals("used", ignoreCase = true)
}

@Serializable
data class TrackerQrKitsuLoginResponse(
    val ok: Boolean = false,
    val error: String? = null
)