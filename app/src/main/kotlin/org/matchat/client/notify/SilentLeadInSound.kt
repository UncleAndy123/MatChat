package org.matchat.client.notify

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Prepends ~1s of silence to a notification sound, so the audio *stream*
 * itself starts early enough for a Bluetooth speaker to finish connecting
 * before the audible tone begins. A delayed post wouldn't achieve this —
 * AudioAttributes/audio focus have no pre-roll concept, and a Bluetooth
 * route only starts negotiating once the stream actually opens, so "wait,
 * then play" gives a BT device no extra time at all; the silence has to be
 * *inside* the one continuous stream.
 *
 * A compressed audio stream (MP3/AAC/OGG/...) can't have silence spliced
 * into it without decoding first, so the full round trip is: decode
 * [source] to raw 16-bit PCM (MediaExtractor + MediaCodec), write a WAV
 * file consisting of a second of zero-PCM plus the decoded audio, cache the
 * result (keyed by the source URI, so this only runs once per sound), and
 * expose it via this app's existing FileProvider (the same one
 * MediaFiles.kt uses for attachments) so NotificationChannel.setSound() can
 * point at it.
 *
 * On any failure — an unsupported codec, DRM-protected content, an I/O
 * error, a `content://` URI this device's MediaExtractor can't open — logs
 * a warning and returns [source] unprocessed. A missing lead-in must never
 * mean a missing notification (same philosophy as [MessageNotifier]'s own
 * notify()-failure fallback).
 */
object SilentLeadInSound {
    private const val TAG = "SilentLeadInSound"
    private const val LEAD_IN_MS = 1_000L
    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val CACHE_DIR_NAME = "notification_sounds"
    private const val BITS_PER_SAMPLE = 16

    /** Blocking — decodes/writes/caches on the calling thread. Callers must
     *  already be off the main thread (see [MessageNotifier]'s withContext
     *  wrapping). Cheap on a cache hit (a single file-existence check). */
    fun process(context: Context, source: Uri): Uri = runCatching {
        val cacheDir = File(context.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
        val outFile = File(cacheDir, "${cacheKeyFor(source.toString())}.wav")
        if (!outFile.exists() || outFile.length() == 0L) {
            decodeWithLeadIn(context, source, outFile)
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        // A NotificationChannel's sound Uri isn't Intent-scoped, so the usual
        // FLAG_GRANT_READ_URI_PERMISSION-on-an-Intent path doesn't apply —
        // granting directly to "android" is the standard workaround so the
        // system notification renderer (a different process) can read it.
        context.grantUriPermission("android", uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        uri
    }.getOrElse { e ->
        Log.w(TAG, "silent lead-in processing failed for $source; using it unprocessed", e)
        source
    }

    internal fun cacheKeyFor(uriString: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(uriString.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun decodeWithLeadIn(context: Context, source: Uri, outFile: File) {
        val extractor = MediaExtractor()
        val tempPcm = File.createTempFile("lead_in_pcm", ".raw", context.cacheDir)
        try {
            extractor.setDataSource(context, source, null)
            val trackIndex = selectAudioTrack(extractor) ?: error("no audio track in $source")
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: error("no MIME for $source")
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val codec = MediaCodec.createDecoderByType(mime)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                decodeToPcm(extractor, codec, tempPcm)
            } finally {
                codec.stop()
                codec.release()
            }
            writeWav(outFile, tempPcm, sampleRate, channelCount)
        } finally {
            extractor.release()
            tempPcm.delete()
        }
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            if (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) return i
        }
        return null
    }

    /** Classic synchronous MediaCodec decode loop: feed compressed samples in,
     *  drain decoded PCM out, until both the extractor and the codec report
     *  end-of-stream. Assumes the decoder's default 16-bit PCM output, true
     *  for the built-in audio codecs (MP3/AAC/OGG/...) this is meant for. */
    private fun decodeToPcm(extractor: MediaExtractor, codec: MediaCodec, pcmFile: File) {
        var sawInputEos = false
        var sawOutputEos = false
        val bufferInfo = MediaCodec.BufferInfo()
        pcmFile.outputStream().use { pcmOut ->
            while (!sawOutputEos) {
                if (!sawInputEos) {
                    val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                        val sampleSize = inputBuffer?.let { extractor.readSampleData(it, 0) } ?: -1
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                if (outputIndex >= 0) {
                    if (bufferInfo.size > 0) {
                        codec.getOutputBuffer(outputIndex)?.let { outputBuffer ->
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            val chunk = ByteArray(bufferInfo.size)
                            outputBuffer.get(chunk)
                            pcmOut.write(chunk)
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
                }
            }
        }
    }

    private fun writeWav(outFile: File, pcm: File, sampleRate: Int, channelCount: Int) {
        val silenceBytes = silenceByteCount(sampleRate, channelCount)
        val dataSize = silenceBytes.toLong() + pcm.length()
        outFile.outputStream().use { out ->
            out.write(wavHeader(dataSize, sampleRate, channelCount))
            writeSilence(out, silenceBytes)
            pcm.inputStream().use { it.copyTo(out) }
        }
    }

    /** How many PCM bytes [LEAD_IN_MS] of silence is, rounded down to a whole
     *  number of sample frames (never a partial frame). */
    internal fun silenceByteCount(sampleRate: Int, channelCount: Int, bitsPerSample: Int = BITS_PER_SAMPLE): Int {
        val blockAlign = channelCount * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        return (((LEAD_IN_MS * byteRate) / 1000).toInt() / blockAlign) * blockAlign
    }

    /** The standard 44-byte RIFF/WAVE header for 16-bit PCM audio, sized for
     *  a total data chunk of [dataSize] bytes (silence + decoded audio
     *  together) — computed upfront (both lengths are already known once
     *  decoding finishes) rather than written then patched. */
    internal fun wavHeader(
        dataSize: Long,
        sampleRate: Int,
        channelCount: Int,
        bitsPerSample: Int = BITS_PER_SAMPLE,
    ): ByteArray {
        val blockAlign = channelCount * bitsPerSample / 8
        val byteRate = sampleRate * blockAlign
        val out = ByteArrayOutputStream(44)
        out.write("RIFF".toByteArray())
        out.writeIntLE((36 + dataSize).toInt())
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.writeIntLE(16) // PCM fmt chunk size
        out.writeShortLE(1) // PCM (uncompressed) format tag
        out.writeShortLE(channelCount)
        out.writeIntLE(sampleRate)
        out.writeIntLE(byteRate)
        out.writeShortLE(blockAlign)
        out.writeShortLE(bitsPerSample)
        out.write("data".toByteArray())
        out.writeIntLE(dataSize.toInt())
        return out.toByteArray()
    }

    private fun writeSilence(out: OutputStream, count: Int) {
        val zeros = ByteArray(minOf(count, 8192))
        var remaining = count
        while (remaining > 0) {
            val n = minOf(remaining, zeros.size)
            out.write(zeros, 0, n)
            remaining -= n
        }
    }

    private fun ByteArrayOutputStream.writeIntLE(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
        write((v ushr 16) and 0xFF)
        write((v ushr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeShortLE(v: Int) {
        write(v and 0xFF)
        write((v ushr 8) and 0xFF)
    }
}
