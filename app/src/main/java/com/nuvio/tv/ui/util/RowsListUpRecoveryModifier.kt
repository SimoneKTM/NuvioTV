package com.nuvio.tv.ui.util

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.launch

/**
 * Intercetta SU non consumato sulla LazyColumn verticale delle righe
 * (home e schermate sezione): quando la riga sopra quella attiva non è
 * composta (lista scrollata in basso e prefetch non ancora pronto) il
 * focus search fallisce e l'evento salirebbe fino a MainActivity, che
 * piazza il focus dritto sulla barra sezioni saltando tutte le righe
 * intermedie. Qui risaliamo di una riga scrollando, attendiamo la
 * composizione della riga emersa e riproviamo il focus search, che
 * atterra sulla card sopra quella attiva. Con la riga attiva già in cima
 * alla lista l'evento resta libero di salire in barra, com'è corretto.
 * Stesso comportamento della gestione SU di ModernHomeRowsList.
 */
fun Modifier.rowsListUpRecovery(state: LazyListState): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionUp) {
            return@onKeyEvent false
        }
        val topComposed = state.layoutInfo.visibleItemsInfo.firstOrNull()?.index
            ?: return@onKeyEvent false
        if (topComposed <= 0) return@onKeyEvent false
        // Se la riga sopra è composta (visibile o coperta dall'hero) il
        // focus search normale deve potersi muovere; se è solo bloccato
        // (focus in transizione) il tentativo non costa nulla.
        if (focusManager.moveFocus(FocusDirection.Up)) return@onKeyEvent true
        if (!state.canScrollBackward) return@onKeyEvent false
        scope.launch {
            state.scrollToItem(topComposed - 1)
            // Attendi la composizione della riga emersa prima di riprovare.
            repeat(3) { withFrameNanos { } }
            focusManager.moveFocus(FocusDirection.Up)
        }
        true
    }
}
