package org.matchat.feature.timeline

import android.media.MediaPlayer
import java.io.File

/**
 * A tiny single-track player for voice/audio attachments (S9). Feature phones
 * like the DuraXV ship no media-viewer app, so audio is played in-app instead of
 * handed to a (non-existent) system player. One track at a time: starting a new
 * one stops the previous. Prepare is synchronous and must run off the main thread.
 */
internal class AudioPlayback {

    private var player: MediaPlayer? = null
    private var playingPath: String? = null

    val currentPath: String? get() = playingPath

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
        p.setOnCompletionListener { stop(); onDone() }
        p.setOnErrorListener { _, _, _ -> stop(); onDone(); true }
        runCatching { p.start() }.onFailure { stop(); onDone() }
    }

    fun isPlaying(path: String): Boolean =
        playingPath == path && runCatching { player?.isPlaying == true }.getOrDefault(false)

    fun stop() {
        runCatching { player?.release() }
        player = null
        playingPath = null
    }
}
