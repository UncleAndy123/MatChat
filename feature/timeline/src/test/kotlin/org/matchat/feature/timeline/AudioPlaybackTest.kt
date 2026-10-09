package org.matchat.feature.timeline

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit coverage for the Android-free playback-time formatter (Voice playback
 *  round). MediaPlayer itself can't run on the JVM, so only this pure helper
 *  is exercised here; the play/pause/progress UI is covered by
 *  VoiceBubbleRowScreenshotTest. */
class AudioPlaybackTest {

    @Test
    fun `formats sub-minute positions as m colon ss`() {
        assertEquals("0:00", formatPlaybackTime(0))
        assertEquals("0:05", formatPlaybackTime(5_000))
        assertEquals("0:59", formatPlaybackTime(59_900)) // truncates, doesn't round up
    }

    @Test
    fun `formats minutes with a zero-padded seconds field`() {
        assertEquals("1:00", formatPlaybackTime(60_000))
        assertEquals("2:03", formatPlaybackTime(123_000))
        assertEquals("12:34", formatPlaybackTime(754_000))
    }

    @Test
    fun `clamps a negative position to zero`() {
        assertEquals("0:00", formatPlaybackTime(-1))
    }
}
