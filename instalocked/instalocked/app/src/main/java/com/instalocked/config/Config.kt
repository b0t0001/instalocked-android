package com.instalocked.config

import android.content.Context
import android.graphics.Color
import org.json.JSONObject
import java.io.File

/**
 * A single signal to look for in the scanned node tree.
 *
 * [mode] matters more than it looks. Substring matching on resource IDs is a
 * trap: "main_feed" is a substring of "main_feed_action_bar", which Instagram
 * renders on EVERY screen, so a substring rule for the home feed matched the
 * entire app. Resource IDs now default to exact matching on the short name
 * (the part after "id/"). Use prefix or contains only deliberately.
 */
data class Matcher(
    val type: String,
    val contains: String,
    val mode: String = "equals"
) {
    companion object {
        fun from(o: JSONObject) = Matcher(
            type = o.optString("type", "resourceId"),
            contains = o.optString("contains", "").lowercase(),
            mode = o.optString("mode", if (o.optString("type") == "resourceId") "equals" else "contains")
        )
    }
}

/** Fires when ANY of [any] matches and NONE of [none] matches. */
data class ScreenRule(val any: List<Matcher>, val none: List<Matcher>)

data class Policy(
    val feedCap: Int,
    val sessionMinutes: Int,
    val gateWordCount: Int,
    val gateMinDistinctWords: Int,
    val gateSimilarityRejectThreshold: Double,
    val maskRings: Boolean,
    val ringThicknessFraction: Float,
    val ringOuterFraction: Float,
    val ringVerticalBiasFraction: Float,
    val ringColor: Int,
    val dailySessionLimit: Int,
    val dmReelProvenanceMs: Long,
    val dmReelBounce: Boolean
)

class Config(
    val version: Int,
    val targetPackage: String,
    val screens: Map<String, ScreenRule>,
    val feedEndMarkers: List<String>,
    val trayContainerIds: List<String>,
    val trayItemIds: List<String>,
    val policy: Policy
) {
    companion object {
        const val OVERRIDE_FILE = "selectors.json"

        /**
         * Load order: a user-supplied override in filesDir, else the asset shipped
         * in the APK. The override is what lets us re-calibrate after an Instagram
         * update without rebuilding and reinstalling.
         */
        fun load(ctx: Context): Config {
            val override = File(ctx.filesDir, OVERRIDE_FILE)
            val raw = try {
                if (override.exists() && override.length() > 0) {
                    override.readText()
                } else {
                    ctx.assets.open(OVERRIDE_FILE).bufferedReader().use { it.readText() }
                }
            } catch (t: Throwable) {
                // Absolute last resort: an empty config means every screen is UNKNOWN,
                // which means everything is allowed. Fail open, never fail closed.
                "{}"
            }
            return parse(raw)
        }

        fun parse(raw: String): Config {
            val root = try { JSONObject(raw) } catch (t: Throwable) { JSONObject() }

            val screens = HashMap<String, ScreenRule>()
            val screensObj = root.optJSONObject("screens") ?: JSONObject()
            for (key in screensObj.keys()) {
                val ruleObj = screensObj.optJSONObject(key) ?: continue
                screens[key] = ScreenRule(
                    any = matcherList(ruleObj, "any"),
                    none = matcherList(ruleObj, "none")
                )
            }

            val markers = root.optJSONObject("markers") ?: JSONObject()
            val feedEnd = ArrayList<String>()
            markers.optJSONArray("feedEnd")?.let { arr ->
                for (i in 0 until arr.length()) feedEnd.add(arr.optString(i).lowercase())
            }

            val tray = root.optJSONObject("storyTray") ?: JSONObject()
            val containerIds = ArrayList<String>()
            tray.optJSONArray("containerIds")?.let { arr ->
                for (i in 0 until arr.length()) containerIds.add(arr.optString(i).lowercase())
            }
            val itemIds = ArrayList<String>()
            tray.optJSONArray("itemIds")?.let { arr ->
                for (i in 0 until arr.length()) itemIds.add(arr.optString(i).lowercase())
            }

            val p = root.optJSONObject("policy") ?: JSONObject()
            val policy = Policy(
                feedCap = p.optInt("feedCap", 20),
                sessionMinutes = p.optInt("sessionMinutes", 5),
                gateWordCount = p.optInt("gateWordCount", 30),
                gateMinDistinctWords = p.optInt("gateMinDistinctWords", 14),
                gateSimilarityRejectThreshold = p.optDouble("gateSimilarityRejectThreshold", 0.65),
                maskRings = p.optBoolean("maskRings", true),
                ringThicknessFraction = p.optDouble("ringThicknessFraction", 0.055).toFloat(),
                ringOuterFraction = p.optDouble("ringOuterFraction", 0.92).toFloat(),
                ringVerticalBiasFraction = p.optDouble("ringVerticalBiasFraction", 0.44).toFloat(),
                ringColor = parseColor(p.optString("ringColor", "#FF6E6E6E")),
                dailySessionLimit = p.optInt("dailySessionLimit", 4),
                dmReelProvenanceMs = p.optLong("dmReelProvenanceMs", 25_000L),
                dmReelBounce = p.optBoolean("dmReelBounce", true)
            )

            return Config(
                version = root.optInt("version", 0),
                targetPackage = root.optString("targetPackage", "com.instagram.android"),
                screens = screens,
                feedEndMarkers = feedEnd,
                trayContainerIds = containerIds,
                trayItemIds = itemIds,
                policy = policy
            )
        }

        private fun matcherList(o: JSONObject, key: String): List<Matcher> {
            val arr = o.optJSONArray(key) ?: return emptyList()
            val out = ArrayList<Matcher>(arr.length())
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { out.add(Matcher.from(it)) }
            }
            return out
        }

        private fun parseColor(s: String): Int =
            try { Color.parseColor(s) } catch (t: Throwable) { 0xFF6E6E6E.toInt() }
    }
}
