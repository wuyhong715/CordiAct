package com.cordiact.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.cordiact.app.render.PreviewInbox
import com.cordiact.app.render.PreviewWindow
import com.cordiact.app.search.SearchSettingsDialog
import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelCall
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelId
import com.cordiact.interaction.InteractionContext
import com.cordiact.interaction.InteractionDiagnostic
import com.cordiact.interaction.InteractionToken
import com.cordiact.interaction.MentionDescriptor
import com.cordiact.interaction.MentionType
import com.cordiact.interaction.PreparedInteraction
import com.cordiact.interaction.TokenStatus

private val DEFAULT_MODEL = ModelId("deepseek", "deepseek-flash")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 临时演示开关：true = 紧凑 UI 演示，false = 原交互界面
        val showCompactDemo = intent.getBooleanExtra("compact_demo", false)
        
        setContent {
            if (showCompactDemo) {
                // 紧凑 UI 演示界面
                com.cordiact.app.ui.theme.CordiActTheme {
                    com.cordiact.app.ui.window.chat.CompactChatDemo()
                }
            } else {
                // 原交互界面
                MaterialTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        InteractionScreen(application as CordiActApplication)
                    }
                }
            }
        }
    }
}

@Composable
private fun InteractionScreen(app: CordiActApplication) {
    // One worker for the blocking turn. Not a pool: the turn service already refuses two
    // concurrent turns on one Session, and a queue keeps a second submit from racing the first.
    val turnExecutor = remember {
        java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "cordiact-turn").apply { isDaemon = true }
        }
    }
    val workspace = remember { runCatching { app.workspaceManager.activeWorkspace() }.getOrNull() }
    val sessionId = app.activeSessionId
    var input by remember { mutableStateOf(TextFieldValue()) }
    var response by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var activeCall by remember { mutableStateOf<ModelCall?>(null) }
    // True from submit until the turn returns. Separate from [activeCall] because the call is
    // only available once the blocking turn is already over.
    var submitting by remember { mutableStateOf(false) }
    var fillVersion by remember { mutableStateOf(0) }
    var showFillDialog by remember { mutableStateOf(false) }
    var showSearchSettings by remember { mutableStateOf(false) }
    var showPreviewWindow by remember { mutableStateOf(false) }
    var fillKey by remember { mutableStateOf("") }
    var fillValue by remember { mutableStateOf("") }
    var history by remember(sessionId) {
        mutableStateOf(
            sessionId?.let { runCatching { app.sessions.messages(it) }.getOrDefault(emptyList()) }
                ?: emptyList(),
        )
    }
    val context = remember(sessionId, workspace?.id) {
        if (sessionId != null && workspace != null) InteractionContext(sessionId, workspace.id) else null
    }
    val prepared = remember(input.text, context) {
        context?.let {
            runCatching { app.interactionService.prepare(input.text, it) }.getOrElse { failure ->
                PreparedInteraction(
                    envelope = null,
                    tokens = emptyList(),
                    diagnostics = listOf(
                        InteractionDiagnostic(
                            "service_unavailable",
                            failure.message ?: "Interaction service unavailable",
                            0,
                            input.text.length,
                        ),
                    ),
                )
            }
        }
    }
    val commandSuggestions = remember(input.text, app.interactionService) {
        val query = input.text.removePrefix("/").takeWhile { !it.isWhitespace() && it != ':' && it != '：' }
        if (input.text.startsWith("/") && !input.text.contains(':') && !input.text.contains('：') && !input.text.contains(' ')) {
            app.interactionService.commandCandidates().filter { descriptor ->
                descriptor.name.startsWith(query) || descriptor.aliases.any { it.startsWith(query) }
            }.take(8)
        } else emptyList()
    }
    val mentionState = remember(input.text, context, app.interactionService) {
        val structuredParent = Regex("@\\{([^\\{\\}]*)\\}\\s*[：:]$").find(input.text)?.groupValues?.get(1)
        val at = input.text.lastIndexOf('@')
        val fragment = if (structuredParent == null && at >= 0) input.text.substring(at + 1) else ""
        if (context != null && (structuredParent != null || (at >= 0 && !fragment.contains(Regex("[\\s，。；,.;!?！？]"))))) {
            val candidates = runCatching {
                app.interactionService.mentionCandidates(context, structuredParent)
            }.getOrDefault(emptyList())
            candidates to structuredParent
        } else emptyList<MentionDescriptor>() to null
    }
    val mentionSuggestions = mentionState.first
    val mentionParentId = mentionState.second
    val fillSuggestions = remember(input.text, fillVersion, app) {
        val fragment = input.text.substringAfterLast('#', "")
        if (input.text.lastIndexOf('#') >= 0 && !fragment.contains(Regex("[\\s，。；,.;!?！？]"))) {
            app.fillValues.keys().filter { it.startsWith(fragment) }.sorted().take(8)
        } else emptyList()
    }

    fun submit() {
        val current = prepared ?: return
        if (input.text.isBlank()) {
            error = "请输入消息内容"
            return
        }
        if (!current.valid || activeCall != null || submitting) {
            error = current.diagnostics.firstOrNull()?.message ?: "请先修正输入"
            return
        }
        val envelope = current.envelope ?: return
        error = null
        response = ""
        val handler = Handler(Looper.getMainLooper())
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val callback: (ModelEvent) -> Unit = { event ->
            handler.post {
                when (event) {
                    is ModelEvent.Delta -> response += event.text
                    // Tool rounds are internal; the user sees only the final answer, which
                    // arrives as Deltas once the model stops calling tools.
                    is ModelEvent.ToolCallsRequested -> Unit
                    is ModelEvent.Completed -> {
                        finished.set(true)
                        submitting = false
                        activeCall = null
                        history = runCatching { app.sessions.messages(envelope.context.sessionId) }.getOrDefault(history)
                    }
                    is ModelEvent.Failed -> {
                        finished.set(true)
                        submitting = false
                        activeCall = null
                        error = event.message
                    }
                    else -> Unit
                }
            }
        }
        input = TextFieldValue("")
        // A tool-using turn blocks from the first round to the last, and a delegated task waits
        // inside one of its tool calls. Running that on the UI thread would freeze the app for
        // the whole turn, so the submit — not just the callback — goes to a worker.
        activeCall = null
        submitting = true
        turnExecutor.execute {
            val call = try {
                if (envelope.command == null) {
                    app.interactionService.submitOrdinary(current, DEFAULT_MODEL, onEvent = callback)
                } else {
                    app.interactionService.dispatch(current, DEFAULT_MODEL, onEvent = callback).modelCall
                }
            } catch (failure: Exception) {
                handler.post {
                    finished.set(true)
                    submitting = false
                    error = failure.message ?: "发送失败"
                }
                null
            }
            handler.post {
                submitting = false
                // Only offer cancellation while something is still in flight; a turn that already
                // returned its terminal event would hand the user a button that does nothing.
                if (call != null && !finished.get() && activeCall == null) activeCall = call
            }
        }
    }

    if (showFillDialog) {
        AlertDialog(
            onDismissRequest = { showFillDialog = false },
            title = { Text("设置填充值") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = fillKey, onValueChange = { fillKey = it }, label = { Text("键，例如 111") })
                    OutlinedTextField(value = fillValue, onValueChange = { fillValue = it }, label = { Text("值") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { app.fillValues.set(fillKey, fillValue) }
                        .onSuccess {
                            fillKey = ""
                            fillValue = ""
                            fillVersion++
                            showFillDialog = false
                        }
                        .onFailure { error = it.message ?: "填充值保存失败" }
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showFillDialog = false }) { Text("取消") } },
        )
    }

    if (showPreviewWindow) {
        PreviewWindow(sessionId = sessionId, onDismiss = { showPreviewWindow = false })
    }

    if (showSearchSettings) {
        val workspaceId = app.activeSessionWorkspaceId()
        if (workspaceId == null) {
            showSearchSettings = false
            error = "当前没有可用的 Workspace"
        } else {
            SearchSettingsDialog(
                plugins = app.conversationCatalog,
                workspaceId = workspaceId,
                onDismiss = { showSearchSettings = false },
                onChanged = { },
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("CordiAct", style = MaterialTheme.typography.headlineMedium)
        Text(
            text = if (workspace == null) "Workspace 不可用" else "Workspace：${workspace.name} · 模型：deepseek-flash",
            color = if (workspace == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(history) { message -> MessageRow(message) }
            if (response.isNotEmpty()) {
                item {
                    MessageRow(ConversationMessage("stream", sessionId ?: "", MessageRole.ASSISTANT, response, 0L))
                }
            }
        }
        when {
            commandSuggestions.isNotEmpty() -> SuggestionRow(commandSuggestions.map { "/${it.name}：" }) { selected ->
                val name = selected.removePrefix("/").removeSuffix("：")
                input = input.copy(text = "/$name：", selection = TextRange("/$name：".length))
            }
            mentionSuggestions.isNotEmpty() -> SuggestionRow(mentionSuggestions.map { "@${it.label}" }) { selected ->
                val descriptor = mentionSuggestions.first { "@${it.label}" == selected }
                val replacement = "@{${descriptor.id}}"
                if (mentionParentId != null) {
                    val cursor = input.selection.end
                    input = input.copy(
                        text = input.text.substring(0, cursor) + replacement + input.text.substring(cursor),
                        selection = TextRange(cursor + replacement.length),
                    )
                } else {
                    val at = input.text.lastIndexOf('@')
                    val next = input.text.substring(0, at) + replacement + input.text.substring(input.selection.end)
                    input = TextFieldValue(next, TextRange(at + replacement.length))
                }
            }
            fillSuggestions.isNotEmpty() -> SuggestionRow(fillSuggestions.map { "#$it" }) { selected ->
                val key = selected.removePrefix("#")
                val hash = input.text.lastIndexOf('#')
                val next = input.text.substring(0, hash) + "#$key" + input.text.substring(input.selection.end)
                input = TextFieldValue(next, TextRange(hash + key.length + 1))
            }
        }
        BasicTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = MaterialTheme.typography.bodyLarge.fontSize),
            visualTransformation = InteractionVisualTransformation(prepared?.tokens.orEmpty()),
            keyboardOptions = KeyboardOptions.Default,
            keyboardActions = KeyboardActions(onSend = { submit() }),
            decorationBox = { field ->
                Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (input.text.isEmpty()) Text("输入消息，使用 /、@ 或 #", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        field()
                    }
                }
            },
        )
        prepared?.diagnostics?.firstOrNull()?.let { diagnostic ->
            Text(diagnostic.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { submit() }, enabled = activeCall == null && !submitting && prepared?.valid == true) { Text("发送") }
            OutlinedButton(onClick = { showFillDialog = true }) { Text("设置 #") }
            OutlinedButton(onClick = { showSearchSettings = true }) { Text("搜索") }
            OutlinedButton(onClick = { showPreviewWindow = true }) {
                val unseen = PreviewInbox.unseenCount(sessionId)
                Text(if (unseen > 0) "预览 ($unseen)" else "预览")
            }
            if (activeCall != null) OutlinedButton(onClick = { activeCall?.cancel(); activeCall = null }) { Text("取消") }
        }
    }
}

@Composable
private fun MessageRow(message: ConversationMessage) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(if (message.role == MessageRole.USER) "你" else "模型", style = MaterialTheme.typography.labelMedium)
        Text(message.content)
    }
}

@Composable
private fun SuggestionRow(items: List<String>, onSelected: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { item -> OutlinedButton(onClick = { onSelected(item) }) { Text(item) } }
    }
}

private class InteractionVisualTransformation(private val tokens: List<InteractionToken>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val builder = AnnotatedString.Builder(text)
        tokens.forEach { token ->
            val color = when (token.status) {
                TokenStatus.VALID -> Color(0xFF1976D2)
                TokenStatus.CONFIRMATION_REQUIRED -> Color(0xFFB26A00)
                else -> Color(0xFFC62828)
            }
            val start = token.start.coerceIn(0, text.length)
            val end = token.endExclusive.coerceIn(start, text.length)
            if (start < end) builder.addStyle(SpanStyle(color = color), start, end)
        }
        return TransformedText(builder.toAnnotatedString(), OffsetMapping.Identity)
    }
}
