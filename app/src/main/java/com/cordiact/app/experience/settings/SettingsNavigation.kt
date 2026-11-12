package com.cordiact.app.experience.settings

import java.net.URI

internal enum class SettingsRoute(val title: String) {
    HOME("设置"), PROVIDERS("模型提供商"), ADD("添加模型提供商"), DETAIL("提供商详情"),
    ROLES("模型角色"), AUDIT("审计日志"), PERMISSIONS("权限档位"), PRIVILEGE("特权执行"),
    CREDENTIALS("已存凭据"), CONNECTIONS("连接与能力"), ATTACHMENTS("附件限制"),
    SSH("SSH 主机"), MCP("MCP 服务器"), SEARCH("搜索服务"), ABOUT("关于")
}

/** Pure navigation state: a real stack, shared by system and toolbar back actions. */
internal data class SettingsNavigation(val entries: List<SettingsRoute> = listOf(SettingsRoute.HOME)) {
    init { require(entries.isNotEmpty() && entries.first() == SettingsRoute.HOME) }
    val current: SettingsRoute get() = entries.last()
    fun push(route: SettingsRoute): SettingsNavigation = when {
        route == SettingsRoute.HOME -> SettingsNavigation()
        route == current -> this
        else -> copy(entries = entries + route)
    }
    fun pop(): SettingsNavigation? = if (entries.size == 1) null else copy(entries = entries.dropLast(1))
    fun providerList(): SettingsNavigation {
        val index = entries.indexOfLast { it == SettingsRoute.PROVIDERS }
        return if (index >= 0) copy(entries = entries.take(index + 1))
        else SettingsNavigation(listOf(SettingsRoute.HOME, SettingsRoute.PROVIDERS))
    }
    companion object {
        fun restore(names: List<String>): SettingsNavigation = runCatching {
            SettingsNavigation(names.map(SettingsRoute::valueOf))
        }.getOrDefault(SettingsNavigation())
    }
}

internal data class SettingsLink(val route: SettingsRoute, val providerId: String = "", val connectionId: String? = null) {
    fun navigation(): SettingsNavigation = SettingsNavigation().let {
        if (route == SettingsRoute.DETAIL) it.push(SettingsRoute.PROVIDERS).push(route) else it.push(route)
    }
    companion object {
        private val connections = setOf("termux", "screen", "accessibility", "browser", "shizuku", "root", "privilege")
        /** Internal settings links only. Never open arbitrary URLs supplied by a status message. */
        fun parse(raw: String?): SettingsLink? = runCatching {
            if (raw == "connections") return SettingsLink(SettingsRoute.CONNECTIONS)
            val uri = URI(raw ?: return null)
            if (uri.scheme != "settings" || uri.rawQuery != null || uri.rawFragment != null || uri.userInfo != null || uri.port != -1) return null
            val path = uri.path.orEmpty().trim('/').split('/').filter(String::isNotEmpty)
            when (uri.host) {
                "models" -> when {
                    path == listOf("providers") -> SettingsLink(SettingsRoute.PROVIDERS)
                    path.size == 2 && path[0] == "providers" && path[1].isNotBlank() -> SettingsLink(SettingsRoute.DETAIL, providerId = path[1])
                    path == listOf("roles") -> SettingsLink(SettingsRoute.ROLES)
                    else -> null
                }
                "connections" -> when {
                    path.isEmpty() -> SettingsLink(SettingsRoute.CONNECTIONS)
                    path.size == 1 && path[0] in connections -> SettingsLink(SettingsRoute.CONNECTIONS,
                        connectionId = when (path[0]) { "accessibility" -> "screen"; "shizuku", "root" -> "privilege"; else -> path[0] })
                    else -> null
                }
                "search" -> if (path.isEmpty()) SettingsLink(SettingsRoute.SEARCH) else null
                "security" -> when (path) {
                    listOf("permissions") -> SettingsLink(SettingsRoute.PERMISSIONS)
                    listOf("privilege") -> SettingsLink(SettingsRoute.PRIVILEGE)
                    listOf("credentials") -> SettingsLink(SettingsRoute.CREDENTIALS)
                    listOf("audit") -> SettingsLink(SettingsRoute.AUDIT)
                    else -> null
                }
                "ssh" -> if (path.isEmpty()) SettingsLink(SettingsRoute.SSH) else null
                "mcp" -> if (path.isEmpty()) SettingsLink(SettingsRoute.MCP) else null
                "about" -> if (path.isEmpty()) SettingsLink(SettingsRoute.ABOUT) else null
                "attachments" -> if (path.isEmpty()) SettingsLink(SettingsRoute.ATTACHMENTS) else null
                else -> null
            }
        }.getOrNull()
    }
}
