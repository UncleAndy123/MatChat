package org.matchat.core.model

/** Wall-clock time, injected so ViewModels format times against a clock a test
 *  can pin (AGENTS.md §3 — formatting is unit-testable). Provided in :app. */
fun interface MillisClock {
    fun now(): Long

    companion object {
        val SYSTEM = MillisClock { System.currentTimeMillis() }
    }
}
