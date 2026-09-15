package org.matchat.core.rtc.internal

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.matchat.core.rtc.AudioTransport
import org.matchat.core.rtc.TransportConfig
import javax.inject.Inject

/**
 * Audio-only media transport over the LiveKit Android SDK (docs/VOICE.md, ADR
 * 0006). Joins the SFU room with the JWT from lk-jwt-service, publishes the mic,
 * and subscribes to remote audio automatically. Earpiece is the default route;
 * `*` (Options) toggles the loudspeaker. No video is ever enabled.
 *
 * v1 is SFU-trusted (no frame-level E2EE) — the in-call UI says so.
 */
internal class LiveKitAudioTransport @Inject constructor(
    @ApplicationContext private val context: Context,
) : AudioTransport {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _connected = MutableStateFlow(false)
    override val connected: Flow<Boolean> = _connected

    private var room: Room? = null

    override suspend fun connect(config: TransportConfig): Boolean = runCatching {
        val r = room ?: LiveKit.create(context.applicationContext, overrides = audioOverrides())
            .also { room = it }
        r.connect(config.livekitUrl, config.token)
        _connected.value = true
        // Mic is best-effort: if RECORD_AUDIO was denied the call still connects
        // receive-only rather than dropping the whole call (docs/VOICE.md §6).
        runCatching { r.localParticipant.setMicrophoneEnabled(true) }
            .onFailure { Log.w(TAG, "mic publish failed: ${it.message}") }
        true
    }.getOrElse {
        Log.w(TAG, "LiveKit connect failed: ${it.message}")
        disconnect()
        false
    }

    override fun disconnect() {
        runCatching { room?.disconnect() }
        room = null
        _connected.value = false
    }

    override fun setMicMuted(muted: Boolean) {
        val r = room ?: return
        scope.launch { runCatching { r.localParticipant.setMicrophoneEnabled(!muted) } }
    }

    override fun setSpeakerOn(on: Boolean) {
        // Route earpiece/loudspeaker via AudioManager. The proximity sensor is
        // unreliable on these phones (docs/VOICE.md §6), so routing is explicit.
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // isSpeakerphoneOn is a no-op from API 31; the communication-device
                // API is the supported route control.
                val type =
                    if (on) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                val device = am.availableCommunicationDevices.firstOrNull { it.type == type }
                if (device != null) am.setCommunicationDevice(device) else am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = on
            }
        }.onFailure { Log.w(TAG, "speaker route failed: ${it.message}") }
    }

    /**
     * The entry-level MediaTek chips these phones use (Helio A22, docs/VOICE.md
     * §6) ship broken built-in AEC/noise-suppressor hardware that mangles the
     * captured PCM — the far side (e.g. Element) hears garbled, constantly noisy
     * audio while its own mic is fine. Turn the hardware effects off so WebRTC
     * does echo cancellation and noise suppression in software instead.
     */
    private fun audioOverrides() = LiveKitOverrides(
        audioOptions = AudioOptions(
            javaAudioDeviceModuleCustomizer = { builder ->
                builder.setUseHardwareAcousticEchoCanceler(false)
                builder.setUseHardwareNoiseSuppressor(false)
            },
        ),
    )

    private companion object { const val TAG = "LiveKitAudioTransport" }
}
