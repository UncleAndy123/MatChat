package org.matchat.core.matrix.internal

/**
 * The pure decision half of the sync-loop watchdog in [RustMatrixClientHolder]
 * (docs/adr/0004, "always-on" amendment). The SDK's `SyncService` reports its
 * own state; when it lands in `ERROR` or `TERMINATED` without us having asked it
 * to stop, the loop is dead and nothing in the SDK restarts it — which used to
 * leave "MatChat is running" showing over a loop that received nothing. This
 * decides whether and when to restart; the holder only executes it.
 *
 * Kept free of SDK types (the holder maps the SDK enum onto [LoopState]) so it
 * is unit-testable on the JVM.
 */
internal class SyncRestartPolicy(
    private val initialDelayMillis: Long = INITIAL_DELAY_MILLIS,
    private val maxDelayMillis: Long = MAX_DELAY_MILLIS,
) {
    /** The SDK loop states this policy distinguishes. */
    enum class LoopState { IDLE, RUNNING, OFFLINE, TERMINATED, ERROR }

    private var nextDelayMillis = initialDelayMillis

    /**
     * Called on every SDK state update. Returns the delay before a restart
     * should be attempted, or null when no restart is wanted: the loop is
     * healthy, the SDK's own offline mode is already retrying, or [paused] says
     * the stop was intentional (FGS timeout hand-off, catch-up window end,
     * sign-out). Each consecutive failure doubles the delay up to the cap; a
     * return to RUNNING resets it.
     */
    fun onState(state: LoopState, paused: Boolean): Long? {
        return when (state) {
            LoopState.RUNNING -> {
                nextDelayMillis = initialDelayMillis
                null
            }
            // Offline mode (SyncServiceBuilder.withOfflineMode) makes the SDK
            // probe the server and resume by itself; IDLE is the pre-start state.
            LoopState.OFFLINE, LoopState.IDLE -> null
            LoopState.TERMINATED, LoopState.ERROR -> {
                if (paused) return null
                val delay = nextDelayMillis
                nextDelayMillis = (nextDelayMillis * 2).coerceAtMost(maxDelayMillis)
                delay
            }
        }
    }

    /** The network came back: the next restart should happen right away. */
    fun onNetworkAvailable() {
        nextDelayMillis = initialDelayMillis
    }

    companion object {
        const val INITIAL_DELAY_MILLIS = 2_000L
        const val MAX_DELAY_MILLIS = 60_000L
    }
}
