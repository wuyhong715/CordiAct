package com.cordiact.app.experience.model

/** Display-only index: never contains message bodies, credentials or child-agent sessions. */
data class SidebarConversation(val id: String, val title: String, val updatedAtMillis: Long)
data class SidebarWorkspace(
    val id: String,
    val name: String,
    val updatedAtMillis: Long,
    val sessions: List<SidebarConversation>,
)

fun sidebarGroups(workspaces: List<SidebarWorkspace>, query: String): List<SidebarWorkspace> {
    val search = query.trim()
    return workspaces.sortedWith(compareByDescending<SidebarWorkspace> { it.updatedAtMillis }.thenBy { it.id })
        .mapNotNull { workspace ->
            val sessions = workspace.sessions.sortedWith(
                compareByDescending<SidebarConversation> { it.updatedAtMillis }.thenBy { it.id },
            )
            when {
                search.isEmpty() || workspace.name.contains(search, ignoreCase = true) -> workspace.copy(sessions = sessions)
                else -> sessions.filter { it.title.contains(search, ignoreCase = true) }
                    .takeIf { it.isNotEmpty() }?.let { workspace.copy(sessions = it) }
            }
        }
}

/** Same upper bound as WorkspaceManager; reject rather than silently truncate input. */
fun sidebarNameError(name: String): String? = when {
    name.trim().isEmpty() -> "请输入工作区名称"
    name.trim().length > 80 -> "工作区名称不能超过 80 个字符"
    name.any { it == '\n' || it == '\r' || it == '\u0000' } -> "名称不能包含换行或控制字符"
    else -> null
}
