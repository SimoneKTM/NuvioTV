package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvPlaylist
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
    private val okHttpClient: OkHttpClient
) {
    suspend fun fetchPlaylist(playlist: LiveTvPlaylist): Result<List<LiveTvChannel>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(playlist.sourceUrl)
                    .header("User-Agent", "NuvioTV/1.0 (Live TV)")
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        error("HTTP ${response.code} per ${playlist.name}")
                    }
                    val body = response.body?.string() ?: error("Playlist vuota")
                    parseM3u(body, playlist)
                }
            }
        }

    fun parseM3u(content: String, playlist: LiveTvPlaylist): List<LiveTvChannel> {
        val baseUrl = playlist.sourceUrl.toHttpUrlOrNull()
        val lines = content.lines()
        val channels = mutableListOf<LiveTvChannel>()
        var pendingName: String? = null
        var pendingLogo: String? = null
        var pendingGroup: String? = null
        var index = 0

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTINF") -> {
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
                line.startsWith("#EXTGRP:") -> {
                    pendingGroup = line.substringAfter(':').trim().ifBlank { null }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val streamUrl = resolveStreamUrl(line, baseUrl)
                    val channelName = pendingName?.takeIf(String::isNotBlank)
                        ?: streamUrl.substringAfterLast('/').takeIf(String::isNotBlank)
                        ?: "Canale ${index + 1}"
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
        return channels
    }

    private fun resolveStreamUrl(line: String, baseUrl: okhttp3.HttpUrl?): String {
        if (line.startsWith("http://", ignoreCase = true) || line.startsWith("https://", ignoreCase = true)) {
            return line
        }
        return baseUrl?.resolve(line)?.toString() ?: line
    }

    private fun attrValue(attrs: String, key: String): String? {
        val regex = Regex("""$key="([^"]*)"""")
        return regex.find(attrs)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { decode(it) }
    }

    private fun decode(value: String): String = runCatching {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }.getOrDefault(value)

    companion object {
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