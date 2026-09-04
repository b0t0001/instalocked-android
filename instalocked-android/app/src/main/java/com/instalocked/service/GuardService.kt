package com.instalocked.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.instalocked.config.Config
import com.instalocked.overlay.OverlayManager
import com.instalocked.policy.GuardState
import com.instalocked.policy.PolicyEngine
import com.instalocked.scan.ClassifyContext
import com.instalocked.scan.NodeScan
import com.instalocked.scan.Screen
import com.instalocked.scan.ScreenClassifier
import com.instalocked.store.Store
import com.instalocked.ui.GateActivity

class GuardService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GuardService? = null

        /** Throttle for content-changed events; state-changed always goes through. */
        private const val CONTENT_THROTTLE_MS = 350L

        /** Quiet period after mutating an overlay, to break the event feedback loop. */
        const val OVERLAY_QUIET_MS = 600L

        /** Consecutive all-clear evaluations required before taking a scrim down. */
        const val CLEAR_STREAK_REQUIRED = 2

        /** Consecutive gate verdicts required before a scrim is raised. */
        const val GATE_STREAK_REQUIRED = 2

        /** Minimum gap between the back-actions that stop reel playback. */
        const val GATE_BACK_COOLDOWN_MS = 1500L

        /** Upper bound on distinct screens recorded in one capture run. */
        const val MAX_CAPTURED_SCREENS = 24

        /** Max dumps recorded per classification, so one screen can't hog the budget. */
        const val PER_SCREEN_QUOTA = 3
    }

    private lateinit var config: Config
    private lateinit var overlays: OverlayManager
    private lateinit var state: GuardState

    private val main = Handler(Looper.getMainLooper())
    private val screenRect = Rect()
    private var lastContentScan = 0L
    private var currentScreen = Screen.UNKNOWN
    private var lastScreen = Screen.UNKNOWN

    /** Adapter position the shared reel opened at; anything else means a swipe. */
    private var dmReelIndex = Int.MIN_VALUE
    private var lastBounceAt = 0L

    /**
     * Anti-flicker state.
     *
     * Adding or removing a fullscreen overlay makes Instagram emit window and
     * content events, which triggers another evaluation, which can toggle the
     * overlay again. That loop is what makes the screen strobe. Two brakes:
     * a quiet period after any overlay mutation, and a requirement that a
     * non-gated classification repeat before we take a scrim down.
     */
    private var overlayQuietUntil = 0L
    private var clearStreak = 0
    private var gateStreak = 0
    private var scrimScreen: Screen? = null
    private var lastGateBackAt = 0L

    /** Distinct screens already written during capture, keyed by resource-ID set. */
    private val capturedSignatures = HashSet<Int>()

    /**
     * Per-classification quota for capture.
     *
     * Keying dedup on the exact resource-ID set was not enough: the home feed's
     * IDs shift slightly with every post scrolled, so each scroll position read
     * as a brand new screen and the entire capture budget was spent on the feed
     * before ever reaching Reels or Explore. A per-screen-type quota guarantees
     * coverage across the screens that actually matter.
     */
    private val capturedPerScreen = HashMap<Screen, Int>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        config = Config.load(this)
        overlays = OverlayManager(this)
        state = Store.loadState(this)
        val dm = resources.displayMetrics
        screenRect.set(0, 0, dm.widthPixels, dm.heightPixels)
    }

    fun reloadConfig() {
        config = Config.load(this)
    }

    /** Called when a fresh capture run starts, so each run collects screens anew. */
    fun resetCapture() {
        capturedSignatures.clear()
        capturedPerScreen.clear()
    }

    /** Called by SessionService when the five minutes are up. */
    fun onSessionEnded() {
        state.sessionEndsAt = 0L
        Store.saveState(this, state)
        main.post {
            overlays.hideChip()
            evaluate(force = true)
        }
    }

    fun onSessionStarted(endsAt: Long) {
        state.sessionEndsAt = endsAt
        Store.saveState(this, state)
        main.post { overlays.hideScrim() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != config.targetPackage) return
        if (!Store.isEnabled(this)) { overlays.hideAll(); return }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (currentScreen == Screen.REELS_FROM_DM && config.policy.dmReelBounce) {
                    val to = event.toIndex
                    var swiped = false
                    if (to >= 0) {
                        if (dmReelIndex == Int.MIN_VALUE) {
                            dmReelIndex = to
                        } else if (to != dmReelIndex) {
                            swiped = true
                        }
                    }
                    // Fallback: some Instagram builds run the shared-reel viewer
                    // as a pager that never reports an adapter index. Vertical
                    // scroll delta catches the swipe in that case.
                    if (!swiped && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val dy = try { event.scrollDeltaY } catch (t: Throwable) { 0 }
                        if (dy != 0 && kotlin.math.abs(dy) > 40) swiped = true
                    }
                    if (swiped) {
                        bounceOutOfDmReel()
                        return
                    }
                }
                if (currentScreen == Screen.FEED) {
                    // toIndex is the adapter position the RecyclerView scrolled to.
                    // This is the cheapest reliable way to count posts consumed.
                    val to = event.toIndex
                    if (to >= 0) PolicyEngine.onFeedScroll(state, to)
                }
                if (currentScreen == Screen.REELS_CONSUME && state.creativeGraceIndex >= 0) {
                    // Left the post we just uploaded: the grace period is over.
                    state.creativeGraceIndex = 1
                }
                evaluate()
            }

            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                lastContentScan = 0L
                evaluate(force = true)
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val now = System.currentTimeMillis()
                if (now - lastContentScan < CONTENT_THROTTLE_MS) return
                lastContentScan = now
                evaluate()
            }
        }
    }

    private fun evaluate(force: Boolean = false) {
        val root = try { rootInActiveWindow } catch (t: Throwable) { null } ?: return
        val scan = try { NodeScan.of(root, config, screenRect) } catch (t: Throwable) { return }


        val now = System.currentTimeMillis()
        val ctx = ClassifyContext(
            msSinceDms = if (state.lastDmsAt == 0L) Long.MAX_VALUE else now - state.lastDmsAt,
            provenanceWindowMs = config.policy.dmReelProvenanceMs
        )
        var screen = ScreenClassifier.classify(scan, config, ctx)

        // A reel a friend sent can run longer than the provenance window. Once
        // we have recognised one, keep treating the viewer as a shared reel
        // until the user actually leaves it, rather than letting a timer expire
        // mid-watch and slam the gate down on your friend's video.
        if (screen == Screen.REELS_CONSUME && currentScreen == Screen.REELS_FROM_DM) {
            screen = Screen.REELS_FROM_DM
        }
        maybeCapture(scan, screen)

        if (screen != currentScreen) {
            lastScreen = currentScreen
            currentScreen = screen
            onScreenChanged(screen, now)
        }

        if (screen == Screen.FEED && ScreenClassifier.hasFeedEndMarker(scan, config)) {
            state.feedEndMarkerSeen = true
        }

        val decision = PolicyEngine.decide(screen, state, config, now)

        // Quiet period after any overlay mutation. Adding or removing a
        // fullscreen window makes Instagram emit more events, and reacting to
        // those is what produced the strobing.
        if (now < overlayQuietUntil) return

        // ---- gate ----
        if (decision.gate) {
            clearStreak = 0
            // Require the same verdict twice running. A transient or overly
            // broad selector match should not be able to throw a full-screen
            // block in front of you on a screen you are only passing through.
            gateStreak++
            if (gateStreak >= GATE_STREAK_REQUIRED && !overlays.scrimVisible) {
                // An overlay hides pixels; it does not pause a video. Leave the
                // reel viewer first so playback and audio actually stop, then
                // put the gate up over wherever we land.
                if (now - lastGateBackAt > GATE_BACK_COOLDOWN_MS) {
                    lastGateBackAt = now
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                val what = if (screen == Screen.EXPLORE_GRID) "Explore" else "Reels"
                scrimScreen = screen
                overlays.showScrim(
                    title = "$what is gated",
                    body = "Type thirty words about why you are opening this. " +
                        "Then you get ${config.policy.sessionMinutes} minutes.",
                    primaryLabel = "Write the reason",
                    onPrimary = { launchGate(screen) },
                    secondaryLabel = "Leave Reels",
                    onSecondary = {
                        overlays.hideScrim()
                        overlayQuietUntil = System.currentTimeMillis() + OVERLAY_QUIET_MS
                        leaveReelsTab()
                    }
                )
                overlayQuietUntil = now + OVERLAY_QUIET_MS
            }
        } else if (decision.feedEnd) {
            clearStreak = 0
            gateStreak = 0
            if (!overlays.scrimVisible) {
                scrimScreen = screen
                overlays.showScrim(
                    title = "That's the feed",
                    body = if (decision.reason.contains("suggested"))
                        "Everything below this point is suggested posts, not people you follow."
                    else
                        "You've seen ${config.policy.feedCap} posts from people you follow.",
                    primaryLabel = null,
                    onPrimary = null,
                    secondaryLabel = "Back to top",
                    onSecondary = {
                        overlays.hideScrim()
                        overlayQuietUntil = System.currentTimeMillis() + OVERLAY_QUIET_MS
                        scrollFeedToTop()
                    }
                )
                overlayQuietUntil = now + OVERLAY_QUIET_MS
            }
        } else if (overlays.scrimVisible) {
            gateStreak = 0
            // Require the all-clear twice running before taking a scrim down.
            // A single stray frame classifying as something benign was enough to
            // start the show/hide oscillation.
            clearStreak++
            if (clearStreak >= CLEAR_STREAK_REQUIRED) {
                clearStreak = 0
                scrimScreen = null
                overlays.hideScrim()
                overlayQuietUntil = now + OVERLAY_QUIET_MS
            }
        } else {
            clearStreak = 0
            gateStreak = 0
        }

        // Black boxes over configured regions, driven purely by what is visible
        // rather than by classification, so it works even on screens the
        // classifier does not recognise.
        if (scan.coverBounds.isNotEmpty()) overlays.showCover(scan.coverBounds)
        else overlays.hideCover()

        // ---- countdown chip ----
        if (state.sessionActive(now) && now - lastBounceAt > 1800L) {
            val left = ((state.sessionEndsAt - now) / 1000L).coerceAtLeast(0)
            overlays.showChip("%d:%02d left".format(left / 60, left % 60))
        }
    }

    private fun onScreenChanged(screen: Screen, now: Long) {
        when (screen) {
            Screen.REELS_CREATE -> {
                state.lastCreateScreenAt = now
                state.creativeGraceIndex = 0
            }
            Screen.DMS -> {
                state.lastDmsAt = now
                dmReelIndex = Int.MIN_VALUE
                PolicyEngine.resetFeedCounters(state)
            }
            Screen.REELS_CONSUME, Screen.EXPLORE_GRID, Screen.SEARCH -> {
                // Left the home feed for real: drop the post counter so a stale
                // high-water mark can't make the end-of-feed scrim follow you
                // onto screens that have nothing to do with the feed.
                PolicyEngine.resetFeedCounters(state)
            }
            Screen.REELS_FROM_DM -> {
                // Re-arm on each entry so the first scroll event establishes the
                // baseline position rather than being read as a swipe.
                dmReelIndex = Int.MIN_VALUE
            }
            Screen.FEED -> {
                // Only reset the counter when arriving from somewhere that isn't
                // the feed, so backing out of a post doesn't hand out 20 more.
                if (lastScreen != Screen.STORY && lastScreen != Screen.PROFILE) {
                    PolicyEngine.resetFeedCounters(state)
                }
            }
            else -> Unit
        }
    }

    /**
     * Swiping inside a shared reel means leaving the thing your friend sent, so
     * we exit the viewer instead of letting the algorithmic feed take over.
     *
     * This is reactive rather than preventive: Android gives no way to swallow a
     * gesture aimed at another app without also swallowing taps. You will see a
     * frame or two of the next reel before the bounce lands.
     */
    private fun bounceOutOfDmReel() {
        val now = System.currentTimeMillis()
        if (now - lastBounceAt < 700L) return
        lastBounceAt = now
        dmReelIndex = Int.MIN_VALUE
        performGlobalAction(GLOBAL_ACTION_BACK)
        overlays.showChip("Sent Reels don't scroll")
        main.postDelayed({ overlays.hideChip() }, 1800L)
    }

    /**
     * Walk the feed back to the very top rather than just dismissing the scrim.
     *
     * GLOBAL_ACTION_BACK left you exactly where you were, which meant the block
     * could be cleared and immediately re-earned with a couple of small scrolls.
     * Returning to position zero makes the cap mean something.
     */
    /**
     * Walk the feed back to position zero.
     *
     * The previous version silently did nothing, for two reasons. First it read
     * rootInActiveWindow while the scrim was still up, and the scrim is a
     * focusable overlay, so the "active window" was our own blocker rather than
     * Instagram. Second, findScrollable took the first scrollable in the tree,
     * which is swipeable_nav_view_pager_inner_recycler_view: the HORIZONTAL tab
     * pager. Scrolling that backward switches tabs instead of scrolling posts.
     *
     * Now: wait for the scrim to come down, then pick the feed list by id and
     * require it to be taller than it is wide.
     */
    private fun scrollFeedToTop() {
        main.postDelayed({ doScrollToTop(30) }, 260L)
        PolicyEngine.resetFeedCounters(state)
    }

    private fun doScrollToTop(remainingIn: Int) {
        val root = rootInActiveWindow ?: return
        val node = findFeedList(root) ?: return

        // Fast path: RecyclerView honours an explicit jump to row zero.
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_ROW_INT, 0)
            putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_COLUMN_INT, 0)
        }
        val jumped = try {
            node.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_TO_POSITION.id, args
            )
        } catch (t: Throwable) { false }
        if (jumped) {
            PolicyEngine.resetFeedCounters(state)
            return
        }

        // Otherwise page backwards until it stops moving.
        var remaining = remainingIn
        val step = object : Runnable {
            override fun run() {
                if (remaining-- <= 0) { PolicyEngine.resetFeedCounters(state); return }
                val live = rootInActiveWindow?.let { findFeedList(it) } ?: return
                val moved = try {
                    live.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                } catch (t: Throwable) { false }
                if (moved) main.postDelayed(this, 80L)
                else PolicyEngine.resetFeedCounters(state)
            }
        }
        main.post(step)
    }

    /**
     * The vertical feed list, not the horizontal tab pager sitting above it.
     * Confirmed ids from the device dump: "list" and "sticky_header_list".
     */
    private fun findFeedList(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val preferred = listOf("list", "sticky_header_list", "recycler_view")
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0
        val b = Rect()
        while (stack.isNotEmpty() && guard < 1200) {
            guard++
            val n = stack.removeLast()
            if (n.isScrollable) {
                n.getBoundsInScreen(b)
                val vertical = b.height() > b.width()
                val id = n.viewIdResourceName?.lowercase()?.substringAfter("id/")
                val area = b.width() * b.height()
                if (vertical && area > bestArea) {
                    // A preferred id wins outright; otherwise take the largest
                    // vertical scroller on screen.
                    if (id != null && preferred.contains(id)) return n
                    best = n
                    bestArea = area
                }
            }
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (t: Throwable) { null }
                if (c != null) stack.addLast(c)
            }
        }
        return best
    }

    private fun leaveReelsTab() {
        val root = rootInActiveWindow
        val home = root?.let { findById(it, "feed_tab") }
        val clicked = try {
            home?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
        } catch (t: Throwable) { false }
        if (!clicked) performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun findById(root: AccessibilityNodeInfo, shortId: String): AccessibilityNodeInfo? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 900) {
            guard++
            val n = stack.removeLast()
            val vid = n.viewIdResourceName?.lowercase()
            if (vid != null && vid.substringAfter("id/") == shortId) {
                var cur: AccessibilityNodeInfo? = n
                var hops = 0
                while (cur != null && !cur.isClickable && hops++ < 4) cur = cur.parent
                return cur ?: n
            }
            for (i in 0 until n.childCount) {
                val c = try { n.getChild(i) } catch (t: Throwable) { null }
                if (c != null) stack.addLast(c)
            }
        }
        return null
    }

    private fun launchGate(screen: Screen) {
        overlays.hideScrim()
        val i = Intent(this, GateActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(GateActivity.EXTRA_SCREEN, screen.name)
        }
        try { startActivity(i) } catch (t: Throwable) { }
    }

    /**
     * Capture mode. Writes the flattened node tree so selectors can be
     * re-derived after an Instagram update without a rebuild.
     *
     * Two constraints learned the hard way. First, this fires on every
     * accessibility event, so without deduplication three minutes of capture
     * produces hundreds of near-identical dumps and a file too large to read.
     * We key on the set of resource IDs and write each distinct screen once.
     *
     * Second, we do NOT dump arbitrary on-screen text. On a DM screen that is
     * your actual conversation. Only text matching a configured marker is
     * recorded, since that is all the classifier ever looks at.
     */
    private fun maybeCapture(scan: NodeScan, screen: Screen) {
        val until = Store.captureUntil(this)
        if (until <= 0L || System.currentTimeMillis() > until) return
        if (scan.idNames.isEmpty()) return
        if (capturedSignatures.size >= MAX_CAPTURED_SCREENS) return

        // Quota per screen type, so the home feed cannot eat the whole budget.
        val seen = capturedPerScreen[screen] ?: 0
        if (seen >= PER_SCREEN_QUOTA) return

        val signature = scan.idNames.sorted().joinToString("|").hashCode()
        if (!capturedSignatures.add(signature)) return
        capturedPerScreen[screen] = seen + 1

        val markerHits = config.feedEndMarkers.filter { scan.hasText(it) }

        val sb = StringBuilder()
        sb.append("=== screen ").append(capturedSignatures.size)
            .append("  classified=").append(screen.name)
            .append("  nodes=").append(scan.nodeCount)
            .append(" truncated=").append(scan.truncated).append("\n")
        sb.append("-- VISIBLE resourceIds --\n")
        scan.visibleIds.sorted().forEach { sb.append("  ").append(it).append("\n") }
        sb.append("-- present but OFF-SCREEN --\n")
        (scan.idNames - scan.visibleIds).sorted()
            .forEach { sb.append("  ").append(it).append("\n") }
        sb.append("-- contentDescs --\n")
        scan.contentDescs.sorted().take(40).forEach { sb.append("  ").append(it).append("\n") }
        sb.append("-- marker text hits --\n")
        if (markerHits.isEmpty()) sb.append("  (none)\n")
        else markerHits.forEach { sb.append("  ").append(it).append("\n") }
        sb.append("\n")
        Store.appendDump(this, sb.toString())
    }

    override fun onInterrupt() { }

    override fun onUnbind(intent: Intent?): Boolean {
        overlays.hideAll()
        Store.saveState(this, state)
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        overlays.hideAll()
        instance = null
        super.onDestroy()
    }
}
