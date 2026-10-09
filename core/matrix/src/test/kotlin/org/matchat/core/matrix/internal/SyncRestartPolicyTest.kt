package org.matchat.core.matrix.internal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.matchat.core.matrix.internal.SyncRestartPolicy.LoopState

class SyncRestartPolicyTest {

    private val policy = SyncRestartPolicy(initialDelayMillis = 1_000L, maxDelayMillis = 4_000L)

    @Test
    fun `a running loop needs no restart`() {
        assertNull(policy.onState(LoopState.RUNNING, paused = false))
    }

    @Test
    fun `offline and idle leave recovery to the SDK`() {
        assertNull(policy.onState(LoopState.OFFLINE, paused = false))
        assertNull(policy.onState(LoopState.IDLE, paused = false))
    }

    @Test
    fun `an error restarts after the initial delay`() {
        assertEquals(1_000L, policy.onState(LoopState.ERROR, paused = false))
    }

    @Test
    fun `a terminated loop restarts too`() {
        assertEquals(1_000L, policy.onState(LoopState.TERMINATED, paused = false))
    }

    @Test
    fun `an intentional pause is never restarted`() {
        assertNull(policy.onState(LoopState.TERMINATED, paused = true))
        assertNull(policy.onState(LoopState.ERROR, paused = true))
    }

    @Test
    fun `repeated failures back off up to the cap`() {
        assertEquals(1_000L, policy.onState(LoopState.ERROR, paused = false))
        assertEquals(2_000L, policy.onState(LoopState.ERROR, paused = false))
        assertEquals(4_000L, policy.onState(LoopState.ERROR, paused = false))
        assertEquals(4_000L, policy.onState(LoopState.ERROR, paused = false))
    }

    @Test
    fun `running again resets the backoff`() {
        policy.onState(LoopState.ERROR, paused = false)
        policy.onState(LoopState.ERROR, paused = false)
        policy.onState(LoopState.RUNNING, paused = false)
        assertEquals(1_000L, policy.onState(LoopState.ERROR, paused = false))
    }

    @Test
    fun `the network returning resets the backoff`() {
        policy.onState(LoopState.ERROR, paused = false)
        policy.onState(LoopState.ERROR, paused = false)
        policy.onNetworkAvailable()
        assertEquals(1_000L, policy.onState(LoopState.ERROR, paused = false))
    }
}
