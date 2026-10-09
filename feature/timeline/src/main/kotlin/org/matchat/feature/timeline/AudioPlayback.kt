package org.matchat.feature.timeline

import android.media.MediaPlayer
import java.io.File

/**
 * A tiny single-track player for voice/audio attachments (S9). Feature phones
 * like the DuraXV ship no media-viewer app, so audio is played in-app instead of
 * handed to a (non-existent) system player. One track at a time: starting a new
 * one stops the previous. Prepare is synchronous and must run off the main thread.
 *
 * Voice playback round: the player now also [pause]s/[resume]s in place (keeping
 * position) and exposes [positionMs]/[durationMs], so the voice bubble's own
 * play/pause button and progress fill can reflect live playback — see
 * TimelineFragment's playback wiring.
 */
internal class AudioPlayback {

    private var player: MediaPlayer? = null
    private var playingPath: String? = null

    val currentPath: String? get() = playingPath

    /** Current playhead in ms, or 0 when nothing is loaded. */
    val positionMs: Int get() = runCatching { player?.currentPosition ?: 0 }.getOrDefault(0)

    /** Total length in ms once prepared, or 0 when unknown. */
    val durationMs: Int get() = runCatching { player?.duration ?: 0 }.getOrDefault(0)

    /** Prepares [file] on the calling (background) thread. Call [start] after. */
    fun prepare(file: File): Boolean {
        stop()
        return runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare() // synchronous — caller guarantees a background thread
                player = this
                playingPath = file.absolutePath
            }
            true
        }.getOrDefault(false)
    }

    /** Starts playback; [onDone] fires on completion or error so the UI can reset. */
    fun start(onDone: () -> Unit) {
        val p = player ?: return onDone()
        p.setOnCompletionListener {
            stop()
            onDone()
        }
        p.setOnErrorListener { _, _, _ ->
            stop()
            onDone()
            true
        }
        runCatching { p.start() }.onFailure {
            stop()
            onDone()
        }
    }

    /** Pauses the loaded track in place (position is kept for [resume]). */
    fun pause() {
        runCatching { player?.takeIf { it.isPlaying }?.pause() }
    }

    /** Resumes a [pause]d track from where it stopped. */
    fun resume() {
        runCatching { player?.start() }
    }

    fun stop() {
        runCatching { player?.release() }
        player = null
        playingPath = null
    }
}

/** Pre-formatted "m:ss" for a playback position/duration in ms — the same
 *  convention as TimelineViewModel.formatDuration, kept as a top-level,
 *  Android-free function so it is unit-testable (the ViewModel's copy is
 *  private to it). */
internal fun formatPlaybackTime(ms: Int): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
