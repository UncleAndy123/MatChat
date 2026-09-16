package org.matchat.core.rtc.internal

import android.content.Context
import android.util.Log
import com.twilio.audioswitch.AudioDevice
import dagger.hilt.android.qualifiers.ApplicationContext
import io.livekit.android.AudioOptions
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.audio.AudioSwitchHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.RemoteTrackPublication
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.TrackPublication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    private var eventsJob: Job? = null

    /**
     * The single owner of in-call audio routing. Earpiece is placed BEFORE
     * Speakerphone (LiveKit's default order is the reverse) so a call opens on the
     * earpiece like a normal phone call (docs/VOICE.md §6). With speakerphone as
     * the default, the mic re-captures the loudspeaker output as echo — the
     * constant call noise on these devices. The `*`/Options key flips it via
     * [setSpeakerOn], driven through this same handler so nothing else fights it.
     */
    private val audioHandler = AudioSwitchHandler(context).apply {
        preferredDeviceList = listOf(
            AudioDevice.BluetoothHeadset::class.java,
            AudioDevice.WiredHeadset::class.java,
            AudioDevice.Earpiece::class.java,
            AudioDevice.Speakerphone::class.java,
        )
    }

    override suspend fun connect(config: TransportConfig): Boolean = runCatching {
        val r = room ?: LiveKit.create(context.applicationContext, overrides = audioOverrides())
            .also { room = it }
        // Audio-only, so never auto-subscribe: with autoSubscribe on, the SFU
        // forwards the far side's (Element) video track and LiveKit spins up a VP8
        // decoder. On these low-end devices that decoder fails to configure and
        // retries in a hot loop, starving the audio threads — the garbled, noisy
        // call audio. We subscribe to audio publications ourselves and leave video
        // untouched, so no video decoder is ever created (docs/VOICE.md §2).
        r.connect(config.livekitUrl, config.token, ConnectOptions(autoSubscribe = false))
        _connected.value = true
        subscribeToRemoteAudio(r)
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

    /**
     * Subscribe to remote AUDIO tracks only (video is never decoded — see
     * [connect]). Handles both tracks already published when we join and any
     * published later via [RoomEvent.TrackPublished].
     */
    private fun subscribeToRemoteAudio(room: Room) {
        eventsJob?.cancel()
        eventsJob = scope.launch {
            room.events.collect { event ->
                if (event is RoomEvent.TrackPublished) subscribeIfAudio(event.publication)
            }
        }
        room.remoteParticipants.values.forEach { participant ->
            participant.trackPublications.values.forEach(::subscribeIfAudio)
        }
    }

    private fun subscribeIfAudio(publication: TrackPublication) {
        if (publication.kind == Track.Kind.AUDIO) {
            (publication as? RemoteTrackPublication)?.let { runCatching { it.setSubscribed(true) } }
        }
    }

    override fun disconnect() {
        eventsJob?.cancel()
        eventsJob = null
        runCatching { room?.disconnect() }
        room = null
        _connected.value = false
    }

    override fun setMicMuted(muted: Boolean) {
        val r = room ?: return
        scope.launch { runCatching { r.localParticipant.setMicrophoneEnabled(!muted) } }
    }

    override fun setSpeakerOn(on: Boolean) {
        // Drive routing through the same AudioSwitchHandler LiveKit uses, so there
        // is one owner and nothing overrides it back. Pick the built-in speaker or
        // earpiece from what is actually available; a wired/BT headset, if present,
        // is left to the handler's own preference.
        val device = audioHandler.availableAudioDevices.firstOrNull {
            if (on) it is AudioDevice.Speakerphone else it is AudioDevice.Earpiece
        }
        if (device != null) audioHandler.selectDevice(device)
    }

    /**
     * Hand LiveKit our [audioHandler] so calls default to the earpiece instead of
     * the loudspeaker (the echo/noise source on these phones). Hardware AEC/noise
     * suppression is left at the SDK default (on where supported) — VOICE_COMMUNICATION
     * capture plus the platform echo canceller is what keeps a speaker call clean.
     */
    private fun audioOverrides() = LiveKitOverrides(
        audioOptions = AudioOptions(audioHandler = audioHandler),
    )

    private companion object {
        const val TAG = "LiveKitAudioTransport"
    }
}
