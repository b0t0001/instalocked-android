package com.instalocked.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.instalocked.config.Config
import com.instalocked.service.GuardService
import com.instalocked.store.Store
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var col: LinearLayout

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(build())
    }

    override fun onResume() {
        super.onResume()
        setContentView(build())
    }

    private fun build(): ViewGroup {
        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(40), dp(24), dp(40))
        }

        heading("InstaLocked")
        val cfg = Config.load(this)
        body("Selector config v${cfg.version} \u00b7 feed cap ${cfg.policy.feedCap} \u00b7 " +
            "${cfg.policy.sessionMinutes} min sessions \u00b7 ${cfg.policy.dailySessionLimit}/day")

        space(20)
        heading2("Setup")

        statusRow("Accessibility service", accessibilityEnabled()) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        statusRow("Draw over other apps", overlayGranted()) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
        actionRow("Battery optimisation \u2192 Don't optimise") {
            // Motorola's manager will kill the session timer overnight otherwise.
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (t: Throwable) {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")))
            }
        }

        space(24)
        heading2("Guard")
        val enabled = Store.isEnabled(this)
        actionRow(if (enabled) "Guard is ON \u2014 turn off" else "Guard is OFF \u2014 turn on") {
            Store.setEnabled(this, !enabled)
            setContentView(build())
        }
        body("Sessions used today: ${Store.sessionCountToday(this)}")

        space(24)
        heading2("Calibration")
        body("Instagram updates rename its view IDs, which is what breaks the " +
            "classifier. Start capture, open the screen that misbehaved, come " +
            "back, and read the dump.")

        val capturing = Store.captureUntil(this) > System.currentTimeMillis()
        actionRow(if (capturing) "Capturing \u2014 stop" else "Start capture (3 min)") {
            if (capturing) {
                Store.stopCapture(this)
            } else {
                GuardService.instance?.resetCapture()
                Store.startCapture(this)
            }
            setContentView(build())
        }
        body("Dump size: ${Store.dumpSizeKb(this)} KB")
        actionRow("Save dump to Downloads") {
            val where = Store.exportDump(this)
            body(if (where != null) "Saved to $where" else "Nothing to export yet.")
            setContentView(build())
        }
        actionRow("View last dump") { showDump() }
        actionRow("Reload selectors.json") {
            GuardService.instance?.reloadConfig()
            body("Reloaded.")
            setContentView(build())
        }
        body("Override path: ${File(filesDir, Config.OVERRIDE_FILE).absolutePath}")

        space(24)
        heading2("Your reasons")
        val essays = Store.readEssays(this, 8)
        if (essays.isEmpty()) {
            body("Nothing yet.")
        } else {
            val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)
            essays.reversed().forEach { (ts, text) ->
                body("${fmt.format(Date(ts))} \u2014 ${text.take(140)}")
            }
        }

        space(24)
        body("This app has no internet permission. Nothing it reads can leave " +
            "the device, because the OS will not open a socket for it.")

        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#101010"))
            addView(col)
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun heading(t: String) = col.addView(TextView(this).apply {
        text = t
        setTextColor(Color.parseColor("#F2F2F2"))
        textSize = 26f
    })

    private fun heading2(t: String) = col.addView(TextView(this).apply {
        text = t
        setTextColor(Color.parseColor("#F2F2F2"))
        textSize = 17f
        setPadding(0, 0, 0, dp(8))
    })

    private fun body(t: String) = col.addView(TextView(this).apply {
        text = t
        setTextColor(Color.parseColor("#9A9A9A"))
        textSize = 14f
        setPadding(0, dp(4), 0, dp(4))
    })

    private fun space(h: Int) = col.addView(android.view.View(this).apply {
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(h)
        )
    })

    private fun statusRow(label: String, ok: Boolean, onClick: () -> Unit) {
        actionRow(if (ok) "\u2713  $label" else "\u2717  $label \u2014 tap to fix", ok, onClick)
    }

    // onClick must be the LAST parameter. Kotlin only allows trailing-lambda
    // syntax for the final argument, and every other call site here uses it.
    private fun actionRow(label: String, ok: Boolean = false, onClick: () -> Unit) {
        col.addView(Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.parseColor(if (ok) "#7FB069" else "#E0E0E0"))
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#1C1C1C"))
            }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        })
    }

    private fun showDump() {
        val dump = Store.readDump(this)
        val view = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#101010"))
            addView(TextView(this@MainActivity).apply {
                text = if (dump.isBlank()) "No dump captured yet." else dump
                setTextColor(Color.parseColor("#C8C8C8"))
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(dp(16), dp(32), dp(16), dp(32))
                setTextIsSelectable(true)
            })
        }
        setContentView(view)
    }

    private fun accessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.packageName == packageName }
    }

    private fun overlayGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this)
        else true
}
