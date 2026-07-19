package com.xxxifan.dashcam.device.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceSelectionTest {
    @Test
    fun selectsProtocolMatchWhenSsidModelDiffers() {
        val protocolMatch = DeviceProbeResult(
            device = DeviceModel.Dc1.definition,
            status = DeviceProbeStatus.Supported,
            detail = "设备属性 JSON 接口可访问",
        )

        val selected = selectPreferredSupportedResult(
            results = listOf(protocolMatch),
            remembered = null,
            currentModel = DeviceModel.Dc5,
        )

        assertEquals(protocolMatch, selected)
    }
}
