package com.nuvio.tv.ui.components

import com.nuvio.tv.ui.theme.NuvioTheme

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.CardGlow
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import kotlin.math.exp

/**
 * Same elevation [rememberArtworkBackedCardGlow] asks tv-material to draw.
 * `internal`: il viewport delle righe (ModernHomeRows) si ritira di questo
 * valore per non ritagliare più il bagliore a sinistra della prima card.
 */
internal val CardGlowElevation = 28.dp

/**
 * Il bagliore è volutamente bianco puro (richiesta: "lo vorrei bianco").
 * Prima il colore veniva campionato dall'artwork e normalizzato in HSV: ne
 * usciva una tinta diversa per ogni card (verdastro su "Attori", caldo su
 * "Sale Cinematografiche"). Ora è costante, quindi l'alone non cambia tinta
 * nemmeno quando l'artwork finisce di caricare. Alfa 0.5: a 0.92 attorno
 * alle card l'alone risultava troppo intenso ("troppo bagliore attorno").
 */
private val GlowColor = Color.White.copy(alpha = 0.5f)

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
 * Colore del bagliore della card focalizzata: costante e bianco
 * ([GlowColor]). I parametri artwork/seed restano nell'interfaccia perché
 * usati dai call site, ma non servono più a derivare la tinta.
 */
@Composable
internal fun rememberArtworkGlowColor(
    imageUrl: String?,
    fallbackSeed: String,
    enabled: Boolean,
    fallbackColor: Color = NuvioTheme.colors.FocusBackground
): State<Color> {
    return remember { mutableStateOf(GlowColor) }
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
