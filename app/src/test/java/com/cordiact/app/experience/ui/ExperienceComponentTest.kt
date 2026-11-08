package com.cordiact.app.experience.ui

import android.app.Application
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
        ExperienceTheme { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { block() } }
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
        compose.onNodeWithText("found config").assertIsDisplayed().performClick()
        assertEquals("c", inspect?.id)
    }
    @Test fun reasoning_isOptInInlineContent() {
        content { TranscriptView(ready(listOf(TranscriptTurn("t", "prompt", reasoning = "provider reasoning"))), {}, {}) }
        compose.onNodeWithText("provider reasoning").assertDoesNotExist()
        compose.onNodeWithText("思考过程").performClick()
        compose.onNodeWithText("provider reasoning").assertIsDisplayed()
    }
    @Test fun preview_hasNoInventedArtifacts() {
        content { PreviewView(ready(), {}) }
        compose.onNodeWithText("结果将在这里汇集").assertIsDisplayed()
    }
    @Test fun inspector_staysBelowHalfViewport() {
        content { CompactInspector(ToolActivity("c", "terminal", "echo test", "x\n".repeat(200), startedAt = 100), {}) }
        compose.onNodeWithContentDescription("关闭工具详情").assertIsDisplayed()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val title = compose.onNodeWithText("terminal").getUnclippedBoundsInRoot()
        assertTrue("Sheet header should start in lower half", title.top > (root.bottom - root.top) * .5f)
    }
}
