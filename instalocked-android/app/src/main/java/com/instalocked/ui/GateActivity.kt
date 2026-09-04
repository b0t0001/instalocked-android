package com.instalocked.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.instalocked.config.Config
import com.instalocked.policy.PolicyEngine
import com.instalocked.service.SessionService
import com.instalocked.store.Store

class GateActivity : Activity() {

    companion object {
        const val EXTRA_SCREEN = "screen"
        private const val MAX_BULK_INSERT = 3
    }

    private lateinit var config: Config
    private lateinit var input: NoPasteEditText
    private lateinit var counter: TextView
    private lateinit var error: TextView
    private lateinit var submit: Button
    private var screenName = "REELS_CONSUME"
    private var suppressWatcher = false

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Config.load(this)
        screenName = intent?.getStringExtra(EXTRA_SCREEN) ?: "REELS_CONSUME"
        setContentView(buildUi())
        updateCounter()
    }

    private fun buildUi(): ViewGroup {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(48), dp(28), dp(32))
        }

        col.addView(TextView(this).apply {
            text = "Why are you opening this?"
            setTextColor(Color.parseColor("#F2F2F2"))
            textSize = 24f
        })

        col.addView(TextView(this).apply {
            text = "${config.policy.gateWordCount} words, typed. " +
                "Pasting is disabled, and repeating a previous answer won't pass."
            setTextColor(Color.parseColor("#9A9A9A"))
            textSize = 14f
            setPadding(0, dp(10), 0, dp(24))
        })

        input = NoPasteEditText(this).apply {
            hint = "Start typing..."
            setHintTextColor(Color.parseColor("#5A5A5A"))
            setTextColor(Color.parseColor("#F2F2F2"))
            textSize = 16f
            gravity = Gravity.TOP or Gravity.START
            minHeight = dp(180)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#1C1C1C"))
                setStroke(dp(1), Color.parseColor("#2E2E2E"))
            }
        }
        input.addTextChangedListener(bulkInsertGuard())
        col.addView(input)

        counter = TextView(this).apply {
            setTextColor(Color.parseColor("#9A9A9A"))
            textSize = 13f
            setPadding(0, dp(12), 0, 0)
        }
        col.addView(counter)

        error = TextView(this).apply {
            setTextColor(Color.parseColor("#E2725B"))
            textSize = 13f
            visibility = TextView.GONE
            setPadding(0, dp(10), 0, 0)
        }
        col.addView(error)

        submit = Button(this).apply {
            text = "Unlock ${config.policy.sessionMinutes} minutes"
            isAllCaps = false
            textSize = 16f
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#5B8DEF"))
            }
            setPadding(dp(24), dp(16), dp(24), dp(16))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(24) }
            setOnClickListener { attemptSubmit() }
        }
        col.addView(submit)

        col.addView(Button(this).apply {
            text = "Never mind"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.parseColor("#9A9A9A"))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#1C1C1C"))
            }
            setPadding(dp(24), dp(14), dp(24), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
            setOnClickListener { finish() }
        })

        return ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#101010"))
            addView(col)
        }
    }

    /** Layer 3: reject any single insertion large enough to be a paste. */
    private fun bulkInsertGuard() = object : TextWatcher {
        private var before = ""
        private var pendingRevert = false

        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
            if (suppressWatcher) return
            before = s?.toString() ?: ""
            pendingRevert = after - count > MAX_BULK_INSERT
        }

        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { }

        override fun afterTextChanged(s: Editable?) {
            if (suppressWatcher) return
            if (pendingRevert) {
                pendingRevert = false
                suppressWatcher = true
                input.setText(before)
                input.setSelection(before.length)
                suppressWatcher = false
                showError("Pasting is disabled here. Type it out.")
                return
            }
            hideError()
            updateCounter()
        }
    }

    private fun updateCounter() {
        val n = words(input.text?.toString() ?: "").size
        val need = config.policy.gateWordCount
        counter.text = if (n >= need) "$n words" else "$n / $need words"
        counter.setTextColor(
            if (n >= need) Color.parseColor("#7FB069") else Color.parseColor("#9A9A9A")
        )
    }

    private fun attemptSubmit() {
        val text = (input.text?.toString() ?: "").trim()
        val tokens = words(text)
        val p = config.policy

        if (tokens.size < p.gateWordCount) {
            showError("${p.gateWordCount - tokens.size} more words.")
            return
        }
        if (tokens.toSet().size < p.gateMinDistinctWords) {
            showError("Too much repetition. Write an actual sentence.")
            return
        }

        val prior = Store.readEssays(this, 40).map { words(it.second).toSet() }
        val current = tokens.toSet()
        val tooSimilar = prior.any { jaccard(current, it) >= p.gateSimilarityRejectThreshold }
        if (tooSimilar) {
            showError("That's close to something you've written before. Say something new.")
            return
        }

        val state = Store.loadState(this)
        val started = PolicyEngine.startSession(
            state, config, System.currentTimeMillis(), Store.today()
        )
        if (!started) {
            showError("You've used all ${p.dailySessionLimit} sessions today.")
            submit.isEnabled = false
            return
        }
        Store.saveState(this, state)
        Store.appendEssay(this, text, screenName)
        SessionService.start(this, p.sessionMinutes, screenName)
        finish()
    }

    private fun words(s: String): List<String> =
        s.lowercase()
            .split(Regex("[^a-z0-9']+"))
            .filter { it.length >= 2 }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.count { b.contains(it) }.toDouble()
        return inter / (a.size + b.size - inter)
    }

    private fun showError(msg: String) {
        error.text = msg
        error.visibility = TextView.VISIBLE
    }

    private fun hideError() {
        error.visibility = TextView.GONE
    }

    override fun onBackPressed() {
        // Backing out is allowed; it just doesn't unlock anything.
        finish()
    }
}
