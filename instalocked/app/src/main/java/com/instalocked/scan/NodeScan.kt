package com.instalocked.scan

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.instalocked.config.Config

/**
 * A flattened snapshot of one window's node tree.
 *
 * This runs on the accessibility callback thread, which shares a budget with
 * Instagram's own rendering. On a Snapdragon 662 an unbounded walk of a feed
 * with video rows is easily 20ms+, which shows up as visible jank in the app
 * being watched. Hence the hard caps on node count and depth.
 */
class NodeScan(
    val resourceIds: Set<String>,
    val contentDescs: Set<String>,
    val texts: Set<String>,
    val trayItemBounds: List<Rect>,
    val nodeCount: Int,
    val truncated: Boolean
) {
    fun hasResourceId(fragment: String) = resourceIds.any { it.contains(fragment) }
    fun hasContentDesc(fragment: String) = contentDescs.any { it.contains(fragment) }
    fun hasText(fragment: String) = texts.any { it.contains(fragment) }

    fun matches(m: com.instalocked.config.Matcher): Boolean = when (m.type) {
        "resourceId" -> hasResourceId(m.contains)
        "contentDesc" -> hasContentDesc(m.contains)
        "text" -> hasText(m.contains)
        "any" -> hasResourceId(m.contains) || hasContentDesc(m.contains) || hasText(m.contains)
        else -> false
    }

    companion object {
        private const val MAX_NODES = 900
        private const val MAX_DEPTH = 28

        fun of(root: AccessibilityNodeInfo?, config: Config): NodeScan {
            val ids = HashSet<String>(128)
            val descs = HashSet<String>(64)
            val texts = HashSet<String>(128)
            val trayBounds = ArrayList<Rect>(12)
            var count = 0
            var truncated = false

            if (root == null) {
                return NodeScan(ids, descs, texts, trayBounds, 0, false)
            }

            // Explicit stack rather than recursion: Instagram's trees are deep and
            // a StackOverflowError inside an accessibility callback kills the service.
            val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            stack.addLast(root to 0)

            while (stack.isNotEmpty()) {
                if (count >= MAX_NODES) { truncated = true; break }
                val (node, depth) = stack.removeLast()
                count++

                val vid = node.viewIdResourceName?.lowercase()
                if (vid != null) {
                    ids.add(vid)
                    // Collect story tray item geometry while we are already here,
                    // so masking never needs a second traversal.
                    if (config.trayItemIds.any { vid.contains(it) }) {
                        val r = Rect()
                        node.getBoundsInScreen(r)
                        if (r.width() > 0 && r.height() > 0) trayBounds.add(r)
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
                    val n = node.childCount
                    for (i in 0 until n) {
                        val child = try { node.getChild(i) } catch (t: Throwable) { null }
                        if (child != null) stack.addLast(child to depth + 1)
                    }
                } else {
                    truncated = true
                }
            }

            // If we found a tray container but no items matched, fall back to
            // treating the container's direct children as items.
            if (trayBounds.isEmpty() && config.trayContainerIds.isNotEmpty()) {
                findTrayByContainer(root, config, trayBounds)
            }

            trayBounds.sortBy { it.left }
            return NodeScan(ids, descs, texts, trayBounds, count, truncated)
        }

        private fun findTrayByContainer(
            root: AccessibilityNodeInfo,
            config: Config,
            out: MutableList<Rect>
        ) {
            val stack = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
            stack.addLast(root to 0)
            var guard = 0
            while (stack.isNotEmpty() && guard < MAX_NODES) {
                guard++
                val (node, depth) = stack.removeLast()
                val vid = node.viewIdResourceName?.lowercase()
                if (vid != null && config.trayContainerIds.any { vid.contains(it) }) {
                    for (i in 0 until node.childCount) {
                        val child = try { node.getChild(i) } catch (t: Throwable) { null } ?: continue
                        val r = Rect()
                        child.getBoundsInScreen(r)
                        if (r.width() > 0 && r.height() > 0) out.add(r)
                    }
                    return
                }
                if (depth < MAX_DEPTH) {
                    for (i in 0 until node.childCount) {
                        val child = try { node.getChild(i) } catch (t: Throwable) { null }
                        if (child != null) stack.addLast(child to depth + 1)
                    }
                }
            }
        }
    }
}
