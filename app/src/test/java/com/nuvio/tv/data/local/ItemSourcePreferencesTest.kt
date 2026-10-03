package com.nuvio.tv.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ItemSourcePreferencesTest {

    @Test
    fun `keysFor normalizes tv variants into one series key`() {
        val fromRoute = ItemSourcePreferences.keysFor("series", "tt13196080")
        val fromCalendar = ItemSourcePreferences.keysFor("tv", "tt13196080")
        val fromTraktRaw = ItemSourcePreferences.keysFor("anime", "TT13196080")

        assertEquals(listOf("series:tt13196080"), fromRoute)
        assertEquals(fromRoute, fromCalendar)
        assertEquals(fromRoute, fromTraktRaw)
    }

    @Test
    fun `keysFor keeps movie and series numeric ids apart`() {
        val movie = ItemSourcePreferences.keysFor("movie", "603")
        val series = ItemSourcePreferences.keysFor("tv", "603")

        assertEquals(listOf("movie:603"), movie)
        assertEquals(listOf("series:603"), series)
    }

    @Test
    fun `keysFor drops blank ids and dedupes aliases`() {
        val keys = ItemSourcePreferences.keysFor("series", "tt13196080", "", "  ", "tt13196080", null)

        assertEquals(listOf("series:tt13196080"), keys)
        assertNull(ItemSourcePreferences.key("series", "   "))
    }

    @Test
    fun `keysFor keeps distinct alias forms of the same title`() {
        val keys = ItemSourcePreferences.keysFor(
            "series", "tmdb:13196080", "13196080", "tt13196080"
        )

        assertEquals(
            listOf("series:tmdb:13196080", "series:13196080", "series:tt13196080"),
            keys
        )
    }

    @Test
    fun `trimToCapacity drops eldest entries first`() {
        val map = LinkedHashMap<String, String>()
        repeat(ItemSourcePreferences.MAX_ENTRIES + 10) { index ->
            map["series:tt$index"] = "https://addon$index"
        }

        ItemSourcePreferences.trimToCapacity(map, ItemSourcePreferences.MAX_ENTRIES)

        assertEquals(ItemSourcePreferences.MAX_ENTRIES, map.size)
        assertNull(map["series:tt0"])
        assertNull(map["series:tt9"])
        assertTrue(map.containsKey("series:tt${ItemSourcePreferences.MAX_ENTRIES + 9}"))
    }

    @Test
    fun `parseStored survives garbage and keeps insertion order`() {
        assertTrue(ItemSourcePreferences.parseStored(null).isEmpty())
        assertTrue(ItemSourcePreferences.parseStored("not-json").isEmpty())

        val parsed = ItemSourcePreferences.parseStored(
            """{"series:tt1":"https://a","series:tt2":"https://b"}"""
        )

        assertEquals(listOf("series:tt1", "series:tt2"), parsed.keys.toList())
        assertEquals("https://b", parsed["series:tt2"])
    }
}
