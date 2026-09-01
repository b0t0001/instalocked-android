package com.instalocked.policy

import com.instalocked.config.Config
import com.instalocked.scan.Screen

/** What the overlay layer should be showing right now. */
data class Decision(
    val gate: Boolean = false,
    val feedEnd: Boolean = false,
    val maskRings: Boolean = false,
    /** Swiping to a different item should kick the user out rather than be gated. */
    val bounceScroll: Boolean = false,
    val reason: String = ""
)

/** Everything mutable the engine needs, kept outside it so the engine stays pure. */
data class GuardState(
    var sessionEndsAt: Long = 0L,
    var feedMaxIndex: Int = 0,
    var feedEndMarkerSeen: Boolean = false,
    var lastCreateScreenAt: Long = 0L,
    var lastDmsAt: Long = 0L,
    var creativeGraceIndex: Int = -1,
    var sessionsToday: Int = 0,
    var sessionsTodayDate: String = ""
) {
    fun sessionActive(now: Long) = now < sessionEndsAt
}

object PolicyEngine {

    /**
     * Window after leaving a create/upload screen during which landing in the
     * Reels pager is treated as "reviewing what I just posted" rather than
     * "scrolling". Instagram routinely drops you into the pager at your own new
     * post; without this the gate would fire on your own content.
     */
    const val CREATE_GRACE_MS = 90_000L

    fun decide(
        screen: Screen,
        state: GuardState,
        config: Config,
        now: Long
    ): Decision {
        val p = config.policy

        // A reel a friend sent you: full colour, no gate, no countdown. The only
        // restriction is that it is a dead end. You watch that one and you are
        // done; swiping out of it returns you to the thread rather than dropping
        // you into the algorithmic feed.
        if (screen == Screen.REELS_FROM_DM) {
            return Decision(bounceScroll = p.dmReelBounce, reason = "dm-reel")
        }

        // Creation and messaging are never touched, under any circumstances.
        when (screen) {
            Screen.REELS_CREATE, Screen.DMS, Screen.SEARCH,
            Screen.PROFILE, Screen.STORY, Screen.UNKNOWN ->
                return Decision(reason = "allowed:${screen.name.lowercase()}")
            else -> Unit
        }

        if (screen == Screen.REELS_CONSUME || screen == Screen.EXPLORE_GRID) {
            if (state.sessionActive(now)) {
                return Decision(reason = "session active")
            }
            // Grace period straight after posting: allow the first item only.
            if (now - state.lastCreateScreenAt < CREATE_GRACE_MS &&
                state.creativeGraceIndex <= 0
            ) {
                return Decision(reason = "post-upload review")
            }
            return Decision(gate = true, reason = "gate:${screen.name.lowercase()}")
        }

        if (screen == Screen.FEED) {
            val capped = state.feedMaxIndex >= p.feedCap
            val marker = state.feedEndMarkerSeen
            if (capped || marker) {
                return Decision(
                    feedEnd = true,
                    maskRings = false,
                    reason = if (marker) "feed:suggested-posts" else "feed:cap"
                )
            }
            return Decision(maskRings = p.maskRings, reason = "feed:ok")
        }

        return Decision(reason = "default-allow")
    }

    /** Called on every scroll event while the home feed is showing. */
    fun onFeedScroll(state: GuardState, toIndex: Int) {
        if (toIndex > state.feedMaxIndex) state.feedMaxIndex = toIndex
    }

    /** Reset when the user leaves and re-enters the feed from elsewhere. */
    fun resetFeedCounters(state: GuardState) {
        state.feedMaxIndex = 0
        state.feedEndMarkerSeen = false
    }

    fun startSession(state: GuardState, config: Config, now: Long, today: String): Boolean {
        if (state.sessionsTodayDate != today) {
            state.sessionsTodayDate = today
            state.sessionsToday = 0
        }
        if (state.sessionsToday >= config.policy.dailySessionLimit) return false
        state.sessionsToday += 1
        state.sessionEndsAt = now + config.policy.sessionMinutes * 60_000L
        return true
    }

    fun endSession(state: GuardState) {
        state.sessionEndsAt = 0L
    }
}
