package com.xxxifan.dashcam.device.remote

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Legacy169DeviceDriverTest {
    @Test
    fun sizeFieldIsConvertedFromKiBToBytes() {
        assertEquals(131_203_072L, legacy169SizeKiBToBytes(128_128L))
    }

    @Test
    fun invalidOrOverflowingSizeIsIgnored() {
        assertNull(legacy169SizeKiBToBytes(-1L))
        assertNull(legacy169SizeKiBToBytes(Long.MAX_VALUE))
    }

    @Test
    fun storageCapacityIsConvertedFromMiBToBytes() {
        assertEquals(63_860_375_552L, legacy169StorageMiBToBytes(60_902L))
        assertEquals(2_252_341_248L, legacy169StorageMiBToBytes(2_148L))
    }

    @Test
    fun invalidOrOverflowingStorageCapacityIsIgnored() {
        assertNull(legacy169StorageMiBToBytes(-1L))
        assertNull(legacy169StorageMiBToBytes(Long.MAX_VALUE))
    }

    @Test
    fun heartbeatFailuresAreLoggedImmediatelyAndThenOncePerMinute() {
        assertTrue(shouldLogHeartbeatFailure(1))
        assertFalse(shouldLogHeartbeatFailure(2))
        assertFalse(shouldLogHeartbeatFailure(14))
        assertTrue(shouldLogHeartbeatFailure(15))
        assertFalse(shouldLogHeartbeatFailure(16))
        assertTrue(shouldLogHeartbeatFailure(30))
    }

    @Test
    fun previewPreparationRetriesOneTransientFailure() {
        assertTrue(shouldRetryLegacy169PreviewPreparation(1, IOException("timeout")))
        assertFalse(shouldRetryLegacy169PreviewPreparation(2, IOException("timeout")))
    }

    @Test
    fun previewPreparationDoesNotRetryProtocolFailure() {
        assertFalse(
            shouldRetryLegacy169PreviewPreparation(
                attempt = 1,
                error = DeviceProtocolException("进入录像模式失败：HTTP 404"),
            ),
        )
    }
}
