package org.matchat.core.matrix

import kotlinx.coroutines.flow.StateFlow
import org.matchat.core.model.SasState

/**
 * Drives emoji (SAS) verification of THIS device against an already-verified
 * device (S6). The flow is: [start] requests verification → the other device
 * accepts → emojis arrive as [SasState.Comparing] → the user [approve]s or
 * [decline]s → [SasState.Success] or [SasState.Cancelled].
 */
interface SessionVerification {
    val state: StateFlow<SasState>

    suspend fun start()
    suspend fun approve()
    suspend fun decline()
    suspend fun cancel()

    /** Return to Idle so the screen can start a fresh attempt. */
    fun reset()
}
