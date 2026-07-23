package com.xxxifan.dashcam.device.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceMediaTimestampParserTest {
    @Test
    fun parsesCompactTimestampFromFileName() {
        val timestamp = RemoteMediaTimestampParser.parse("DC1_20260721_143542.MP4")

        assertEquals("2026/07/21 14:35:42", timestamp?.formatRemoteMediaTimestamp())
    }

    @Test
    fun parsesSeparatedTimestampFromProtocolData() {
        val timestamp = RemoteMediaTimestampParser.parse(
            name = "record.MP4",
            protocolData = mapOf("TIME" to "2026-07-21 14:35:42"),
        )

        assertEquals("2026/07/21 14:35:42", timestamp?.formatRemoteMediaTimestamp())
    }

    @Test
    fun normalizesUnixSecondsAndRejectsInvalidTimestamp() {
        assertEquals(1_751_491_200_000L, RemoteMediaTimestampParser.normalize(1_751_491_200L))
        assertNull(RemoteMediaTimestampParser.parse("record-without-time.MP4"))
    }
}
