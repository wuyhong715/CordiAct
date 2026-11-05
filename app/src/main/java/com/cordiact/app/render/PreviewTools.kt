package com.cordiact.app.render

import android.os.Handler
import android.os.Looper
import com.cordiact.core.render.PREVIEW_MAX_CSS_CHARS
import com.cordiact.core.render.PREVIEW_MAX_HTML_CHARS
import com.cordiact.core.render.PREVIEW_MAX_TITLE_CHARS
import com.cordiact.core.render.PREVIEW_PUSH_TOOL_ID
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolDefinition
import com.cordiact.core.tool.ToolDefinitionRegistry
import com.cordiact.core.tool.ToolDescriptor
import com.cordiact.core.tool.ToolExecutor
import com.cordiact.core.tool.ToolInputSchema
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.tool.ToolParameter
import com.cordiact.core.tool.ToolResult
import com.cordiact.core.tool.ToolValue
import com.cordiact.core.tool.ToolValueType
import com.cordiact.core.workspace.WORKSPACE_MANAGER_PLUGIN_ID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `preview.push` — the agent's half of the preview window.
 *
 * Owned by the workspace manager rather than a plugin of its own, and always published: a
 * preview is a session-surface capability, not an integration, so it must never be missing
 * because a toggle is off. The cost of it being always available is bounded by the tool doing
 * nothing except appending to a capped queue.
 */
fun previewPushToolDescriptor(): ToolDescriptor = ToolDescriptor(
    id = PREVIEW_PUSH_TOOL_ID,
    name = "preview_push",
    description = """
        Put a self-contained HTML document (optional CSS) into the user's preview window, so the
        user can look at the UI you just wrote or changed.

        This does NOT render anything: the window stays empty until the user opens it and presses
        渲染. Pushing is therefore cheap, but it spends the user's attention — push one item per
        meaningful change, not one per tweak, and title it so the user recognises the screen
        ("登录页 v2", "首页 · 空状态").

        The document is loaded with scripts enabled and no network access; external URLs will not
        load. Inline styles and fonts that ship with the device are fine.
    """.trimIndent(),
    inputSchema = ToolInputSchema(
        parameters = listOf(
            ToolParameter(
                name = "title",
                type = ToolValueType.STRING,
                required = true,
                description = "Short label shown in the preview list.",
                maxLength = PREVIEW_MAX_TITLE_CHARS,
            ),
            ToolParameter(
                name = "html",
                type = ToolValueType.STRING,
                required = true,
                description = "HTML fragment or full document. Self-contained.",
                maxLength = PREVIEW_MAX_HTML_CHARS,
            ),
            ToolParameter(
                name = "css",
                type = ToolValueType.STRING,
                required = false,
                description = "Stylesheet for the document, injected into <head>.",
                maxLength = PREVIEW_MAX_CSS_CHARS,
            ),
            ToolParameter(
                name = "width_dp",
                type = ToolValueType.INTEGER,
                required = false,
                description = "Viewport width in dp. 360 phone, 390 modern phone, 768 tablet.",
                minimum = 200.0,
                maximum = 2000.0,
            ),
            ToolParameter(
                name = "height_dp",
                type = ToolValueType.INTEGER,
                required = false,
                description = "Viewport height in dp.",
                minimum = 200.0,
                maximum = 3000.0,
            ),
        ),
    ),
    ownerPluginId = WORKSPACE_MANAGER_PLUGIN_ID,
    defaultEnabled = true,
    // The queue is in-process state; a 2s budget is generous for appending one item.
    timeoutMs = 2_000L,
    concurrencySafe = false,
)

fun registerPreviewTools(registry: ToolDefinitionRegistry) {
    registry.register(
        ToolDefinition(
            previewPushToolDescriptor(),
            ToolExecutor { context, arguments -> executePush(context, arguments) },
        ),
    )
}

private fun executePush(
    context: ToolInvocationContext,
    arguments: ToolArguments,
): ToolResult {
    val html = arguments.string("html")
    if (html.isBlank()) {
        return failure("html must not be empty; send the markup you want the user to see")
    }
    val css = arguments.string("css").orEmpty()
    val title = arguments.string("title").orEmpty().trim().take(PREVIEW_MAX_TITLE_CHARS)
    val viewport = PreviewViewport(
        widthDp = arguments.number("width_dp")?.toInt() ?: PreviewViewport.Phone.widthDp,
        heightDp = arguments.number("height_dp")?.toInt() ?: PreviewViewport.Phone.heightDp,
    ).clamped()

    val accepted = html.take(PREVIEW_MAX_HTML_CHARS)
    val acceptedCss = css.take(PREVIEW_MAX_CSS_CHARS)
    val truncated = accepted.length < html.length || acceptedCss.length < css.length

    // Snapshot state belongs to the UI thread; a tool runs wherever the turn runs.
    val unseen = onMainThread {
        PreviewInbox.push(
            sessionId = context.sessionId,
            title = title,
            html = accepted,
            css = acceptedCss,
            viewport = viewport,
            origin = PreviewOrigin.AGENT,
        )
        PreviewInbox.unseenCount(context.sessionId)
    } ?: return failure("the preview queue did not accept the item in time")

    return ToolResult(
        values = linkedMapOf(
            "ok" to ToolValue.BooleanValue(true),
            "title" to ToolValue.StringValue(title.ifBlank { "未命名预览" }),
            "viewport" to ToolValue.StringValue("${viewport.widthDp}×${viewport.heightDp}"),
            "unseen" to ToolValue.NumberValue(unseen.toDouble()),
            "truncated" to ToolValue.BooleanValue(truncated),
            "note" to ToolValue.StringValue(
                "Queued, not rendered. The user opens the preview window to render it. " +
                    "Do not push the same document twice.",
            ),
        ),
        metadata = context.sessionId
            ?.let { sessionId -> mapOf("sessionId" to ToolValue.StringValue(sessionId)) }
            ?: emptyMap(),
    )
}

private fun failure(message: String): ToolResult = ToolResult(
    values = linkedMapOf(
        "ok" to ToolValue.BooleanValue(false),
        "error" to ToolValue.StringValue(message),
    ),
)

/** Runs [block] on the main thread and returns null if it could not be scheduled in time. */
private fun <T> onMainThread(block: () -> T): T? {
    if (Looper.myLooper() == Looper.getMainLooper()) return block()
    val latch = CountDownLatch(1)
    var result: T? = null
    Handler(Looper.getMainLooper()).post {
        result = block()
        latch.countDown()
    }
    return if (latch.await(2, TimeUnit.SECONDS)) result else null
}

private fun ToolArguments.string(name: String): String? =
    (values[name] as? ToolValue.StringValue)?.value

private fun ToolArguments.number(name: String): Double? =
    (values[name] as? ToolValue.NumberValue)?.value
