package com.cordiact.core.jobs

/** Orthogonal to durable task lifecycle. Entries are process-local and never replay work. */
enum class ExecutionPhase { PREPARING, MODEL, TOOL, WAITING_USER, STOPPING }

data class ExecutionPresence(
    val id: String,
    val phase: ExecutionPhase,
    /** Eligibility must be established by the Android caller, not inferred from RUNNING. */
    val foregroundEligible: Boolean = false,
)

data class ExecutionPresenceSnapshot(val entries: List<ExecutionPresence>) {
    val activeCount: Int get() = entries.size
    val needsForeground: Boolean get() = entries.any { it.foregroundEligible }
    val summary: String get() = entries.groupingBy { it.phase }.eachCount().entries.joinToString(" · ") {
        "${when (it.key) {
            ExecutionPhase.PREPARING -> "准备中"
            ExecutionPhase.MODEL -> "模型执行中"
            ExecutionPhase.TOOL -> "工具执行中"
            ExecutionPhase.WAITING_USER -> "等待确认"
            ExecutionPhase.STOPPING -> "正在停止"
        }} ${it.value}"
    }
}

/** Source-owned updates prevent one producer from clearing another producer's execution. */
class ExecutionPresenceRegistry {
    private val sources = linkedMapOf<String, List<ExecutionPresence>>()
    @Synchronized fun replace(source: String, entries: List<ExecutionPresence>): ExecutionPresenceSnapshot {
        require(source.isNotBlank())
        require(entries.map { it.id }.distinct().size == entries.size) { "Duplicate execution id" }
        if (entries.isEmpty()) sources.remove(source) else sources[source] = entries.toList()
        return snapshot()
    }
    @Synchronized fun snapshot() = ExecutionPresenceSnapshot(sources.values.flatten())
}
