package com.instalocked.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import com.instalocked.config.Policy

/**
 * Draws an opaque grey annulus over each story-tray ring.
 *
 * This is the honest limit of the whole project: Android gives no way to
 * desaturate another app's pixels. We are not removing colour, we are painting
 * over the place the colour was. Consequences you will actually notice:
 *   - during a fast fling of the tray the donuts trail the real rings by a
 *     frame or two, because we only get new bounds when accessibility tells us
 *   - the geometry is derived from item bounds, so it needs calibrating once
 *     via the three ring* fractions in selectors.json
 */
@SuppressLint("ViewConstructor")
class RingMaskView(context: Context, private var policy: Policy) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = policy.ringColor
    }

    private var rects: List<Rect> = emptyList()

    fun update(newRects: List<Rect>, newPolicy: Policy) {
        policy = newPolicy
        paint.color = newPolicy.ringColor
        // Cheap identity check: redrawing on every content-changed event on a
        // budget SoC is a measurable battery cost for zero visual gain.
        if (newRects.size == rects.size && newRects.zip(rects).all { it.first == it.second }) return
        rects = newRects
        invalidate()
    }

    fun clear() {
        if (rects.isEmpty()) return
        rects = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (rects.isEmpty()) return
        for (r in rects) {
            // The avatar sits in the upper portion of a tray cell, with the
            // username label below it. Diameter tracks the narrower dimension.
            val cell = minOf(r.width(), r.height()).toFloat()
            val outer = cell * policy.ringOuterFraction
            val thickness = outer * policy.ringThicknessFraction
            if (thickness < 0.7f || outer < 4f) continue

            val cx = r.exactCenterX()
            val cy = r.top + r.height() * policy.ringVerticalBiasFraction
            val radius = outer / 2f - thickness / 2f

            paint.strokeWidth = thickness
            // Two passes: a single stroke leaves the saturated gradient faintly
            // visible through the antialiased edge.
            canvas.drawCircle(cx, cy, radius, paint)
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }
}
