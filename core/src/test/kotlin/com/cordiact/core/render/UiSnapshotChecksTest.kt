package com.cordiact.core.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UiSnapshotChecksTest {

    private val viewport = SnapshotViewport(width = 390.0, height = 844.0)

    private fun snapshot(vararg nodes: SnapshotNode) = UiSnapshot(viewport = viewport, nodes = nodes.toList())

    private fun node(
        selector: String = "#a",
        parent: String? = "body",
        rect: SnapshotRect = SnapshotRect(0.0, 0.0, 100.0, 100.0),
        positioned: Boolean = false,
        interactive: Boolean = false,
        text: String? = null,
        style: SnapshotStyle = SnapshotStyle(),
        scrollWidth: Double? = null,
        clientWidth: Double? = null,
        scrollHeight: Double? = null,
        clientHeight: Double? = null,
    ) = SnapshotNode(
        selector = selector,
        tag = "div",
        parentSelector = parent,
        text = text,
        rect = rect,
        scrollWidth = scrollWidth,
        clientWidth = clientWidth,
        scrollHeight = scrollHeight,
        clientHeight = clientHeight,
        positioned = positioned,
        interactive = interactive,
        style = style,
    )

    @Test
    fun overflowIsReportedWhenTheAxisCannotScroll() {
        val findings = UiSnapshotChecks.check(
            snapshot(node(selector = "#card", scrollWidth = 420.0, clientWidth = 358.0)),
        )
        assertEquals(1, findings.size)
        assertEquals(SnapshotIssueKind.OVERFLOW_X, findings.single().kind)
    }

    @Test
    fun scrollContainerIsNotReportedAsOverflow() {
        val findings = UiSnapshotChecks.check(
            snapshot(
                node(
                    selector = "#carousel",
                    scrollWidth = 900.0,
                    clientWidth = 358.0,
                    style = SnapshotStyle(overflowX = "auto"),
                ),
            ),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun subPixelOverflowIsIgnored() {
        val findings = UiSnapshotChecks.check(
            snapshot(node(selector = "#card", scrollWidth = 358.4, clientWidth = 358.0)),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun siblingOverlapIsReported() {
        val findings = UiSnapshotChecks.check(
            snapshot(
                node(selector = "#avatar", rect = SnapshotRect(10.0, 10.0, 80.0, 80.0)),
                node(selector = "#title", rect = SnapshotRect(40.0, 40.0, 80.0, 60.0)),
            ),
        )
        assertEquals(listOf(SnapshotIssueKind.OVERLAP), findings.map { it.kind })
    }

    @Test
    fun positionedSiblingIsExemptFromOverlap() {
        val findings = UiSnapshotChecks.check(
            snapshot(
                node(selector = "#avatar", rect = SnapshotRect(10.0, 10.0, 80.0, 80.0)),
                node(
                    selector = "#badge",
                    rect = SnapshotRect(40.0, 40.0, 20.0, 20.0),
                    positioned = true,
                ),
            ),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun enclosingSiblingIsNotReportedAsOverlap() {
        val findings = UiSnapshotChecks.check(
            snapshot(
                node(selector = "#wrapper", rect = SnapshotRect(0.0, 0.0, 200.0, 200.0)),
                node(selector = "#inner", rect = SnapshotRect(10.0, 10.0, 100.0, 40.0)),
            ),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun differentParentsDoNotOverlap() {
        val findings = UiSnapshotChecks.check(
            snapshot(
                node(selector = "#a .x", parent = "#a", rect = SnapshotRect(0.0, 0.0, 100.0, 100.0)),
                node(selector = "#b .y", parent = "#b", rect = SnapshotRect(10.0, 10.0, 100.0, 100.0)),
            ),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun lowContrastTextIsReported() {
        val findings = UiSnapshotChecks.check(
            snapshot(
                node(
                    selector = "#caption",
                    text = "副标题",
                    style = SnapshotStyle(color = "#777777", fontSizePx = 14.0),
                ),
            ),
        )
        assertEquals(listOf(SnapshotIssueKind.LOW_CONTRAST), findings.map { it.kind })
    }

    @Test
    fun largeTextUsesTheLowerThreshold() {
        val findings = UiSnapshotChecks.check(
            snapshot(
                node(
                    selector = "#headline",
                    text = "标题",
                    style = SnapshotStyle(color = "#888888", fontSizePx = 32.0),
                ),
            ),
        )
        assertTrue(findings.isEmpty(), "3.18:1 passes AA for large text")
    }

    @Test
    fun transparentNodeBackgroundFallsThroughToThePage() {
        val findings = UiSnapshotChecks.check(
            snapshot(
                node(
                    selector = "#caption",
                    text = "副标题",
                    style = SnapshotStyle(
                        color = "#a8a8a8",
                        backgroundColor = "rgba(0, 0, 0, 0)",
                        fontSizePx = 13.0,
                    ),
                ),
            ),
        )
        assertEquals(listOf(SnapshotIssueKind.LOW_CONTRAST), findings.map { it.kind })
    }

    @Test
    fun translucentColourIsSkippedRatherThanGuessed() {
        val findings = UiSnapshotChecks.check(
            snapshot(
                node(
                    selector = "#overlay",
                    text = "覆盖层",
                    style = SnapshotStyle(color = "rgba(0, 0, 0, 0.4)", fontSizePx = 14.0),
                ),
            ),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun smallTapTargetIsReported() {
        val findings = UiSnapshotChecks.check(
            snapshot(
                node(
                    selector = "#icon-button",
                    rect = SnapshotRect(0.0, 0.0, 40.0, 40.0),
                    interactive = true,
                ),
            ),
        )
        assertEquals(listOf(SnapshotIssueKind.SMALL_TAP_TARGET), findings.map { it.kind })
    }

    @Test
    fun fullSizeTapTargetIsAccepted() {
        val findings = UiSnapshotChecks.check(
            snapshot(
                node(
                    selector = "#button",
                    rect = SnapshotRect(0.0, 0.0, 48.0, 48.0),
                    interactive = true,
                ),
            ),
        )
        assertTrue(findings.isEmpty())
    }

    @Test
    fun clickableNodeOutsideViewportIsReported() {
        val findings = UiSnapshotChecks.check(
            snapshot(
                node(
                    selector = "#cta",
                    rect = SnapshotRect(0.0, 900.0, 120.0, 48.0),
                    interactive = true,
                ),
            ),
        )
        assertEquals(listOf(SnapshotIssueKind.OFF_SCREEN), findings.map { it.kind })
    }

    @Test
    fun findingsAreSortedDeterministically() {
        val snapshot = snapshot(
            node(selector = "#z", rect = SnapshotRect(0.0, 0.0, 20.0, 20.0), interactive = true),
            node(selector = "#a", rect = SnapshotRect(200.0, 200.0, 20.0, 20.0), interactive = true),
        )
        val first = UiSnapshotChecks.check(snapshot).map { it.selector }
        val second = UiSnapshotChecks.check(snapshot).map { it.selector }
        assertEquals(first, second)
        assertEquals(listOf("#a", "#z"), first)
    }

    @Test
    fun colourParsingHandlesTheFormatsThePreviewEmits() {
        assertEquals(21.0, UiSnapshotChecks.contrastRatio(
            assertNotNull(UiSnapshotChecks.parseColor("#000000")),
            assertNotNull(UiSnapshotChecks.parseColor("#fff")),
        ), 0.001)
        assertEquals(
            assertNotNull(UiSnapshotChecks.parseColor("rgb(255, 255, 255)")),
            assertNotNull(UiSnapshotChecks.parseColor("#ffffff")),
        )
        assertEquals(null, UiSnapshotChecks.parseColor("transparent"))
        assertEquals(null, UiSnapshotChecks.parseColor("var(--fg)"))
    }
}
