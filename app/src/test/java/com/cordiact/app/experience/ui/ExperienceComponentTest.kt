package com.cordiact.app.experience.ui

import android.app.Application
import androidx.compose.ui.platform.testTag
import com.cordiact.app.render.PreviewInbox
import com.cordiact.app.render.PreviewOrigin
import org.junit.After
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.*
import com.cordiact.app.experience.model.*
import com.cordiact.core.conversation.ModelDescriptor
import com.cordiact.core.conversation.ModelId
import com.cordiact.core.plugin.AuthoredPluginDefinition
import com.cordiact.core.plugin.AuthoredPluginDraftEntry
import com.cordiact.core.plugin.AuthoredPluginDraftPage
import com.cordiact.core.plugin.AuthoredPluginInstallationEntry
import com.cordiact.core.plugin.AuthoredPluginInstallationStatus
import com.cordiact.core.plugin.AuthoredPluginInstalledRecord
import com.cordiact.core.plugin.AuthoredPluginReceipt
import com.cordiact.core.plugin.AuthoredToolDefinition
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
class ExperienceComponentTest {
    @get:Rule val compose = createComposeRule()
    private val model = ModelId("test", "fake-model")
    private fun ready(turns: List<TranscriptTurn> = emptyList()) = ExperienceState(
        loading = false, sessionId = "test-session", model = model,
        models = listOf(ModelDescriptor(model, "Test Model")), turns = turns,
    )
    private fun content(block: @Composable () -> Unit) = compose.setContent {
        ExperienceTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Box(Modifier.fillMaxSize()) { block() } } }
    }

    @Composable
    private fun composer(state: ExperienceState, send: (String) -> Boolean, stop: () -> Unit) {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val scope = rememberCoroutineScope()
        val controller = remember { com.cordiact.app.experience.composer.ComposerController(app, scope,
            { error("No catalogue needed in text input test") }, { error("No importer needed in text input test") }) }
        val draft by controller.state.collectAsState()
        com.cordiact.app.experience.composer.ComposerBar(state, draft.copy(model = model), controller,
            "", {}, { text -> val accepted = send(text); if (accepted) controller.text(""); accepted }, stop, {}, {}, {})
    }

    @Test fun composer_withoutModelOffersOnlyPreparedLocalCommand() {
        val offered = com.cordiact.interaction.PreparedInteraction(null, emptyList(), emptyList())
        val workspace = com.cordiact.core.workspace.Workspace("w", "工作区", 0, 0)
        var sent: com.cordiact.interaction.PreparedInteraction? = null
        content {
            val app = org.robolectric.RuntimeEnvironment.getApplication()
            val scope = rememberCoroutineScope()
            val controller = remember { com.cordiact.app.experience.composer.ComposerController(app, scope,
                { error("No catalogue") }, { error("No importer") }) }
            val draft by controller.state.collectAsState()
            val prepare = remember { { text: String, owner: com.cordiact.interaction.InteractionContext ->
                offered.takeIf { text == "/local" && owner.workspaceId == "w" && owner.sessionId == "test-session" }
            } }
            com.cordiact.app.experience.composer.ComposerBar(ready().copy(workspace = workspace, model = null, models = emptyList()),
                draft, controller, "", {}, { error("No ordinary send") }, {}, {}, {}, {},
                onSendLocal = { sent = it; true }, prepareLocal = prepare)
        }
        val input = compose.onNodeWithTag("composer-input")
        input.performTextInput("ordinary")
        compose.onNodeWithTag("send-message").assertIsNotEnabled()
        input.performTextReplacement("/local")
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasTestTag("send-message") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("send-message").performClick()
        assertSame(offered, sent)
    }

    @Test fun settingsSections_selectsOnlyRequestedSection() {
        var selected by mutableStateOf("services")
        content {
            com.cordiact.app.experience.settings.SettingsSectionBar(
                com.cordiact.app.experience.settings.settingsSections(com.cordiact.app.experience.settings.SettingsRoute.SEARCH),
                selected, { selected = it })
        }
        compose.onNodeWithTag("settings-section-services").assertIsSelected()
        compose.onNodeWithTag("settings-section-policy").performClick()
        compose.onNodeWithTag("settings-section-policy").assertIsSelected()
        compose.onNodeWithTag("settings-section-services").assertIsNotSelected()
        assertEquals("policy", selected)
    }

    @Test fun compactSidebar_searchesAndSettingsStaysBottomRight() {
        var settings = 0
        content {
            ConversationSidebar(
                groups = listOf(SidebarWorkspace("w", "工作区A", 0, listOf(SidebarConversation("s", "示例会话", 0)))),
                activeWorkspaceId = "w", activeSessionId = "s", runningSessionIds = emptySet(), busy = false, error = null,
                onClose = {}, onSettings = { settings++ }, onNewSession = {}, onSelectWorkspace = {},
                onSelectSession = {}, onCreateWorkspace = { _, _ -> }, onRenameWorkspace = { _, _, _ -> }, onRetry = {})
        }
        compose.onNodeWithText("示例会话").assertIsDisplayed()
        compose.onNodeWithTag("sidebar-search-open").performClick()
        compose.onNodeWithTag("sidebar-search").performTextInput("不匹配")
        compose.onNodeWithText("没有匹配的对话或工作区").assertIsDisplayed()
        val panel = compose.onNodeWithTag("sidebar").getUnclippedBoundsInRoot()
        val settingsBounds = compose.onNodeWithTag("sidebar-settings").getUnclippedBoundsInRoot()
        assertTrue(settingsBounds.left > panel.left + (panel.right - panel.left) / 2)
        assertTrue(settingsBounds.top > panel.top + (panel.bottom - panel.top) / 2)
        compose.onNodeWithTag("sidebar-settings").performClick()
        assertEquals(1, settings)
    }

    @Test fun emptyState_hasActionsWithoutDemoTranscript() {
        var selected = ""
        content { TranscriptView(ready(), { selected = it }, {}) }
        compose.onNodeWithText("今天想做什么？").assertIsDisplayed()
        compose.onNodeWithText("了解工作区").performClick()
        assertTrue(selected.contains("当前工作区"))
        compose.onNodeWithText("CordiAct", useUnmergedTree = true).assertDoesNotExist()
    }
    @Test fun input_clearsOnlyWhenSubmissionAccepted() {
        var accepted = false
        var submitted = ""
        content { composer(ready(), { submitted = it; accepted }, {}) }
        compose.onNodeWithTag("composer-input").performTextInput("检查项目")
        compose.onNodeWithTag("send-message").performClick()
        assertEquals("检查项目", submitted)
        compose.onNodeWithTag("composer-input").assertTextContains("检查项目")
        accepted = true
        compose.onNodeWithTag("send-message").performClick()
        compose.onNodeWithTag("composer-input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }
    @Test fun runningComposer_stopNeverSubmitsInput() {
        var stopped = 0
        var sends = 0
        content { composer(ready(listOf(TranscriptTurn("t", "p", phase = RunPhase.RUNNING))), { sends++; true }, { stopped++ }) }
        compose.onNodeWithTag("stop-generation").performClick()
        assertEquals(1, stopped)
        assertEquals(0, sends)
    }
    @Test fun toolGroup_isCollapsedUntilUserExpands() {
        val step = ToolActivity("c", "read_file", "{}", "found config", ToolPhase.SUCCEEDED, 100, 200, 100)
        var inspect: ToolActivity? = null
        content { TranscriptView(ready(listOf(TranscriptTurn("t", "prompt", tools = listOf(step)))), {}, { inspect = it }) }
        compose.onNodeWithText("read_file").assertDoesNotExist()
        compose.onNodeWithText("执行过程 · 1 步完成").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNodeWithText("read_file").performScrollTo().assertIsDisplayed().performClick()
        assertEquals("c", inspect?.id)
    }
    @Test fun reasoning_isOptInInlineContent() {
        content { TranscriptView(ready(listOf(TranscriptTurn("t", "prompt", reasoning = "provider reasoning"))), {}, {}) }
        compose.onNodeWithText("provider reasoning").assertDoesNotExist()
        compose.onNodeWithText("思考过程").performClick()
        compose.onNodeWithText("provider reasoning").assertIsDisplayed()
    }
    @Test fun preview_neverRendersAssistantTextAsArtifact() {
        content { PreviewView(ready(listOf(TranscriptTurn("t", "p", answer = "not-an-artifact")))) }
        compose.onNodeWithText("等待可渲染产物").assertIsDisplayed()
        compose.onNodeWithText("not-an-artifact").assertDoesNotExist()
    }
    @Test fun inspector_staysBelowHalfViewport() {
        content { CompactInspector(ToolActivity("c", "terminal", "echo test", "x\n".repeat(200), startedAt = 100), {}) }
        compose.onNodeWithContentDescription("关闭工具详情").assertIsDisplayed()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val title = compose.onNodeWithText("terminal").getUnclippedBoundsInRoot()
        assertTrue("Sheet header should start in lower half", title.top > (root.bottom - root.top) * .5f)
    }
    @After fun clearPreviewQueue() { PreviewInbox.clear("revision2-artifacts"); PreviewInbox.clear("other-artifacts") }

    @Test fun tokens_supportInlineInputAndShortcutInsertion() {
        content {
            val app = org.robolectric.RuntimeEnvironment.getApplication()
            val scope = rememberCoroutineScope()
            val controller = remember { com.cordiact.app.experience.composer.ComposerController(app, scope, { error("No catalogue needed for input test") }, { error("No import needed for input test") }) }
            val state by controller.state.collectAsState()
            com.cordiact.app.experience.composer.ComposerBar(ready(), state.copy(model = model), controller, "", {}, { true }, {}, {}, {}, {})
        }
        // The composers rails were retired: tokens stay typeable, the hint carries the affordance.
        listOf("/", "@", "#").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithTag("composer-input").performTextInput("/inspect @config #api-key")
        compose.onNodeWithTag("composer-input").assertTextContains("/inspect @config #api-key")
    }

    @Test fun artifactQueue_isSessionScopedAndOnlyRendersAfterExplicitSelection() {
        val current = PreviewInbox.push("revision2-artifacts", "Agent dashboard", "<main>real-artifact</main>", origin = PreviewOrigin.AGENT)
        PreviewInbox.push("other-artifacts", "Other session", "<main>private</main>")
        var renders = 0
        content {
            ArtifactPreviewWindow("revision2-artifacts", visible = true, render = { item ->
                DisposableEffect(item.id) { renders++; onDispose {} }
                androidx.compose.material3.Text("rendered:" + item.html)
            })
        }
        assertEquals(0, renders)
        compose.onNodeWithText("Other session").assertDoesNotExist()
        compose.onNodeWithText("Agent dashboard").assertIsDisplayed().performClick()
        compose.onNodeWithText("rendered:<main>real-artifact</main>").assertIsDisplayed()
        assertEquals(1, renders)
        assertEquals(0, PreviewInbox.unseenCount("revision2-artifacts"))
        compose.onNodeWithText("返回列表").performClick()
        compose.onNodeWithTag("artifact-" + current.id).assertIsDisplayed()
    }

    @Test fun artifactRenderer_isDisposedWhenWindowLeavesViewport() {
        PreviewInbox.push("revision2-artifacts", "Interactive widget", "<div/>")
        var visible by mutableStateOf(true)
        var live = 0
        content {
            ArtifactPreviewWindow("revision2-artifacts", visible, render = { item ->
                DisposableEffect(item.id) { live++; onDispose { live-- } }
                androidx.compose.material3.Text("widget-renderer")
            })
        }
        compose.onNodeWithText("Interactive widget").performClick()
        compose.runOnIdle { assertEquals(1, live); visible = false }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, live) }
        compose.onNodeWithText("预览已暂停").assertIsDisplayed()
    }

    @Test fun keyboardInset_keepsEmptyConversationSuggestionsAboveComposer() {
        var ime by mutableStateOf(0.dp)
        var prompt: String? = null
        content {
            Box(Modifier.requiredSize(400.dp, 790.dp)) {
                ExperienceFrame(
                    insets = WindowInsets(top = 24.dp, bottom = ime),
                    top = { Box(Modifier.fillMaxWidth().height(100.dp).testTag("empty-top")) },
                    bottom = { Box(Modifier.fillMaxWidth().height(110.dp).testTag("empty-composer")) },
                    content = { TranscriptView(ready(), { prompt = it }, {}, it) },
                )
            }
        }
        val title = compose.onNodeWithText("今天想做什么？")
        val before = title.getUnclippedBoundsInRoot().top
        compose.runOnIdle { ime = 300.dp }
        val topBar = compose.onNodeWithTag("empty-top").getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag("empty-composer").getUnclippedBoundsInRoot()
        val helper = compose.onNodeWithText("建议只会填入输入框，不会自动发送").assertIsDisplayed()
        assertTrue(title.getUnclippedBoundsInRoot().top > topBar.bottom)
        assertTrue(helper.getUnclippedBoundsInRoot().bottom < footer.top)
        compose.onNodeWithText("开始一项任务").assertIsDisplayed().performClick()
        compose.runOnIdle { assertNotNull(prompt) }
        compose.runOnIdle { ime = 0.dp }
        assertEquals(before.value, title.getUnclippedBoundsInRoot().top.value, 1f)
    }

    @Test fun keyboardInset_movesComposerExactlyOnceAndPreservesTopBar() {
        var ime by mutableStateOf(0.dp)
        content {
            val system = WindowInsets(top = 24.dp, bottom = 24.dp)
            val keyboard = WindowInsets(bottom = ime)
            Box(Modifier.requiredSize(400.dp, 700.dp)) {
                ExperienceFrame(
                    insets = system.union(keyboard),
                    top = { Box(Modifier.fillMaxWidth().height(50.dp).testTag("frame-top")) },
                    bottom = { Box(Modifier.fillMaxWidth().height(90.dp).testTag("frame-composer")) },
                    content = { Box(it.testTag("frame-content")) },
                )
            }
        }
        val topBefore = compose.onNodeWithTag("frame-top").getUnclippedBoundsInRoot().top.value
        val before = compose.onNodeWithTag("frame-composer").getUnclippedBoundsInRoot().bottom.value
        val viewport = compose.onNodeWithTag("frame-content").getUnclippedBoundsInRoot()
        val chrome = compose.onNodeWithTag("frame-composer").getUnclippedBoundsInRoot()
        assertTrue("Content must extend behind floating chrome", viewport.bottom >= chrome.bottom)
        compose.runOnIdle { ime = 300.dp }
        compose.waitForIdle()
        val after = compose.onNodeWithTag("frame-composer").getUnclippedBoundsInRoot().bottom.value
        val topAfter = compose.onNodeWithTag("frame-top").getUnclippedBoundsInRoot().top.value
        assertEquals("IME uses max(systemBars, ime), not sum", 276f, before - after, 1f)
        assertEquals("Focusing input must not pan top bar off screen", topBefore, topAfter, 1f)
        compose.runOnIdle { ime = 0.dp }
        compose.waitForIdle()
        assertEquals(before, compose.onNodeWithTag("frame-composer").getUnclippedBoundsInRoot().bottom.value, 1f)
    }

    @Test fun studioMenu_routesToArtifactsAndReportsSelection() {
        var pane by mutableStateOf(StudioPane.Conversation)
        content { StudioPaneMenu(pane, tools = 2, previews = 3, onSelect = { pane = it }) }
        compose.onNodeWithText("产物 3").assertExists()
        compose.onNodeWithTag("studio-pane-menu").performClick()
        compose.onNodeWithTag("studio-pane-conversation").assertIsSelected()
        compose.onNodeWithTag("studio-pane-artifacts").performClick()
        compose.onNodeWithTag("studio-pane-menu").performClick()
        compose.onNodeWithTag("studio-pane-artifacts").assertIsSelected()
    }

    @Test fun sessionPlugins_canOpenFromWorkbenchWithoutModelAndRevokeOnlyAfterConfirmation() {
        val receipt = AuthoredPluginReceipt("demo", "a".repeat(64), listOf("extension.authored.demo.show"))
        var shown by mutableStateOf(false)
        var revoked: AuthoredPluginReceipt? = null
        content {
            StudioPaneMenu(StudioPane.Conversation, tools = 0, previews = 0, onSelect = {}, onSessionPlugins = { shown = true })
            if (shown) SessionPluginsDialog(
                receipts = listOf(receipt), reading = false, busy = false, note = null, failure = null,
                onDismiss = { shown = false }, onRefresh = {}, onRevoke = { revoked = it },
            )
        }
        compose.onNodeWithTag("studio-pane-menu").performClick()
        compose.onNodeWithTag("studio-session-plugins").performClick()
        compose.onNodeWithText("extension.authored.demo.show").assertExists()
        compose.onNodeWithTag("session-plugin-revoke-demo").performClick()
        compose.runOnIdle { assertNull(revoked) }
        compose.onNodeWithTag("session-plugin-confirm-revoke").performClick()
        compose.runOnIdle { assertSame(receipt, revoked) }
    }

    @Test fun pluginDrafts_emptyFilteredPageCanAdvanceAndPreviewShowsDifferentActiveVersion() {
        val draft = AuthoredPluginDefinition("demo", "草稿标题", listOf(AuthoredToolDefinition("show", "说明", "固定正文")), "a".repeat(64))
        val active = AuthoredPluginReceipt("demo", "b".repeat(64), listOf("extension.authored.demo.show"))
        var view by mutableStateOf("active")
        var cursor by mutableStateOf<String?>(null)
        content {
            when (view) {
                "active" -> SessionPluginsDialog(listOf(active), false, false, null, null,
                    onDismiss = {}, onRefresh = {}, onRevoke = { error("Preview must not revoke") }, onOpenDrafts = { view = "drafts" })
                "drafts" -> PluginDraftsDialog(
                    if (cursor == null) AuthoredPluginDraftPage(emptyList(), "opaque")
                    else AuthoredPluginDraftPage(listOf(AuthoredPluginDraftEntry("demo", 192L)), null),
                    null, cursor == null, onPreview = { view = it }, onNext = { cursor = it },
                    onFirstPage = { cursor = null }, onDismiss = { view = "active" },
                )
                else -> PluginDraftPreviewDialog(view, draft, active, null, onBack = { view = "drafts" })
            }
        }
        compose.onNodeWithTag("session-plugin-drafts").performClick()
        compose.onNodeWithTag("plugin-draft-next").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("opaque", cursor) }
        compose.onNodeWithTag("plugin-draft-preview-demo").performClick()
        compose.onNodeWithText("草稿与本会话启用的版本不同；编辑草稿不会更新启用版本。").assertExists()
        compose.onNodeWithText("固定正文").assertExists()
        compose.onNodeWithText("启用", substring = false).assertDoesNotExist()
        compose.onNodeWithTag("session-plugin-revoke-demo").assertDoesNotExist()
    }

    @Test fun pluginPreview_requiresSecondClickAndPassesNoActionUnderReadOnly() {
        val draft = AuthoredPluginDefinition("demo", "草稿标题", listOf(AuthoredToolDefinition("show", "说明", "固定正文")), "a".repeat(64))
        var readOnly by mutableStateOf(true)
        var enabled = 0
        content {
            PluginDraftPreviewDialog("demo", draft, null, null, onBack = {}, readOnly = readOnly, onEnable = { enabled++ })
        }
        compose.onNodeWithTag("plugin-draft-enable").assertDoesNotExist()
        compose.runOnIdle { readOnly = false }
        compose.onNodeWithTag("plugin-draft-enable").performClick()
        compose.runOnIdle { assertEquals(0, enabled) }
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertEquals(0, enabled) }
        compose.onNodeWithTag("plugin-draft-enable").performClick()
        compose.onNodeWithTag("plugin-draft-confirm-enable").performClick()
        compose.runOnIdle { assertEquals(1, enabled) }
    }

    @Test fun savingDraftRequiresExplicitConfirmationAndNeverEnables() {
        val draft = AuthoredPluginDefinition("demo", "草稿标题", listOf(AuthoredToolDefinition("show", "说明", "固定正文")), "a".repeat(64))
        var saves = 0
        var activations = 0
        content { PluginDraftPreviewDialog("demo", draft, null, null, onBack = {},
            onEnable = { activations++ }, onSave = { saves++ }) }
        compose.onNodeWithTag("plugin-draft-save").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, saves); assertEquals(0, activations) }
        compose.onNodeWithTag("plugin-draft-confirm-save").performClick()
        compose.runOnIdle { assertEquals(1, saves); assertEquals(0, activations) }
    }

    @Test fun savedPluginListDistinguishesInvalidVersionFromNoSavedRecords() {
        var shown by mutableStateOf(false)
        content {
            if (!shown) SessionPluginsDialog(emptyList(), false, false, null, null,
                onDismiss = {}, onRefresh = {}, onRevoke = {}, onOpenSaved = { shown = true })
            else WorkspacePluginVersionsDialog(
                listOf(AuthoredPluginInstallationEntry("broken", AuthoredPluginInstallationStatus.CORRUPT, null),
                    AuthoredPluginInstallationEntry("future", AuthoredPluginInstallationStatus.UNSUPPORTED, null)),
                error = false, onRefresh = {}, onBack = { shown = false },
                onPreview = { error("Unreadable records must not open a preview") },
            )
        }
        compose.onNodeWithTag("session-plugin-saved-versions").performClick()
        compose.onNodeWithText("内容或摘要校验失败").assertExists()
        compose.onNodeWithText("此应用版本不支持该记录格式").assertExists()
        compose.onNodeWithText("当前工作区尚无已保存版本。").assertDoesNotExist()
        compose.onNodeWithTag("workspace-plugin-preview-broken").assertDoesNotExist()
        compose.onNodeWithTag("workspace-plugin-preview-future").assertDoesNotExist()
    }

    @Test fun savedPluginPreviewShowsSourceAndRequiresExplicitEnable() {
        val digest = "a".repeat(64)
        val record = AuthoredPluginInstalledRecord("w", "demo", digest, 1, 1, 192, 1L,
            "批准标题", listOf("extension.authored.demo.show"))
        val definition = AuthoredPluginDefinition("demo", "批准标题",
            listOf(AuthoredToolDefinition("show", "说明", "快照正文")), digest)
        var selected by mutableStateOf<AuthoredPluginInstalledRecord?>(null)
        var readOnly by mutableStateOf(true)
        var activations = 0
        content {
            if (selected == null) {
                WorkspacePluginVersionsDialog(listOf(AuthoredPluginInstallationEntry("demo", AuthoredPluginInstallationStatus.READY, record)),
                    error = false, onRefresh = {}, onBack = {}, onPreview = { selected = it })
            } else {
                PluginDraftPreviewDialog("demo", definition, null, null, onBack = { selected = null },
                    readOnly = readOnly, onEnable = { activations++ },
                    onSave = { error("Save cannot be shown for an installed preview") }, fromSaved = true)
            }
        }
        compose.onNodeWithTag("workspace-plugin-preview-demo").performScrollTo().performClick()
        compose.runOnIdle { assertSame(record, selected) }
        compose.onNodeWithText("已保存版本 SHA-256").assertExists()
        compose.onNodeWithText("快照正文").assertExists()
        compose.onNodeWithTag("plugin-draft-enable").assertDoesNotExist()
        compose.runOnIdle { readOnly = false }
        compose.onNodeWithTag("plugin-draft-save").assertDoesNotExist()
        compose.onNodeWithTag("plugin-draft-enable").performScrollTo().performClick()
        compose.onNodeWithText("仅启用下列摘要对应的已保存版本，不读取草稿。").assertExists()
        compose.runOnIdle { assertEquals(0, activations) }
        compose.onNodeWithTag("plugin-draft-confirm-enable").performClick()
        compose.runOnIdle { assertEquals(1, activations) }
    }

    @Test fun savedPluginReviewKeepsDeleteReplaceAndEnableSeparate() {
        val old = AuthoredPluginDefinition("demo", "旧版", listOf(AuthoredToolDefinition("show", "说明", "旧正文")), "a".repeat(64))
        val candidate = AuthoredPluginDefinition("demo", "新版", listOf(AuthoredToolDefinition("show", "说明", "新正文")), "b".repeat(64))
        var removes = 0
        var replacements = 0
        var enables = 0
        content {
            PluginDraftPreviewDialog("demo", old, null, null, onBack = {}, onEnable = { enables++ },
                fromSaved = true, replacement = candidate, onRemoveSaved = { removes++ }, onReplaceSaved = { replacements++ })
        }
        compose.onNodeWithText("新正文").assertExists()
        compose.onNodeWithTag("plugin-saved-replace").performScrollTo().performClick()
        compose.onNodeWithText("旧版 SHA-256：${old.sha256}").assertExists()
        compose.onNodeWithText("新版 SHA-256：${candidate.sha256}").assertExists()
        compose.runOnIdle { assertEquals(0, replacements) }
        compose.onNodeWithTag("plugin-draft-confirm-replace").performClick()
        compose.onNodeWithTag("plugin-saved-remove").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, removes); assertEquals(1, replacements); assertEquals(0, enables) }
        compose.onNodeWithTag("plugin-draft-confirm-remove").performClick()
        compose.runOnIdle { assertEquals(1, removes); assertEquals(1, replacements); assertEquals(0, enables) }
    }

    @Test fun everyOriginalGlyphCanBeBuiltAndDrawn() {
        content {
            Column {
                GlyphName.entries.chunked(6).forEach { names -> Row { names.forEach { AppGlyph(it, it.name, Modifier.size(24.dp)) } } }
            }
        }
        GlyphName.entries.forEach { compose.onNodeWithContentDescription(it.name).assertExists() }
    }

}
