package com.nuvio.tv.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.crossfade

/**
 * Single source of truth for poster image requests: identical cache key and
 * decode size for every grid (Home, Details, Library, Search, Discover) so the
 * same decoded bitmap is shared instead of re-decoded per screen.
 */
fun posterMemoryCacheKey(url: String?, widthPx: Int, heightPx: Int): String? =
    url?.takeIf { it.isNotBlank() }?.let { "${it}_${widthPx}x${heightPx}" }

fun posterImageRequest(
    context: PlatformContext,
    url: String?,
    widthPx: Int,
    heightPx: Int,
    crossfade: Boolean = true,
): ImageRequest? = posterMemoryCacheKey(url, widthPx, heightPx)?.let { cacheKey ->
    ImageRequest.Builder(context)
        .data(url!!)
        .crossfade(crossfade)
        .memoryCacheKey(cacheKey)
        .size(width = widthPx, height = heightPx)
        .build()
}

@Composable
fun rememberPosterImageRequest(
    url: String?,
    widthPx: Int,
    heightPx: Int,
    crossfade: Boolean = true,
): ImageRequest? {
    val context = LocalContext.current
    return remember(context, url, widthPx, heightPx, crossfade) {
        posterImageRequest(context, url, widthPx, heightPx, crossfade)
    }
}
