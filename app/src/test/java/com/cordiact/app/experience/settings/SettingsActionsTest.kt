package com.cordiact.app.experience.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsActionsTest {
    private fun item(id: String, reason: String = "ACCESSIBILITY_DISCONNECTED", route: String = "settings://connections/screen", state: SettingsStatus = SettingsStatus.NEEDS_ACTION) =
        SettingsReadiness(id, "屏幕自动化", state, "问题说明", reason, route)

    @Test fun sharedRemedyCollapsesAndCountsErrors() {
        val actions = settingsActions(listOf(item("a"), item("b", state = SettingsStatus.ERROR), item("c")))
        assertEquals(1, actions.size)
        assertEquals("开启屏幕无障碍", actions.single().title)
        assertTrue(actions.single().urgent)
    }
    @Test fun separateProvidersStaySeparate() {
        val actions = settingsActions(listOf(item("p1", "CREDENTIAL_MISSING", "settings://models/providers/p1"), item("p2", "CREDENTIAL_MISSING", "settings://models/providers/p2")))
        assertEquals(2, actions.size)
        assertTrue(actions.all { SettingsLink.parse(it.route) != null })
    }
    @Test fun readyItemsDisappearAndSearchHasActionTitle() {
        val actions = settingsActions(listOf(item("ready", state = SettingsStatus.READY), item("search", "CREDENTIAL_MISSING", "settings://search")))
        assertEquals("配置搜索服务密钥", actions.single().title)
    }
    @Test fun brokenRolesHaveAnActionAndInvalidRoutesAreNotDeadButtons() {
        val role = SettingsReadiness("role:CONVERSATION", "对话默认模型", SettingsStatus.ERROR, "已删除")
        val actions = settingsActions(listOf(role, item("invalid", "UNKNOWN", "https://invalid.example")))
        assertEquals("重新选择对话默认模型", actions.single().title)
        assertEquals("settings://models/roles", actions.single().route)
    }
}
