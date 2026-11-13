package com.nuvio.tv.ui.util

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import com.nuvio.tv.LocalContentFocusRequester
import com.nuvio.tv.LocalFocusSectionTopBar
import com.nuvio.tv.ui.screens.detail.requestFocusAfterFrames
import kotlinx.coroutines.launch

/**
 * Intercetta SU sulle LazyColumn verticale delle righe (home e schermate sezione).
 *
 * Due tranelli che questo modificatore deve aggirare:
 *  1. se la riga sopra quella attiva non è composta, il focus search non fallisce:
 *     aggancia la barra sezioni (è sempre composta sopra i contenuti) e il focus
 *     esce dalle righe senza che nulla scrolli;
 *  2. se la lista è in cima, MainActivity intercetta comunque SU non consumato e
 *     piazza il focus sulla tab di sezione, saltando tutte le righe intermedie.
 *
 * Qui si muove sempre il focus e si *verifica* dove è finito (lo stato di focus
 * del contenuto è letto a qualche frame di distanza): se è uscito mentre la lista
 * è ancora scrollata, si rientra nel contenuto (focusRestorer riaggancia la card
 * attiva), si scrolla di una riga e si riprove, così la riga emersa viene composta
 * prima del secondo tentativo. Con la riga attiva in cima alla lista l'uscita in
 * barra è corretta e non viene corretta.
 */
fun Modifier.rowsListUpRecovery(state: LazyListState): Modifier = composed {
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val contentFocusRequester = LocalContentFocusRequester.current
    val focusSectionTopBar = LocalFocusSectionTopBar.current
    var focusInside by remember { mutableStateOf(true) }
    var recovering by remember { mutableStateOf(false) }

    onFocusChanged { focusInside = it.hasFocus }
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || event.key != Key.DirectionUp) {
                return@onKeyEvent false
            }
            // Una riperluzione è già in corso: assorbi le pressioni ripetute.
            if (recovering) return@onKeyEvent true
            val topComposed = state.layoutInfo.visibleItemsInfo.firstOrNull()?.index
                ?: return@onKeyEvent false
            recovering = true
            scope.launch {
                val moved = focusManager.moveFocus(FocusDirection.Up)
                // Attendi che gli eventi di focus siano distribuiti prima di leggere.
                repeat(3) { withFrameNanos { } }
                val escaped = !focusInside
                when {
                    // Focus avanzato dentro le righe (la riga sopra era composta):
                    // fatto. Copre anche il caso lista in cima con la riga attiva
                    // alla seconda riga: non si salta più in barra.
                    moved && !escaped -> Unit

                    // Uscito con la lista ancora scrollata: rientra nel contenuto,
                    // scrolla di una riga e riprova, così la riga sopra viene
                    // composta prima del secondo tentativo.
                    escaped && state.canScrollBackward -> {
                        runCatching { contentFocusRequester.requestFocusAfterFrames() }
                        val newTop = state.layoutInfo.visibleItemsInfo.firstOrNull()?.index
                        val target = (newTop ?: topComposed) - 1
                        if (target >= 0) {
                            // Scroll animato, non istantaneo: scendendo il
                            // bringIntoView di Compose anima già la riga,
                            // quindi salendo un salto secco faceva sembrare
                            // il movimento dei poster diverso. Animando anche
                            // qui il gesto è speculare.
                            state.animateScrollToItem(target)
                            repeat(3) { withFrameNanos { } }
                            focusManager.moveFocus(FocusDirection.Up)
                            repeat(3) { withFrameNanos { } }
                            if (!focusInside) {
                                val restored = runCatching {
                                    contentFocusRequester.requestFocusAfterFrames()
                                }.getOrDefault(false)
                                // Rete di sicurezza: il focus non deve mai
                                // restare sul rail laterale dopo un SU.
                                if (!restored) {
                                    focusSectionTopBar?.invoke()
                                }
                            }
                        } else {
                            // Nessuna riga sopra (lista ferma su un item parziale
                            // in testa, es. hero del Calendario): riportala in cima
                            // e consegna il focus alla barra, altrimenti il focus
                            // rimbalza sul contenuto e non si esce mai.
                            state.scrollToItem(0)
                            repeat(3) { withFrameNanos { } }
                            val focused = focusSectionTopBar?.invoke() == true
                            if (!focused) {
                                runCatching { contentFocusRequester.requestFocusAfterFrames() }
                            }
                        }
                    }

                    // Ancora dentro ma nessun candidato composto sopra e la lista
                    // può ancora scrollare: fai emergere la riga sopra e riprova.
                    !moved && !escaped && state.canScrollBackward -> {
                        if (topComposed > 0) {
                            // Vedi sopra: stessa animazione del movimento in
                            // discesa invece del salto istantaneo.
                            state.animateScrollToItem(topComposed - 1)
                            repeat(3) { withFrameNanos { } }
                            focusManager.moveFocus(FocusDirection.Up)
                        } else {
                            // Primo item composto ma lista ancora scrollabile
                            // (item parziale in testa, es. l'hero di Extra).
                            // Non esiste una riga sopra: topComposed - 1 era -1
                            // e faceva crashare l'app (Extra). Riporta la lista
                            // in cima e consegna il focus alla barra sezione.
                            state.scrollToItem(0)
                            repeat(3) { withFrameNanos { } }
                            if (focusSectionTopBar?.invoke() != true) {
                                runCatching {
                                    contentFocusRequester.requestFocusAfterFrames()
                                }
                            }
                        }
                    }

                    // In cima alla lista e il focus non può avanzare: consegnalo
                    // alla barra alta di sezione. Un moveFocus alla cieca qui
                    // aggancia il rail laterale, quindi miriamo alla tab.
                    else -> {
                        val focused = focusSectionTopBar?.invoke() == true
                        if (!focused) {
                            // Fallback: rientra nel contenuto per non lasciare il
                            // focus sul rail.
                            runCatching { contentFocusRequester.requestFocusAfterFrames() }
                        }
                    }
                }
                recovering = false
            }
            true
        }
}
