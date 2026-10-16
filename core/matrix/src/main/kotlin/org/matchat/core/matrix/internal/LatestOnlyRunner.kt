package org.matchat.core.matrix.internal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Runs [work] one call at a time, coalescing requests that arrive while it is
 * busy into a single follow-up run. Used for the room-list mapping: launching
 * one coroutine per diff let a slow, older mapping finish *after* a newer one
 * and overwrite it, which showed the previous message in notifications.
 * Because runs are serial and [work] reads its input when it starts, the last
 * run always sees the latest state.
 */
// The for-loop below iterates the channel only to re-trigger [work]; each
// element is a bare "run again" signal with no value, so detekt's
// UnusedPrivateProperty on the loop variable is expected and suppressed here.
@Suppress("UnusedPrivateProperty")
internal class LatestOnlyRunner(scope: CoroutineScope, private val work: suspend () -> Unit) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in requests) runCatching { work() }
        }
    }

    fun request() {
        requests.trySend(Unit)
    }
}
