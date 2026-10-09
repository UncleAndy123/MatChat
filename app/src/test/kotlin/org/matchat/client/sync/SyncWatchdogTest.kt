package org.matchat.client.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import org.matchat.client.sync.SyncWatchdog.Decision

class SyncWatchdogTest {

    @Test
    fun `signed out cancels the watchdog`() {
        assertEquals(Decision.CANCEL, SyncWatchdog.decide(signedIn = false, hostActive = true, fgsTimedOut = false))
    }

    @Test
    fun `a live host is only nudged`() {
        assertEquals(Decision.NUDGE, SyncWatchdog.decide(signedIn = true, hostActive = true, fgsTimedOut = false))
        assertEquals(Decision.NUDGE, SyncWatchdog.decide(signedIn = true, hostActive = true, fgsTimedOut = true))
    }

    @Test
    fun `no host restarts one`() {
        assertEquals(
            Decision.START_HOST,
            SyncWatchdog.decide(signedIn = true, hostActive = false, fgsTimedOut = false),
        )
    }

    @Test
    fun `a timed-out foreground service falls back to catch-up`() {
        assertEquals(Decision.CATCH_UP, SyncWatchdog.decide(signedIn = true, hostActive = false, fgsTimedOut = true))
    }
}
