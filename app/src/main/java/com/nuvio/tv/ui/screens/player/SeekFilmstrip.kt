package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nuvio.tv.core.player.SeekThumbnailEntryPoint
import com.nuvio.tv.core.player.SeekThumbnailGenerator
import com.nuvio.tv.core.player.SeekThumbnailSlots
import com.nuvio.tv.ui.theme.NuvioTheme
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlin.math.abs

private const val SEEK_FILMSTRIP_DEBOUNCE_MS = 220L
private val SEEK_FILMSTRIP_TILE_WIDTH = 116.dp
private val SEEK_FILMSTRIP_TILE_HEIGHT = 66.dp

/**
 * Netflix-style filmstrip of frame previews shown above the seek bar while the
 * user scrubs. Frames come from [SeekThumbnailGenerator] and are cached per
 * timeline bucket, so holding a direction key only decodes a new frame when the
 * scrub crosses a bucket boundary.
 *
 * Renders nothing when the stream cannot provide frames.
 */
@Composable
fun SeekFilmstrip(
    streamUrl: String?,
    headers: Map<String, String>,
    currentPosition: Long,
    duration: Long,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val generator = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            SeekThumbnailEntryPoint::class.java
        ).seekThumbnailGenerator()
    }

    DisposableEffect(streamUrl) {
        onDispose { generator.release() }
    }

    val slots = remember(duration, currentPosition) {
        SeekThumbnailSlots.slots(duration, currentPosition)
    }
    val bucketKey = remember(slots) { slots.map { it.bucketMs } }
    val stepMs = remember(duration) { SeekThumbnailSlots.stepMs(duration) }
    val supported = visible && !streamUrl.isNullOrBlank() && generator.isSupported(streamUrl)

    val frames = remember(streamUrl) { mutableStateMapOf<Long, Bitmap?>() }
    val failedBuckets = remember(streamUrl) { mutableSetOf<Long>() }

    LaunchedEffect(streamUrl, bucketKey, supported) {
        if (!supported || streamUrl == null || stepMs <= 0L) return@LaunchedEffect
        delay(SEEK_FILMSTRIP_DEBOUNCE_MS)
        // Center tile first so the strip anchors visually before its edges fill in.
        val ordered = slots.sortedBy { slot ->
            abs((slot.bucketMs + stepMs / 2) - currentPosition)
        }
        for (slot in ordered) {
            if (frames.containsKey(slot.bucketMs) || slot.bucketMs in failedBuckets) continue
            val frame = generator.getFrame(
                url = streamUrl,
                headers = headers,
                positionMs = slot.bucketMs + stepMs / 2
            )
            if (frame != null) {
                frames[slot.bucketMs] = frame
            } else {
                failedBuckets += slot.bucketMs
            }
        }
    }

    AnimatedVisibility(
        visible = supported && frames.isNotEmpty(),
        enter = fadeIn(animationSpec = tween(160)),
        exit = fadeOut(animationSpec = tween(140)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = NuvioTheme.spacing.sm),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(NuvioTheme.radii.sm))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                slots.forEach { slot ->
                    val frame = frames[slot.bucketMs]
                    val tileShape = RoundedCornerShape(4.dp)
                    Box(
                        modifier = Modifier
                            .size(
                                width = SEEK_FILMSTRIP_TILE_WIDTH,
                                height = SEEK_FILMSTRIP_TILE_HEIGHT
                            )
                            .clip(tileShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .then(
                                if (slot.isCenter) {
                                    Modifier.border(
                                        width = 2.dp,
                                        color = NuvioTheme.colors.Secondary,
                                        shape = tileShape
                                    )
                                } else {
                                    Modifier
                                }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        frame?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}
