package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvPlaylist
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

@Singleton
class LiveTvRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context
) {
    suspend fun fetchPlaylist(playlist: LiveTvPlaylist): Result<List<LiveTvChannel>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val playlistUrl = fetchUrl(playlist)
                val request = Request.Builder()
                    .url(playlistUrl)
                    .header("User-Agent", "NuvioTV/1.0 (Live TV)")
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error(context.getString(R.string.live_tv_error_http, response.code, playlist.name))
                    }
                    val body = response.body?.string()
                        ?: error(context.getString(R.string.live_tv_error_empty_playlist))
                    parseM3u(body, playlist, playlistUrl)
                }
            }
        }

    fun fetchUrl(playlist: LiveTvPlaylist): String =
        if (playlist.isXtream) {
            xtreamPlaylistUrl(
                playlist.xtreamServerUrl.orEmpty(),
                playlist.xtreamUsername.orEmpty(),
                playlist.xtreamPassword.orEmpty()
            )
        } else {
            playlist.sourceUrl
        }

    fun parseM3u(content: String, playlist: LiveTvPlaylist, playlistUrl: String = playlist.sourceUrl): List<LiveTvChannel> {
        // UTF-8 BOM from some playlist servers breaks the first line check.
        val normalized = content.removePrefix("\uFEFF")
        if (!looksLikeM3u(normalized)) {
            error(context.getString(R.string.live_tv_error_invalid_playlist))
        }
        val baseUrl = playlistUrl.toHttpUrlOrNull()
        val lines = normalized.lines()
        val channels = mutableListOf<LiveTvChannel>()
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var index = 0

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    val commaIndex = line.indexOf(',')
                    val rawName = if (commaIndex >= 0) line.substring(commaIndex + 1).trim() else ""
                    val attrs = line.substring(
                        line.indexOf(':') + 1,
                        if (commaIndex >= 0) commaIndex else line.length
                    )
                    pendingLogo = attrValue(attrs, "tvg-logo")
                    pendingGroup = attrValue(attrs, "group-title")
                    pendingName = rawName.ifBlank { null }
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroup = line.substringAfter(':').trim().ifBlank { null }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val streamUrl = resolveStreamUrl(line, baseUrl)
                    val channelName = pendingName?.takeIf(String::isNotBlank)
                        ?: streamUrl.substringAfterLast('/').takeIf(String::isNotBlank)
                        ?: context.getString(R.string.live_tv_channel_fallback, index + 1)
                    channels += LiveTvChannel(
                        id = "${playlist.id}|$index|$streamUrl",
                        name = channelName,
                        group = pendingGroup,
                        logo = pendingLogo,
                        streamUrl = streamUrl
                    )
                    index += 1
                    pendingName = null
                    pendingLogo = null
                    pendingGroup = null
                }
            }
        }
        if (channels.isEmpty()) {
            error(context.getString(R.string.live_tv_error_invalid_playlist))
        }
        return channels
    }

    private fun looksLikeM3u(content: String): Boolean {
        val firstLine = content.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return firstLine.startsWith("#EXTM3U", ignoreCase = true) ||
            content.contains("#EXTINF", ignoreCase = true)
    }

    private fun resolveStreamUrl(line: String, baseUrl: okhttp3.HttpUrl?): String {
        if (line.startsWith("http://", ignoreCase = true) || line.startsWith("https://", ignoreCase = true)) {
            return line
        }
        return baseUrl?.resolve(line)?.toString() ?: line
    }

    private val attrTokenRegex = Regex("""([A-Za-z0-9_-]+)\s*=\s*(?:"([^"]*)"|([^\s"]+))""")

    private fun attrValue(attrs: String, key: String): String? {
        return attrTokenRegex.findAll(attrs)
            .firstOrNull { it.groupValues[1].equals(key, ignoreCase = true) }
            ?.groupValues
            ?.let { values -> values[2].ifEmpty { values[3] } }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { decode(it) }
    }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

    companion object {
        fun xtreamPlaylistUrl(serverUrl: String, username: String, password: String): String {
            val base = serverUrl.trimEnd('/')
            val encodedUser = java.net.URLEncoder.encode(username.trim(), StandardCharsets.UTF_8.name())
            val encodedPassword = java.net.URLEncoder.encode(password, StandardCharsets.UTF_8.name())
            return "$base/get.php?username=$encodedUser&password=$encodedPassword&type=m3u_plus"
        }

        fun displayNameForUrl(url: String): String {
            val decoded = URLDecoder.decode(url, StandardCharsets.UTF_8.name())
            val host = decoded.toHttpUrlOrNull()?.host ?: decoded.substringAfter("://").substringBefore('/')
            val lastPath = decoded.substringAfterLast('/').takeIf { it.isNotBlank() }
            return when {
                lastPath?.endsWith(".m3u", ignoreCase = true) == true ||
                    lastPath?.endsWith(".m3u8", ignoreCase = true) == true -> {
                    lastPath.substringBeforeLast('.')
                }
                else -> host
            }
        }
    }
}