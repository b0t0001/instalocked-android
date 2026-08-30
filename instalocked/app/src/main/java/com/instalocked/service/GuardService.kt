package com.instalocked.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
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
    }

    private lateinit var config: Config
    private lateinit var overlays: OverlayManager
    private lateinit var state: GuardState

    private val main = Handler(Looper.getMainLooper())
    private var lastContentScan = 0L
    private var currentScreen = Screen.UNKNOWN
    private var lastScreen = Screen.UNKNOWN

    /** Adapter position the shared reel opened at; anything else means a swipe. */
    private var dmReelIndex = Int.MIN_VALUE
    private var lastBounceAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        config = Config.load(this)
        overlays = OverlayManager(this)
        state = Store.loadState(this)
    }

    fun reloadConfig() {
        config = Config.load(this)
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
                    if (to >= 0) {
                        if (dmReelIndex == Int.MIN_VALUE) {
                            dmReelIndex = to
                        } else if (to != dmReelIndex) {
                            bounceOutOfDmReel()
                            return
                        }
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
        val scan = try { NodeScan.of(root, config) } catch (t: Throwable) { return }

        maybeCapture(scan)

        val now = System.currentTimeMillis()
        val ctx = ClassifyContext(
            msSinceDms = if (state.lastDmsAt == 0L) Long.MAX_VALUE else now - state.lastDmsAt,
            provenanceWindowMs = config.policy.dmReelProvenanceMs
        )
        val screen = ScreenClassifier.classify(scan, config, ctx)

        if (screen != currentScreen) {
            lastScreen = currentScreen
            currentScreen = screen
            onScreenChanged(screen, now)
        }

        if (screen == Screen.FEED && ScreenClassifier.hasFeedEndMarker(scan, config)) {
            state.feedEndMarkerSeen = true
        }

        val decision = PolicyEngine.decide(screen, state, config, now)

        // ---- gate ----
        if (decision.gate) {
            if (!overlays.scrimVisible) {
                val what = if (screen == Screen.EXPLORE_GRID) "Explore" else "Reels"
                overlays.showScrim(
                    title = "$what is gated",
                    body = "Type thirty words about why you are opening this. " +
                        "Then you get ${config.policy.sessionMinutes} minutes.",
                    primaryLabel = "Write the reason",
                    onPrimary = { launchGate(screen) },
                    secondaryLabel = "Go back",
                    onSecondary = {
                        overlays.hideScrim()
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                )
            }
        } else if (decision.feedEnd) {
            if (!overlays.scrimVisible) {
                overlays.showScrim(
                    title = "That's the feed",
                    body = if (decision.reason.contains("suggested"))
                        "Everything below this point is suggested posts, not people you follow."
                    else
                        "You've seen ${config.policy.feedCap} posts from people you follow.",
                    primaryLabel = null,
                    onPrimary = null,
                    secondaryLabel = "Go back",
                    onSecondary = {
                        overlays.hideScrim()
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                )
            }
        } else {
            overlays.hideScrim()
        }

        // ---- ring masking ----
        if (decision.maskRings && scan.trayItemBounds.isNotEmpty()) {
            overlays.showRings(scan.trayItemBounds, config.policy)
        } else if (screen != Screen.FEED) {
            overlays.hideRings()
        }

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

    private fun launchGate(screen: Screen) {
        overlays.hideScrim()
        val i = Intent(this, GateActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(GateActivity.EXTRA_SCREEN, screen.name)
        }
        try { startActivity(i) } catch (t: Throwable) { }
    }

    /**
     * Capture mode. Writes the flattened node tree to a file for a few minutes
     * so selectors can be re-derived after an Instagram update without a rebuild.
     */
    private fun maybeCapture(scan: NodeScan) {
        val until = Store.captureUntil(this)
        if (until <= 0L || System.currentTimeMillis() > until) return
        val sb = StringBuilder()
        sb.append("=== ").append(System.currentTimeMillis())
            .append("  nodes=").append(scan.nodeCount)
            .append(" truncated=").append(scan.truncated).append("\n")
        sb.append("-- resourceIds --\n")
        scan.resourceIds.sorted().forEach { sb.append("  ").append(it).append("\n") }
        sb.append("-- contentDescs --\n")
        scan.contentDescs.sorted().take(60).forEach { sb.append("  ").append(it).append("\n") }
        sb.append("-- texts --\n")
        scan.texts.sorted().take(60).forEach { sb.append("  ").append(it).append("\n") }
        sb.append("-- trayItemBounds --\n")
        scan.trayItemBounds.forEach { sb.append("  ").append(it.toShortString()).append("\n") }
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
