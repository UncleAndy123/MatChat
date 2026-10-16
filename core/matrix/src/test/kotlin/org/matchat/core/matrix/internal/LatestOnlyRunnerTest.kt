package org.matchat.core.matrix.internal

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Runs on [UnconfinedTestDispatcher] so the runner's consumer coroutine executes
 * eagerly as requests arrive, rather than relying on `advanceUntilIdle()` to drive
 * a backgroundScope coroutine (an earlier version did, and it never ran the
 * consumer on some setups). Each run parks on [release] until the test lets it
 * finish, so the coalescing is verified with no timing assumptions at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LatestOnlyRunnerTest {

    @Test
    fun `the last result always reflects the latest input`() = runTest(UnconfinedTestDispatcher()) {
        val release = Channel<Unit>(Channel.UNLIMITED) // one token lets one run finish
        var input = 0
        var published = -1
        val runner = LatestOnlyRunner(backgroundScope) {
            val snapshot = input
            release.receive() // park mid-run until the test releases it
            published = snapshot
        }

        input = 1
        runner.request() // run 1 starts eagerly with snapshot = 1, parks on release

        input = 2
        runner.request() // coalesced follow-up, buffered while run 1 is parked

        release.send(Unit) // run 1 finishes (publishes 1), run 2 starts with snapshot = 2
        release.send(Unit) // run 2 finishes (publishes 2)

        assertEquals(2, published)
    }

    @Test
    fun `requests during a run collapse into one more run`() = runTest(UnconfinedTestDispatcher()) {
        val release = Channel<Unit>(Channel.UNLIMITED)
        var runs = 0
        val runner = LatestOnlyRunner(backgroundScope) {
            runs++
            release.receive()
        }

        runner.request()
        assertEquals(1, runs) // run 1 in progress

        runner.request()
        runner.request() // both coalesce while run 1 is parked
        assertEquals(1, runs) // no new run yet — still one in flight

        release.send(Unit) // finish run 1 -> the coalesced follow-up starts
        assertEquals(2, runs)

        release.send(Unit) // finish run 2 -> nothing queued
        assertEquals(2, runs) // the two requests collapsed into a single extra run
    }
}
