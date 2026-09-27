package com.nuvio.tv.data.repository

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class OmdbAwardsDiskCacheTest {

    private lateinit var file: File

    @Before
    fun setUp() {
        file = File.createTempFile("omdb_awards_cache_test", ".json")
        check(file.delete())
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun `entries survive a new cache instance`() {
        OmdbAwardsDiskCache(file).put("omdb:tt0903747", "Won 13 Primetime Emmys.", 1_000L)

        val reopened = OmdbAwardsDiskCache(file)
        val entry = reopened.get("omdb:tt0903747")
        assertNotNull(entry)
        assertEquals("Won 13 Primetime Emmys.", entry?.raw)
        assertEquals(1_000L, entry?.fetchedAtMs)
    }

    @Test
    fun `putAll stores the same raw under every key`() {
        val cache = OmdbAwardsDiskCache(file)
        cache.putAll(listOf("omdb:tt1", "omdb:id:movie:tmdb:1"), "Won 1 Oscar.", 5L)

        assertEquals("Won 1 Oscar.", cache.get("omdb:tt1")?.raw)
        assertEquals("Won 1 Oscar.", cache.get("omdb:id:movie:tmdb:1")?.raw)

        val reopened = OmdbAwardsDiskCache(file)
        assertEquals("Won 1 Oscar.", reopened.get("omdb:id:movie:tmdb:1")?.raw)
    }

    @Test
    fun `blank raw and empty key sets are ignored`() {
        val cache = OmdbAwardsDiskCache(file)
        cache.put("omdb:tt1", "   ")
        cache.putAll(emptyList(), "Won 1 Oscar.")
        assertNull(cache.get("omdb:tt1"))
    }

    @Test
    fun `entries are evicted beyond the configured maximum`() {
        val cache = OmdbAwardsDiskCache(file, maxEntries = 2)
        cache.put("k1", "v1", 1L)
        cache.put("k2", "v2", 2L)
        cache.put("k3", "v3", 3L)

        assertNull(cache.get("k1"))
        assertNotNull(cache.get("k2"))
        assertNotNull(cache.get("k3"))
    }

    @Test
    fun `corrupt cache file falls back to an empty store`() {
        file.writeText("{ this is not json")

        val cache = OmdbAwardsDiskCache(file)
        assertNull(cache.get("omdb:tt1"))

        cache.put("omdb:tt1", "Won 1 Oscar.", 7L)
        assertEquals("Won 1 Oscar.", cache.get("omdb:tt1")?.raw)
    }

    @Test
    fun `missing cache file yields a miss`() {
        assertNull(OmdbAwardsDiskCache(file).get("omdb:tt1"))
    }
}
