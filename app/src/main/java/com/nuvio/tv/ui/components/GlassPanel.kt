package com.nuvio.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.nuvio.tv.ui.theme.NuvioTheme

/**
 * Translucent "liquid glass" panel: soft dark scrim whose right/bottom edges
 * dissolve into the artwork (no hard frame), luminous top edge and hairline
 * gradient border. Shared by the detail hero and the calendar hero.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val shape = RoundedCornerShape(20.dp)
    val featherRight = 40.dp
    val featherBottom = 24.dp
    Box(
        modifier = modifier
            .clip(shape)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                val w = size.width
                val h = size.height
                val stopRight = ((w - featherRight.toPx()) / w).coerceIn(0f, 1f)
                val stopBottom = ((h - featherBottom.toPx()) / h).coerceIn(0f, 1f)
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to Color.White,
                        stopRight to Color.White,
                        1f to Color.Transparent
                    ),
                    blendMode = BlendMode.DstIn
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to Color.White,
                        stopBottom to Color.White,
                        1f to Color.Transparent
                    ),
                    blendMode = BlendMode.DstIn
                )
            }
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.60f),
                        Color.Black.copy(alpha = 0.72f)
                    )
                )
            )
            .border(
                border = BorderStroke(
                    NuvioTheme.spacing.hairline,
                    Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.35f),
                            Color.White.copy(alpha = 0.12f),
                            Color.White.copy(alpha = 0.06f)
                        )
                    )
                ),
                shape = shape
            )
    ) {
        // Subtle specular line along the top edge: reads as glass, not plastic.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.5.dp)
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.28f),
                            Color.White.copy(alpha = 0.10f),
                            Color.White.copy(alpha = 0.03f)
                        )
                    )
                )
        )
        // Right/bottom padding keeps text inside the solid zone so the
        // feathered edges only eat empty space, never the glyphs.
        Column(
            modifier = Modifier.padding(
                start = 16.dp,
                top = 6.dp,
                end = 16.dp + featherRight,
                bottom = 8.dp + featherBottom
            ),
            content = content
        )
    }
}
