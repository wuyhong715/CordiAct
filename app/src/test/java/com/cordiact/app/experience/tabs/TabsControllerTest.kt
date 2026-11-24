package com.cordiact.app.experience.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabsControllerTest {
    private class Memory(var keys: List<String> = emptyList(), var active: String? = null) : TabStore {
        override fun load() = keys to active
        override fun save(keys: List<String>, active: String?) { this.keys = keys; this.active = active }
    }

    @Test fun followSession_opensTabAfterActiveAndPersists() {
        val store = Memory()
        val tabs = TabsController(store)
        tabs.followSession("a"); tabs.followSession("b")
        assertEquals(listOf("s:a", "s:b"), store.keys)
        assertEquals("s:b", store.active)
        assertEquals(listOf("s:a", "s:b"), TabsController(store).tabs.map { it.key })
    }

    @Test fun lastConversationTabCannotClose_andClosingActivePicksNeighbour() {
        val tabs = TabsController(Memory(listOf("s:a", "p:cordiact.trace", "s:b"), "s:b"))
        assertEquals("p:cordiact.trace", tabs.close("s:b")?.key)
        assertNull(tabs.close("s:a"))
        assertEquals(listOf("s:a", "p:cordiact.trace"), tabs.tabs.map { it.key })
    }

    @Test fun prune_dropsDeletedSessionsAndUninstalledPlugins() {
        val tabs = TabsController(Memory(listOf("s:a", "s:gone", "p:x"), "s:gone"))
        tabs.prune(setOf("a"), emptySet())
        assertEquals(listOf("s:a"), tabs.tabs.map { it.key })
        assertEquals("s:a", tabs.activeKey)
    }

    @Test fun withdrawnPageAlwaysReturnsToLoadedConversation() {
        val store = Memory(listOf("s:other", "p:cordiact.trace", "s:loaded"), "p:cordiact.trace")
        val tabs = TabsController(store)
        tabs.prune(emptySet(), emptySet(), loadedSession = "loaded")
        assertEquals(listOf("s:other", "s:loaded"), store.keys)
        assertEquals("s:loaded", store.active)
    }

    @Test fun move_reorders() {
        val tabs = TabsController(Memory(listOf("s:a", "s:b", "s:c"), "s:a"))
        tabs.move(0, 2)
        assertEquals(listOf("s:b", "s:c", "s:a"), tabs.tabs.map { it.key })
    }
}
