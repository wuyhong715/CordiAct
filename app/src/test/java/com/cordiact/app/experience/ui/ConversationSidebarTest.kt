package com.cordiact.app.experience.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.*
import com.cordiact.app.experience.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h892dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationSidebarTest {
    @get:Rule val compose = createComposeRule()
    private val groups = listOf(
        SidebarWorkspace("default", "默认工作区", 10, listOf(SidebarConversation("a", "侧边栏设计", 20), SidebarConversation("b", "接口审查", 15))),
        SidebarWorkspace("research", "研究项目", 5, listOf(SidebarConversation("c", "资料收集", 8))),
    )
    private var selected = ""
    private var newAt: String? = "not-called"
    private var created = ""
    private var renamed = ""
    private var settings = 0
    private var retries = 0

    private fun show(data: List<SidebarWorkspace> = groups, busy: Boolean = false, error: String? = null, width: Int = 354) {
        compose.setContent {
            ExperienceTheme {
                Box(Modifier.fillMaxSize()) {
                    Surface(Modifier.width(width.dp).fillMaxHeight()) {
                        ConversationSidebar(data, "default", "a", setOf("b"), busy, error,
                            onClose = {}, onNewSession = { newAt = it }, onSelectWorkspace = { selected = "workspace:$it" },
                            onSelectSession = { selected = "session:$it" }, onCreateWorkspace = { value, onSuccess -> created = value; onSuccess() },
                            onRenameWorkspace = { id, value, onSuccess -> renamed = "$id:$value"; onSuccess() }, onRetry = { retries++ }, onSettings = { settings++ })
                    }
                }
            }
        }
    }
    @Test fun activeWorkspaceStartsExpandedAndCurrentSessionSelected() {
        show()
        compose.onNodeWithText("侧边栏设计").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("资料收集").assertDoesNotExist()
        compose.onNodeWithText("接口审查").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "执行中"))
    }
    @Test fun collapseDoesNotChangeSelectedWorkspace() {
        show()
        compose.onNodeWithContentDescription("收起工作区 默认工作区").performClick()
        compose.onNodeWithText("侧边栏设计").assertDoesNotExist()
        assertEquals("", selected)
        compose.onNodeWithContentDescription("展开工作区 默认工作区").performClick()
        compose.onNodeWithText("侧边栏设计").assertIsDisplayed()
    }
    @Test fun selectingOtherWorkspaceAndConversationUsesCorrectIds() {
        show()
        compose.onNodeWithContentDescription("展开工作区 研究项目").performClick()
        compose.onNodeWithText("资料收集").performClick()
        assertEquals("session:c", selected)
        compose.onNodeWithText("研究项目", useUnmergedTree = false).performClick()
        assertEquals("workspace:research", selected)
    }
    @Test fun searchingTemporarilyExpandsMatchingGroupAndClearsCleanly() {
        show()
        compose.onNodeWithContentDescription("搜索对话与工作区").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("资料")
        compose.onNodeWithText("资料收集").assertIsDisplayed()
        compose.onNodeWithText("默认工作区").assertDoesNotExist()
        compose.onNodeWithContentDescription("收起搜索").performClick()
        compose.onNodeWithText("默认工作区").assertIsDisplayed()
        compose.onNodeWithText("资料收集").assertDoesNotExist()
    }
    @Test fun noMatchesShowsEmptyStateAndKeepsSettingsAccessible() {
        show()
        compose.onNodeWithContentDescription("搜索对话与工作区").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("不存在")
        compose.onNodeWithText("没有匹配的对话或工作区").assertIsDisplayed()
        compose.onNodeWithText("设置").assertIsDisplayed().performClick()
        assertEquals(1, settings)
    }
    @Test fun createWorkspaceValidatesNameAndTrimsInput() {
        show()
        compose.onNodeWithContentDescription("新建工作区").performClick()
        compose.onNodeWithText("创建", substring = false).assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("  新项目  ")
        compose.onNodeWithText("创建", substring = false).performClick()
        assertEquals("新项目", created)
    }
    @Test fun workspaceMenuRenamesRealWorkspaceAndCreatesInThatGroup() {
        show()
        compose.onNodeWithContentDescription("工作区 研究项目 的更多操作").performClick()
        compose.onNodeWithText("重命名工作区").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("研究二期")
        compose.onNodeWithText("保存").performClick()
        assertEquals("research:研究二期", renamed)
        compose.onNodeWithContentDescription("工作区 研究项目 的更多操作").performClick()
        compose.onNodeWithText("在此新建对话").performClick()
        assertEquals("research", newAt)
    }
    @Test fun loadingDisablesDuplicateCreateAndSelectionButNotClosingOrSettings() {
        show(busy = true)
        compose.onNodeWithText("新对话", substring = false).assertIsNotEnabled()
        compose.onNodeWithContentDescription("新建工作区").assertIsNotEnabled()
        compose.onNodeWithText("侧边栏设计").assertIsNotEnabled()
        compose.onNodeWithContentDescription("关闭侧边栏").assertIsEnabled()
        compose.onNodeWithText("设置").assertIsEnabled()
    }
    @Test fun failureShowsRetryAndLeavesSelectionVisible() {
        show(error = "工作区读取失败")
        compose.onNodeWithText("工作区读取失败").assertIsDisplayed()
        compose.onNodeWithText("重试").performClick()
        assertEquals(1, retries)
        compose.onNodeWithText("侧边栏设计").assertIsSelected()
    }
    @Test fun longListScrollsWithoutMovingSettingsAndSupportsNarrowScreens() {
        val many = groups.take(1).map { it.copy(sessions = (1..60).map { i -> SidebarConversation("s$i", "对话 $i", i.toLong()) }) }
        show(data = many, width = 275)
        val before = compose.onNodeWithText("设置").fetchSemanticsNode().boundsInRoot
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("对话 1", substring = false))
        compose.onNodeWithText("对话 1", substring = false).assertIsDisplayed()
        assertEquals(before, compose.onNodeWithText("设置").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithContentDescription("新建工作区").assertIsDisplayed()
    }
    @Test fun rendersReviewImage() {
        show()
        compose.waitForIdle()
        val file = java.io.File("build/reports/tests/sidebar-batch1.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { out ->
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        assertTrue(file.length() > 0)
    }
}
