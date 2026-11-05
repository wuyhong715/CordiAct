package com.cordiact.core.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UiSnapshotDiffTest {

    private val viewport = SnapshotViewport(width = 390.0, height = 844.0)

    private fun snapshot(vararg nodes: SnapshotNode) = UiSnapshot(viewport = viewport, nodes = nodes.toList())

    private fun node(
        selector: String,
        rect: SnapshotRect,
        parent: String? = "body",
        interactive: Boolean = false,
        style: SnapshotStyle = SnapshotStyle(),
        text: String? = null,
    ) = SnapshotNode(
        selector = selector,
        tag = "div",
        parentSelector = parent,
        text = text,
        rect = rect,
        interactive = interactive,
        style = style,
    )

    @Test
    fun identicalScreensHaveNoChanges() {
        val screen = snapshot(node("#a", SnapshotRect(0.0, 0.0, 100.0, 40.0)))
        assertTrue(UiSnapshotDiff.compare(screen, screen).isEmpty())
    }

    @Test
    fun aMovedElementIsReportedWithItsDelta() {
        val changes = UiSnapshotDiff.compare(
            snapshot(node("#cta", SnapshotRect(0.0, 100.0, 120.0, 48.0))),
            snapshot(node("#cta", SnapshotRect(0.0, 140.0, 120.0, 48.0))),
        )
        assertEquals(listOf(ChangeKind.MOVED), changes.map { it.kind })
        assertTrue(changes.single().detail.contains("(0, 40)"), changes.single().detail)
    }

    @Test
    fun aResizedElementIsReportedSeparatelyFromMovement() {
        val changes = UiSnapshotDiff.compare(
            snapshot(node("#card", SnapshotRect(0.0, 0.0, 100.0, 40.0))),
            snapshot(node("#card", SnapshotRect(0.0, 0.0, 100.0, 90.0))),
        )
        assertEquals(listOf(ChangeKind.RESIZED), changes.map { it.kind })
    }

    @Test
    fun subPixelJitterIsIgnored() {
        val changes = UiSnapshotDiff.compare(
            snapshot(node("#a", SnapshotRect(10.0, 10.0, 100.0, 40.0))),
            snapshot(node("#a", SnapshotRect(10.4, 10.3, 100.2, 40.0))),
        )
        assertTrue(changes.isEmpty(), "got $changes")
    }

    @Test
    fun addedAndRemovedElementsAreReported() {
        val changes = UiSnapshotDiff.compare(
            snapshot(
                node("#old", SnapshotRect(0.0, 0.0, 100.0, 40.0)),
                node("#kept", SnapshotRect(0.0, 60.0, 100.0, 40.0)),
            ),
            snapshot(
                node("#kept", SnapshotRect(0.0, 60.0, 100.0, 40.0)),
                node("#new", SnapshotRect(0.0, 120.0, 100.0, 40.0)),
            ),
        )
        val added = changes.filter { it.kind == ChangeKind.ADDED }.map { it.selector }
        val removed = changes.filter { it.kind == ChangeKind.REMOVED }.map { it.selector }
        assertEquals(listOf("#new"), added)
        assertEquals(listOf("#old"), removed)
    }

    @Test
    fun aNewRuleViolationIsCalledOutAsAnIssue() {
        val before = snapshot(node("#btn", SnapshotRect(0.0, 0.0, 60.0, 60.0), interactive = true))
        val after = snapshot(node("#btn", SnapshotRect(0.0, 0.0, 30.0, 30.0), interactive = true))

        val changes = UiSnapshotDiff.compare(before, after)
        val issues = changes.filter { it.kind == ChangeKind.NEW_ISSUE }
        assertEquals(1, issues.size)
        assertTrue(
            issues.single().detail.contains("SMALL_TAP_TARGET"),
            issues.single().detail,
        )
    }

    @Test
    fun aFixedRuleViolationIsCalledOutToo() {
        val before = snapshot(
            node(
                selector = "#caption",
                rect = SnapshotRect(0.0, 0.0, 100.0, 20.0),
                text = "副标题",
                style = SnapshotStyle(color = "#a8a8a8", fontSizePx = 13.0),
            ),
        )
        val after = snapshot(
            node(
                selector = "#caption",
                rect = SnapshotRect(0.0, 0.0, 100.0, 20.0),
                text = "副标题",
                style = SnapshotStyle(color = "#333333", fontSizePx = 13.0),
            ),
        )

        val changes = UiSnapshotDiff.compare(before, after)
        assertEquals(listOf(ChangeKind.FIXED_ISSUE), changes.map { it.kind })
    }

    @Test
    fun changesAreOrderedDeterministically() {
        val before = snapshot(
            node("#b", SnapshotRect(0.0, 0.0, 10.0, 10.0)),
            node("#a", SnapshotRect(0.0, 0.0, 10.0, 10.0)),
        )
        val after = snapshot(
            node("#b", SnapshotRect(0.0, 20.0, 10.0, 10.0)),
            node("#a", SnapshotRect(0.0, 20.0, 10.0, 10.0)),
        )
        val first = UiSnapshotDiff.compare(before, after).map { it.selector }
        val second = UiSnapshotDiff.compare(before, after).map { it.selector }
        assertEquals(first, second)
        assertEquals(listOf("#a", "#b"), first)
    }
}
