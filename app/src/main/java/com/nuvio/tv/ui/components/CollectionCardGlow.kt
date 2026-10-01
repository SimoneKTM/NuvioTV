package com.nuvio.tv.ui.components

import com.nuvio.tv.ui.theme.NuvioTheme

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardGlow
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.max

/** Same elevation [rememberArtworkBackedCardGlow] asks tv-material to draw. */
private val CardGlowElevation = 28.dp

/**
 * Hardware fallback for devices where [rememberArtworkBackedCardGlow] is a no-op.
 *
 * tv-material draws `Glow` with `Paint.setShadowLayer`, which the hardware-accelerated
 * pipeline only supports for non-text drawing from API 28 (see the "setShadowLayer()
 * (other than text) | 28" row of the Android hardware acceleration support table).
 * Fire OS 6 sticks (Android 7.x, API 24-27) therefore render the glow's transparent
 * fill and silently drop the blur, leaving the focused collection card without any
 * bagliore. Those devices get the same halo rebuilt from concentric strokes instead.
 */
private const val LegacyGlowBands = 14

/** Fraction of the glow alpha sitting right at the card edge (blurred-edge parity). */
private const val LegacyGlowEdgeAlpha = 0.55f

/** Gaussian falloff of the fallback halo across the glow radius. */
private const val LegacyGlowFalloff = 3.5f

@Composable
fun rememberArtworkBackedCardGlow(
    imageUrl: String?,
    fallbackSeed: String,
    enabled: Boolean,
    fallbackColor: Color = NuvioTheme.colors.FocusBackground
): CardGlow {
    val noGlow = remember { CardDefaults.glow(focusedGlow = Glow.None) }
    if (!enabled) return noGlow

    val glowColor = rememberArtworkGlowColor(imageUrl, fallbackSeed, enabled, fallbackColor).value

    return remember(glowColor) {
        CardDefaults.glow(
            focusedGlow = Glow(
                elevationColor = glowColor,
                elevation = CardGlowElevation
            )
        )
    }
}

/**
 * Halo drawn behind a focused collection card on API levels where the tv-material
 * `Glow` cannot render (see [LegacyGlowBands]). Returns [Modifier] everywhere else,
 * so call sites can chain it unconditionally.
 */
@Composable
internal fun rememberLegacyCardGlowHalo(
    imageUrl: String?,
    fallbackSeed: String,
    enabled: Boolean,
    focused: Boolean,
    shape: Shape,
    fallbackColor: Color = NuvioTheme.colors.FocusBackground
): Modifier {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P || !enabled) return Modifier
    val glowColor = rememberArtworkGlowColor(imageUrl, fallbackSeed, enabled, fallbackColor).value
    if (!focused || glowColor.alpha <= 0f) return Modifier
    return Modifier.legacyCardGlowHalo(shape, glowColor, CardGlowElevation)
}

/**
 * Samples the card artwork down to the color the focus glow should carry, falling
 * back to a seeded accent when there is no image to sample.
 */
@Composable
internal fun rememberArtworkGlowColor(
    imageUrl: String?,
    fallbackSeed: String,
    enabled: Boolean,
    fallbackColor: Color = NuvioTheme.colors.FocusBackground
): State<Color> {
    val context = LocalContext.current
    val glowColor = remember(imageUrl, fallbackSeed, fallbackColor) {
        mutableStateOf(deriveFallbackGlowColor(fallbackSeed, fallbackColor))
    }

    LaunchedEffect(context, imageUrl, fallbackSeed, fallbackColor, enabled) {
        if (!enabled) {
            glowColor.value = deriveFallbackGlowColor(fallbackSeed, fallbackColor)
            return@LaunchedEffect
        }

        val fallback = deriveFallbackGlowColor(fallbackSeed, fallbackColor)
        if (imageUrl.isNullOrBlank()) {
            glowColor.value = fallback
            return@LaunchedEffect
        }

        glowColor.value = withContext(Dispatchers.IO) {
            val request = ImageRequest.Builder(context)
                .data(imageUrl)
                .allowHardware(false)
                .size(coil3.size.Size(96, 96))
                .build()
            val result = context.imageLoader.execute(request)
            val image = (result as? SuccessResult)?.image ?: return@withContext fallback
            val bitmap = (image as? coil3.BitmapImage)?.bitmap ?: return@withContext fallback
            sampledGlowColor(bitmap)
                ?: fallback
        }
    }

    return glowColor
}

private fun Modifier.legacyCardGlowHalo(
    shape: Shape,
    color: Color,
    elevation: Dp
): Modifier = drawWithCache {
    val elevationPx = elevation.toPx()
    val outline = if (elevationPx > 0f) {
        shape.createOutline(size, layoutDirection, this)
    } else {
        null
    }
    val bounds: Rect
    val baseRadius: CornerRadius
    when (outline) {
        is Outline.Rounded -> {
            bounds = Rect(
                outline.roundRect.left,
                outline.roundRect.top,
                outline.roundRect.right,
                outline.roundRect.bottom
            )
            baseRadius = outline.roundRect.topLeftCornerRadius
        }
        is Outline.Rectangle -> {
            bounds = outline.rect
            baseRadius = CornerRadius(0f, 0f)
        }
        else -> return@drawWithCache onDrawBehind { }
    }

    val bandWidth = elevationPx / LegacyGlowBands
    val bandAlphas = FloatArray(LegacyGlowBands) { index ->
        val position = (index + 0.5f) / LegacyGlowBands
        color.alpha * LegacyGlowEdgeAlpha * exp(-LegacyGlowFalloff * position * position)
    }

    onDrawBehind {
        for (index in 0 until LegacyGlowBands) {
            // Disjoint rings from the card edge out to `elevation`, each one fainter
            // than the last — a stroke-based stand-in for the blur we cannot draw.
            val inset = bandWidth * (index + 0.5f)
            drawRoundRect(
                color = color.copy(alpha = bandAlphas[index]),
                topLeft = Offset(bounds.left - inset, bounds.top - inset),
                size = Size(bounds.width + inset * 2f, bounds.height + inset * 2f),
                cornerRadius = CornerRadius(
                    baseRadius.x + inset,
                    baseRadius.y + inset
                ),
                style = Stroke(width = bandWidth)
            )
        }
    }
}

private fun sampledGlowColor(bitmap: Bitmap): Color? {
    if (bitmap.width <= 0 || bitmap.height <= 0) return null

    val stepX = max(1, bitmap.width / 12)
    val stepY = max(1, bitmap.height / 12)
    var weightedRed = 0f
    var weightedGreen = 0f
    var weightedBlue = 0f
    var totalWeight = 0f

    for (y in 0 until bitmap.height step stepY) {
        for (x in 0 until bitmap.width step stepX) {
            val pixel = bitmap.getPixel(x, y)
            val alpha = android.graphics.Color.alpha(pixel) / 255f
            if (alpha < 0.35f) continue

            weightedRed += android.graphics.Color.red(pixel) * alpha
            weightedGreen += android.graphics.Color.green(pixel) * alpha
            weightedBlue += android.graphics.Color.blue(pixel) * alpha
            totalWeight += alpha
        }
    }

    if (totalWeight <= 0f) return null

    return stabilizeGlowColor(
        Color(
            red = (weightedRed / totalWeight) / 255f,
            green = (weightedGreen / totalWeight) / 255f,
            blue = (weightedBlue / totalWeight) / 255f,
            alpha = 0.92f
        )
    )
}

private fun deriveFallbackGlowColor(seed: String, fallbackColor: Color): Color {
    if (seed.isBlank()) return stabilizeGlowColor(fallbackColor)
    val hue = ((seed.hashCode().toLong() and 0xffffffffL) % 360L).toFloat()
    val seededAccent = Color.hsv(hue = hue, saturation = 0.48f, value = 0.82f)
    return stabilizeGlowColor(lerp(fallbackColor, seededAccent, 0.55f))
}

private fun stabilizeGlowColor(color: Color): Color {
    val opaque = color.copy(alpha = 1f)
    val balanced = when {
        opaque.luminance() < 0.18f -> lerp(opaque, Color.White, 0.30f)
        opaque.luminance() > 0.84f -> lerp(opaque, Color.Black, 0.18f)
        else -> opaque
    }
    return balanced.copy(alpha = 0.92f)
}
