package com.xxxifan.dashcam.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {
    @Test
    fun newerVersion_isDetectedAcrossCommonTagFormats() {
        assertTrue(VersionComparator.isNewer("v0.2.0", "0.1.9"))
        assertTrue(VersionComparator.isNewer("1.0.1", "1.0.0"))
        assertTrue(VersionComparator.isNewer("1.1", "1.0.99"))
    }

    @Test
    fun equalOrOlderVersion_isNotNewer() {
        assertFalse(VersionComparator.isNewer("v1.0.0", "1.0"))
        assertFalse(VersionComparator.isNewer("0.9.9", "1.0.0"))
    }

    @Test
    fun stableVersion_isNewerThanPrerelease() {
        assertTrue(VersionComparator.isNewer("1.0.0", "1.0.0-beta.2"))
        assertTrue(VersionComparator.isNewer("1.0.0-beta.10", "1.0.0-beta.2"))
        assertFalse(VersionComparator.isNewer("1.0.0-beta.2", "1.0.0"))
    }

    @Test
    fun invalidVersion_isNotReportedAsUpdate() {
        assertFalse(VersionComparator.isNewer("latest", "1.0.0"))
        assertFalse(VersionComparator.isNewer("1.0.1", "development"))
    }
}
