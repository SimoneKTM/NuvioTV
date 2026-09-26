package com.nuvio.tv.ui.screens.search

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchPresentationRulesTest {

    @Test
    fun `one character does not become a submitted search`() {
        assertEquals("", submittedSearchQuery("a"))
    }

    @Test
    fun `query is trimmed and only submitted at the minimum length`() {
        assertEquals("", submittedSearchQuery(" a "))
        assertEquals("ab", submittedSearchQuery(" ab "))
        assertEquals("abc", submittedSearchQuery("abc"))
    }
}
