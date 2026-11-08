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

    @Test fun emptyState_hasActionsWithoutDemoTranscript() {
        var selected = ""
        content { TranscriptView(ready(), { selected = it }, {}) }
        compose.onNodeWithText("从一个想法开始。").assertIsDisplayed()
        compose.onNodeWithText("了解工作区").performClick()
        assertTrue(selected.contains("当前工作区"))
        compose.onNodeWithText("CordiAct", useUnmergedTree = true).assertDoesNotExist()
    }
    @Test fun input_clearsOnlyWhenSubmissionAccepted() {
        var accepted = false
        var submitted = ""
        content { Composer(ready(), "", {}, { submitted = it; accepted }, {}, {}) }
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
        content { Composer(ready(listOf(TranscriptTurn("t", "p", phase = RunPhase.RUNNING))), "", {}, { sends++; true }, { stopped++ }, {}) }
        compose.onNodeWithTag("stop-generation").performClick()
        assertEquals(1, stopped)
        assertEquals(0, sends)
    }
    @Test fun toolGroup_isCollapsedUntilUserExpands() {
        val step = ToolActivity("c", "read_file", "{}", "found config", ToolPhase.SUCCEEDED, 100, 200, 100)
        var inspect: ToolActivity? = null
        content { TranscriptView(ready(listOf(TranscriptTurn("t", "prompt", tools = listOf(step)))), {}, { inspect = it }) }
        compose.onNodeWithText("found config").assertDoesNotExist()
        compose.onNodeWithText("完成 1 / 1 个步骤").performClick()
        compose.mainClock.advanceTimeBy(1000)
        compose.waitForIdle()
        compose.onNodeWithText("found config").performScrollTo().assertIsDisplayed().performClick()
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

    @Test fun tokens_areTypedInlineAndNeverOccupyShortcutButtons() {
        content { Composer(ready(), "", {}, { true }, {}, {}) }
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

    @Test fun browserTabRail_isCompactAndChangesSelectionWithoutResizingGlyphs() {
        var page by mutableIntStateOf(0)
        content { WindowTabs(page, page.toFloat(), 0, 0, onSelect = { page = it }) }
        val rail = compose.onNodeWithTag("window-tabs").getUnclippedBoundsInRoot()
        assertEquals(40f, (rail.bottom - rail.top).value, 1f)
        val widths = listOf("chat", "trace", "preview").map { id ->
            val bounds = compose.onNodeWithTag("window-" + id).getUnclippedBoundsInRoot()
            (bounds.right - bounds.left).value
        }
        assertTrue(widths.all { it <= 105f })
        repeat(3) {
            compose.onNodeWithTag("window-trace").performClick().assertIsSelected()
            compose.onNodeWithTag("window-chat").performClick().assertIsSelected()
        }
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
