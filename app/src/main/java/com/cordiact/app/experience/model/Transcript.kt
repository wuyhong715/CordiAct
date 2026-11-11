package com.cordiact.app.experience.model

/** Immutable presentation data. No Android or provider dependency: reducers are JVM-testable. */
data class TranscriptTurn(
    val id: String,
    val prompt: String,
    val answer: String = "",
    val reasoning: String = "",
    val tools: List<ToolActivity> = emptyList(),
    val phase: RunPhase = RunPhase.COMPLETE,
    val startedAt: Long = 0,
    val finishedAt: Long? = null,
    val error: String? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val attachments: List<TranscriptAttachment> = emptyList(),
) {
    val isRunning: Boolean get() = phase == RunPhase.RUNNING
}

enum class RunPhase { RUNNING, COMPLETE, FAILED, CANCELLED }
enum class ToolPhase { RUNNING, SUCCEEDED, FAILED, INTERRUPTED }
data class ToolActivity(
    val id: String,
    val name: String,
    val arguments: String = "",
    val summary: String? = null,
    val phase: ToolPhase = ToolPhase.RUNNING,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val durationMillis: Long? = null,
)

sealed interface TranscriptEvent {
    data class Answer(val text: String) : TranscriptEvent
    data class Reasoning(val text: String) : TranscriptEvent
    data class ToolStarted(val id: String, val name: String, val arguments: String, val at: Long) : TranscriptEvent
    data class ToolFinished(val id: String, val name: String, val success: Boolean, val summary: String?, val duration: Long, val at: Long) : TranscriptEvent
    data class Finished(val phase: RunPhase, val at: Long, val error: String? = null, val input: Long? = null, val output: Long? = null) : TranscriptEvent
}

/** Terminal states ignore late provider deltas. Tool identity, not tool name, owns updates. */
fun TranscriptTurn.reduce(event: TranscriptEvent): TranscriptTurn {
    if (!isRunning) return this
    return when (event) {
        is TranscriptEvent.Answer -> copy(answer = answer + event.text)
        is TranscriptEvent.Reasoning -> copy(reasoning = reasoning + event.text)
        is TranscriptEvent.ToolStarted -> if (tools.any { it.id == event.id }) this else copy(
            tools = tools + ToolActivity(event.id, event.name, event.arguments, startedAt = event.at),
        )
        is TranscriptEvent.ToolFinished -> {
            val existing = tools.find { it.id == event.id }
                ?: ToolActivity(event.id, event.name, startedAt = event.at - event.duration.coerceAtLeast(0))
            val result = existing.copy(
                phase = if (event.success) ToolPhase.SUCCEEDED else ToolPhase.FAILED,
                summary = event.summary, finishedAt = event.at, durationMillis = event.duration.coerceAtLeast(0),
            )
            copy(tools = if (tools.any { it.id == event.id }) tools.map { if (it.id == event.id) result else it } else tools + result)
        }
        is TranscriptEvent.Finished -> copy(
            phase = event.phase, finishedAt = event.at, error = event.error,
            inputTokens = event.input, outputTokens = event.output,
            tools = tools.map { if (it.phase == ToolPhase.RUNNING) it.copy(phase = ToolPhase.INTERRUPTED, finishedAt = event.at) else it },
        )
    }
}

/** Group persisted messages by user turn, without fabricating runtime details absent from storage. */
fun restoreTranscript(messages: List<StoredMessage>): List<TranscriptTurn> {
    val result = mutableListOf<TranscriptTurn>()
    messages.forEach { message ->
        when (message.role) {
            "USER" -> result += TranscriptTurn(message.id, message.content, startedAt = message.at, attachments = message.attachments)
            "ASSISTANT" -> {
                if (result.isEmpty()) result += TranscriptTurn(message.id, "", answer = message.content, startedAt = message.at)
                else result[result.lastIndex] = result.last().let { it.copy(answer = listOf(it.answer, message.content).filter(String::isNotBlank).joinToString("\n\n")) }
            }
            // TOOL/SYSTEM rows aren't conversation prose. Runtime metadata is not invented.
        }
    }
    return result
}
data class TranscriptAttachment(
    val descriptor: com.cordiact.core.attachment.AttachmentDescriptor,
    val interpretation: com.cordiact.core.attachment.Interpretation,
    val renditionId: String? = null,
)
data class StoredMessage(val id: String, val role: String, val content: String, val at: Long,
    val attachments: List<TranscriptAttachment> = emptyList())

fun durationLabel(millis: Long): String = when {
    millis < 1000 -> "${millis.coerceAtLeast(0)} ms"
    millis < 60_000 -> "${"%.1f".format(java.util.Locale.ROOT, millis / 1000.0)} s"
    else -> "${millis / 60_000}m ${millis / 1000 % 60}s"
}

/** Raw-pixel threshold by contract; programmatic/flick movement never unlocks the list. */
class PinnedScrollPolicy(private val thresholdPixels: Float = 10f) {
    var pinned: Boolean = true
        private set
    private var historyDrag = 0f
    fun userDrag(deltaY: Float): Boolean {
        historyDrag = if (deltaY > 0) historyDrag + deltaY else 0f
        if (historyDrag >= thresholdPixels) pinned = false
        return pinned
    }
    fun pin() { pinned = true; historyDrag = 0f }
}
