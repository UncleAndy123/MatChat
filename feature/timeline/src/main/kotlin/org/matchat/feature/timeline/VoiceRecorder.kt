package org.matchat.feature.timeline

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/**
 * Records a voice note to AAC-in-MP4 (.m4a). Opus/Ogg — the Matrix-native voice
 * format — needs API 29 to encode, but the target hardware is API 24/25, so AAC
 * is used instead (the approach DPAD-Messaging takes). Clients still play it; the
 * message is tagged as a voice message so it renders with a voice bar.
 *
 * While recording, [MediaRecorder.getMaxAmplitude] is sampled to build a coarse
 * waveform for the voice-message metadata. All Android; no SDK types here.
 */
internal class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAtMs = 0L
    private val amplitudes = mutableListOf<Int>()

    val mimeType: String get() = "audio/mp4"

    /** Begins recording into a fresh cache/media file. Returns false on failure. */
    fun start(): Boolean {
        stopQuietly()
        val out = File(File(context.cacheDir, "media").apply { mkdirs() }, "voice_${System.currentTimeMillis()}.m4a")
        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else legacyRecorder()
        return runCatching {
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioEncodingBitRate(BIT_RATE)
                setOutputFile(out.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            file = out
            startedAtMs = SystemClock.elapsedRealtime()
            amplitudes.clear()
            true
        }.getOrElse {
            runCatching { rec.release() }
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyRecorder() = MediaRecorder()

    /** Sample the current input level; call periodically to shape the waveform. */
    fun sampleAmplitude() {
        val amp = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        amplitudes.add(amp)
    }

    fun elapsedMs(): Long = if (startedAtMs == 0L) 0 else SystemClock.elapsedRealtime() - startedAtMs

    /** Stops recording and returns the result, or null if nothing usable was captured. */
    fun stop(): Result? {
        val rec = recorder ?: return null
        val out = file
        val durationMs = elapsedMs()
        val ok = runCatching { rec.stop() }.isSuccess
        runCatching { rec.release() }
        recorder = null
        file = null
        startedAtMs = 0L
        if (!ok || out == null || !out.exists() || out.length() == 0L) {
            runCatching { out?.delete() }
            return null
        }
        return Result(out, durationMs, buildWaveform())
    }

    /** Discards the in-progress recording and its file. */
    fun cancel() {
        val out = file
        stopQuietly()
        runCatching { out?.delete() }
    }

    private fun stopQuietly() {
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        file = null
        startedAtMs = 0L
        amplitudes.clear()
    }

    /** Down-samples the captured amplitudes to [WAVEFORM_BARS] values in 0..1. */
    private fun buildWaveform(): List<Float> {
        if (amplitudes.isEmpty()) return List(WAVEFORM_BARS) { 0f }
        val max = (amplitudes.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
        val step = (amplitudes.size.toFloat() / WAVEFORM_BARS).coerceAtLeast(1f)
        return (0 until WAVEFORM_BARS).map { bar ->
            val idx = (bar * step).toInt().coerceIn(0, amplitudes.lastIndex)
            (amplitudes[idx] / max).coerceIn(0f, 1f)
        }
    }

    data class Result(val file: File, val durationMs: Long, val waveform: List<Float>)

    private companion object {
        const val SAMPLE_RATE = 22_050
        const val BIT_RATE = 64_000
        const val WAVEFORM_BARS = 30
    }
}
