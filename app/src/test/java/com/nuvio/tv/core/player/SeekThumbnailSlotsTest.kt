package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekThumbnailSlotsTest {

    private val fortyMinutesMs = 40L * 60L * 1000L

    @Test
    fun stepMs_splitsDurationIntoTwentyBuckets() {
        assertEquals(120_000L, SeekThumbnailSlots.stepMs(fortyMinutesMs))
        assertEquals(5_000L, SeekThumbnailSlots.stepMs(90_000L))
        assertEquals(0L, SeekThumbnailSlots.stepMs(0L))
    }

    @Test
    fun slots_returnsFiveTiles() {
        val slots = SeekThumbnailSlots.slots(fortyMinutesMs, 20 * 60 * 1000L)
        assertEquals(SeekThumbnailSlots.VISIBLE_SLOTS, slots.size)
    }

    @Test
    fun slots_marksTileContainingScrubPositionAsCenter() {
        val positionMs = 12 * 60 * 1000L
        val slots = SeekThumbnailSlots.slots(fortyMinutesMs, positionMs)

        val center = slots.single { it.isCenter }
        assertTrue(positionMs >= center.bucketMs)
        assertTrue(positionMs <= center.bucketMs + SeekThumbnailSlots.stepMs(fortyMinutesMs))
    }

    @Test
    fun slots_areConsecutiveBuckets() {
        val slots = SeekThumbnailSlots.slots(fortyMinutesMs, 20 * 60 * 1000L)
        val step = SeekThumbnailSlots.stepMs(fortyMinutesMs)

        slots.zipWithNext().forEach { (current, next) ->
            assertEquals(step, next.bucketMs - current.bucketMs)
        }
    }

    @Test
    fun slots_stayWithinDurationAtTheEdges() {
        listOf(0L, 1L, fortyMinutesMs - 1L, fortyMinutesMs).forEach { position ->
            val slots = SeekThumbnailSlots.slots(fortyMinutesMs, position)
            slots.forEach { slot ->
                assertTrue(slot.bucketMs in 0L..fortyMinutesMs)
                assertTrue(slot.positionMs in 0L..fortyMinutesMs)
            }
            assertEquals(1, slots.count { it.isCenter })
        }
    }

    @Test
    fun slots_areEmptyWithoutDuration() {
        assertTrue(SeekThumbnailSlots.slots(0L, 1_000L).isEmpty())
        assertTrue(SeekThumbnailSlots.slots(-5L, 1_000L).isEmpty())
    }

    @Test
    fun slots_rejectOutOfRangePosition() {
        val slots = SeekThumbnailSlots.slots(fortyMinutesMs, fortyMinutesMs + 60_000L)
        slots.forEach { slot ->
            assertTrue(slot.positionMs <= fortyMinutesMs)
        }
        assertFalse(slots.none { it.isCenter })
    }
}
