package org.matchat.feature.timeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View

/**
 * A non-touch image canvas for feature phones (S9 image viewer). The image opens
 * fit-to-screen; `*` zooms in, `#` zooms out, and the D-pad pans once the image is
 * larger than the screen. There is no pinch or drag — everything is a key press,
 * so the whole gesture set works on a hardware keypad (AGENTS.md §4).
 *
 * The view is focusable so it receives D-pad and keypad key events through the
 * normal platform dispatch (MainActivity passes directional and star/pound keys
 * straight through). Panning is clamped to the image edges; zoom steps through
 * discrete levels, and 0 resets to fit.
 */
class ZoomPanImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()

    private var bitmap: Bitmap? = null
    private var zoom = FIT // multiplier over the fit scale; FIT == fills the screen
    private var panX = 0f
    private var panY = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun setBitmap(bmp: Bitmap?) {
        bitmap = bmp
        zoom = FIT
        panX = 0f
        panY = 0f
        requestLayout()
        invalidate()
    }

    // D-pad and `*`/`#` reach the focused view directly (the host forwards them to
    // the platform). Digit keys are routed to the Fragment instead, which calls the
    // public pan/zoom/reset methods below — so both paths drive the same logic.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (bitmap == null) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_STAR -> { zoomIn(); true }
            KeyEvent.KEYCODE_POUND -> { zoomOut(); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> pan(PAN_STEP, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> pan(-PAN_STEP, 0f)
            KeyEvent.KEYCODE_DPAD_UP -> pan(0f, PAN_STEP)
            KeyEvent.KEYCODE_DPAD_DOWN -> pan(0f, -PAN_STEP)
            else -> super.onKeyDown(keyCode, event)
        }
    }

    fun zoomIn() = stepZoom(+1)
    fun zoomOut() = stepZoom(-1)
    fun panLeft() = pan(PAN_STEP, 0f)
    fun panRight() = pan(-PAN_STEP, 0f)
    fun panUp() = pan(0f, PAN_STEP)
    fun panDown() = pan(0f, -PAN_STEP)

    fun resetView() {
        zoom = FIT
        panX = 0f
        panY = 0f
        invalidate()
    }

    /** Moves to the next/previous discrete zoom level (crisper than a continuous
     *  factor on a small screen). */
    private fun stepZoom(direction: Int) {
        val current = ZOOM_LEVELS.indexOfLast { it <= zoom + 0.01f }.coerceAtLeast(0)
        val next = (current + direction).coerceIn(0, ZOOM_LEVELS.lastIndex)
        zoom = ZOOM_LEVELS[next]
        if (zoom <= FIT) { panX = 0f; panY = 0f }
        clampPan()
        invalidate()
    }

    /** Pans by (dx, dy); returns true when the image actually moved (so a pan at an
     *  edge, or with nothing to pan, does not steal focus behaviour elsewhere). */
    private fun pan(dx: Float, dy: Float): Boolean {
        val beforeX = panX
        val beforeY = panY
        panX += dx
        panY += dy
        clampPan()
        val moved = panX != beforeX || panY != beforeY
        if (moved) invalidate()
        // Always consume directional keys in the viewer: there is nothing else to
        // move focus to, and an unconsumed key would beep/scroll unexpectedly.
        return true
    }

    private fun clampPan() {
        val bmp = bitmap ?: return
        val scale = fitScale(bmp) * zoom
        val drawW = bmp.width * scale
        val drawH = bmp.height * scale
        val maxX = maxOf(0f, (drawW - width) / 2f)
        val maxY = maxOf(0f, (drawH - height) / 2f)
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    private fun fitScale(bmp: Bitmap): Float =
        if (width == 0 || height == 0) 1f
        else minOf(width.toFloat() / bmp.width, height.toFloat() / bmp.height)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampPan()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        val scale = fitScale(bmp) * zoom
        val drawW = bmp.width * scale
        val drawH = bmp.height * scale
        // Centre the image, then apply the pan offset.
        val left = (width - drawW) / 2f + panX
        val top = (height - drawH) / 2f + panY
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(left, top)
        canvas.drawBitmap(bmp, matrix, paint)
    }

    private companion object {
        const val FIT = 1f
        const val PAN_STEP = 48f
        val ZOOM_LEVELS = floatArrayOf(1f, 2f, 3f, 4f, 6f)
    }
}
