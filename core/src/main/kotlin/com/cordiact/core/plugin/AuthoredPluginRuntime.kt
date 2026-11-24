package com.cordiact.core.plugin

import com.cordiact.core.action.ApprovalDecision
import com.cordiact.core.action.ApprovalGateway
import com.cordiact.core.action.ApprovalOwner
import com.cordiact.core.action.ApprovalRequest
import com.cordiact.core.file.WorkspaceFileService
import com.cordiact.core.tool.ToolDefinition
import com.cordiact.core.tool.ToolDescriptor
import com.cordiact.core.tool.ToolInputSchema
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.tool.ToolParameter
import com.cordiact.core.tool.ToolResult
import com.cordiact.core.tool.ToolValue
import com.cordiact.core.tool.ToolValueType
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID

const val AUTHORED_PLUGIN_HOST_ID = "core.authored-plugins"
const val AUTHORED_PLUGIN_TOOL_ID = "workspace.plugins.author"

fun authoredPluginHostSpec() = PluginSpec(AUTHORED_PLUGIN_HOST_ID, "1.0.0", PluginTarget.ANDROID_NATIVE)

/** Names reserved for host-generated pure-text executors, not user-supplied implementations. */
object AuthoredPluginNames {
    private val ownerPattern = Regex("authored\\.[a-z][a-z0-9-]{1,23}")
    private val toolNamePattern = Regex("[a-z][a-z0-9-]{1,23}")
    fun owner(pluginId: String): String = "authored.$pluginId"
    fun tool(pluginId: String, toolName: String): String = "extension.${owner(pluginId)}.$toolName"
    fun isOwner(owner: String): Boolean = ownerPattern.matches(owner)
    fun isPureTool(toolId: String, owner: String): Boolean = isOwner(owner) &&
        toolId.startsWith("extension.$owner.") && toolNamePattern.matches(toolId.removePrefix("extension.$owner."))

    /** Legacy name for the same ID/owner check; it does not distinguish the two tool kinds. */
    fun isStaticTool(toolId: String, owner: String): Boolean = isPureTool(toolId, owner)
}

/** A receipt for one exact source version; generated tool IDs do not change within that version. */
data class AuthoredPluginReceipt(val id: String, val sha256: String, val toolIds: List<String>)

class AuthoredPluginException(val code: Code, message: String) : IllegalStateException(message) {
    enum class Code {
        CALLER_UNAVAILABLE, VERSION_CHANGED, NOT_ACTIVE, ALREADY_ACTIVE, BUSY, QUOTA, DENIED, ACTIVATION_FAILED, CLEANUP_FAILED,
    }
}

/**
 * Foreground-only trial host. Approval activates frozen source for a single live Session and never
 * restores it after process restart. Drafts remain in the workspace; no code runs to parse them.
 * [mayManage] must use live host state, not model JSON or a remembered selection.
 */
class AuthoredPluginRuntime(
    private val files: WorkspaceFileService,
    private val approval: ApprovalGateway,
    private val kernel: PluginKernel,
    private val tools: PluginToolContributions,
    private val mayManage: (ToolInvocationContext) -> Boolean,
) {
    private data class Caller(val workspaceId: String, val sessionId: String) {
        fun instance(pluginId: String) = PluginInstanceId(AuthoredPluginNames.owner(pluginId), workspaceId, sessionId)
        fun approvalOwner() = ApprovalOwner(workspaceId, sessionId)
    }

    private class PendingTicket(val caller: Caller) {
        var deactivating: AuthoredPluginReceipt? = null
        var approvalId: String? = null
        @Volatile var live = true
    }

    private val lock = Any()
    private val active = linkedMapOf<Caller, MutableMap<String, AuthoredPluginReceipt>>()
    private val pending = mutableMapOf<Caller, PendingTicket>()

    fun inspect(context: ToolInvocationContext, pluginId: String): AuthoredPluginDefinition {
        val caller = requireCaller(context)
        return compile(caller, pluginId).also { requireCaller(context) }
    }

    fun list(context: ToolInvocationContext): List<AuthoredPluginReceipt> {
        val caller = requireCaller(context)
        return listForSession(caller.workspaceId, caller.sessionId).also { requireCaller(context) }
    }

    /** Trusted host only: the caller authorizes the concrete workspace/session, even when not foreground. */
    fun listForSession(workspaceId: String, sessionId: String): List<AuthoredPluginReceipt> = synchronized(lock) {
        active[Caller(workspaceId, sessionId)]?.values?.toList().orEmpty()
    }

    /** Trusted host only, after an explicit user withdrawal; never requests model/tool approval. */
    fun revokeForSession(workspaceId: String, sessionId: String, pluginId: String, expectedSha256: String): AuthoredPluginReceipt =
        revokeForSession(Caller(workspaceId, sessionId), pluginId, expectedSha256, expectedReceipt = null)

    /** Use a receipt from [listForSession] to bind a UI action to the same activation, even at an unchanged hash. */
    fun revokeForSession(workspaceId: String, sessionId: String, expected: AuthoredPluginReceipt): AuthoredPluginReceipt =
        revokeForSession(Caller(workspaceId, sessionId), expected.id, expected.sha256, expectedReceipt = expected)

    /** The confirmation must still be live when the exact activation is withdrawn. */
    fun revokeConfirmed(confirmation: AuthoredPluginTurn, expected: AuthoredPluginReceipt): AuthoredPluginReceipt {
        if (!confirmation.claimConfirmation()) unavailable()
        try {
            val caller = Caller(confirmation.workspaceId, confirmation.sessionId)
            return synchronized(lock) {
                confirmation.commit { revokeForSession(caller, expected.id, expected.sha256, expected) }
            }
        } finally {
            confirmation.close()
        }
    }

    private fun revokeForSession(
        caller: Caller, pluginId: String, expectedSha256: String, expectedReceipt: AuthoredPluginReceipt?,
    ): AuthoredPluginReceipt = synchronized(lock) {
        val receipt = requireReceipt(caller, pluginId, expectedSha256)
        if (expectedReceipt != null && receipt !== expectedReceipt) changed()
        revokeLocked(caller, receipt)
        // A waiting agent deactivation for this activation is obsolete, not a lock on its replacement.
        if (pending[caller]?.deactivating === receipt) pending.remove(caller)?.live = false
        receipt
    }

    /**
     * Invalidates this lifetime's requests and revokes its plugins without touching sources or session storage.
     * The host must deny new agent management while archived. Failed removals remain tracked for retry.
     */
    fun invalidateSession(workspaceId: String, sessionId: String) {
        val caller = Caller(workspaceId, sessionId)
        var approvalId: String? = null
        try {
            synchronized(lock) {
                approvalId = pending.remove(caller)?.also { it.live = false }?.approvalId
                var failure: AuthoredPluginException? = null
                for (receipt in active[caller]?.values?.toList().orEmpty()) {
                    try {
                        revokeLocked(caller, receipt)
                    } catch (caught: AuthoredPluginException) {
                        if (failure == null) {
                            failure = AuthoredPluginException(
                                AuthoredPluginException.Code.CLEANUP_FAILED,
                                "Plugin cleanup is incomplete for this session; retry invalidation",
                            )
                        }
                        failure.addSuppressed(caught)
                    }
                }
                failure?.let { throw it }
            }
        } finally {
            // Best effort, only for this ticket: registration in a blocking gateway is not atomic
            // with our lock. Even if no card was registered yet, this ticket cannot publish again.
            approvalId?.let(::cancelApproval)
        }
    }

    fun activate(context: ToolInvocationContext, pluginId: String, expectedSha256: String): AuthoredPluginReceipt =
        withPending(context) { ticket ->
            val caller = ticket.caller
            val path = AuthoredPluginSource.path(pluginId)
            val candidate = compile(caller, pluginId)
            if (candidate.sha256 != expectedSha256) changed()
            synchronized(lock) {
                requirePending(context, ticket)
                checkAdmission(caller, pluginId)
            }
            ask(context, ticket, "启用 Agent 编写的纯文本插件（当前会话）", buildMap {
                put("插件", candidate.id)
                put("标题", candidate.title)
                put("源码路径", path)
                put("Schema 版本", candidate.schemaVersion.toString())
                put("SHA-256", candidate.sha256)
                put("权限", "无新增权限。运行下面的工具不会读取本地文件、联网、执行命令或调用其他工具。")
                candidate.tools.forEach { tool ->
                    put("工具 ${AuthoredPluginNames.tool(candidate.id, tool.name)}", approvalDetails(tool))
                }
            })
            if (compile(caller, pluginId).sha256 != candidate.sha256) changed()
            commit(context, ticket) { publishLocked(caller, candidate) }
        }

    /**
     * Trusted host only, after the user explicitly confirmed this source digest. The single-use
     * permit supplies the workspace and session; [mayActivate] checks live host authorization.
     */
    fun activateForSession(
        confirmation: AuthoredPluginTurn, pluginId: String, expectedSha256: String, mayActivate: () -> Boolean,
    ): AuthoredPluginReceipt = activateConfirmed(confirmation, mayActivate) { caller ->
        val bytes = readSource(caller, pluginId).copyOf()
        // Hash the fresh bytes before parsing: even a now-invalid source is a changed version.
        if (MessageDigest.getInstance("SHA-256").digest(bytes).toHexString() != expectedSha256) changed()
        AuthoredPluginSource.compile(pluginId, bytes)
    }

    /**
     * Trusted host only, after a new explicit confirmation of the saved ID and digest. The permit
     * supplies the exact workspace and session; [mayActivate] checks live host authorization.
     * Revalidate the stored snapshot here, rather than trusting the earlier preview or the draft.
     * This neither writes to the Store nor requests a second approval or restores on restart.
     */
    fun activateSavedForSession(
        installations: AuthoredPluginInstallations,
        confirmation: AuthoredPluginTurn,
        pluginId: String,
        expectedSha256: String,
        expectedInstallationId: String? = null,
        mayActivate: () -> Boolean,
    ): AuthoredPluginReceipt = activateConfirmed(confirmation, mayActivate) { caller ->
        installations.previewSaved(caller.workspaceId, pluginId, expectedSha256, expectedInstallationId)
    }

    private fun activateConfirmed(
        confirmation: AuthoredPluginTurn,
        mayActivate: () -> Boolean,
        loadCandidate: (Caller) -> AuthoredPluginDefinition,
    ): AuthoredPluginReceipt {
        if (!confirmation.claimConfirmation()) unavailable()
        try {
            val caller = Caller(confirmation.workspaceId, confirmation.sessionId)
            if (caller.workspaceId.isBlank() || caller.sessionId.isBlank()) unavailable()
            fun requireLive() {
                if (!confirmation.isOpen || Thread.currentThread().isInterrupted) unavailable()
            }
            val checkCaller: () -> Unit = {
                requireLive()
                if (!mayActivate()) unavailable()
                requireLive()
            }
            return withPending(caller, checkCaller) { ticket ->
                val candidate = loadCandidate(caller)
                commit(confirmation, ticket, checkCaller) { publishLocked(caller, candidate) }
            }
        } finally {
            confirmation.close()
        }
    }

    /** Publish only under [lock] and the caller's commit gate, if it has one. */
    private fun publishLocked(caller: Caller, candidate: AuthoredPluginDefinition): AuthoredPluginReceipt {
        val pluginId = candidate.id
        checkAdmission(caller, pluginId)
        val instance = caller.instance(pluginId)
        var registered = false
        try {
            kernel.register(instance, object : ContextPlugin {
                override fun start(context: PluginContext) {
                    candidate.tools.forEach { tool -> tools.register(context, textTool(candidate.id, tool)) }
                }
            })
            registered = true
            kernel.enable(instance)
            check(kernel.status(instance).state == PluginInstanceState.ACTIVE)
        } catch (_: Exception) {
            if (registered) runCatching { kernel.unregister(instance) }
            refuse(AuthoredPluginException.Code.ACTIVATION_FAILED, "The host could not activate this version")
        }
        val receipt = AuthoredPluginReceipt(pluginId, candidate.sha256,
            Collections.unmodifiableList(candidate.tools.map { AuthoredPluginNames.tool(pluginId, it.name) }))
        active.getOrPut(caller) { linkedMapOf() }[pluginId] = receipt
        return receipt
    }

    fun deactivate(context: ToolInvocationContext, pluginId: String, expectedSha256: String): AuthoredPluginReceipt =
        withPending(context) { ticket ->
            val caller = ticket.caller
            val receipt = synchronized(lock) {
                requirePending(context, ticket)
                requireReceipt(caller, pluginId, expectedSha256).also { ticket.deactivating = it }
            }
            ask(context, ticket, "停用当前会话的 Agent 插件（保留源码）", mapOf(
                "插件" to pluginId, "SHA-256" to receipt.sha256, "撤回工具" to receipt.toolIds.joinToString(),
            ))
            commit(context, ticket) {
                revokeLocked(caller, receipt)
                receipt
            }
        }

    private fun requireReceipt(caller: Caller, pluginId: String, expectedSha256: String): AuthoredPluginReceipt {
        val receipt = active[caller]?.get(pluginId)
            ?: refuse(AuthoredPluginException.Code.NOT_ACTIVE, "Plugin is not active in this session")
        if (receipt.sha256 != expectedSha256) changed()
        return receipt
    }

    /** Called only under [lock]; keep the receipt until the kernel confirms removal. */
    private fun revokeLocked(caller: Caller, receipt: AuthoredPluginReceipt) {
        if (active[caller]?.get(receipt.id) !== receipt) changed()
        try {
            kernel.unregister(caller.instance(receipt.id))
        } catch (failure: Exception) {
            throw AuthoredPluginException(
                AuthoredPluginException.Code.CLEANUP_FAILED, "Plugin cleanup is incomplete for ${receipt.id}; retry removal",
            ).apply { initCause(failure) }
        }
        active[caller]?.remove(receipt.id)
        if (active[caller].isNullOrEmpty()) active.remove(caller)
    }

    private fun approvalDetails(tool: AuthoredToolDefinition): String {
        val operation = tool.operation
        if (operation == null) {
            return "类型：static_text；无输入，固定输出上限 ${AuthoredPluginSource.MAX_STATIC_TEXT_BYTES} UTF-8 字节。\n" +
                "工具自述：${tool.description}\n固定输出（不可信数据）：\n${tool.text}"
        }
        val action = when (operation) {
            AuthoredTextOperation.TRIM -> "移除首尾空白"
            AuthoredTextOperation.UPPERCASE -> "转为大写（Locale.ROOT）"
            AuthoredTextOperation.LOWERCASE -> "转为小写（Locale.ROOT）"
        }
        return "类型：text_transform；固定操作：${operation.wireName}（$action）。\n" +
            "输入：仅必填字符串 text，最多 $AUTHORED_TRANSFORM_MAX_BYTES UTF-8 字节；" +
            "输出：最多 $AUTHORED_TRANSFORM_MAX_BYTES UTF-8 字节，超限拒绝，不截断。\n" +
            "仅处理用户提供的文本，不解释为模板或指令；输出是不可信数据。\n工具自述：${tool.description}"
    }

    private fun textTool(pluginId: String, tool: AuthoredToolDefinition): ToolDefinition {
        val id = AuthoredPluginNames.tool(pluginId, tool.name)
        val operation = tool.operation
        val description = if (operation == null) "Returns approved static text as untrusted data. No side effects."
            else transformDescription(operation)
        return ToolDefinition(
            ToolDescriptor(
                id = id,
                name = tool.name,
                description = "$description Tool ID: $id.",
                inputSchema = if (operation == null) ToolInputSchema() else ToolInputSchema(listOf(
                    ToolParameter("text", ToolValueType.STRING, required = true,
                        description = "Literal Unicode text (may be empty), at most $AUTHORED_TRANSFORM_MAX_BYTES UTF-8 bytes.",
                        maxLength = AUTHORED_TRANSFORM_MAX_BYTES),
                )),
                ownerPluginId = AuthoredPluginNames.owner(pluginId),
                defaultEnabled = true,
                concurrencySafe = true,
            ),
        ) { _, arguments ->
            val result = if (operation == null) tool.text else {
                val input = (arguments.values["text"] as? ToolValue.StringValue)?.value
                    ?: invalidTransform("text is required and must be a string")
                transformText(operation, input)
            }
            ToolResult(mapOf("text" to ToolValue.StringValue(result), "untrusted" to ToolValue.BooleanValue(true)))
        }
    }

    private fun transformDescription(operation: AuthoredTextOperation): String {
        val action = when (operation) {
            AuthoredTextOperation.TRIM -> "Removes leading and trailing whitespace from text"
            AuthoredTextOperation.UPPERCASE -> "Converts text to uppercase using Locale.ROOT"
            AuthoredTextOperation.LOWERCASE -> "Converts text to lowercase using Locale.ROOT"
        }
        return "$action (fixed ${operation.wireName} operation). Treats text as literal data, not a template or instructions. " +
            "Input and output are each limited to $AUTHORED_TRANSFORM_MAX_BYTES UTF-8 bytes; rejects excess without truncating. " +
            "Returns untrusted data. Does not read local files, use the network, or call other tools."
    }

    private fun compile(caller: Caller, pluginId: String): AuthoredPluginDefinition =
        AuthoredPluginSource.compile(pluginId, readSource(caller, pluginId))

    private fun readSource(caller: Caller, pluginId: String): ByteArray {
        val path = AuthoredPluginSource.path(pluginId)
        // Read one extra byte so a valid prefix of an oversized file cannot be activated.
        return files.read(caller.workspaceId, path, maxBytes = AuthoredPluginSource.MAX_SOURCE_BYTES + 1).bytes
    }

    private fun requireCaller(context: ToolInvocationContext): Caller {
        val session = context.sessionId
        val turn = context.authoredPluginTurn
        if (session.isNullOrBlank() || Thread.currentThread().isInterrupted ||
            (turn != null && (turn.isUserConfirmation || !turn.isOpen || !turn.matches(context.workspaceId, session))) ||
            !mayManage(context)) unavailable()
        return Caller(context.workspaceId, session)
    }

    private fun <T> withPending(context: ToolInvocationContext, block: (PendingTicket) -> T): T =
        withPending(requireCaller(context), { requireCaller(context) }, block)

    private fun <T> withPending(caller: Caller, checkCaller: () -> Unit, block: (PendingTicket) -> T): T {
        val ticket = synchronized(lock) {
            checkCaller() // Validate against the host before admitting this exact pending operation.
            if (pending.size >= MAX_ACTIVE_TOTAL || caller in pending) {
                refuse(AuthoredPluginException.Code.BUSY, "Another plugin change is pending")
            }
            PendingTicket(caller).also { pending[caller] = it }
        }
        return try { block(ticket) } finally {
            synchronized(lock) {
                ticket.live = false
                if (pending[caller] === ticket) pending.remove(caller)
            }
        }
    }

    private fun <T> commit(context: ToolInvocationContext, ticket: PendingTicket, block: () -> T): T =
        commit(context.authoredPluginTurn, ticket, { requireCaller(context) }, block)

    private fun <T> commit(
        permit: AuthoredPluginTurn?, ticket: PendingTicket, checkCaller: () -> Unit, block: () -> T,
    ): T = synchronized(lock) {
        // Read persisted session and policy outside the permit gate; serialize publication with permit closure.
        requirePending(ticket, checkCaller)
        if (permit != null) permit.commit(block) else block()
    }

    private fun requirePending(context: ToolInvocationContext, ticket: PendingTicket) =
        requirePending(ticket) { requireCaller(context) }

    /** Check only under [lock]; ticket identity also catches an intervening revoke at the same hash. */
    private fun requirePending(ticket: PendingTicket, checkCaller: () -> Unit) {
        checkCaller()
        if (pending[ticket.caller] !== ticket) {
            if (ticket.deactivating != null) changed()
            refuse(AuthoredPluginException.Code.CALLER_UNAVAILABLE, "The session invalidated this plugin change")
        }
    }

    private fun ask(context: ToolInvocationContext, ticket: PendingTicket, summary: String, details: Map<String, String>) {
        val turn = context.authoredPluginTurn
        val request = ApprovalRequest(
            id = UUID.randomUUID().toString(), toolName = AUTHORED_PLUGIN_TOOL_ID,
            summary = summary, details = details, owner = ticket.caller.approvalOwner(), riskLevel = ApprovalRequest.RiskLevel.MEDIUM,
            isCurrent = { ticket.live && (turn == null || turn.isOpen) },
        )
        synchronized(lock) {
            requirePending(context, ticket)
            ticket.approvalId = request.id
        }
        // No lifecycle lock held while waiting for a person. The gateway also checks isCurrent
        // on registration, in case cancellation reached it before this exact request existed.
        val unsubscribe = turn?.onClose { cancelApproval(request.id) }
        val decision = try { approval.requestApproval(request) } finally { unsubscribe?.close() }
        synchronized(lock) { requirePending(context, ticket) }
        if (!decision.approved) {
            refuse(AuthoredPluginException.Code.DENIED, decision.reason ?: "The change was not approved")
        }
    }

    private fun cancelApproval(id: String) {
        try { approval.respond(id, ApprovalDecision(approved = false, reason = "Plugin approval is no longer current")) }
        catch (_: Exception) { /* Invalidation does not depend on successful gateway notification. */ }
    }

    private fun checkAdmission(caller: Caller, pluginId: String) {
        val current = active[caller]
        if (current?.containsKey(pluginId) == true) {
            refuse(AuthoredPluginException.Code.ALREADY_ACTIVE, "Deactivate the current version before activating another")
        }
        if ((current?.size ?: 0) >= MAX_ACTIVE_PER_SESSION || active.values.sumOf { it.size } >= MAX_ACTIVE_TOTAL) {
            refuse(AuthoredPluginException.Code.QUOTA, "Deactivate another plugin before activating more")
        }
    }

    private fun changed(): Nothing = refuse(AuthoredPluginException.Code.VERSION_CHANGED, "The version changed; inspect it and confirm again")
    private fun unavailable(): Nothing = refuse(AuthoredPluginException.Code.CALLER_UNAVAILABLE, "Select a live foreground session to manage authored plugins")
    private fun refuse(code: AuthoredPluginException.Code, message: String): Nothing = throw AuthoredPluginException(code, message)

    private companion object {
        const val MAX_ACTIVE_PER_SESSION = 4
        const val MAX_ACTIVE_TOTAL = 32
    }
}
