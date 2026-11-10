package com.cordiact.core.security

import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolDefinition
import com.cordiact.core.tool.ToolDescriptor
import com.cordiact.core.tool.ToolExecutor
import com.cordiact.core.tool.ToolInputSchema
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.tool.ToolParameter
import com.cordiact.core.tool.ToolResult
import com.cordiact.core.tool.ToolValue
import com.cordiact.core.tool.ToolValueType
import com.cordiact.core.vault.SecurityContextResolver

object SecurityAuditToolIds {
    const val QUERY_CURRENT_RUN = "security_audit_query_current_run"
    const val EXPLAIN_DECISION = "security_explain_decision"
}

/**
 * Read-only audit query surface. These tools expose sanitized event summaries for the current
 * Workspace/run only. They cannot mutate policy, approve anything or enumerate another session.
 */
class SecurityAuditToolset(
    private val audit: AuditLog,
    private val contextResolver: SecurityContextResolver,
) {
    fun definitions(): List<ToolDefinition> = listOf(
        definition(
            id = SecurityAuditToolIds.QUERY_CURRENT_RUN,
            name = "Query current run audit",
            description = "Return sanitized security audit events for the current run.",
            parameters = listOf(
                ToolParameter("limit", ToolValueType.INTEGER, required = false, minimum = 1.0, maximum = 200.0),
            ),
        ) { context, args ->
            val security = contextResolver.resolve(context) ?: return@definition failure("SECURITY_CONTEXT_MISSING")
            val limit = (args.values["limit"] as? ToolValue.NumberValue)?.value?.toInt()?.coerceIn(1, 200) ?: 100
            val events = audit.query(
                AuditFilter(workspaceId = security.workspaceId, limit = limit),
            ).filter { it.draft.runId == security.runId }
            success("events" to ToolValue.ArrayValue(events.map(::eventValue)))
        },
        definition(
            id = SecurityAuditToolIds.EXPLAIN_DECISION,
            name = "Explain a security decision",
            description = "Return the sanitized audit timeline for one correlation id in the current Workspace.",
            parameters = listOf(
                ToolParameter("correlationId", ToolValueType.STRING, required = true, maxLength = 128),
            ),
        ) { context, args ->
            val security = contextResolver.resolve(context) ?: return@definition failure("SECURITY_CONTEXT_MISSING")
            val correlationId = (args.values["correlationId"] as? ToolValue.StringValue)?.value
                ?: return@definition failure("MISSING_CORRELATION_ID")
            val events = audit.query(
                AuditFilter(workspaceId = security.workspaceId, correlationId = correlationId, limit = 200),
            )
            success("events" to ToolValue.ArrayValue(events.map(::eventValue)))
        },
    )

    fun register(registry: com.cordiact.core.tool.ToolDefinitionRegistry) {
        definitions().forEach(registry::register)
    }

    private fun eventValue(event: AuditEvent): ToolValue.ObjectValue = ToolValue.ObjectValue(
        mapOf(
            "eventId" to ToolValue.StringValue(event.eventId),
            "sequence" to ToolValue.NumberValue(event.sequence.toDouble()),
            "occurredAt" to ToolValue.StringValue(event.occurredAt.toString()),
            "category" to ToolValue.StringValue(event.draft.category.name),
            "phase" to ToolValue.StringValue(event.draft.phase.name),
            "outcome" to ToolValue.StringValue(event.draft.outcome.name),
            "reasonCode" to ToolValue.StringValue(event.draft.reasonCode),
            "approvalId" to ToolValue.StringValue(event.draft.approvalId ?: ""),
            "approvalAuthority" to ToolValue.StringValue(event.draft.approvalAuthority?.name ?: ""),
            "target" to ToolValue.StringValue(event.draft.targetDescriptorSanitized ?: ""),
            "receiver" to ToolValue.StringValue(event.draft.receiverDescriptorSanitized ?: ""),
        ),
    )

    private fun definition(
        id: String,
        name: String,
        description: String,
        parameters: List<ToolParameter>,
        handler: (ToolInvocationContext, ToolArguments) -> ToolResult,
    ): ToolDefinition = ToolDefinition(
        descriptor = ToolDescriptor(
            id = id,
            name = name,
            description = description,
            inputSchema = ToolInputSchema(parameters = parameters),
            ownerPluginId = SECURITY_AUDIT_PLUGIN_ID,
            defaultEnabled = true,
            timeoutMs = 30_000,
        ),
        executor = ToolExecutor(handler),
    )

    private fun success(vararg values: Pair<String, ToolValue>): ToolResult =
        ToolResult(values = mapOf("ok" to ToolValue.BooleanValue(true)) + values)

    private fun failure(code: String): ToolResult = ToolResult(
        values = mapOf(
            "ok" to ToolValue.BooleanValue(false),
            "error" to ToolValue.StringValue(code),
        ),
    )
}
