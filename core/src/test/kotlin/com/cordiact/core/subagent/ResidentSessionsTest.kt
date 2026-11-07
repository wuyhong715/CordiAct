package com.cordiact.core.subagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ResidentSessionsTest {

    @Test
    fun `the least recently used session is evicted first`() {
        val registry = ResidentSessions(maxResident = 3) { true }
        listOf("a", "b", "c").forEach(registry::touch)

        // Using "a" again makes "b" the oldest.
        registry.touch("a")
        registry.touch("d")

        val evicted = registry.reap()

        assertEquals(listOf("b"), evicted)
        assertEquals(listOf("c", "a", "d"), registry.residents())
        assertFalse(registry.isResident("b"))
    }

    @Test
    fun `a session the host refuses to release is skipped`() {
        val running = setOf("a")
        val registry = ResidentSessions(maxResident = 2) { it !in running }
        listOf("a", "b", "c").forEach(registry::touch)

        val evicted = registry.reap()

        // "a" is the oldest but still running, so "b" goes instead.
        assertEquals(listOf("b"), evicted)
        assertTrue(registry.isResident("a"))
        assertTrue(registry.isResident("c"))
    }

    @Test
    fun `the newest session is never evicted to satisfy the cap`() {
        val registry = ResidentSessions(maxResident = 1) { false }
        registry.touch("only")

        assertTrue(registry.reap().isEmpty())
        assertEquals(1, registry.size())
        assertTrue(registry.isResident("only"))
    }

    @Test
    fun `reaping stops as soon as the cap holds`() {
        val registry = ResidentSessions(maxResident = 2) { true }
        listOf("a", "b", "c", "d", "e").forEach(registry::touch)

        val evicted = registry.reap()

        assertEquals(listOf("a", "b", "c"), evicted)
        assertEquals(listOf("d", "e"), registry.residents())
    }

    @Test
    fun `forgetting a session is explicit and independent of the cap`() {
        val registry = ResidentSessions(maxResident = 8) { false }
        registry.touch("a")
        registry.touch("b")

        assertTrue(registry.forget("a"))
        assertFalse(registry.forget("a"))
        assertEquals(listOf("b"), registry.residents())
    }
}
