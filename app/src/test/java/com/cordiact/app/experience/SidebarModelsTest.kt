package com.cordiact.app.experience

import com.cordiact.app.experience.model.*
import org.junit.Assert.*
import org.junit.Test

class SidebarModelsTest {
    private val a = SidebarWorkspace("a", "默认工作区", 10, listOf(
        SidebarConversation("old", "早期研究", 1), SidebarConversation("new", "CordiAct 抽屉", 7),
    ))
    private val b = SidebarWorkspace("b", "Research", 20, listOf(SidebarConversation("beta", "API design", 4)))

    @Test fun blankQueryOrdersWorkspacesAndConversations() {
        val result = sidebarGroups(listOf(a, b), "  ")
        assertEquals(listOf("b", "a"), result.map { it.id })
        assertEquals(listOf("new", "old"), result[1].sessions.map { it.id })
    }
    @Test fun workspaceMatchIncludesEveryConversation() {
        assertEquals(2, sidebarGroups(listOf(a, b), "默认").single().sessions.size)
    }
    @Test fun conversationMatchPreservesOwningWorkspaceOnly() {
        val result = sidebarGroups(listOf(a, b), "抽屉").single()
        assertEquals("a", result.id)
        assertEquals(listOf("new"), result.sessions.map { it.id })
    }
    @Test fun searchIsCaseInsensitiveAndTrimmed() {
        assertEquals("b", sidebarGroups(listOf(a, b), "  research ").single().id)
        assertEquals("beta", sidebarGroups(listOf(a, b), "api").single().sessions.single().id)
    }
    @Test fun noResultsAndEmptyInputProduceEmptyList() {
        assertTrue(sidebarGroups(listOf(a, b), "不存在").isEmpty())
        assertTrue(sidebarGroups(emptyList(), "").isEmpty())
    }
    @Test fun emptyWorkspaceRemainsVisibleAndSearchable() {
        val empty = SidebarWorkspace("empty", "新工作区", 0, emptyList())
        assertEquals(empty, sidebarGroups(listOf(empty), "新").single())
    }
    @Test fun tiedTimesUseStableIdsWithoutMutatingInputs() {
        val sessions = listOf(SidebarConversation("z", "同名", 4), SidebarConversation("a", "同名", 4))
        val original = a.copy(sessions = sessions)
        assertEquals(listOf("a", "z"), sidebarGroups(listOf(original), "").single().sessions.map { it.id })
        assertEquals("z", original.sessions.first().id)
    }
    @Test fun literalSpecialCharactersDoNotActAsRegex() {
        val special = a.copy(name = "[test].*")
        assertEquals(special.id, sidebarGroups(listOf(special), "[test]").single().id)
    }
    @Test fun namesRejectBlankLongAndControlInput() {
        assertNotNull(sidebarNameError(" \t "))
        assertNotNull(sidebarNameError("a".repeat(81)))
        assertNotNull(sidebarNameError("a\nb"))
        assertNotNull(sidebarNameError("a\u0000b"))
    }
    @Test fun validNamesCanHaveUnicodeAndSurroundingWhitespace() {
        assertNull(sidebarNameError("  工作区 🗂️  "))
        assertNull(sidebarNameError("a".repeat(80)))
    }

    private val mixed = SidebarWorkspace("m", "混合", 40, listOf(
        SidebarConversation("live", "现役对话", 8),
        SidebarConversation("gone", "已归档对话", 9, archivedAtMillis = 10),
        SidebarConversation("pinned", "置顶对话", 2, pinnedAtMillis = 5),
    ))
    private val archivedOnlyWorkspace = SidebarWorkspace("only", "归档区", 30, listOf(
        SidebarConversation("old", "旧对话", 5, archivedAtMillis = 6),
    ))

    @Test fun archivedSessionsAreHiddenAndEmptiedWorkspacesDisappearByDefault() {
        val result = sidebarGroups(listOf(archivedOnlyWorkspace, mixed), "")
        assertEquals(listOf("m"), result.map { it.id })
        assertEquals(listOf("pinned", "live"), result.single().sessions.map { it.id })
    }

    @Test fun includeArchivedShowsArchivedSessionsAndKeepsMarker() {
        val result = sidebarGroups(listOf(mixed), "", includeArchived = true).single()
        assertEquals(listOf("pinned", "gone", "live"), result.sessions.map { it.id })
        assertNotNull(result.sessions.first { it.id == "gone" }.archivedAtMillis)
    }

    @Test fun archivedOnlyReturnsArchivedSessionsAndSkipsWorkspacesWithoutThem() {
        val empty = SidebarWorkspace("empty", "新工作区", 50, emptyList())
        val result = sidebarGroups(listOf(mixed, empty, archivedOnlyWorkspace), "", archivedOnly = true)
        assertEquals(listOf("m", "only"), result.map { it.id })
        assertEquals(listOf("gone"), result.first().sessions.map { it.id })
        assertEquals(listOf("old"), result[1].sessions.map { it.id })
    }

    @Test fun genuinelyEmptyWorkspaceSurvivesDefaultFilterForNewConversations() {
        val empty = SidebarWorkspace("empty", "新工作区", 0, emptyList())
        assertEquals(empty, sidebarGroups(listOf(empty), "").single())
    }

    @Test fun pinnedSessionsSortBeforeNewerUnpinnedOnes() {
        val workspace = SidebarWorkspace("w", "工作区", 1, listOf(
            SidebarConversation("new", "新对话", 90),
            SidebarConversation("pin", "置顶对话", 10, pinnedAtMillis = 5),
        ))
        assertEquals(listOf("pin", "new"), sidebarGroups(listOf(workspace), "").single().sessions.map { it.id })
    }

    @Test fun searchRunsAfterArchiveFiltering() {
        assertTrue(sidebarGroups(listOf(mixed), "已归档").isEmpty())
        assertEquals("gone", sidebarGroups(listOf(mixed), "已归档", includeArchived = true).single().sessions.single().id)
    }

    @Test fun sessionNamesTrimAndRejectBlankLongAndControlInput() {
        assertNotNull(sidebarSessionNameError(""))
        assertNotNull(sidebarSessionNameError("   "))
        assertNotNull(sidebarSessionNameError("a".repeat(121)))
        assertNotNull(sidebarSessionNameError("a\nb"))
        assertNotNull(sidebarSessionNameError("a\tb"))
        assertNull(sidebarSessionNameError("  对话 🗂️  "))
        assertNull(sidebarSessionNameError("a".repeat(120)))
    }
}
