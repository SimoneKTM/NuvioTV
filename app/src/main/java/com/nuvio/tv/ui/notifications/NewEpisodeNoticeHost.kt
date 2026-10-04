package com.nuvio.tv.ui.notifications

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

// La notifica della libreria sparisce da sola dopo 5 secondi.
private const val NOTICE_AUTO_DISMISS_MS = 5_000L

/**
 * Top-of-screen host for the "new episode in your library" notification.
 *
 * Mirrors [com.nuvio.tv.updater.ui.UpdateBannerHost]: the banner expands from
 * the top and the app content is pushed below it while visible.
 */
@Composable
fun NewEpisodeNoticeHost(
    state: NewEpisodeNoticeUiState,
    onCheck: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(Unit) { onCheck() }

    // La notifica sparisce da sola dopo 5 secondi.
    val visible = state.showBanner && state.notices.isNotEmpty()
    LaunchedEffect(visible) {
        if (visible) {
            kotlinx.coroutines.delay(NOTICE_AUTO_DISMISS_MS)
            onDismiss()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                onCheck()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            onCheck()
        }
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = expandVertically(
                expandFrom = Alignment.Top,
                animationSpec = tween(durationMillis = 300)
            ) + fadeIn(animationSpec = tween(durationMillis = 180)),
            exit = shrinkVertically(
                shrinkTowards = Alignment.Top,
                animationSpec = tween(durationMillis = 240)
            ) + fadeOut(animationSpec = tween(durationMillis = 150))
        ) {
            NewEpisodeNoticeBanner(
                notices = state.notices,
                onDismiss = onDismiss
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            content()
        }
    }
}
