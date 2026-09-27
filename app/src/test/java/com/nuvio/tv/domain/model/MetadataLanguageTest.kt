package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataLanguageTest {

    @Test
    fun `blank preference resolves to the system language`() {
        val system = systemMetadataLanguage()
        assertEquals(system, resolveMetadataLanguage(null))
        assertEquals(system, resolveMetadataLanguage(""))
        assertEquals(system, resolveMetadataLanguage("   "))
    }

    @Test
    fun `system sentinel resolves to the system language`() {
        val system = systemMetadataLanguage()
        assertEquals(system, resolveMetadataLanguage("system"))
        assertEquals(system, resolveMetadataLanguage("SYSTEM"))
        assertEquals(system, resolveMetadataLanguage(" System "))
    }

    @Test
    fun `explicit language codes pass through`() {
        assertEquals("en", resolveMetadataLanguage("en"))
        assertEquals("it", resolveMetadataLanguage(" it "))
        assertEquals("pt-BR", resolveMetadataLanguage("pt_BR"))
        assertEquals("pt-BR", resolveMetadataLanguage("pt-BR"))
    }

    @Test
    fun `system language is a non blank iso code`() {
        assertTrue(systemMetadataLanguage().matches(Regex("[a-z]{2,3}")))
    }
}
