package com.cordiact.app.experience.settings

/** Group by remedy, never by translated title. Unknown failures retain their identity. */
internal data class SettingsAction(val key: String, val title: String, val route: String, val urgent: Boolean)
internal fun settingsActions(readiness: List<SettingsReadiness>): List<SettingsAction> = readiness
    .filter { it.state == SettingsStatus.ERROR || it.state == SettingsStatus.NEEDS_ACTION }
    .mapNotNull { item ->
        val original = item.route ?: if (item.id.startsWith("role:")) "settings://models/roles" else return@mapNotNull null
        val remedy = when (item.reasonCode) {
            "ACCESSIBILITY_DISCONNECTED" -> "开启屏幕无障碍" to "settings://connections/screen"
            "PRIVILEGE_NOT_READY" -> "启动并授权 Shizuku" to "settings://security/privilege"
            "ROOT_UNVERIFIED" -> "检测并授权 Root" to "settings://security/privilege"
            "SSH_PIN_REQUIRED" -> "验证 SSH 主机指纹" to "settings://ssh"
            "TERMUX_SETUP_REQUIRED" -> "配置 Termux 命令权限" to "settings://connections/termux"
            "WEBVIEW_UNAVAILABLE" -> "配置 WebView 运行环境" to "settings://connections/browser"
            "CREDENTIAL_MISSING" -> (if (original == "settings://search") "配置搜索服务密钥" else "配置${item.title}凭据") to original
            else -> (if (item.id.startsWith("role:")) "重新选择${item.title}" else "检查${item.title}配置") to original
        }
        if (SettingsLink.parse(remedy.second) == null) return@mapNotNull null
        val shared = item.reasonCode in setOf("ACCESSIBILITY_DISCONNECTED", "PRIVILEGE_NOT_READY", "ROOT_UNVERIFIED", "TERMUX_SETUP_REQUIRED", "WEBVIEW_UNAVAILABLE")
        val key = if (shared) "${item.reasonCode}:${remedy.second}" else "${item.reasonCode}:$original:${item.id}"
        SettingsAction(key, remedy.first, remedy.second, item.state == SettingsStatus.ERROR)
    }.groupBy { it.key }.values.map { group -> group.first().copy(urgent = group.any { it.urgent }) }
    .sortedByDescending { it.urgent }
