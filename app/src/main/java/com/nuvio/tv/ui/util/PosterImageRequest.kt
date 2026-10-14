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
): ImageRequest? {
    // Clamp (stile Prime Video, vedi ClampDecodeInterceptor): le card sono
    // ~400px a schermo, decodere a pieno formato durante lo scroll è benzina
    // per l'LMK. Applicato anche alle chiavi di cache: due viste diverse che
    // collassano sullo stesso target condividono la stessa voce.
    val cappedWidth = widthPx.coerceAtMost(960)
    val cappedHeight = heightPx.coerceAtMost(960)
    return posterMemoryCacheKey(url, cappedWidth, cappedHeight)?.let { cacheKey ->
        ImageRequest.Builder(context)
            .data(url!!)
            .crossfade(crossfade)
            .memoryCacheKey(cacheKey)
            .size(width = cappedWidth, height = cappedHeight)
            .build()
    }
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
