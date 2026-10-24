package com.wahana.swbplugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwbPluginTest {

    private val implementation = SwbPluginImpl()

    @Test
    fun testCalculateStatusUsableOnlyWhenAllConditionsMet() {
        val usableStatus = implementation.calculateStatus(enabled = true, granted = true, isMock = false)
        assertTrue(usableStatus.getBoolean("usable"))
        assertTrue(usableStatus.getBoolean("enabled"))
        assertTrue(usableStatus.getBoolean("granted"))
        assertFalse(usableStatus.getBoolean("isMock"))

        val mockStatus = implementation.calculateStatus(enabled = true, granted = true, isMock = true)
        assertFalse(mockStatus.getBoolean("usable"))

        val noGpsStatus = implementation.calculateStatus(enabled = false, granted = true, isMock = false)
        assertFalse(noGpsStatus.getBoolean("usable"))

        val noPermStatus = implementation.calculateStatus(enabled = true, granted = false, isMock = false)
        assertFalse(noPermStatus.getBoolean("usable"))
    }

    @Test
    fun testCalculateStatusDiagnosisMessagesAndSolutions() {
        val usableStatus = implementation.calculateStatus(enabled = true, granted = true, isMock = false)
        assertEquals("OK", usableStatus.getString("code"))
        assertTrue(usableStatus.getString("solution")?.contains("siap digunakan") == true)

        val noGpsStatus = implementation.calculateStatus(enabled = false, granted = true, isMock = false)
        assertEquals("GPS_DISABLED", noGpsStatus.getString("code"))
        assertTrue(noGpsStatus.getString("solution")?.contains("Aktifkan GPS") == true)

        val noGpsNoPermStatus = implementation.calculateStatus(enabled = false, granted = false, isMock = false)
        assertEquals("GPS_DISABLED", noGpsNoPermStatus.getString("code"))
        assertTrue(noGpsNoPermStatus.getString("solution")?.contains("Aktifkan GPS") == true)

        val noPermStatus = implementation.calculateStatus(enabled = true, granted = false, isMock = false)
        assertEquals("PERMISSION_DENIED", noPermStatus.getString("code"))
        assertTrue(noPermStatus.getString("solution")?.contains("Pengaturan") == true)

        val coarseOnlyStatus = implementation.calculateStatus(enabled = true, granted = false, isMock = false, isCoarseOnly = true)
        assertEquals("COARSE_ONLY", coarseOnlyStatus.getString("code"))
        assertTrue(coarseOnlyStatus.getString("solution")?.contains("Akurat") == true)

        val mockStatus = implementation.calculateStatus(enabled = true, granted = true, isMock = true)
        assertEquals("MOCK_DETECTED", mockStatus.getString("code"))
        assertTrue(mockStatus.getString("solution")?.contains("Fake GPS") == true)
    }

    @Test
    fun testLocationMockNullLocationReturnsFalse() {
        assertFalse(implementation.isLocationMock(null))
    }
}

