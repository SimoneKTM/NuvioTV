package com.nuvio.tv.ui.util

import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp

/**
 * Come [Modifier.width], ma legge [widthState] in fase di measure anziché in
 * composizione. Un `Modifier.width(value)` con value derivato da uno stato
 * animato ricomposa l'intero nodo (e tutto il suo albero) a ogni tick
 * dell'animazione; qui il tick invalida solo il measure, quindi il main
 * thread fa misura+layout senza ricomposizione — ed è questa la differenza
 * che riporta il frame entro il tick da 16,7 ms.
 */
fun Modifier.animatedWidth(widthState: State<Dp>): Modifier =
    layout { measurable, constraints ->
        val target = widthState.value.roundToPx()
            .coerceIn(constraints.minWidth, constraints.maxWidth)
        val placeable = measurable.measure(
            constraints.copy(minWidth = target, maxWidth = target)
        )
        layout(target, placeable.height) {
            placeable.place(0, 0)
        }
    }
