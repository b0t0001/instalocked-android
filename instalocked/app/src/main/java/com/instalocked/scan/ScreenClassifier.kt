package com.instalocked.scan

import com.instalocked.config.Config
import com.instalocked.config.ScreenRule

enum class Screen {
    FEED,
    REELS_CONSUME,
    REELS_FROM_DM,
    REELS_CREATE,
    EXPLORE_GRID,
    SEARCH,
    DMS,
    STORY,
    PROFILE,
    UNKNOWN
}

/**
 * Context the classifier needs but cannot read off the screen.
 *
 * A reel a friend sent you and a reel the algorithm served you are very often
 * the same view hierarchy. Structure alone cannot separate them, so we carry
 * provenance: how long ago the user was demonstrably in a DM thread.
 */
data class ClassifyContext(
    val msSinceDms: Long,
    val provenanceWindowMs: Long
)

object ScreenClassifier {

    /**
     * Screens resolved purely structurally, in precedence order.
     * REELS_CREATE, REELS_CONSUME and REELS_FROM_DM are handled ahead of this
     * list because they need special ordering against each other and against
     * DMS: a shared reel carries a reply composer, which would otherwise make
     * the DMS rule claim it first and skip the no-scroll enforcement.
     */
    private val ORDER = listOf(
        "DMS" to Screen.DMS,
        "SEARCH" to Screen.SEARCH,
        "STORY" to Screen.STORY,
        "EXPLORE_GRID" to Screen.EXPLORE_GRID,
        "PROFILE" to Screen.PROFILE,
        "FEED" to Screen.FEED
    )

    fun classify(scan: NodeScan, config: Config, ctx: ClassifyContext): Screen {
        // 1. Creating always wins over anything that looks like consuming.
        config.screens["REELS_CREATE"]?.let {
            if (fires(it, scan)) return Screen.REELS_CREATE
        }

        // 2. Is a reel viewer on screen at all?
        val reelRule = config.screens["REELS_CONSUME"]
        if (reelRule != null && fires(reelRule, scan)) {
            val dmRule = config.screens["REELS_FROM_DM"]
            val structuralDm = dmRule != null && fires(dmRule, scan)
            val cameFromDms = ctx.msSinceDms in 0..ctx.provenanceWindowMs

            // Either signal is enough. Provenance covers the case where the
            // shared-reel chrome is hidden; structure covers the case where the
            // user lingered past the provenance window before tapping.
            return if (structuralDm || cameFromDms) Screen.REELS_FROM_DM
            else Screen.REELS_CONSUME
        }

        // 3. Everything else, structurally.
        for ((key, screen) in ORDER) {
            val rule = config.screens[key] ?: continue
            if (fires(rule, scan)) return screen
        }

        // Unrecognised means allowed. A broken selector after an Instagram
        // update degrades to "no restrictions", never to "locked out of DMs".
        return Screen.UNKNOWN
    }

    private fun fires(rule: ScreenRule, scan: NodeScan): Boolean {
        if (rule.any.isEmpty()) return false
        if (rule.none.any { scan.matches(it) }) return false
        return rule.any.any { scan.matches(it) }
    }

    fun hasFeedEndMarker(scan: NodeScan, config: Config): Boolean =
        config.feedEndMarkers.any { scan.hasText(it) }
}
