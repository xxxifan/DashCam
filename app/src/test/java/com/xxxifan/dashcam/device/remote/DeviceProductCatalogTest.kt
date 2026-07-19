package com.xxxifan.dashcam.device.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceProductCatalogTest {
    @Test
    fun recognizesSupportedDevicePrefixesCaseInsensitively() {
        assertEquals(DeviceModel.Dc1, "DC1-A123".deviceModelOrNull())
        assertEquals(DeviceModel.Dc5, "dc5_pro".deviceModelOrNull())
    }

    @Test
    fun ignoresUnrelatedWifiNetworks() {
        assertNull("Home-WiFi".deviceModelOrNull())
        assertNull("DC9-UNKNOWN".deviceModelOrNull())
    }

    @Test
    fun dc1OwnsThe169JsonProtocolDefinition() {
        val definition = DeviceModel.Dc1.definition

        assertEquals("legacy-192-168-169-1", definition.id)
        assertEquals("192.168.169.1", definition.host)
        assertEquals("JSON / HTTP / RTSP TCP", definition.protocolName)
        assertEquals(DeviceModel.Dc1, Legacy169DeviceDriver().definition.model)
    }

    @Test
    fun dc5OwnsThe254NovatekProtocolDefinition() {
        val definition = DeviceModel.Dc5.definition

        assertEquals("legacy-192-168-1-254", definition.id)
        assertEquals("192.168.1.254", definition.host)
        assertEquals("Novatek XML / HTTP / RTSP", definition.protocolName)
        assertEquals(DeviceModel.Dc5, Legacy254DeviceDriver().definition.model)
    }
}
