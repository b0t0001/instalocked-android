package com.instalocked.store

import android.content.Context
import android.content.SharedPreferences
import com.instalocked.policy.GuardState
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Deliberately no Room, no SQLite, no annotation processor. Two small files and
 * a SharedPreferences blob. Keeps the APK tiny and the build free of kapt/ksp.
 */
object Store {

    private const val PREFS = "instalocked"
    private const val K_SESSION_ENDS = "session_ends_at"
    private const val K_SESSIONS_TODAY = "sessions_today"
    private const val K_SESSIONS_DATE = "sessions_date"
    private const val K_CAPTURE_UNTIL = "capture_until"
    private const val K_ENABLED = "guard_enabled"

    const val ESSAYS_FILE = "essays.jsonl"
    const val SESSIONS_FILE = "sessions.jsonl"
    const val DUMP_FILE = "screen_dump.txt"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun loadState(ctx: Context): GuardState {
        val p = prefs(ctx)
        return GuardState(
            sessionEndsAt = p.getLong(K_SESSION_ENDS, 0L),
            sessionsToday = p.getInt(K_SESSIONS_TODAY, 0),
            sessionsTodayDate = p.getString(K_SESSIONS_DATE, "") ?: ""
        )
    }

    fun saveState(ctx: Context, s: GuardState) {
        prefs(ctx).edit()
            .putLong(K_SESSION_ENDS, s.sessionEndsAt)
            .putInt(K_SESSIONS_TODAY, s.sessionsToday)
            .putString(K_SESSIONS_DATE, s.sessionsTodayDate)
            .apply()
    }

    var Context.guardEnabled: Boolean
        get() = prefs(this).getBoolean(K_ENABLED, true)
        set(v) { prefs(this).edit().putBoolean(K_ENABLED, v).apply() }

    fun isEnabled(ctx: Context) = prefs(ctx).getBoolean(K_ENABLED, true)
    fun setEnabled(ctx: Context, v: Boolean) =
        prefs(ctx).edit().putBoolean(K_ENABLED, v).apply()

    // ---- Capture mode: a 3 minute window in which the guard dumps node trees ----

    fun captureUntil(ctx: Context): Long = prefs(ctx).getLong(K_CAPTURE_UNTIL, 0L)

    fun startCapture(ctx: Context, minutes: Int = 3) {
        prefs(ctx).edit()
            .putLong(K_CAPTURE_UNTIL, System.currentTimeMillis() + minutes * 60_000L)
            .apply()
        File(ctx.filesDir, DUMP_FILE).delete()
    }

    fun stopCapture(ctx: Context) {
        prefs(ctx).edit().putLong(K_CAPTURE_UNTIL, 0L).apply()
    }

    fun appendDump(ctx: Context, text: String) {
        try {
            File(ctx.filesDir, DUMP_FILE).appendText(text)
        } catch (t: Throwable) { /* dumping is best effort */ }
    }

    fun readDump(ctx: Context): String {
        val f = File(ctx.filesDir, DUMP_FILE)
        return if (f.exists()) f.readText() else ""
    }

    // ---- Essays ----

    /**
     * Stored so the gate can refuse a near-repeat. Without this the friction
     * evaporates in about a week, when a memorised paragraph gets typed on
     * autopilot. The full text is kept for the weekly review, not for scoring.
     */
    fun appendEssay(ctx: Context, text: String, screen: String) {
        val o = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("screen", screen)
            .put("text", text)
        try {
            File(ctx.filesDir, ESSAYS_FILE).appendText(o.toString() + "\n")
        } catch (t: Throwable) { }
    }

    fun readEssays(ctx: Context, limit: Int = 40): List<Pair<Long, String>> {
        val f = File(ctx.filesDir, ESSAYS_FILE)
        if (!f.exists()) return emptyList()
        return try {
            f.readLines().takeLast(limit).mapNotNull { line ->
                try {
                    val o = JSONObject(line)
                    o.optLong("ts") to o.optString("text")
                } catch (t: Throwable) { null }
            }
        } catch (t: Throwable) { emptyList() }
    }

    // ---- Session log ----

    fun appendSession(ctx: Context, screen: String, minutes: Int) {
        val o = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("screen", screen)
            .put("minutes", minutes)
        try {
            File(ctx.filesDir, SESSIONS_FILE).appendText(o.toString() + "\n")
        } catch (t: Throwable) { }
    }

    fun sessionCountToday(ctx: Context): Int {
        val f = File(ctx.filesDir, SESSIONS_FILE)
        if (!f.exists()) return 0
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = fmt.format(Date())
        return try {
            f.readLines().count { line ->
                try { fmt.format(Date(JSONObject(line).optLong("ts"))) == today }
                catch (t: Throwable) { false }
            }
        } catch (t: Throwable) { 0 }
    }
}
