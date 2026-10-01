package org.matchat.client.sync

/** The [SyncWorker] watchdog's decision, pure so it is unit-testable. */
object SyncWatchdog {
    enum class Decision { CANCEL, NUDGE, START_HOST, CATCH_UP }

    fun decide(signedIn: Boolean, hostActive: Boolean, fgsTimedOut: Boolean): Decision = when {
        !signedIn -> Decision.CANCEL
        hostActive -> Decision.NUDGE
        fgsTimedOut -> Decision.CATCH_UP
        else -> Decision.START_HOST
    }
}
