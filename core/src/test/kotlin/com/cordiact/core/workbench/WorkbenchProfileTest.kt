package com.cordiact.core.workbench

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WorkbenchProfileTest {
    @Test fun classicUsesPublicationOrder() {
        val profile = WorkbenchProfile("经典")
        val available = listOf("cordiact.trace", "cordiact.artifacts", "cordiact.git-tree")

        assertEquals(available.map { WorkbenchPanel(it, PanelPlacement.TAB) }, profile.visiblePanels(available, true))
    }

    @Test fun layoutRetainsMissingEntriesAndUsesTabsOnNarrowWindows() {
        val configured = listOf(
            WorkbenchPanel("pkg.checklist", PanelPlacement.DOCK),
            WorkbenchPanel("cordiact.git-tree", PanelPlacement.DOCK),
            WorkbenchPanel("cordiact.trace", PanelPlacement.HIDDEN),
        )
        val profile = WorkbenchProfile("开发", panels = configured)
        val available = listOf("cordiact.trace", "cordiact.artifacts", "cordiact.git-tree")
        val artifacts = WorkbenchPanel("cordiact.artifacts", PanelPlacement.TAB)

        assertEquals(configured + artifacts, profile.editorPanels(available))
        assertEquals(listOf(configured[1], artifacts), profile.visiblePanels(available, true))
        assertEquals(listOf(configured[1].copy(placement = PanelPlacement.TAB), artifacts), profile.visiblePanels(available, false))
        assertEquals(listOf(configured[0], configured[1], artifacts), profile.visiblePanels(available + "pkg.checklist", true))
    }

    @Test fun rejectsDuplicatePanelsAndInvalidNames() {
        val panel = WorkbenchPanel("cordiact.trace", PanelPlacement.TAB)

        assertFailsWith<IllegalArgumentException> { WorkbenchProfile("重复", panels = listOf(panel, panel)) }
        assertFailsWith<IllegalArgumentException> { WorkbenchProfile("跨\n行") }
        assertFailsWith<IllegalArgumentException> { WorkbenchPanel("../hidden", PanelPlacement.HIDDEN) }
    }
}
