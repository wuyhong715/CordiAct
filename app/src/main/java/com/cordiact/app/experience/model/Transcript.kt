package com.cordiact.app.experience.model

import com.cordiact.core.conversation.ToolHistoryProvenance

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
    val process: List<ProcessEntry> = emptyList(),
    val packageContextSources: List<String> = emptyList(),
    val subagentPreset: TranscriptSubagentPreset? = null,
) {
    val isRunning: Boolean get() = phase == RunPhase.RUNNING
}

enum class RunPhase { RUNNING, COMPLETE, TRUNCATED, PARTIAL, FAILED, CANCELLED }
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
    val provenance: ToolHistoryProvenance? = null,
)

/** Observed order within a live turn. Tool completions update their original entry. */
sealed interface ProcessEntry {
    data class Thinking(val start: Int, val end: Int) : ProcessEntry
    data class Tool(val id: String) : ProcessEntry
}

sealed interface TranscriptEvent {
    data class Answer(val text: String) : TranscriptEvent
    data class Reasoning(val text: String) : TranscriptEvent
    data class PackageContext(val sources: List<String>) : TranscriptEvent
    data class ToolStarted(val id: String, val name: String, val arguments: String, val at: Long) : TranscriptEvent
    data class ToolFinished(
        val id: String, val name: String, val success: Boolean, val summary: String?, val duration: Long, val at: Long,
        val provenance: ToolHistoryProvenance? = null,
    ) : TranscriptEvent
    data class Finished(val phase: RunPhase, val at: Long, val error: String? = null, val input: Long? = null, val output: Long? = null) : TranscriptEvent
}

/** Terminal states ignore late provider deltas. Tool identity, not tool name, owns updates. */
fun TranscriptTurn.reduce(event: TranscriptEvent): TranscriptTurn {
    if (!isRunning) return this
    return when (event) {
        is TranscriptEvent.Answer -> copy(answer = answer + event.text)
        is TranscriptEvent.PackageContext -> copy(packageContextSources = event.sources)
        is TranscriptEvent.Reasoning -> {
            if (event.text.isEmpty()) return this
            val end = reasoning.length + event.text.length
            val previous = process.lastOrNull() as? ProcessEntry.Thinking
            val entries = if (previous?.end == reasoning.length) {
                process.dropLast(1) + previous.copy(end = end)
            } else {
                process + ProcessEntry.Thinking(reasoning.length, end)
            }
            copy(reasoning = reasoning + event.text, process = entries)
        }
        is TranscriptEvent.ToolStarted -> if (tools.any { it.id == event.id }) this else copy(
            tools = tools + ToolActivity(event.id, event.name, event.arguments, startedAt = event.at),
            process = process + ProcessEntry.Tool(event.id),
        )
        is TranscriptEvent.ToolFinished -> {
            val existing = tools.find { it.id == event.id }
                ?: ToolActivity(event.id, event.name, startedAt = event.at - event.duration.coerceAtLeast(0))
            val result = existing.copy(
                phase = if (event.success) ToolPhase.SUCCEEDED else ToolPhase.FAILED,
                summary = event.summary, finishedAt = event.at, durationMillis = event.duration.coerceAtLeast(0),
                provenance = event.provenance.takeIf { event.success },
            )
            if (tools.any { it.id == event.id }) {
                copy(tools = tools.map { if (it.id == event.id) result else it })
            } else {
                copy(tools = tools + result, process = process + ProcessEntry.Tool(event.id))
            }
        }
        is TranscriptEvent.Finished -> copy(
            phase = event.phase, finishedAt = event.at, error = event.error,
            inputTokens = event.input, outputTokens = event.output,
            tools = tools.map { if (it.phase == ToolPhase.RUNNING) it.copy(phase = ToolPhase.INTERRUPTED, finishedAt = event.at) else it },
        )
    }
}

/** Group persisted messages by user turn, restoring persisted trace metadata when present without fabricating absent timings. */
fun restoreTranscript(messages: List<StoredMessage>): List<TranscriptTurn> {
    val result = mutableListOf<TranscriptTurn>()
    messages.forEach { message ->
        when (message.role) {
            "USER" -> result += TranscriptTurn(message.id, message.content, startedAt = message.at, attachments = message.attachments,
                packageContextSources = message.packageContextSources, subagentPreset = message.subagentPreset)
            "ASSISTANT" -> {
                val phase = message.phase
                val trace = message.trace
                val traceTools = trace?.tools.orEmpty()
                val traceReasoning = trace?.reasoning.orEmpty()
                val traceProcess = buildList {
                    if (traceReasoning.isNotEmpty()) add(ProcessEntry.Thinking(0, traceReasoning.length))
                    traceTools.forEach { add(ProcessEntry.Tool(it.id)) }
                }
                if (result.isEmpty()) {
                    result += TranscriptTurn(
                        id = message.id,
                        prompt = "",
                        answer = message.content,
                        reasoning = traceReasoning,
                        tools = traceTools,
                        phase = phase,
                        startedAt = trace?.startedAt ?: message.at,
                        finishedAt = trace?.finishedAt,
                        inputTokens = trace?.inputTokens,
                        outputTokens = trace?.outputTokens,
                        process = traceProcess,
                    )
                } else {
                    result[result.lastIndex] = result.last().let { existing ->
                        val mergedReasoning = listOf(existing.reasoning, traceReasoning).filter(String::isNotBlank).joinToString("\n\n")
                        val existingToolIds = existing.tools.mapTo(hashSetOf()) { it.id }
                        val mergedTools = existing.tools + traceTools.filter { it.id !in existingToolIds }
                        val mergedProcess = if (existing.process.isNotEmpty()) {
                            existing.process + traceTools.filter { it.id !in existingToolIds }.map { ProcessEntry.Tool(it.id) }
                        } else {
                            buildList {
                                if (mergedReasoning.isNotEmpty()) add(ProcessEntry.Thinking(0, mergedReasoning.length))
                                mergedTools.forEach { add(ProcessEntry.Tool(it.id)) }
                            }
                        }
                        existing.copy(
                            phase = phase,
                            answer = listOf(existing.answer, message.content).filter(String::isNotBlank).joinToString("\n\n"),
                            reasoning = mergedReasoning,
                            tools = mergedTools,
                            startedAt = trace?.startedAt ?: existing.startedAt,
                            finishedAt = trace?.finishedAt ?: existing.finishedAt,
                            inputTokens = trace?.inputTokens ?: existing.inputTokens,
                            outputTokens = trace?.outputTokens ?: existing.outputTokens,
                            process = mergedProcess,
                        )
                    }
                }
            }
            // TOOL/SYSTEM rows aren't conversation prose. Runtime metadata is only restored from persisted trace.
        }
    }
    return result
}
data class StoredTurnTrace(
    val startedAt: Long? = null,
    val finishedAt: Long? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val reasoning: String? = null,
    val tools: List<ToolActivity> = emptyList(),
)
data class TranscriptAttachment(
    val descriptor: com.cordiact.core.attachment.AttachmentDescriptor,
    val interpretation: com.cordiact.core.attachment.Interpretation,
    val renditionId: String? = null,
)
data class StoredMessage(
    val id: String,
    val role: String,
    val content: String,
    val at: Long,
    val attachments: List<TranscriptAttachment> = emptyList(),
    val phase: RunPhase = RunPhase.COMPLETE,
    val trace: StoredTurnTrace? = null,
    val packageContextSources: List<String> = emptyList(),
    val subagentPreset: TranscriptSubagentPreset? = null,
)

@ConsistentCopyVisibility
data class TranscriptSubagentPreset private constructor(val id: String, val source: String) {
    companion object {
        private val presetId = Regex("([a-z][a-z0-9-]{1,23})\\.[a-z][a-z0-9-]{1,23}")
        private val version = Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")

        fun parse(id: String?, source: String?): TranscriptSubagentPreset? {
            if (id == null || source == null) return null
            val owner = presetId.matchEntire(id)?.groupValues?.get(1) ?: return null
            val release = source.substringAfter('@', "")
            return if (source == "$owner@$release" && version.matches(release)) TranscriptSubagentPreset(id, source) else null
        }
    }
}

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
