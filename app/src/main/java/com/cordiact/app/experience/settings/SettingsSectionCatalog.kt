package com.cordiact.app.experience.settings

internal data class SettingsSection(val id: String, val label: String)
internal fun settingsSections(route: SettingsRoute): List<SettingsSection> = when (route) {
    SettingsRoute.AGENT_PRESET_EDITOR -> listOf(SettingsSection("configuration", "运行配置"), SettingsSection("capabilities", "可用能力"), SettingsSection("scope", "作用范围"))
    SettingsRoute.SEARCH -> listOf(SettingsSection("services", "服务"), SettingsSection("policy", "策略"), SettingsSection("diagnostics", "诊断"))
    SettingsRoute.MCP -> listOf(SettingsSection("servers", "服务器"), SettingsSection("diagnostics", "诊断"))
    SettingsRoute.SSH -> listOf(SettingsSection("hosts", "主机"), SettingsSection("diagnostics", "连接检查"))
    else -> emptyList()
}
