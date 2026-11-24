package com.cordiact.app.experience.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertTrue(hits.all { it.path.contains("›") })
        assertEquals(setOf(SettingsRoute.SEARCH, SettingsRoute.MCP, SettingsRoute.PLUGIN_DIAGNOSTICS),
            hits.map { it.entry.route }.toSet())
        val plugin = hits.single { it.entry.route == SettingsRoute.PLUGIN_DIAGNOSTICS }
        assertEquals("工作区设置 › 插件与连接", plugin.path)
        assertNull(plugin.section)
        assertTrue(hits.filterNot { it === plugin }.all { it.section == "diagnostics" })
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
    fun workspacePackagesAreSearchableThroughTheirHostBranch() {
        for (query in listOf("工作区插件包", "声明包", "导入 JSON", "workspace packages")) {
            val hit = searchSettings(query).first()
            assertEquals(SettingsRoute.WORKSPACE_PACKAGES, hit.entry.route)
            assertEquals("工作区设置 › 插件与连接", hit.path)
            assertEquals(SettingsRoute.PLUGINS, SettingsNavigation().push(hit.entry.route).pop()!!.current)
        }
        assertTrue(searchSettings("插件说明").none { it.entry.route == SettingsRoute.WORKSPACE_PACKAGE_DESCRIPTION })
        val diagnostics = searchSettings("插件诊断").first()
        assertEquals(SettingsRoute.PLUGIN_DIAGNOSTICS, diagnostics.entry.route)
        assertEquals(SettingsRoute.PLUGINS, SettingsNavigation().push(diagnostics.entry.route).pop()!!.current)
    }

    @Test
    fun unmatchedTermReturnsNothing() {
        assertTrue(searchSettings("zzzz-no-such-setting").isEmpty())
    }
}
