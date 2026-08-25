package com.xxxifan.dashcam.device.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceSaveProgressTest {
    @Test
    fun `mp4 save maps download to first ninety percent`() {
        assertEquals(0f, downloadOverallFraction(0f, convertToMp4 = true)!!, 0.0001f)
        assertEquals(0.45f, downloadOverallFraction(0.5f, convertToMp4 = true)!!, 0.0001f)
        assertEquals(0.9f, downloadOverallFraction(1f, convertToMp4 = true)!!, 0.0001f)
    }

    @Test
    fun `mp4 save maps conversion to final ten percent`() {
        assertEquals(0.9f, conversionOverallFraction(0f)!!, 0.0001f)
        assertEquals(0.95f, conversionOverallFraction(0.5f)!!, 0.0001f)
        assertEquals(1f, conversionOverallFraction(1f)!!, 0.0001f)
    }

    @Test
    fun `original save uses full progress range`() {
        assertEquals(0.5f, downloadOverallFraction(0.5f, convertToMp4 = false)!!, 0.0001f)
        assertEquals(1f, downloadOverallFraction(1f, convertToMp4 = false)!!, 0.0001f)
        assertNull(downloadOverallFraction(null, convertToMp4 = false))
    }
}
