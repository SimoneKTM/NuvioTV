package com.nuvio.tv.core.image

import coil3.intercept.ImageResult
import coil3.intercept.Interceptor
import coil3.size.Dimension
import coil3.size.Size

/**
 * Clamp universale delle decodifiche immagine — la scelta di Prime Video:
 * card compatte a decode fissa. Su una Stick da 1 GB ogni bitmap decodificata
 * a pieno formato durante lo scroll veloce è benzina per l'LMK (kill del
 * 14/10 ore 14:17: RSS 438 MB durante scroll, processo totale 572 MB).
 * Le card mostrano al massimo ~400 px: decodere con lato lungo ≤
 * [MAX_DECODE_PX] è indistinguibile a schermo e taglia la corsa RSS di ~4x.
 *
 * Registrato come interceptor globale in NuvioApplication.newImageLoader:
 * copre poster, sfondi, loghi e miniature senza toccare le chiamate.
 */
class ClampDecodeInterceptor : Interceptor {

    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request()
        val size = runCatching { request.sizeResolver.size() }.getOrNull()
            ?: return chain.proceed(request)
        val widthPx = (size.width as? Dimension.Pixels)?.px
        val heightPx = (size.height as? Dimension.Pixels)?.px
        val cappedWidth =
            if (widthPx != null && widthPx > MAX_DECODE_PX) Dimension.Pixels(MAX_DECODE_PX) else size.width
        val cappedHeight =
            if (heightPx != null && heightPx > MAX_DECODE_PX) Dimension.Pixels(MAX_DECODE_PX) else size.height
        if (cappedWidth == size.width && cappedHeight == size.height) {
            return chain.proceed(request)
        }
        return chain.proceed(
            request.newBuilder()
                .size(Size(cappedWidth, cappedHeight))
                .build()
        )
    }

    companion object {
        const val MAX_DECODE_PX = 960
    }
}
