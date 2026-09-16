package org.matchat.client.notify

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure, non-Android parts of [SilentLeadInSound] — cache-key hashing and
 * WAV-header/silence-length byte math. The actual MediaExtractor/MediaCodec
 * decode path needs a real device or emulator codec and isn't covered here
 * (see the plan's own note on this).
 */
class SilentLeadInSoundTest {

    @Test
    fun `cache key is stable for the same uri string`() {
        val uri = "content://media/external/audio/media/8301"
        assertEquals(SilentLeadInSound.cacheKeyFor(uri), SilentLeadInSound.cacheKeyFor(uri))
    }

    @Test
    fun `cache key differs for different uri strings`() {
        assertNotEquals(
            SilentLeadInSound.cacheKeyFor("content://media/external/audio/media/1"),
            SilentLeadInSound.cacheKeyFor("content://media/external/audio/media/2"),
        )
    }

    @Test
    fun `cache key is a lowercase hex sha-256 digest`() {
        val key = SilentLeadInSound.cacheKeyFor("content://settings/system/notification_sound")
        assertEquals(64, key.length) // SHA-256 = 32 bytes = 64 hex chars
        assertTrue(key.all { it in "0123456789abcdef" })
    }

    @Test
    fun `silence byte count is exactly 1 second, rounded to a whole frame`() {
        // 44100 Hz, 16-bit, stereo: 4 bytes/frame, 1000 ms of silence.
        val bytes = SilentLeadInSound.silenceByteCount(sampleRate = 44_100, channelCount = 2)
        assertEquals(44_100 * 4, bytes)
        assertEquals(0, bytes % 4) // a whole number of frames, never a partial one
    }

    @Test
    fun `silence byte count scales with sample rate and channel count`() {
        val mono = SilentLeadInSound.silenceByteCount(sampleRate = 16_000, channelCount = 1)
        val stereo = SilentLeadInSound.silenceByteCount(sampleRate = 16_000, channelCount = 2)
        assertEquals(16_000 * 2, mono) // 1000ms * 16000Hz * 2 bytes/sample
        assertEquals(mono * 2, stereo)
    }

    @Test
    fun `wav header is exactly 44 bytes`() {
        val header = SilentLeadInSound.wavHeader(dataSize = 1000, sampleRate = 44_100, channelCount = 2)
        assertEquals(44, header.size)
    }

    @Test
    fun `wav header starts RIFF, declares WAVE, and ends with the data chunk id`() {
        val header = SilentLeadInSound.wavHeader(dataSize = 12_345, sampleRate = 8_000, channelCount = 1)
        assertArrayEquals("RIFF".toByteArray(), header.copyOfRange(0, 4))
        assertArrayEquals("WAVE".toByteArray(), header.copyOfRange(8, 12))
        assertArrayEquals("fmt ".toByteArray(), header.copyOfRange(12, 16))
        assertArrayEquals("data".toByteArray(), header.copyOfRange(36, 40))
    }

    @Test
    fun `wav header encodes RIFF chunk size as dataSize plus 36, little-endian`() {
        val dataSize = 12_345L
        val header = SilentLeadInSound.wavHeader(dataSize, sampleRate = 8_000, channelCount = 1)
        assertEquals((dataSize + 36).toInt(), littleEndianInt(header, 4))
        assertEquals(dataSize.toInt(), littleEndianInt(header, 40)) // the data chunk's own size field
    }

    @Test
    fun `wav header encodes sample rate, channel count, and bit depth correctly`() {
        val header = SilentLeadInSound.wavHeader(dataSize = 100, sampleRate = 22_050, channelCount = 2)
        assertEquals(2, littleEndianShort(header, 22)) // channel count
        assertEquals(22_050, littleEndianInt(header, 24)) // sample rate
        assertEquals(16, littleEndianShort(header, 34)) // bits per sample
        val blockAlign = 2 * 16 / 8
        assertEquals(blockAlign, littleEndianShort(header, 32))
        assertEquals(22_050 * blockAlign, littleEndianInt(header, 28)) // byte rate
    }

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int = (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
}
