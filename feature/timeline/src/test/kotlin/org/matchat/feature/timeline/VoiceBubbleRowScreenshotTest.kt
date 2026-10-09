package org.matchat.feature.timeline

import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test
import org.matchat.core.ui.theme.themeColor
import org.matchat.feature.timeline.databinding.ItemVoiceBubbleBinding
import org.matchat.core.ui.R as UiR

/**
 * Screenshot coverage for the Voice bubble + Voice playback rounds, mirroring
 * MessageRowScreenshotTest's own conventions (same reference viewport, same
 * own/received bubble backgrounds, same focus-via-drawable-state approach).
 * Covers the idle row (▶, no fill) and a mid-playback row (⏸, accent progress
 * fill, "elapsed / total" readout). A diff is a review conversation; an
 * unreviewed diff blocks.
 */
class VoiceBubbleRowScreenshotTest {

    private val config = DeviceConfig(
        screenWidth = 240,
        screenHeight = 320,
        density = Density.MEDIUM,
        orientation = ScreenOrientation.PORTRAIT,
    )

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = config)

    private val sampleWaveform = List(30) { i -> (i % 10) / 10f + 0.05f }
    private val flatWaveform = List(30) { 0.15f }

    private fun row(
        isOwn: Boolean,
        waveform: List<Float>,
        duration: String = "0:12",
        playing: Boolean = false,
        progress: Float = 0f,
        positionText: String? = null,
    ): View {
        val binding = ItemVoiceBubbleBinding.inflate(LayoutInflater.from(paparazzi.context))
        val ctx = paparazzi.context
        binding.voiceWaveform.setValues(waveform)
        binding.voiceWaveform.setBarColor(ctx.themeColor(UiR.attr.colorTextOnFocus))
        binding.voiceWaveform.setProgressColor(ctx.themeColor(UiR.attr.colorFocusAccent))
        binding.voiceWaveform.setProgress(progress)
        binding.voicePlay.text = if (playing) "⏸" else "▶"
        binding.voiceDuration.text = positionText?.let { "$it / $duration" } ?: duration
        binding.voiceTime.text = "3:42 PM"
        // Mirror VoiceBubbleVH.bind: bubble background per side, cluster + time
        // aligned to the same side.
        binding.voiceBubble.setBackgroundResource(
            if (isOwn) UiR.drawable.bubble_own else UiR.drawable.bubble_received,
        )
        val gravity = if (isOwn) Gravity.END else Gravity.START
        (binding.voiceRow.layoutParams as LinearLayout.LayoutParams).gravity = gravity
        (binding.voiceTime.layoutParams as LinearLayout.LayoutParams).gravity = gravity
        return binding.root
    }

    @Test
    fun voiceBubble_received_realWaveform() {
        paparazzi.snapshot(row(isOwn = false, waveform = sampleWaveform))
    }

    @Test
    fun voiceBubble_own_realWaveform() {
        paparazzi.snapshot(row(isOwn = true, waveform = sampleWaveform))
    }

    @Test
    fun voiceBubble_flatWaveform() {
        // AUDIO (never carries real samples) or a VOICE message from a
        // client that omitted one — TimelineViewModel.FLAT_WAVEFORM.
        paparazzi.snapshot(row(isOwn = false, waveform = flatWaveform))
    }

    @Test
    fun voiceBubble_playing_withProgress() {
        // Mid-playback: pause glyph, accent fill up to ~40%, "0:05 / 0:12".
        paparazzi.snapshot(
            row(
                isOwn = false,
                waveform = sampleWaveform,
                playing = true,
                progress = 0.4f,
                positionText = "0:05",
            ),
        )
    }
}
