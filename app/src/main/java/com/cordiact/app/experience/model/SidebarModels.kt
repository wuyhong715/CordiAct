package com.cordiact.app.experience.model

/** Display-only index: never contains message bodies, credentials or child-agent sessions. */
data class SidebarConversation(
    val id: String,
    val title: String,
    val updatedAtMillis: Long,
    val archivedAtMillis: Long? = null,
    val pinnedAtMillis: Long? = null,
) {
    val isArchived: Boolean get() = archivedAtMillis != null
    val isPinned: Boolean get() = pinnedAtMillis != null
}

data class SidebarWorkspace(
    val id: String,
    val name: String,
    val updatedAtMillis: Long,
    val sessions: List<SidebarConversation>,
)

/**
 * Archive filtering happens before workspace-name matching so a workspace whose sessions were
 * all filtered out cannot leak back in through its name. A genuinely empty workspace survives
 * the default filter so it can still host a new conversation.
 */
fun sidebarGroups(
    workspaces: List<SidebarWorkspace>,
    query: String,
    includeArchived: Boolean = false,
    archivedOnly: Boolean = false,
): List<SidebarWorkspace> {
    val search = query.trim()
    return workspaces.sortedWith(compareByDescending<SidebarWorkspace> { it.updatedAtMillis }.thenBy { it.id })
        .mapNotNull { workspace ->
            val hadSessions = workspace.sessions.isNotEmpty()
            val sessions = workspace.sessions
                .filter { session ->
                    when {
                        archivedOnly -> session.archivedAtMillis != null
                        includeArchived -> true
                        else -> session.archivedAtMillis == null
                    }
                }
                .sortedWith(
                    compareByDescending<SidebarConversation> { it.pinnedAtMillis != null }
                        .thenByDescending { it.updatedAtMillis }
                        .thenBy { it.id },
                )
            when {
                // Archive view is about archived conversations; an empty group has no purpose there.
                archivedOnly && sessions.isEmpty() -> null
                // Hide groups emptied purely by the archive filter, but keep truly empty groups.
                !includeArchived && hadSessions && sessions.isEmpty() -> null
                search.isEmpty() || workspace.name.contains(search, ignoreCase = true) ->
                    workspace.copy(sessions = sessions)
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

/** Session titles are 1..120 after trimming and never contain control characters. */
fun sidebarSessionNameError(name: String): String? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> "请输入对话名称"
        trimmed.length > 120 -> "对话名称不能超过 120 个字符"
        name.any { it.isISOControl() || it == '\u2028' || it == '\u2029' } -> "对话名称不能包含控制字符"
        else -> null
    }
}
