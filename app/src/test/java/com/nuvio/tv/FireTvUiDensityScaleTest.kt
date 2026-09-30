package com.nuvio.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class FireTvUiDensityScaleTest {

    @Test
    fun `scales the stock 960dp TV profile down toward the 1280dp layout`() {
        assertEquals(0.75f, fireTvUiDensityScale(960), 1e-6f)
        assertEquals(0.6328125f, fireTvUiDensityScale(810), 1e-6f)
        assertEquals(0.66640625f, fireTvUiDensityScale(853), 1e-6f)
    }

    @Test
    fun `leaves already-wide layouts unchanged`() {
        assertEquals(1f, fireTvUiDensityScale(1280), 1e-6f)
        assertEquals(1f, fireTvUiDensityScale(1920), 1e-6f)
    }

    @Test
    fun `clamps extreme widths and invalid input`() {
        assertEquals(0.5f, fireTvUiDensityScale(640), 1e-6f)
        assertEquals(0.5f, fireTvUiDensityScale(432), 1e-6f)
        assertEquals(0.5f, fireTvUiDensityScale(320), 1e-6f)
        assertEquals(1f, fireTvUiDensityScale(0), 1e-6f)
        assertEquals(1f, fireTvUiDensityScale(-10), 1e-6f)
    }
}
