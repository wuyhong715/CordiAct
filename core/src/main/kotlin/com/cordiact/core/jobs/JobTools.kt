package com.cordiact.core.jobs

import com.cordiact.core.tool.InMemoryToolDefinitionRegistry
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolDefinition
import com.cordiact.core.tool.ToolDescriptor
import com.cordiact.core.tool.ToolExecutor
import com.cordiact.core.tool.ToolInputSchema
import com.cordiact.core.tool.ToolParameter
import com.cordiact.core.tool.ToolResult
import com.cordiact.core.tool.ToolValue
import com.cordiact.core.tool.ToolValueType

const val JOBS_PLUGIN_ID = "core.jobs"
const val JOB_LIST_ID = "job_list"
const val JOB_OUTPUT_ID = "job_output"
const val JOB_KILL_ID = "job_kill"
const val JOB_PROBE_ID = "job_probe"

fun jobListDescriptor(): ToolDescriptor = ToolDescriptor(
    id = JOB_LIST_ID,
    name = "job_list",
    description = "List all background jobs running or completed in the current session.",
    inputSchema = ToolInputSchema(parameters = emptyList()),
    ownerPluginId = JOBS_PLUGIN_ID,
    defaultEnabled = true,
    timeoutMs = 5_000L,
    concurrencySafe = true,
)

fun jobOutputDescriptor(): ToolDescriptor = ToolDescriptor(
    id = JOB_OUTPUT_ID,
    name = "job_output",
    description = """
        Read output from a background job.
        If wait=true is passed, waits up to timeout_ms for new output or completion.
        Timing out returns status="running" instead of an error.
    """.trimIndent(),
    inputSchema = ToolInputSchema(
        parameters = listOf(
            ToolParameter(
                name = "job_id",
                type = ToolValueType.STRING,
                required = true,
                description = "The job id returned when starting a background command.",
            ),
            ToolParameter(
                name = "offset",
                type = ToolValueType.INTEGER,
                required = false,
                description = "Character/byte offset to start reading from. Defaults to 0.",
            ),
            ToolParameter(
                name = "limit_chars",
                type = ToolValueType.INTEGER,
                required = false,
                description = "Maximum output length to return. Defaults to 8000.",
            ),
            ToolParameter(
                name = "wait",
                type = ToolValueType.BOOLEAN,
                required = false,
                description = "Whether to wait for completion or additional output.",
            ),
            ToolParameter(
                name = "timeout_ms",
                type = ToolValueType.INTEGER,
                required = false,
                description = "Wait timeout in milliseconds (max 60000). Defaults to 5000 when wait=true.",
            ),
        ),
    ),
    ownerPluginId = JOBS_PLUGIN_ID,
    defaultEnabled = true,
    timeoutMs = 65_000L,
    concurrencySafe = true,
)

fun jobKillDescriptor(): ToolDescriptor = ToolDescriptor(
    id = JOB_KILL_ID,
    name = "job_kill",
    description = "Cancel and stop a background job.",
    inputSchema = ToolInputSchema(
        parameters = listOf(
            ToolParameter(
                name = "job_id",
                type = ToolValueType.STRING,
                required = true,
                description = "The job id to cancel.",
            ),
            ToolParameter(
                name = "reason",
                type = ToolValueType.STRING,
                required = false,
                description = "Cancellation reason.",
            ),
        ),
    ),
    ownerPluginId = JOBS_PLUGIN_ID,
    defaultEnabled = true,
    timeoutMs = 10_000L,
    concurrencySafe = true,
)

fun jobProbeDescriptor(): ToolDescriptor = ToolDescriptor(
    id = JOB_PROBE_ID,
    name = "job_probe",
    description = "Probe the active state of a remote detached job (e.g. background SSH command).",
    inputSchema = ToolInputSchema(
        parameters = listOf(
            ToolParameter(
                name = "job_id",
                type = ToolValueType.STRING,
                required = true,
                description = "The job id to probe.",
            ),
        ),
    ),
    ownerPluginId = JOBS_PLUGIN_ID,
    defaultEnabled = true,
    timeoutMs = 15_000L,
    concurrencySafe = true,
)

fun registerJobTools(registry: InMemoryToolDefinitionRegistry, jobRegistry: JobRegistry) {
    registry.register(
        ToolDefinition(
            jobListDescriptor(),
            ToolExecutor { context, _ ->
                val sessionId = context.sessionId ?: return@ToolExecutor ToolResult(
                    values = mapOf(
                        "ok" to ToolValue.BooleanValue(false),
                        "error" to ToolValue.StringValue("job_list requires a valid session context"),
                    ),
                )
                val jobs = jobRegistry.list(sessionId)
                val items = jobs.map { job ->
                    ToolValue.ObjectValue(
                        mapOf(
                            "id" to ToolValue.StringValue(job.id),
                            "kind" to ToolValue.StringValue(job.kind.name.lowercase()),
                            "state" to ToolValue.StringValue(job.state.name.lowercase()),
                            "label" to ToolValue.StringValue(job.label),
                            "elapsed_ms" to ToolValue.NumberValue(
                                ((job.finishedAtMillis ?: System.currentTimeMillis()) - job.startedAtMillis).toDouble(),
                            ),
                            "progress" to (job.progressLine?.let { ToolValue.StringValue(it) } ?: ToolValue.NullValue),
                            "exit_code" to (job.exitCode?.let { ToolValue.NumberValue(it.toDouble()) } ?: ToolValue.NullValue),
                        ),
                    )
                }
                val summaryText = if (jobs.isEmpty()) {
                    "No background jobs in this session."
                } else {
                    buildString {
                        append("Background jobs (${jobs.size}):\n")
                        jobs.forEach {
                            append("- [${it.id}] ${it.kind.name.lowercase()} [${it.state.name.lowercase()}]: \"${it.label}\"")
                            it.progressLine?.let { p -> append(" (progress: $p)") }
                            append("\n")
                        }
                    }
                }
                ToolResult(
                    values = mapOf(
                        "ok" to ToolValue.BooleanValue(true),
                        "content" to ToolValue.StringValue(summaryText),
                        "jobs" to ToolValue.ArrayValue(items),
                    ),
                )
            },
        ),
    )

    registry.register(
        ToolDefinition(
            jobOutputDescriptor(),
            ToolExecutor { _, arguments ->
                val jobId = (arguments.values["job_id"] as? ToolValue.StringValue)?.value
                    ?: return@ToolExecutor ToolResult(
                        values = mapOf(
                            "ok" to ToolValue.BooleanValue(false),
                            "error" to ToolValue.StringValue("Missing required parameter 'job_id'"),
                        ),
                    )

                val offset = (arguments.values["offset"] as? ToolValue.NumberValue)?.value?.toLong() ?: 0L
                val limit = (arguments.values["limit_chars"] as? ToolValue.NumberValue)?.value?.toInt() ?: 8_000
                val wait = (arguments.values["wait"] as? ToolValue.BooleanValue)?.value ?: false
                val timeoutMs = (arguments.values["timeout_ms"] as? ToolValue.NumberValue)?.value?.toLong() ?: 5_000L

                val startTime = System.currentTimeMillis()
                val safeTimeout = timeoutMs.coerceIn(100L, 60_000L)

                var readResult = jobRegistry.read(jobId, offset, limit)
                    ?: return@ToolExecutor ToolResult(
                        values = mapOf(
                            "ok" to ToolValue.BooleanValue(false),
                            "error" to ToolValue.StringValue("Job not found: $jobId"),
                        ),
                    )

                if (wait && !readResult.snapshot.isTerminal && readResult.text.isEmpty()) {
                    while (System.currentTimeMillis() - startTime < safeTimeout) {
                        Thread.sleep(200)
                        readResult = jobRegistry.read(jobId, offset, limit) ?: break
                        if (readResult.snapshot.isTerminal || readResult.text.isNotEmpty()) {
                            break
                        }
                    }
                }

                val snapshot = readResult.snapshot
                val formatted = buildString {
                    append("Job [${snapshot.id}] state: ${snapshot.state.name.lowercase()}\n")
                    if (!snapshot.progressLine.isNullOrBlank()) {
                        append("Progress: ${snapshot.progressLine}\n")
                    }
                    if (readResult.text.isNotEmpty()) {
                        append("Output:\n${readResult.text}\n")
                    } else {
                        append("(No new output)\n")
                    }
                    append("[next_offset: ${readResult.nextOffset}, eof: ${readResult.eof}]")
                }

                ToolResult(
                    values = mapOf(
                        "ok" to ToolValue.BooleanValue(true),
                        "content" to ToolValue.StringValue(formatted),
                        "state" to ToolValue.StringValue(snapshot.state.name.lowercase()),
                        "text" to ToolValue.StringValue(readResult.text),
                        "next_offset" to ToolValue.NumberValue(readResult.nextOffset.toDouble()),
                        "eof" to ToolValue.BooleanValue(readResult.eof),
                        "untrusted" to ToolValue.BooleanValue(true),
                    ),
                )
            },
        ),
    )

    registry.register(
        ToolDefinition(
            jobKillDescriptor(),
            ToolExecutor { _, arguments ->
                val jobId = (arguments.values["job_id"] as? ToolValue.StringValue)?.value
                    ?: return@ToolExecutor ToolResult(
                        values = mapOf(
                            "ok" to ToolValue.BooleanValue(false),
                            "error" to ToolValue.StringValue("Missing required parameter 'job_id'"),
                        ),
                    )
                val reason = (arguments.values["reason"] as? ToolValue.StringValue)?.value ?: "cancelled by model"
                val snapshot = jobRegistry.kill(jobId, reason)
                    ?: return@ToolExecutor ToolResult(
                        values = mapOf(
                            "ok" to ToolValue.BooleanValue(false),
                            "error" to ToolValue.StringValue("Job not found: $jobId"),
                        ),
                    )
                ToolResult(
                    values = mapOf(
                        "ok" to ToolValue.BooleanValue(true),
                        "content" to ToolValue.StringValue("Job [${snapshot.id}] state is now ${snapshot.state.name.lowercase()}"),
                        "state" to ToolValue.StringValue(snapshot.state.name.lowercase()),
                    ),
                )
            },
        ),
    )

    registry.register(
        ToolDefinition(
            jobProbeDescriptor(),
            ToolExecutor { _, arguments ->
                val jobId = (arguments.values["job_id"] as? ToolValue.StringValue)?.value
                    ?: return@ToolExecutor ToolResult(
                        values = mapOf(
                            "ok" to ToolValue.BooleanValue(false),
                            "error" to ToolValue.StringValue("Missing required parameter 'job_id'"),
                        ),
                    )
                val snapshot = jobRegistry.probe(jobId)
                    ?: return@ToolExecutor ToolResult(
                        values = mapOf(
                            "ok" to ToolValue.BooleanValue(false),
                            "error" to ToolValue.StringValue("Job not found: $jobId"),
                        ),
                    )
                ToolResult(
                    values = mapOf(
                        "ok" to ToolValue.BooleanValue(true),
                        "content" to ToolValue.StringValue("Job [${snapshot.id}] probe result: ${snapshot.state.name.lowercase()} (diagnostic: ${snapshot.diagnostic ?: "none"})"),
                        "state" to ToolValue.StringValue(snapshot.state.name.lowercase()),
                        "diagnostic" to (snapshot.diagnostic?.let { ToolValue.StringValue(it) } ?: ToolValue.NullValue),
                    ),
                )
            },
        ),
    )
}
