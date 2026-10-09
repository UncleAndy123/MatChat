package org.matchat.feature.timeline

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * A row of fixed-width bars for a voice message's waveform (Voice bubble
 * round) — the visual centerpiece of [TimelineAdapter.VoiceBubbleVH] and the
 * staged-voice attachment preview. Purely decorative: this view is never
 * itself focusable/clickable (the whole bubble row is the one focusable
 * unit, per this app's D-pad convention — see item_message.xml's own note
 * on why reaction chips aren't individually tappable either).
 *
 * [values] are normalized 0f..1f (Mappers.normalizeWaveform /
 * VoiceRecorder.buildWaveform already produce this range) — this view does
 * no scaling of its own beyond mapping 0f..1f onto its measured height.
 *
 * Voice playback round: it doubles as the message's progress bar. Bars left
 * of the [progress] playhead draw in [progressColor] (the accent), the rest
 * in [barColor] — so a glance shows how far along the clip is, with no extra
 * widget. [progress] is 0f when idle, so a not-yet-played clip looks exactly
 * as it did before this round.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var values: List<Float> = emptyList()
    private var progress = 0f // 0f..1f fraction of the clip already played

    /** The color un-played bars are drawn in — set once per bind, since it
     *  depends on isOwn (own vs. received bubble) via ?attr/colorTextOnFocus,
     *  same as the rest of the bubble's text. */
    fun setBarColor(color: Int) {
        barPaint.color = color
        invalidate()
    }

    /** The color of bars left of the playhead (the accent). */
    fun setProgressColor(color: Int) {
        playedPaint.color = color
        invalidate()
    }

    fun setValues(values: List<Float>) {
        this.values = values
        invalidate()
    }

    /** [fraction] of the clip already played (0f..1f); 0f shows no fill. */
    fun setProgress(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped == progress) return
        progress = clamped
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val gap = BAR_GAP_DP * resources.displayMetrics.density
        val barWidth = (width - gap * (values.size - 1)) / values.size
        if (barWidth <= 0f) return
        val minBarHeight = MIN_BAR_HEIGHT_DP * resources.displayMetrics.density
        val playedWidth = width * progress
        values.forEachIndexed { i, v ->
            val barHeight = (height * v.coerceIn(0f, 1f)).coerceAtLeast(minBarHeight)
            val left = i * (barWidth + gap)
            val top = (height - barHeight) / 2f
            // A bar counts as "played" once its centre is behind the playhead.
            val paint = if (left + barWidth / 2f <= playedWidth) playedPaint else barPaint
            canvas.drawRect(left, top, left + barWidth, top + barHeight, paint)
        }
    }

    private companion object {
        const val BAR_GAP_DP = 1.5f
        const val MIN_BAR_HEIGHT_DP = 2f // a silent stretch still reads as a bar, not a gap
    }
}
