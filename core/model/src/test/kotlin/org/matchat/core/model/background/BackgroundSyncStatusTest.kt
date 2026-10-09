package org.matchat.core.model.background

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BackgroundSyncStatusTest {

    @Test
    fun `helper and exemption means no notification`() {
        assertEquals(HelperStatus.ON, BackgroundSyncStatus(batteryExempt = true, helperConnected = true).helperStatus)
    }

    @Test
    fun `helper without exemption needs Run in background`() {
        assertEquals(
            HelperStatus.NEEDS_BATTERY,
            BackgroundSyncStatus(batteryExempt = false, helperConnected = true).helperStatus,
        )
    }

    @Test
    fun `no helper is off whatever the exemption`() {
        assertEquals(HelperStatus.OFF, BackgroundSyncStatus(batteryExempt = true, helperConnected = false).helperStatus)
        assertEquals(HelperStatus.OFF, BackgroundSyncStatus().helperStatus)
    }
}
