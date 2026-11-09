package com.cordiact.core.tool

import com.cordiact.core.action.ApprovalGateway
import com.cordiact.core.action.ApprovalRequest
import java.security.MessageDigest
import java.util.UUID

/** Which execution backend receives the operation. */
enum class ToolExecutionBackend {
    PRIVILEGED_SHELL,
    PRIVILEGED_ROOT,
    SSH,
    TERMUX,
}

/** Privilege the action explicitly requests from the backend. */
enum class ToolExecutionPrivilege {
    /** No shell privilege (for example a Termux command running as the Termux app). */
    STANDARD,

    /** Shizuku/ADB shell (uid 2000). Never silently upgraded to root. */
    SHELL,

    /** Root (uid 0). Must be requested explicitly and re-checked at the execution point. */
    ROOT,
}

/** The kind of payload an action accepts. */
enum class ToolExecutionInputKind {
    /** An arbitrary shell command. Always requires human approval. */
    COMMAND,

    /** Arbitrary bytes typed into an existing interactive session. Always requires human approval. */
    INTERACTIVE_INPUT,

    /** File content sent to a remote destination (outbound data transfer). */
    FILE_CONTENT,

    /**
     * A structurally bounded read-only operation whose inputs are fully
     * understood by the tool. Only this kind may opt into auto-approval; it is
     * intentionally unused by the current shell/SSH/Termux tools.
     */
    STRUCTURED_READ_ONLY,
}

/** Data classes that leave the device or reach a shell. Shown verbatim in the approval card. */
enum class ToolDataClass {
    SHELL_COMMAND,
    INTERACTIVE_INPUT,
    FILE_CONTENT,
    REMOTE_TARGET,
}

/**
 * Declarative metadata for one executable action of a tool.
 *
 * Every tool that can reach a shell, an interactive session or a remote host
 * declares one of these per action. The dispatcher uses this metadata instead
 * of a second hand-written tool-name list, so a newly registered execution
 * tool cannot silently skip authorization: without a matching action the
 * invocation is denied.
 */
data class ToolExecutionAction(
    /** Stable action id, for example `exec`, `send`, `upload`. */
    val action: String,
    val backend: ToolExecutionBackend,
    val inputKind: ToolExecutionInputKind,
    /** Argument carrying the command/input/file path. */
    val inputParameter: String,
    /** Alternative payload arguments for tools that accept one of several encodings. */
    val alternativeInputParameters: List<String> = emptyList(),
    /** Extra argument (for example SSH `stdin`) that is part of the approved operation. */
    val supplementalInputParameters: List<String> = emptyList(),
    /** Whether the raw input is shown in the approval card (large payloads set this false). */
    val showInputInApproval: Boolean = true,
    /** Argument used to select this action when a tool multiplexes operations. */
    val actionParameter: String? = null,
    val actionValue: String? = null,
    /** Argument carrying the hostname (direct hosts). */
    val hostParameter: String? = null,
    /** Argument carrying the port (direct hosts). */
    val portParameter: String? = null,
    /** Argument carrying a saved-host key; resolved through [resolveTarget] when possible. */
    val savedHostParameter: String? = null,
    /** Resolves the actual destination shown to the approver (host:port, never credentials). */
    val resolveTarget: ((ToolArguments) -> String?)? = null,
    val cwdParameter: String? = null,
    val sessionParameter: String? = null,
    val privilege: ToolExecutionPrivilege = ToolExecutionPrivilege.STANDARD,
    val dataClasses: Set<ToolDataClass> = setOf(ToolDataClass.SHELL_COMMAND),
    /**
     * Only a fixed, parameter-bounded structured operation may opt in to
     * auto-approval. Arbitrary command/input actions must leave this false;
     * the policy additionally refuses to auto-approve COMMAND/INTERACTIVE_INPUT.
     */
    val autoApprovable: Boolean = false,
)

data class ToolExecutionMetadata(val actions: List<ToolExecutionAction>)

/** Registration-time guard: these capabilities imply an execution path. */
val TOOL_EXECUTION_CAPABILITIES: Set<String> = setOf(
    com.cordiact.core.automation.AutomationCapabilities.PRIV_SHELL,
    com.cordiact.core.automation.AutomationCapabilities.PRIV_SHELL_ADVANCED,
    com.cordiact.core.automation.AutomationCapabilities.PRIV_ROOT,
    com.cordiact.core.automation.AutomationCapabilities.TERMUX_EXEC,
    com.cordiact.core.automation.AutomationCapabilities.TERMUX_SESSION,
)

/** Parameter names that are treated as an execution payload for registration validation. */
val TOOL_EXECUTION_PARAMETER_NAMES: Set<String> = setOf("command", "input", "stdin")

/** A normalized, immutable description of one concrete execution request. */
data class ToolExecutionRequest(
    val toolId: String,
    val action: String,
    val backend: ToolExecutionBackend,
    val inputKind: ToolExecutionInputKind,
    val input: String,
    val inputParameterName: String,
    val showInputInApproval: Boolean,
    val supplementalInputs: Map<String, String>,
    val target: String?,
    val cwd: String?,
    val session: String?,
    val privilege: ToolExecutionPrivilege,
    val dataClasses: Set<ToolDataClass>,
    val autoApprovable: Boolean,
    /** Digest over every argument value and the resolved action; binds approval to exact parameters. */
    val argumentsDigest: String,
) {
    val requiresHumanApproval: Boolean
        get() = inputKind != ToolExecutionInputKind.STRUCTURED_READ_ONLY || !autoApprovable
}

sealed interface ToolExecutionAuthorization {
    data object Allowed : ToolExecutionAuthorization
    data class Approved(val request: ToolExecutionRequest) : ToolExecutionAuthorization
    data class Denied(val reason: String) : ToolExecutionAuthorization
}

/**
 * Central authorization policy for executable tools.
 *
 * It deliberately does not trust [com.cordiact.core.automation.ShellCommandPolicy]
 * as a security boundary: that classifier is a hint for the approval card only.
 * Every arbitrary shell command, interactive input or outbound file transfer is
 * sent to the human approval gateway. Missing gateway, missing parameters,
 * denial, timeout and cancellation all result in a denial (fail closed).
 */
class ToolExecutionAuthorizationPolicy(
    private val approvalGateway: ApprovalGateway?,
    private val approvalTimeoutMillis: Long = 60_000L,
) {

    fun authorize(descriptor: ToolDescriptor, arguments: ToolArguments): ToolExecutionAuthorization {
        val metadata = descriptor.execution ?: return ToolExecutionAuthorization.Allowed
        val action = resolveAction(metadata, arguments)
        if (action == null) {
            // A tool may expose non-execution actions (session list/read/kill)
            // alongside an execution action. If no declared action matches but
            // the caller did not supply any declared execution payload, this is
            // not a shell/remote execution and can pass. Supplying a payload
            // without a matching action is denied below.
            val payloadNames = metadata.actions.flatMap { candidate ->
                listOfNotNull(candidate.inputParameter) + candidate.alternativeInputParameters
            }
            val suppliedPayload = payloadNames.any { name -> arguments.string(name)?.isNotBlank() == true }
            return if (suppliedPayload) {
                ToolExecutionAuthorization.Denied(
                    "No declared execution action matches the arguments for ${descriptor.id}",
                )
            } else {
                ToolExecutionAuthorization.Allowed
            }
        }
        val request = buildRequest(descriptor, action, arguments)
            ?: return ToolExecutionAuthorization.Denied(
                "Missing execution input '${action.inputParameter}' for ${descriptor.id}",
            )

        if (!request.requiresHumanApproval) return ToolExecutionAuthorization.Allowed

        val gateway = approvalGateway
            ?: return ToolExecutionAuthorization.Denied("Approval gateway is unavailable; execution refused")

        val decision = try {
            gateway.requestApproval(
                ApprovalRequest(
                    id = "tool-exec:${UUID.randomUUID()}",
                    toolName = descriptor.id,
                    summary = approvalSummary(request),
                    details = approvalDetails(request),
                    riskLevel = when (request.privilege) {
                        ToolExecutionPrivilege.ROOT -> ApprovalRequest.RiskLevel.CRITICAL
                        else -> ApprovalRequest.RiskLevel.HIGH
                    },
                ),
                approvalTimeoutMillis,
            )
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
            return ToolExecutionAuthorization.Denied("Approval request failed: ${t.message ?: t::class.simpleName}")
        }

        if (!decision.approved) {
            return ToolExecutionAuthorization.Denied(
                "Operation was rejected by user: ${decision.reason ?: "Confirmation denied"}",
            )
        }

        // Recompute the digest after the (potentially long) wait. A caller that
        // mutates the argument map while the approval card is open cannot reuse
        // the approval for different parameters.
        val after = buildRequest(descriptor, action, arguments)
            ?: return ToolExecutionAuthorization.Denied("Execution arguments disappeared during approval")
        if (after.argumentsDigest != request.argumentsDigest) {
            return ToolExecutionAuthorization.Denied("Execution arguments changed during approval")
        }
        return ToolExecutionAuthorization.Approved(after)
    }

    private fun resolveAction(metadata: ToolExecutionMetadata, arguments: ToolArguments): ToolExecutionAction? {
        val candidates = metadata.actions.filter { action ->
            action.actionParameter == null || arguments.string(action.actionParameter) == action.actionValue
        }
        return when (candidates.size) {
            1 -> candidates.single()
            0 -> null
            else -> candidates.firstOrNull { it.actionParameter == null }
        }
    }

    private fun buildRequest(
        descriptor: ToolDescriptor,
        action: ToolExecutionAction,
        arguments: ToolArguments,
    ): ToolExecutionRequest? {
        val inputEntry = (listOf(action.inputParameter) + action.alternativeInputParameters)
            .firstNotNullOfOrNull { name -> arguments.string(name)?.takeIf { it.isNotBlank() }?.let { name to it } }
            ?: return null
        val (inputParameterName, input) = inputEntry
        val supplemental = action.supplementalInputParameters.mapNotNull { name ->
            arguments.string(name)?.let { name to it }
        }.toMap()
        val target = action.resolveTarget?.invoke(arguments)
            ?: action.hostParameter?.let { name ->
                arguments.string(name)?.let { host ->
                    val port = action.portParameter?.let { arguments.string(it) }
                    if (port.isNullOrBlank()) host else "$host:$port"
                }
            }
            ?: action.savedHostParameter?.let { name ->
                arguments.string(name)?.let { "saved:$it" }
            }
        val cwd = action.cwdParameter?.let { arguments.string(it) }
        val session = action.sessionParameter?.let { arguments.string(it) }
        return ToolExecutionRequest(
            toolId = descriptor.id,
            action = action.action,
            backend = action.backend,
            inputKind = action.inputKind,
            input = input,
            inputParameterName = inputParameterName,
            showInputInApproval = action.showInputInApproval,
            supplementalInputs = supplemental,
            target = target,
            cwd = cwd,
            session = session,
            privilege = action.privilege,
            dataClasses = action.dataClasses,
            autoApprovable = action.autoApprovable,
            argumentsDigest = argumentsDigest(descriptor.id, action, arguments, target, cwd, session),
        )
    }

    private fun approvalSummary(request: ToolExecutionRequest): String = buildString {
        append("执行授权：")
        append(
            when (request.backend) {
                ToolExecutionBackend.PRIVILEGED_SHELL -> "ADB/Shizuku shell"
                ToolExecutionBackend.PRIVILEGED_ROOT -> "ROOT shell"
                ToolExecutionBackend.SSH -> "SSH 远程主机"
                ToolExecutionBackend.TERMUX -> "Termux 会话"
            },
        )
        request.target?.let { append(" ").append(it) }
        append("（").append(request.toolId).append("/").append(request.action).append("）")
    }

    private fun approvalDetails(request: ToolExecutionRequest): Map<String, String> = buildMap {
        put("backend", request.backend.name)
        put("action", request.action)
        put("privilege", request.privilege.name)
        put("data_classes", request.dataClasses.joinToString(",") { it.name })
        request.target?.let { put("destination", it) }
        request.cwd?.let { put("cwd", it) }
        request.session?.let { put("session", it) }
        if (request.showInputInApproval) {
            put(if (request.inputKind == ToolExecutionInputKind.INTERACTIVE_INPUT) "input" else "command", request.input)
        }
        request.supplementalInputs.forEach { (name, value) -> put(name, value) }
        put("input_parameter", request.inputParameterName)
        if (!request.showInputInApproval) {
            put("payload_parameter", request.inputParameterName)
            put("payload_chars", request.input.length.toString())
            put("payload_sha256", sha256(request.input))
        }
        put("arguments_digest", request.argumentsDigest)
    }

    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun argumentsDigest(
        toolId: String,
        action: ToolExecutionAction,
        arguments: ToolArguments,
        target: String?,
        cwd: String?,
        session: String?,
    ): String {
        val canonical = buildString {
            append(toolId).append('\u0000')
            append(action.action).append('\u0000')
            append(action.backend.name).append('\u0000')
            append(action.privilege.name).append('\u0000')
            append(target ?: "").append('\u0000')
            append(cwd ?: "").append('\u0000')
            append(session ?: "").append('\u0000')
            arguments.values.toSortedMap().forEach { (name, value) ->
                append(name).append('=').append(renderValue(value)).append('\u0000')
            }
        }
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun renderValue(value: ToolValue): String = when (value) {
        is ToolValue.StringValue -> "s:${value.value}"
        is ToolValue.NumberValue -> "n:${value.value}"
        is ToolValue.BooleanValue -> "b:${value.value}"
        ToolValue.NullValue -> "null"
        is ToolValue.ArrayValue -> value.values.joinToString(prefix = "[", postfix = "]") { renderValue(it) }
        is ToolValue.ObjectValue -> value.values.toSortedMap().entries.joinToString(prefix = "{", postfix = "}") {
            "${it.key}=${renderValue(it.value)}"
        }
    }
}

private fun ToolArguments.string(name: String): String? = when (val value = values[name]) {
    is ToolValue.StringValue -> value.value
    is ToolValue.NumberValue -> if (value.value % 1.0 == 0.0) value.value.toLong().toString() else value.value.toString()
    is ToolValue.BooleanValue -> value.value.toString()
    else -> null
}
