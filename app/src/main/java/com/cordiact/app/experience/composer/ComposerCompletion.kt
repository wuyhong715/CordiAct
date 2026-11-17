package com.cordiact.app.experience.composer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.cordiact.app.CordiActApplication
import com.cordiact.interaction.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun CompletionEditor(app: CordiActApplication, sessionId: String, workspaceId: String?, text: String,
    enabled: Boolean, onText: (String) -> Unit) {
    var editor by remember(sessionId, workspaceId) { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    var candidates by remember(sessionId, workspaceId) { mutableStateOf(emptyList<InputCompletionCandidate>()) }
    var candidateOwner by remember(sessionId, workspaceId) { mutableStateOf<TextFieldValue?>(null) }
    var dismissed by remember(sessionId, workspaceId) { mutableStateOf<TextFieldValue?>(null) }
    var selected by remember(sessionId, workspaceId) { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(text) { if (text != editor.text) editor = TextFieldValue(text, TextRange(text.length)) }
    val target = if (enabled && editor.composition == null && editor.selection.collapsed && workspaceId != null)
        InputSyntax.completionTarget(editor.text, editor.selection.start) else null
    val snapshot = editor
    LaunchedEffect(snapshot, workspaceId, enabled, dismissed) {
        candidates = emptyList(); candidateOwner = null; selected = 0
        if (target != null && snapshot != dismissed && workspaceId != null) {
            val found = withContext(Dispatchers.IO) {
                runCatching { completionCandidates(target, app.conversationCatalog.interactionPlugin.commandRegistry,
                    app.conversationCatalog.interactionDirectory, app.fillValues,
                    InteractionContext(sessionId, workspaceId)).take(8) }.getOrDefault(emptyList())
            }
            if (editor == snapshot) { candidates = found; candidateOwner = snapshot }
        }
    }
    fun apply(candidate: InputCompletionCandidate) {
        val current = target ?: return
        if (editor != snapshot || candidateOwner != editor || !enabled || editor.composition != null) return
        val updated = editor.text.replaceRange(current.start, current.endExclusive, candidate.insertText)
        editor = TextFieldValue(updated, TextRange(current.start + candidate.insertText.length))
        dismissed = editor; candidates = emptyList(); onText(updated); focus.requestFocus()
    }
    Column(Modifier.fillMaxWidth()) {
        if (candidates.isNotEmpty() && candidateOwner == editor) Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(4.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text("补全", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { dismissed = editor; candidates = emptyList() }) { Text("收起") }
                }
                candidates.forEachIndexed { index, candidate ->
                    TextButton(onClick = { apply(candidate) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth()) {
                            Text((if (selected == index) "› " else "") + candidate.label)
                            if (candidate.detail.isNotBlank()) Text(candidate.detail, maxLines = 1, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
        BasicTextField(value = editor, onValueChange = { editor = it; onText(it.text) }, enabled = enabled,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp)
                .heightIn(min = 24.dp, max = 160.dp).testTag("composer-input").focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (editor.composition != null || candidateOwner != editor || candidates.isEmpty() || event.type != KeyEventType.KeyDown) false
                    else when (event.key) {
                        Key.DirectionDown -> { selected = (selected + 1) % candidates.size; true }
                        Key.DirectionUp -> { selected = (selected - 1 + candidates.size) % candidates.size; true }
                        Key.Tab -> { apply(candidates[selected.coerceIn(candidates.indices)]); true }
                        Key.Escape -> { dismissed = editor; candidates = emptyList(); true }
                        else -> false
                    }
                }, minLines = 1, maxLines = 6,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), decorationBox = { field ->
                Box { if (editor.text.isEmpty()) Text("发送消息…", color = MaterialTheme.colorScheme.onSurfaceVariant); field() }
            })
        Row {
            listOf("/", "@", "#").forEach { trigger ->
                TextButton(enabled = enabled && editor.composition == null, onClick = {
                    val at = editor.selection.min
                    val prefix = if (at > 0 && !editor.text[at - 1].isWhitespace()) " " else ""
                    val insertion = prefix + trigger
                    val updated = editor.text.replaceRange(at, editor.selection.max, insertion)
                    editor = TextFieldValue(updated, TextRange(at + insertion.length))
                    dismissed = null; onText(updated); focus.requestFocus()
                }) { Text(trigger) }
            }
        }
    }
}
