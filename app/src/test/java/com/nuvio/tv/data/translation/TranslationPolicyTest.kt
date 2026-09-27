package com.nuvio.tv.data.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPolicyTest {

    private val longText = "a".repeat(80)

    @Test
    fun `shouldTranslate rejects blank short and oversized text`() {
        assertFalse(shouldTranslate(null, "it"))
        assertFalse(shouldTranslate("   ", "it"))
        assertFalse(shouldTranslate("a".repeat(39), "it"))
        assertTrue(shouldTranslate("a".repeat(40), "it"))
        assertFalse(shouldTranslate("a".repeat(2001), "it"))
    }

    @Test
    fun `shouldTranslate rejects unsupported or missing target languages`() {
        assertFalse(shouldTranslate(longText, null))
        assertFalse(shouldTranslate(longText, "  "))
        assertFalse(shouldTranslate(longText, "xx"))
        assertFalse(shouldTranslate(longText, "haw"))
        assertTrue(shouldTranslate(longText, "it"))
        assertTrue(shouldTranslate(longText, "pt-BR"))
    }

    @Test
    fun `shouldTranslateDetected skips und and same language text`() {
        assertTrue(shouldTranslateDetected(longText, "en", "it"))
        assertFalse(shouldTranslateDetected(longText, "it", "it"))
        assertFalse(shouldTranslateDetected(longText, null, "it"))
        assertFalse(shouldTranslateDetected(longText, "und", "it"))
        assertFalse(shouldTranslateDetected("short", "en", "it"))
    }

    @Test
    fun `normalizeLanguageCode trims lowercases and resolves aliases`() {
        assertEquals("it", normalizeLanguageCode("IT"))
        assertEquals("pt", normalizeLanguageCode("pt-BR"))
        assertEquals("pt", normalizeLanguageCode("pt_BR"))
        assertEquals("he", normalizeLanguageCode("iw"))
        assertEquals("id", normalizeLanguageCode("in"))
        assertEquals("fil", normalizeLanguageCode("tl"))
        assertNull(normalizeLanguageCode(null))
        assertNull(normalizeLanguageCode(""))
        assertNull(normalizeLanguageCode("und"))
        assertNull(normalizeLanguageCode("  "))
    }

    @Test
    fun `translationCacheKey is stable and unique per input`() {
        val key = translationCacheKey("it", longText)
        assertEquals(key, translationCacheKey("it", longText))
        assertNotEquals(key, translationCacheKey("en", longText))
        assertNotEquals(key, translationCacheKey("it", "$longText!"))
    }

    @Test
    fun `short texts pass only with the short minimum length`() {
        val award = "Won 1 Oscar. 4 nominations total."
        assertFalse(shouldTranslate(award, "it"))
        assertTrue(shouldTranslate(award, "it", TRANSLATION_SHORT_MIN_TEXT_LENGTH))
        assertFalse(
            shouldTranslate("!!", "it", TRANSLATION_SHORT_MIN_TEXT_LENGTH)
        )
        assertFalse(
            shouldTranslate(award, "it", TRANSLATION_SHORT_MIN_TEXT_LENGTH + award.length)
        )
    }

    @Test
    fun `detected short awards translate from english to italian`() {
        val award = "Won 1 Oscar. 4 nominations total."
        assertTrue(
            shouldTranslateDetected(award, "en", "it", TRANSLATION_SHORT_MIN_TEXT_LENGTH)
        )
        assertFalse(
            shouldTranslateDetected(award, "it", "it", TRANSLATION_SHORT_MIN_TEXT_LENGTH)
        )
        assertFalse(
            shouldTranslateDetected(award, "und", "it", TRANSLATION_SHORT_MIN_TEXT_LENGTH)
        )
    }
}
