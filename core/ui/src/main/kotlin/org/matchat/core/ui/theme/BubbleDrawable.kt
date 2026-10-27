package org.matchat.core.ui.theme

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import org.matchat.core.ui.R

/**
 * An S9 message bubble, matching TurboText's (per the user's reference clip):
 * a rounded body with a small tail off the bottom corner on the sender's side
 * (own: bottom-right; received: bottom-left), an accent stripe down that same
 * side cut to the bubble's own outline, and a border. Ported from TurboText's
 * BubbleDrawable; XML shapes can't draw the tail.
 *
 * Stateful: the border recolors and thickens while the bubble's state set
 * includes `state_focused` — the S9 focus-border exception (AGENTS.md §4).
 * The bubble view picks up its row's focus via `android:duplicateParentState`.
 *
 * The tail takes the bottom `bubble_tail_height` of the bounds, so the bubble view
 * carries that much extra bottom padding (`@dimen/bubble_pad_bottom`).
 * Everything is anti-aliased paths, never clipPath (not anti-aliased on a
 * hardware canvas).
 */
class BubbleDrawable private constructor(
    private val colors: Colors,
    private val size: Metrics,
    private val isOwn: Boolean,
) : Drawable() {

    /** Theme colors, already composited (veils over the accent). */
    private class Colors(
        val fill: Int,
        val stripe: Int,
        val border: Int,
        val focusedBorder: Int,
    )

    /** Dimensions in px (dimens.xml bubble_*). */
    private class Metrics(
        val radius: Float,
        val stripeWidth: Float,
        val tailWidth: Float,
        val tailHeight: Float,
        val borderWidth: Float,
        val focusedBorderWidth: Float,
    )

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.fill }
    private val stripePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colors.stripe }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val outline = Path()
    private val stripe = Path()
    private val border = Path()
    private var focused = false

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val nowFocused = android.R.attr.state_focused in state
        if (nowFocused == focused) return false
        focused = nowFocused
        buildBorder(RectF(bounds))
        invalidateSelf()
        return true
    }

    override fun onBoundsChange(bounds: Rect) {
        val box = RectF(bounds)
        outline.reset()
        outline.set(shape(box, size.radius))

        stripe.reset()
        val band = Path()
        band.addRect(
            if (isOwn) {
                RectF(box.right - size.stripeWidth, box.top, box.right, box.bottom)
            } else {
                RectF(box.left, box.top, box.left + size.stripeWidth, box.bottom)
            },
            Path.Direction.CW,
        )
        stripe.op(outline, band, Path.Op.INTERSECT)

        buildBorder(box)
    }

    /** The border sits just inside the edge so none of it is cut off. */
    private fun buildBorder(box: RectF) {
        val width = if (focused) size.focusedBorderWidth else size.borderWidth
        borderPaint.strokeWidth = width
        borderPaint.color = if (focused) colors.focusedBorder else colors.border
        border.reset()
        if (width <= 0f || box.isEmpty) return
        val half = width / 2f
        val inner = RectF(box.left + half, box.top + half, box.right - half, box.bottom - half)
        border.set(shape(inner, (size.radius - half).coerceAtLeast(0f)))
    }

    /** The rounded body, which stops the tail height short of [box]'s
     *  bottom, plus the tail: its outer edge carries straight down the
     *  bubble's side to a point, and its inner edge hooks back up to the
     *  body's bottom edge. It overlaps the body's corner so the union is
     *  seamless. */
    private fun shape(box: RectF, r: Float): Path {
        val path = Path()
        val bodyBottom = box.bottom - size.tailHeight
        path.addRoundRect(RectF(box.left, box.top, box.right, bodyBottom), r, r, Path.Direction.CW)

        fun x(fromEdge: Float) = if (isOwn) box.right - fromEdge else box.left + fromEdge
        val tail = Path()
        tail.moveTo(x(0f), bodyBottom - r)
        tail.lineTo(x(0f), box.bottom)
        val innerEdge = r / 2f + size.tailWidth
        tail.quadTo(x(r * TAIL_CURVE_X), bodyBottom + size.tailHeight * TAIL_CURVE_Y, x(innerEdge), bodyBottom - 1f)
        tail.lineTo(x(innerEdge), bodyBottom - r)
        tail.close()
        path.op(tail, Path.Op.UNION)
        return path
    }

    override fun draw(canvas: Canvas) {
        canvas.drawPath(outline, fillPaint)
        if (!stripe.isEmpty) canvas.drawPath(stripe, stripePaint)
        if (!border.isEmpty) canvas.drawPath(border, borderPaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        stripePaint.alpha = alpha
        borderPaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        stripePaint.colorFilter = colorFilter
        borderPaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        private const val TAIL_CURVE_X = 0.25f
        private const val TAIL_CURVE_Y = 0.3f

        /** A bubble for an own ([isOwn]) or received message, colored from
         *  [context]'s theme: received fill = colorBubbleReceived; own fill =
         *  colorBubbleOwnVeil over the accent; stripe and focused border =
         *  colorBubbleStripeVeil over the accent; unfocused border =
         *  colorBubbleBorder. */
        fun create(context: Context, isOwn: Boolean): BubbleDrawable {
            val accent = context.themeColor(R.attr.colorFocusAccent)
            val lightenedAccent = ColorUtils.compositeColors(context.themeColor(R.attr.colorBubbleStripeVeil), accent)
            val fill = if (isOwn) {
                ColorUtils.compositeColors(context.themeColor(R.attr.colorBubbleOwnVeil), accent)
            } else {
                context.themeColor(R.attr.colorBubbleReceived)
            }
            val res = context.resources
            return BubbleDrawable(
                colors = Colors(
                    fill = fill,
                    stripe = lightenedAccent,
                    border = context.themeColor(R.attr.colorBubbleBorder),
                    focusedBorder = lightenedAccent,
                ),
                size = Metrics(
                    radius = res.getDimension(R.dimen.bubble_corner_radius),
                    stripeWidth = res.getDimension(R.dimen.bubble_stripe_width),
                    tailWidth = res.getDimension(R.dimen.bubble_tail_width),
                    tailHeight = res.getDimension(R.dimen.bubble_tail_height),
                    borderWidth = res.getDimension(R.dimen.bubble_border_width),
                    focusedBorderWidth = res.getDimension(R.dimen.bubble_border_width_focused),
                ),
                isOwn = isOwn,
            )
        }
    }
}
