package com.nuvio.tv.core.player

/**
 * One tile of the seek-preview filmstrip.
 *
 * [bucketMs] is the anchor of the fixed timeline bucket that owns the tile and
 * doubles as the extraction cache key, so scrubbing inside the same bucket
 * never re-decodes a frame.
 */
data class SeekThumbnailSlot(
    val bucketMs: Long,
    val positionMs: Long,
    val isCenter: Boolean
)

/**
 * Fixed-bucket layout for the seek preview filmstrip.
 *
 * The timeline is split into [BUCKETS_PER_DURATION] buckets and the five
 * visible tiles are anchored on bucket starts, always centered on the bucket
 * that contains the current scrub position. Anchoring on buckets (instead of
 * the exact scrub position) keeps frame extraction cheap: new frames are only
 * needed when the scrub crosses a bucket boundary.
 */
object SeekThumbnailSlots {
    const val VISIBLE_SLOTS = 5

    private const val MIN_STEP_MS = 5_000L
    private const val BUCKETS_PER_DURATION = 20

    /** Width of one bucket in milliseconds. */
    fun stepMs(durationMs: Long): Long {
        if (durationMs <= 0) return 0L
        return (durationMs / BUCKETS_PER_DURATION).coerceAtLeast(MIN_STEP_MS)
    }

    /** The [VISIBLE_SLOTS] tiles to display for the given scrub position. */
    fun slots(durationMs: Long, positionMs: Long): List<SeekThumbnailSlot> {
        if (durationMs <= 0) return emptyList()

        val step = stepMs(durationMs)
        val position = positionMs.coerceIn(0L, durationMs)
        val centerBucket = position / step
        val lastBucket = durationMs / step
        val half = (VISIBLE_SLOTS - 1) / 2
        val maxFirstBucket = (lastBucket - (VISIBLE_SLOTS - 1)).coerceAtLeast(0L)
        val firstBucket = (centerBucket - half).coerceIn(0L, maxFirstBucket)

        return (0 until VISIBLE_SLOTS).map { index ->
            val bucket = (firstBucket + index).coerceAtMost(lastBucket)
            val bucketStart = bucket * step
            SeekThumbnailSlot(
                bucketMs = bucketStart,
                positionMs = position.coerceIn(
                    bucketStart,
                    (bucketStart + step).coerceAtMost(durationMs)
                ),
                isCenter = bucket == centerBucket
            )
        }
    }
}
