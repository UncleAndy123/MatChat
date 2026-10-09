package org.matchat.client.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncHostPolicyTest {

    @Test
    fun `signed out means no host`() {
        assertNull(SyncHostPolicy.choose(signedIn = false, helperConnected = true, batteryExempt = true))
    }

    @Test
    fun `helper and exemption together host sync without a notification`() {
        assertEquals(
            SyncHost.ACCESSIBILITY,
            SyncHostPolicy.choose(signedIn = true, helperConnected = true, batteryExempt = true),
        )
    }

    @Test
    fun `helper without the exemption keeps the foreground service`() {
        assertEquals(
            SyncHost.FOREGROUND_SERVICE,
            SyncHostPolicy.choose(signedIn = true, helperConnected = true, batteryExempt = false),
        )
    }

    @Test
    fun `exemption without the helper keeps the foreground service`() {
        assertEquals(
            SyncHost.FOREGROUND_SERVICE,
            SyncHostPolicy.choose(signedIn = true, helperConnected = false, batteryExempt = true),
        )
    }

    @Test
    fun `neither keeps the foreground service`() {
        assertEquals(
            SyncHost.FOREGROUND_SERVICE,
            SyncHostPolicy.choose(signedIn = true, helperConnected = false, batteryExempt = false),
        )
    }
}
