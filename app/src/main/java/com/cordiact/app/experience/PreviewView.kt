package com.cordiact.app.experience

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cordiact.app.experience.model.ToolActivity

/** Only real returned content is previewed. No inferred file URL or fabricated screenshot. */
@Composable
fun PreviewView(state: ExperienceState, onInspect: (ToolActivity) -> Unit, modifier: Modifier = Modifier) {
    var source by rememberSaveable(state.sessionId) { mutableStateOf(false) }
    val latest = state.turns.lastOrNull { it.answer.isNotBlank() }
    val outputs = state.turns.flatMap { it.tools }.filter { !it.summary.isNullOrBlank() }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Row {
                Column(Modifier.weight(1f)) {
                    Text("产出预览", style = MaterialTheme.typography.headlineSmall)
                    Text("阅读结果，按需查看原文", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (latest != null) TextButton(onClick = { source = !source }) { Text(if (source) "渲染" else "原文") }
            }
        }
        if (latest == null && outputs.isEmpty()) item {
            Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppGlyph(GlyphName.Preview, tint = MaterialTheme.colorScheme.primary)
                    Text("结果将在这里汇集", style = MaterialTheme.typography.titleMedium)
                    Text("当前支持回复的 Markdown / 原文切换，以及工具返回内容。未返回的页面、Diff 和截图不会生成占位产物。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (latest != null) item {
            Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("最新回复", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    if (source) SelectionContainer { Text(latest.answer, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 19.sp) }
                    else SafeMarkdown(markdown = latest.answer, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyLarge, isTextSelectable = !latest.isRunning)
                }
            }
        }
        if (outputs.isNotEmpty()) item { Text("工具产出 · ${outputs.size}", style = MaterialTheme.typography.titleSmall) }
        items(outputs.asReversed(), key = { it.id }) { tool ->
            Surface(onClick = { onInspect(tool) }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) { ToolRow(tool) { onInspect(tool) } }
        }
    }
}
