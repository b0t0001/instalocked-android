package com.instalocked.scan

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.instalocked.config.Config
import com.instalocked.config.Matcher

/**
 * A flattened snapshot of one window's node tree.
 *
 * The important distinction here is [idNames] versus [visibleIds]. Instagram
 * keeps the Reels tab preloaded inside a swipeable ViewPager, so IDs like
 * clips_video_container and clips_viewer_container exist in the tree on EVERY
 * screen, including the home feed. Matching on their presence classified the
 * whole app as Reels. Only their on-screen bounds reveal which tab you are
 * actually looking at.
 */
class NodeScan(
    /** Short id names present anywhere in the tree. */
    val idNames: Set<String>,
    /** Short id names whose bounds actually intersect the display. */
    val visibleIds: Set<String>,
    val contentDescs: Set<String>,
    val texts: Set<String>,
    /** On-screen bounds of nodes configured for black-box covering. */
    val coverBounds: List<Rect>,
    val nodeCount: Int,
    val truncated: Boolean
) {
    fun hasContentDesc(fragment: String) = contentDescs.any { it.contains(fragment) }
    fun hasText(fragment: String) = texts.any { it.contains(fragment) }

    private fun ids(m: Matcher): Set<String> =
        if (m.mode == "visible") visibleIds else idNames

    private fun idMatches(m: Matcher): Boolean {
        val pool = ids(m)
        return when (m.mode) {
            "prefix" -> pool.any { it.startsWith(m.contains) }
            "visiblePrefix" -> visibleIds.any { it.startsWith(m.contains) }
            "contains" -> pool.any { it.contains(m.contains) }
            else -> pool.contains(m.contains)
        }
    }

    fun matches(m: Matcher): Boolean = when (m.type) {
        "resourceId" -> idMatches(m)
        "contentDesc" -> hasContentDesc(m.contains)
        "text" -> hasText(m.contains)
        else -> false
    }

    companion object {
        // Raised from 900/28: every capture came back truncated at depth 28,
        // meaning the classifier was reasoning about a partial tree.
        private const val MAX_NODES = 1600
        private const val MAX_DEPTH = 45

        /** A node counts as visible only if a real fraction of it is on screen. */
        private const val MIN_VISIBLE_PX = 4

        fun of(root: AccessibilityNodeInfo?, config: Config, screen: Rect): NodeScan {
            val names = HashSet<String>(192)
            val visible = HashSet<String>(128)
            val descs = HashSet<String>(96)
            val texts = HashSet<String>(160)
            val covers = ArrayList<Rect>(4)
            var count = 0
            var truncated = false

            if (root == null) return NodeScan(names, visible, descs, texts, emptyList(), 0, false)

            // Explicit stack, not recursion: Instagram's trees are deep and a
            // StackOverflowError inside an accessibility callback kills the service.
            val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            stack.addLast(root to 0)
            val bounds = Rect()

            while (stack.isNotEmpty()) {
                if (count >= MAX_NODES) { truncated = true; break }
                val (node, depth) = stack.removeLast()
                count++

                val vid = node.viewIdResourceName?.lowercase()
                if (vid != null) {
                    val short = vid.substringAfter("id/")
                    names.add(short)
                    node.getBoundsInScreen(bounds)
                    val onScreen = bounds.width() >= MIN_VISIBLE_PX &&
                        bounds.height() >= MIN_VISIBLE_PX &&
                        bounds.right > screen.left && bounds.left < screen.right &&
                        bounds.bottom > screen.top && bounds.top < screen.bottom
                    if (onScreen) {
                        visible.add(short)
                        if (config.coverIds.any { short == it || short.contains(it) }) {
                            covers.add(Rect(bounds))
                        }
                    }
                }

                node.contentDescription?.let {
                    val s = it.toString().lowercase()
                    if (s.isNotEmpty() && s.length < 200) descs.add(s)
                }
                node.text?.let {
                    val s = it.toString().lowercase()
                    if (s.isNotEmpty() && s.length < 200) texts.add(s)
                }

                if (depth < MAX_DEPTH) {
                    for (i in 0 until node.childCount) {
                        val child = try { node.getChild(i) } catch (t: Throwable) { null }
                        if (child != null) stack.addLast(child to depth + 1)
                    }
                } else {
                    truncated = true
                }
            }

            return NodeScan(names, visible, descs, texts, covers, count, truncated)
        }
    }
}
