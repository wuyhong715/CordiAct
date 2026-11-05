package com.cordiact.core.render

import kotlin.math.abs

/**
 * One difference between two versions of the same screen.
 *
 * [selector] is a CSS path, which is what makes the diff usable: the agent wrote the markup, so
 * "`body > div:nth-child(2) > button` moved 40px down" points straight at the thing it changed.
 */
data class SnapshotChange(
    val selector: String,
    val kind: ChangeKind,
    /** Ready to show; numbers are already rounded, no markdown. */
    val detail: String,
)

enum class ChangeKind {
    /** Same selector, different box position. */
    MOVED,

    /** Same selector and position, different size. */
    RESIZED,

    /** Selector present only in the new version. */
    ADDED,

    /** Selector present only in the old version. */
    REMOVED,

    /** A check that passed before now fails. */
    NEW_ISSUE,

    /** A check that failed before now passes. */
    FIXED_ISSUE,
}

/**
 * Compares two snapshots of the same screen — two versions of a document, or two points in time.
 *
 * This is the answer to "did my last edit help?", and it is deliberately geometric plus
 * rule-based rather than pixel-based: it survives a re-render, it is stable enough to assert in
 * a test, and it says *what* moved instead of *that* something looks different.
 *
 * Selectors are matched exactly. That is a real limitation — renaming a class reshuffles the
 * paths and the diff degenerates into ADDED/REMOVED pairs — but it is honest, and an agent that
 * renames everything is going to want to see ADDED/REMOVED anyway.
 */
object UiSnapshotDiff {

    private const val EPSILON = 1.0

    fun compare(before: UiSnapshot, after: UiSnapshot): List<SnapshotChange> {
        val changes = mutableListOf<SnapshotChange>()
        val old = before.nodes.associateBy { it.selector }
        val new = after.nodes.associateBy { it.selector }

        new.forEach { (selector, node) ->
            val earlier = old[selector]
            if (earlier == null) {
                changes += SnapshotChange(
                    selector,
                    ChangeKind.ADDED,
                    "$selector 新增（${node.rect.w.toInt()}×${node.rect.h.toInt()} " +
                        "@ ${node.rect.x.toInt()},${node.rect.y.toInt()}）",
                )
                return@forEach
            }
            val dx = node.rect.x - earlier.rect.x
            val dy = node.rect.y - earlier.rect.y
            val dw = node.rect.w - earlier.rect.w
            val dh = node.rect.h - earlier.rect.h
            if (abs(dx) >= EPSILON || abs(dy) >= EPSILON) {
                changes += SnapshotChange(
                    selector,
                    ChangeKind.MOVED,
                    "$selector 移动 (${dx.toInt()}, ${dy.toInt()})",
                )
            }
            if (abs(dw) >= EPSILON || abs(dh) >= EPSILON) {
                changes += SnapshotChange(
                    selector,
                    ChangeKind.RESIZED,
                    "$selector 尺寸 (${dw.toInt()}, ${dh.toInt()})",
                )
            }
        }

        old.keys.filterNot { it in new }.forEach { selector ->
            changes += SnapshotChange(selector, ChangeKind.REMOVED, "$selector 已移除")
        }

        val oldIssues = UiSnapshotChecks.check(before).map { it.kind to it.selector }.toSet()
        val newIssues = UiSnapshotChecks.check(after).map { it.kind to it.selector }.toSet()

        (newIssues - oldIssues).forEach { (kind, selector) ->
            changes += SnapshotChange(selector, ChangeKind.NEW_ISSUE, "新问题 $kind：$selector")
        }
        (oldIssues - newIssues).forEach { (kind, selector) ->
            changes += SnapshotChange(selector, ChangeKind.FIXED_ISSUE, "已修好 $kind：$selector")
        }

        return changes.sortedWith(compareBy({ it.kind.ordinal }, { it.selector }))
    }
}
