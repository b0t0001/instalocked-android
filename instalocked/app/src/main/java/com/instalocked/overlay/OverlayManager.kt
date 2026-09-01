package com.instalocked.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.instalocked.config.Policy

/**
 * Everything that draws on top of Instagram lives here.
 *
 * Blocking is done with an overlay rather than by launching an Activity from
 * the service, because Android 10+ restricts background activity starts. The
 * overlay always appears; its button then launches the gate as a foreground,
 * user-initiated action, which is never blocked.
 */
class OverlayManager(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var scrim: View? = null
    private var ringMask: RingMaskView? = null
    private var chip: TextView? = null

    private val overlayType =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    // ------------------------------------------------------------------ scrim

    fun showScrim(
        title: String,
        body: String,
        primaryLabel: String?,
        onPrimary: (() -> Unit)?,
        secondaryLabel: String,
        onSecondary: () -> Unit
    ) {
        if (scrim != null) return

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FF0B0B0B"))
            setPadding(dp(32), dp(64), dp(32), dp(64))
            isClickable = true
            isFocusable = true
        }

        root.addView(TextView(ctx).apply {
            text = title
            setTextColor(Color.parseColor("#F2F2F2"))
            textSize = 22f
            gravity = Gravity.CENTER
        })

        root.addView(TextView(ctx).apply {
            text = body
            setTextColor(Color.parseColor("#9A9A9A"))
            textSize = 15f
            gravity = Gravity.CENTER
            setLineSpacing(dp(4).toFloat(), 1f)
            setPadding(0, dp(16), 0, dp(32))
        })

        if (primaryLabel != null && onPrimary != null) {
            root.addView(pillButton(primaryLabel, "#5B8DEF", "#FFFFFF") { onPrimary() })
        }
        root.addView(pillButton(secondaryLabel, "#2A2A2A", "#E0E0E0") { onSecondary() })

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )

        try {
            wm.addView(root, lp)
            scrim = root
        } catch (t: Throwable) { }
    }

    private fun pillButton(
        label: String,
        bg: String,
        fg: String,
        onClick: () -> Unit
    ): Button = Button(ctx).apply {
        text = label
        setTextColor(Color.parseColor(fg))
        textSize = 15f
        isAllCaps = false
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(Color.parseColor(bg))
        }
        setPadding(dp(24), dp(14), dp(24), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    fun hideScrim() {
        scrim?.let { try { wm.removeView(it) } catch (t: Throwable) { } }
        scrim = null
    }

    val scrimVisible: Boolean get() = scrim != null

    // -------------------------------------------------------------- ring mask

    fun showRings(rects: List<Rect>, policy: Policy) {
        if (rects.isEmpty()) { hideRings(); return }
        var v = ringMask
        if (v == null) {
            v = RingMaskView(ctx, policy)
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType,
                // Not touchable, not focusable: taps fall straight through to
                // Instagram so opening a story still works normally.
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            try {
                wm.addView(v, lp)
                ringMask = v
            } catch (t: Throwable) { return }
        }
        v.update(rects, policy)
    }

    fun hideRings() {
        ringMask?.let { try { wm.removeView(it) } catch (t: Throwable) { } }
        ringMask = null
    }

    // ---------------------------------------------------------- countdown chip

    fun showChip(text: String) {
        var c = chip
        if (c == null) {
            c = TextView(ctx).apply {
                setTextColor(Color.parseColor("#E0E0E0"))
                textSize = 13f
                setPadding(dp(14), dp(7), dp(14), dp(7))
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(Color.parseColor("#CC1C1C1C"))
                }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(90)
            }
            try {
                wm.addView(c, lp)
                chip = c
            } catch (t: Throwable) { return }
        }
        c.text = text
    }

    fun hideChip() {
        chip?.let { try { wm.removeView(it) } catch (t: Throwable) { } }
        chip = null
    }

    fun hideAll() {
        hideScrim(); hideRings(); hideChip()
    }
}
