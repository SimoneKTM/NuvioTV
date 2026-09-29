package com.nuvio.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class FireTvUiDensityScaleTest {

    @Test
    fun `scales narrow widths down toward the 960dp TV profile`() {
        assertEquals(0.5625f, fireTvUiDensityScale(540), 1e-6f)
        assertEquals(0.6666667f, fireTvUiDensityScale(640), 1e-6f)
        assertEquals(0.84375f, fireTvUiDensityScale(810), 1e-6f)
    }

    @Test
    fun `leaves standard and wide layouts unchanged`() {
        assertEquals(1f, fireTvUiDensityScale(960), 1e-6f)
        assertEquals(1f, fireTvUiDensityScale(1280), 1e-6f)
        assertEquals(1f, fireTvUiDensityScale(1920), 1e-6f)
    }

    @Test
    fun `clamps extreme widths and invalid input`() {
        assertEquals(0.5f, fireTvUiDensityScale(432), 1e-6f)
        assertEquals(0.5f, fireTvUiDensityScale(320), 1e-6f)
        assertEquals(1f, fireTvUiDensityScale(0), 1e-6f)
        assertEquals(1f, fireTvUiDensityScale(-10), 1e-6f)
    }
}
