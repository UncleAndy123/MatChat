package org.matchat.client.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How cover-card text is cut and scrolled (docs/COVER-DISPLAY.md). */
class CoverTickerTest {

    @Test
    fun `short text is left alone`() {
        assertEquals("Ann: hi", coverDisplayText("Ann: hi", maxChars = 40))
    }

    @Test
    fun `long text is cut to the limit including the ellipsis`() {
        val shown = coverDisplayText("a".repeat(60), maxChars = 40)
        assertEquals(40, shown.length)
        assertTrue(shown.endsWith("…"))
    }

    @Test
    fun `a cut never leaves a space before the ellipsis`() {
        assertEquals("Ann: see…", coverDisplayText("Ann: see you", maxChars = 10))
    }

    @Test
    fun `text that fits the window is one frame`() {
        assertEquals(listOf("Ann: hi"), tickerFrames("Ann: hi", window = 14, pauseSteps = 3))
    }

    @Test
    fun `a pass holds the start, steps one character, holds the end, and never wraps`() {
        val frames = tickerFrames("abcdef", window = 4, pauseSteps = 2)
        assertEquals(listOf("abcd", "abcd", "bcde", "cdef", "cdef"), frames)
        assertTrue(frames.all { it.length == 4 })
    }
}
