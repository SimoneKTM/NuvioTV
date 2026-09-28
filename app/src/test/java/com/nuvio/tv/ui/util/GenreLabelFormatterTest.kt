package com.nuvio.tv.ui.util

import android.content.Context
import com.nuvio.tv.R
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GenreLabelFormatterTest {

    private val context: Context = mockk()

    @Before
    fun stubStrings() {
        every { context.getString(R.string.genre_documentary) } returns "Documentario"
        every { context.getString(R.string.genre_reality) } returns "Reality"
        every { context.getString(R.string.genre_action) } returns "Azione"
    }

    @Test
    fun `documentaries alias resolves to documentary label`() {
        assertEquals("Documentario", localizedGenreLabel(context, "Documentaries"))
        assertEquals("Documentario", localizedGenreLabel(context, "docuseries"))
    }

    @Test
    fun `reality tv alias resolves to reality label`() {
        assertEquals("Reality", localizedGenreLabel(context, "Reality TV"))
        assertEquals("Reality", localizedGenreLabel(context, "reality shows"))
    }

    @Test
    fun `exact match still resolves`() {
        assertEquals("Azione", localizedGenreLabel(context, "Action"))
        assertEquals("Azione", localizedGenreLabel(context, "  action  "))
    }

    @Test
    fun `unknown genre passes through unchanged`() {
        assertEquals("Some Unknown Genre", localizedGenreLabel(context, "Some Unknown Genre"))
        verify(exactly = 0) { context.getString(any()) }
    }
}
