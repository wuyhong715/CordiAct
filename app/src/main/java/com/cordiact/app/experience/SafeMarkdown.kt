package com.cordiact.app.experience

import android.content.Intent
import android.net.Uri
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.cordiact.app.experience.model.MarkdownBlock
import com.cordiact.app.experience.model.splitMarkdownBlocks
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/**
 * Block-hybrid Markdown renderer:
 * - Prose chunks use Markwon (local-only, no HTML/image/network plugins) with subtle hairline styling.
 * - GFM tables render as native horizontally-scrollable hairline cards (fixing collapsed pipe rows).
 * - Fenced code blocks render as native hairline code cards with copy & collapse support.
 */
@Composable
fun SafeMarkdown(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    isTextSelectable: Boolean = true,
) {
    val blocks = remember(markdown) { splitMarkdownBlocks(markdown) }
    if (blocks.isEmpty()) return
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEachIndexed { index, block ->
            key(index, block::class) {
                when (block) {
                    is MarkdownBlock.Prose -> ProseBlock(
                        markdown = block.text,
                        color = color,
                        style = style,
                        isTextSelectable = isTextSelectable,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    is MarkdownBlock.Table -> TableBlockCard(block, isTextSelectable)
                    is MarkdownBlock.Code -> CodeBlockCard(block, isTextSelectable)
                }
            }
        }
    }
}

@Composable
private fun ProseBlock(
    markdown: String,
    color: Color,
    style: TextStyle,
    isTextSelectable: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val codeBg = colors.surfaceContainer.toArgb()
    val codeInk = colors.onSurface.toArgb()
    val quoteBar = colors.outlineVariant.toArgb()
    val ruleColor = colors.outlineVariant.toArgb()

    val renderer = remember(context, codeBg, codeInk, quoteBar) {
        Markwon.builder(context)
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureTheme(builder: MarkwonTheme.Builder) {
                    builder
                        .codeBackgroundColor(codeBg)
                        .codeTextColor(codeInk)
                        .codeTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, context.resources.displayMetrics).toInt())
                        .blockQuoteColor(quoteBar)
                        .blockQuoteWidth(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2.5f, context.resources.displayMetrics).toInt())
                        .thematicBreakColor(ruleColor)
                        .headingBreakHeight(0)
                }

                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.linkResolver { view, link ->
                        val uri = Uri.parse(link)
                        // Never execute intent:, file:, javascript: or custom app actions from output.
                        if (uri.scheme?.lowercase() in setOf("https", "http")) {
                            runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }
                }
            })
            .usePlugin(TablePlugin.create(context))
            .usePlugin(StrikethroughPlugin.create())
            .usePlugin(TaskListPlugin.create(context))
            .build()
    }
    val current by rememberUpdatedState(markdown)
    val streaming by rememberUpdatedState(!isTextSelectable)
    val parsed by produceState<Spanned?>(null, renderer) {
        snapshotFlow { current }.conflate().collect { input ->
            value = withContext(Dispatchers.Default) { renderer.toMarkdown(input) }
            if (streaming) delay(if (input.length > 20_000) 250 else 75)
        }
    }
    val primary = colors.primary.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextView(ctx).apply {
                includeFontPadding = false
                setPadding(0, 0, 0, 0)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
                    hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NONE
                }
            }
        },
        update = { textView ->
            textView.setTextColor(color.toArgb())
            textView.setLinkTextColor(primary)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSize.takeUnless { it == androidx.compose.ui.unit.TextUnit.Unspecified }?.value ?: 15.5f)
            textView.setLineSpacing(0f, 1.28f)
            textView.setTextIsSelectable(isTextSelectable)
            textView.movementMethod = LinkMovementMethod.getInstance()
            parsed?.let {
                if (textView.tag !== it) {
                    renderer.setParsedMarkdown(textView, it)
                    textView.tag = it
                }
            }
        },
    )
}

@Composable
private fun TableBlockCard(table: MarkdownBlock.Table, selectable: Boolean) {
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1200); copied = false } }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("markdown-table"),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(colors.surfaceContainerLow).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "表格 · ${table.headers.size} 列 × ${table.rows.size} 行",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(table.raw)); copied = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text(if (copied) "已复制" else "复制", style = MaterialTheme.typography.labelSmall,
                        color = if (copied) colors.onSurface else colors.onSurfaceVariant)
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant)
            val scroll = rememberScrollState()
            val colWidths = remember(table) {
                table.headers.indices.map { col ->
                    val maxLen = maxOf(
                        table.headers[col].length,
                        table.rows.maxOfOrNull { it.getOrElse(col) { "" }.length } ?: 0,
                    )
                    when {
                        maxLen <= 8 -> 96.dp
                        maxLen <= 18 -> 148.dp
                        maxLen <= 34 -> 210.dp
                        else -> 260.dp
                    }
                }
            }
            val body: @Composable () -> Unit = {
                Column(Modifier.horizontalScroll(scroll)) {
                    Row(Modifier.background(colors.surfaceContainerLow)) {
                        table.headers.forEachIndexed { col, header ->
                            Text(
                                formatInlineCell(header),
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.width(colWidths[col]).padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                    table.rows.forEachIndexed { rowIndex, row ->
                        HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant)
                        Row(Modifier.background(if (rowIndex % 2 == 1) colors.surfaceContainerLow.copy(alpha = .45f) else Color.Transparent)) {
                            row.forEachIndexed { col, cell ->
                                Text(
                                    formatInlineCell(cell),
                                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 19.sp),
                                    color = colors.onSurface,
                                    modifier = Modifier.width(colWidths[col]).padding(horizontal = 12.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (selectable) SelectionContainer { body() } else body()
        }
    }
}

@Composable
private fun CodeBlockCard(block: MarkdownBlock.Code, selectable: Boolean) {
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1200); copied = false } }
    val lines = remember(block.code) { block.code.split('\n') }
    val collapsible = lines.size > 18 && block.closed
    var expanded by remember(block.code.hashCode()) { mutableStateOf(false) }
    val shownCode = remember(block.code, expanded, collapsible) {
        if (collapsible && !expanded) lines.take(18).joinToString("\n") else block.code
    }
    val label = listOfNotNull(block.language?.lowercase(), "${lines.size} 行").joinToString(" · ")

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = colors.surfaceContainerLow,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("markdown-code"),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { clipboard.setText(AnnotatedString(block.code)); copied = true },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    modifier = Modifier.height(28.dp),
                ) {
                    Text(if (copied) "已复制" else "复制", style = MaterialTheme.typography.labelSmall,
                        color = if (copied) colors.onSurface else colors.onSurfaceVariant)
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant)
            val isDiff = block.language.equals("diff", ignoreCase = true)
            val codeBody: @Composable () -> Unit = {
                Box(Modifier.fillMaxWidth().background(colors.surface).horizontalScroll(rememberScrollState()).padding(12.dp)) {
                    if (isDiff) {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            shownCode.split('\n').forEach { diffLine ->
                                val bg = when {
                                    diffLine.startsWith("+") && !diffLine.startsWith("+++") -> Lumen.Ok.copy(alpha = .10f)
                                    diffLine.startsWith("-") && !diffLine.startsWith("---") -> colors.error.copy(alpha = .09f)
                                    else -> Color.Transparent
                                }
                                Text(
                                    diffLine.ifEmpty { " " },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.5.sp,
                                    lineHeight = 19.sp,
                                    color = colors.onSurface,
                                    modifier = Modifier.background(bg).padding(horizontal = 4.dp),
                                )
                            }
                        }
                    } else {
                        Text(
                            shownCode,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.5.sp,
                            lineHeight = 19.sp,
                            color = colors.onSurface,
                        )
                    }
                }
            }
            if (selectable) SelectionContainer { codeBody() } else codeBody()
            if (collapsible) {
                HorizontalDivider(thickness = 0.5.dp, color = colors.outlineVariant)
                TextButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.fillMaxWidth().height(34.dp),
                ) {
                    Text(
                        if (expanded) "收起代码" else "展开其余 ${lines.size - 18} 行",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun formatInlineCell(cell: String): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    val codeBg = colors.surfaceContainer
    return buildAnnotatedString {
        var i = 0
        while (i < cell.length) {
            if (cell[i] == '`') {
                val end = cell.indexOf('`', i + 1)
                if (end > i + 1) {
                    withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, background = codeBg)) {
                        append(" ${cell.substring(i + 1, end)} ")
                    }
                    i = end + 1
                    continue
                }
            }
            if (cell.startsWith("**", i)) {
                val end = cell.indexOf("**", i + 2)
                if (end > i + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(cell.substring(i + 2, end))
                    }
                    i = end + 2
                    continue
                }
            }
            append(cell[i])
            i++
        }
    }
}
