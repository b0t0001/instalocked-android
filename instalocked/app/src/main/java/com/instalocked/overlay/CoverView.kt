package com.instalocked.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.View

/**
 * Fills opaque black rectangles over specific on-screen regions.
 *
 * Visual only: the window is not touchable, so taps still reach Instagram
 * underneath. That keeps the search field above a covered grid usable.
 */
class CoverView(context: Context) : View(context) {

    private val paint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.BLACK
    }

    private var rects: List<Rect> = emptyList()

    fun update(newRects: List<Rect>) {
        if (newRects.size == rects.size && newRects.zip(rects).all { it.first == it.second }) return
        rects = newRects
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for (r in rects) canvas.drawRect(r, paint)
    }
}
