package com.cordiact.app.experience.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    @Test
    fun agentPresetSearchOpensLibraryRatherThanUnboundEditor() {
        val hits = searchSettings("Agent 预设")
        assertEquals(SettingsRoute.AGENT_PRESETS, hits.first().entry.route)
        assertTrue(hits.none { it.entry.route == SettingsRoute.AGENT_PRESET_EDITOR })
    }

    @Test
    fun blankQueryHasNoResults() {
        assertTrue(searchSettings("   ").isEmpty())
    }

    @Test
    fun aliasReachesTheUsagePage() {
        val hits = searchSettings("token")
        assertEquals(SettingsRoute.USAGE, hits.first().entry.route)
    }

    @Test
    fun sharedSectionLabelKeepsParentPath() {
        val hits = searchSettings("诊断")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.path.contains("›") })
        assertTrue(hits.all { it.section == "diagnostics" })
        assertEquals(setOf(SettingsRoute.SEARCH, SettingsRoute.MCP), hits.map { it.entry.route }.toSet())
        val ssh = searchSettings("连接检查").single()
        assertEquals(SettingsRoute.SSH, ssh.entry.route)
        assertEquals("diagnostics", ssh.section)
    }

    @Test
    fun exactTitleRanksFirst() {
        val hits = searchSettings("用量与统计")
        assertEquals(SettingsRoute.USAGE, hits.first().entry.route)
        assertEquals("用量与统计", hits.first().entry.title)
    }

    @Test
    fun unmatchedTermReturnsNothing() {
        assertTrue(searchSettings("zzzz-no-such-setting").isEmpty())
    }
}
