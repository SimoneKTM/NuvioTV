package com.nuvio.tv.core.network

import org.junit.Assert.assertEquals
import org.junit.Test

class HttpErrorMessageTest {

    @Test
    fun `blank http2 reason phrase falls back to status code`() {
        assertEquals("HTTP 404", httpErrorMessage("", 404))
        assertEquals("HTTP 500", httpErrorMessage("   ", 500))
        assertEquals("HTTP 0", httpErrorMessage(null, 0))
    }

    @Test
    fun `non-blank reason phrase is kept`() {
        assertEquals("Not Found", httpErrorMessage("Not Found", 404))
    }
}
