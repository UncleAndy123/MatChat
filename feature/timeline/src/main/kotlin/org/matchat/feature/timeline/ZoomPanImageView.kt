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
 * straight through). Panning is clamped to the image edges; zoom is clamped to
 * [FIT, MAX_ZOOM].
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (bitmap == null) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_STAR -> { zoomBy(ZOOM_STEP); true }
            KeyEvent.KEYCODE_POUND -> { zoomBy(1f / ZOOM_STEP); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> panBy(PAN_STEP, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> panBy(-PAN_STEP, 0f)
            KeyEvent.KEYCODE_DPAD_UP -> panBy(0f, PAN_STEP)
            KeyEvent.KEYCODE_DPAD_DOWN -> panBy(0f, -PAN_STEP)
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun zoomBy(factor: Float) {
        zoom = (zoom * factor).coerceIn(FIT, MAX_ZOOM)
        clampPan()
        invalidate()
    }

    /** Pans by (dx, dy); returns true when the image actually moved (so a pan at an
     *  edge, or with nothing to pan, does not steal focus behaviour elsewhere). */
    private fun panBy(dx: Float, dy: Float): Boolean {
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
        const val MAX_ZOOM = 8f
        const val ZOOM_STEP = 1.4f
        const val PAN_STEP = 48f
    }
}
