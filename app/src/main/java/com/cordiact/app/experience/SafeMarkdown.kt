package com.cordiact.app.experience

import android.content.Intent
import android.net.Uri
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.Markwon
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tasklist.TaskListPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/**
 * Markwon core + local-only extensions. No HTML, image loader, script or network plugin.
 * Core handles links on explicit user tap; parsing runs off main, conflated not debounced.
 */
@Composable
fun SafeMarkdown(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    isTextSelectable: Boolean = true,
) {
    val context = LocalContext.current
    val renderer = remember(context) {
        Markwon.builder(context)
            .usePlugin(object : AbstractMarkwonPlugin() {
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
            // Do not restart on each token: sustained streams still get frames.
            if (streaming) delay(40)
        }
    }
    val primary = MaterialTheme.colorScheme.primary.toArgb()
    AndroidView(
        modifier = modifier,
        factory = { ctx -> TextView(ctx).apply {
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
        } },
        update = { textView ->
            textView.setTextColor(color.toArgb())
            textView.setLinkTextColor(primary)
            textView.setTextSize(TypedValue.COMPLEX_UNIT_SP, style.fontSize.takeUnless { it == androidx.compose.ui.unit.TextUnit.Unspecified }?.value ?: 16f)
            textView.setLineSpacing(0f, 1.25f)
            textView.setTextIsSelectable(isTextSelectable)
            textView.movementMethod = LinkMovementMethod.getInstance()
            parsed?.let { renderer.setParsedMarkdown(textView, it) }
        },
    )
}
