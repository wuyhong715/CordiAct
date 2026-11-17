package com.cordiact.core.jobs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ExecutionPresenceTest {
    @Test fun sourcesCannotClearEachOther() {
        val registry = ExecutionPresenceRegistry()
        registry.replace("jobs", listOf(ExecutionPresence("j", ExecutionPhase.TOOL, true)))
        registry.replace("automation", listOf(ExecutionPresence("a", ExecutionPhase.MODEL)))
        val left = registry.replace("jobs", emptyList())
        assertEquals(1, left.activeCount)
        assertEquals("a", left.entries.single().id)
        assertFalse(left.needsForeground)
        assertEquals("模型执行中 1", left.summary)
    }
    @Test fun foregroundEligibilityIsExplicit() {
        val registry = ExecutionPresenceRegistry()
        assertFalse(registry.replace("a", listOf(ExecutionPresence("x", ExecutionPhase.MODEL))).needsForeground)
        assertTrue(registry.replace("a", listOf(ExecutionPresence("x", ExecutionPhase.TOOL, true))).needsForeground)
        assertEquals(0, registry.replace("a", emptyList()).activeCount)
    }
    @Test fun callerCannotMutateStoredList() {
        val registry = ExecutionPresenceRegistry()
        val entries = mutableListOf(ExecutionPresence("a", ExecutionPhase.PREPARING))
        registry.replace("a", entries)
        entries.clear()
        assertEquals(1, registry.snapshot().activeCount)
        assertFailsWith<IllegalArgumentException> { registry.replace("", emptyList()) }
    }
}
