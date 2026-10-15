package com.nuvio.tv.ui.components

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import com.nuvio.tv.ui.theme.NuvioFocus

@OptIn(ExperimentalFoundationApi::class)
object NuvioScrollDefaults {
    val smoothScrollSpec = object : BringIntoViewSpec {
        // Snap secco alla stile Prime: molla critica senza overshoot che
        // completa lo scorrimento in ~120ms (stiffness 1500f). La versione
        // precedente passava durations.fast (90) come stiffness — un valore di
        // durata in ms usato al posto della rigidità — producendo animazioni
        // di ~500-700ms per singola pressione e frame p50 a 42ms.
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override val scrollAnimationSpec: AnimationSpec<Float> = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = 1500f
        )

        override fun calculateScrollDistance(
            offset: Float,
            size: Float,
            containerSize: Float
        ): Float {
            if (containerSize <= 0f || size <= 0f) return 0f
            val itemCenter = offset + size / 2f
            val viewportTarget = containerSize * NuvioFocus.tokens.scrollViewportTarget
            return itemCenter - viewportTarget
        }
    }
}
