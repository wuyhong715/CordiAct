package com.cordiact.app.experience.settings

import com.cordiact.app.CordiActApplication
import com.cordiact.core.action.ApprovalOwner
import com.cordiact.core.action.ApprovalRequest
import com.cordiact.core.conversation.*
import com.cordiact.core.mcp.*
import com.cordiact.core.security.*
import com.cordiact.core.settings.WorkspaceDeclarations
import com.cordiact.core.settings.WorkspaceDeclarations.Kind
import com.cordiact.core.tool.TOOL_MANAGER_PLUGIN_ID
import com.cordiact.mcp.client.HttpMcpClientFactory
import com.cordiact.mcp.client.McpToolBridge
import com.cordiact.model.providers.*
import java.net.URI
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Trusted, process-scoped approvals. On process death no connection or authorization is restored. */
internal class WorkspaceConnections(private val app: CordiActApplication) {
    data class Key(val workspace: String, val kind: Kind, val id: String)
    private data class Binding(val revision: String?, val observation: String, val draft: WorkspaceDeclarations.Draft, val profileRevision: Long, val policyEpoch: Long)
    data class Choice(val id: String, val label: String)
    data class Status(val choices: List<Choice>, val granted: Set<String>)
    private class Live(val binding: Binding) {
        var mcp: McpConnection? = null
        var bridge: McpToolBridge? = null
        var backend: ConfigurableModelBackend? = null
        var descriptors: List<McpToolDescriptor> = emptyList()
        var models: List<String> = emptyList()
        @Volatile var granted: Set<String> = emptySet()
    }
    private val lock = Mutex()
    private val providerExecutor = java.util.concurrent.Executors.newCachedThreadPool()
    private val live = ConcurrentHashMap<Key, Live>()
    private val repository by lazy { WorkspaceDeclarations(app.workspaceFiles) }

    fun status(key: Key): Status? = live[key]?.takeIf { valid(key, it) }?.let { item ->
        Status(if (key.kind == Kind.MCP) item.descriptors.map { Choice(it.name, it.name) }
            else item.models.map { Choice(it, it) }, item.granted)
    }

    suspend fun probe(key: Key, expectedRevision: String?, expected: WorkspaceDeclarations.Draft, secret: String?) = lock.withLock {
        require(key.id == expected.id && secret.orEmpty().length <= 4096 && secret.orEmpty().none(Char::isISOControl))
        val binding = resolve(key)
        require(binding.revision == expectedRevision && binding.draft == expected) { "Declaration changed" }
        check(canUseNetwork(key.workspace)) { "Network not allowed by workspace policy" }
        authorize(key, binding, "NETWORK_PROBE")
        if (!secret.isNullOrBlank()) authorize(key, binding, "CREDENTIAL_BINDING")
        check(resolve(key) == binding && canUseNetwork(key.workspace)) { "Declaration changed" }
        retire(key)
        val next = Live(binding)
        try {
            val id = resourceId(key, binding)
            if (key.kind == Kind.MCP) {
                val conf = McpServerConfig(id, binding.draft.endpoint)
                val conn = HttpMcpClientFactory.connect(conf, secret?.takeIf(String::isNotBlank)?.let { McpCredential("Authorization", "Bearer $it") })
                next.mcp = conn
                conn.initialize()
                next.descriptors = conn.listTools().take(200)
            } else {
                val protocol = ModelProtocol.valueOf(binding.draft.protocol)
                require(protocol != ModelProtocol.OPENAI_IMAGES) { "This route does not support conversation streaming" }
                require(!secret.isNullOrBlank()) { "Model provider credential is required" }
                val conn = ModelProviderConnection(id, binding.draft.name, protocol, binding.draft.endpoint)
                next.models = HttpModelDiscoveryService().discover(conn, secret).distinct().take(200)
                require(next.models.isNotEmpty()) { "No models discovered" }
                next.backend = ConfigurableModelBackend(conn.config(next.models), ApiKeyProvider { secret }, providerExecutor)
            }
            check(resolve(key) == binding && canUseNetwork(key.workspace))
            audit(key, "PROBE", AuditPhase.SUCCEEDED, AuditOutcome.SUCCEEDED)
            live[key] = next
        } catch (e: Exception) {
            runCatching { next.bridge?.dispose() }; runCatching { next.mcp?.close() }; next.backend?.cancelActiveCalls()
            audit(key, "PROBE", AuditPhase.FAILED, AuditOutcome.FAILED)
            throw e
        }
    }

    suspend fun grant(key: Key, requested: Set<String>) = lock.withLock {
        val item = requireNotNull(live[key]); require(valid(key, item))
        val names = if (key.kind == Kind.MCP) item.descriptors.map { it.name } else item.models
        val selection = requested.sorted().joinToString("\n")
        require(requested.isNotEmpty() && names.containsAll(requested) && requested.size <= 50 && selection.length <= 3500)
        authorize(key, item.binding, if (key.kind == Kind.MCP) "MCP_TOOL_GRANT" else "MODEL_ROUTE", selection)
        check(valid(key, item))
        if (key.kind == Kind.MCP) {
            val conn = requireNotNull(item.mcp)
            val selected = item.descriptors.filter { it.name in requested }
            // Register once; visibility is false until the exact approved set is published.
            check(item.bridge == null) { "Revoke and probe again before changing an active selection" }
            val bridge = McpToolBridge(resourceId(key, item.binding), app.conversationCatalog.toolRegistry, TOOL_MANAGER_PLUGIN_ID,
                McpServerConfig.DEFAULT_TOOL_CALL_TIMEOUT_MS) { workspace, descriptor ->
                workspace == key.workspace && live[key] === item && descriptor.name in item.granted && valid(key, item)
            }
            bridge.sync(conn, selected)
            item.bridge = bridge
        } else {
            requireNotNull(item.backend)
        }
        audit(key, if (key.kind == Kind.MCP) "MCP_TOOLS_ENABLED" else "MODEL_ROUTE_ENABLED", AuditPhase.SUCCEEDED, AuditOutcome.SUCCEEDED)
        item.granted = requested.toSet()
    }

    fun models(workspace: String): List<ModelDescriptor> = live.entries.flatMap { (key, item) ->
        if (key.workspace != workspace || key.kind != Kind.PROVIDER || item.granted.isEmpty() || !valid(key, item)) emptyList()
        else item.backend?.models()?.filter { it.id.modelId in item.granted }.orEmpty()
    }

    fun toolVisible(workspace: String, id: String): Boolean {
        if (!id.startsWith("core.mcp.wsmcp_")) return true
        return live.entries.any { (key, item) ->
            key.workspace == workspace && key.kind == Kind.MCP && valid(key, item) &&
                item.granted.any { mcpToolId(resourceId(key, item.binding), it) == id }
        }
    }

    fun backend(request: ModelGenerationRequest): ModelBackend? {
        if (!isScopedModel(request.model.providerId)) return null
        val workspace = app.sessions.getSession(request.sessionId)?.workspaceId ?: return null
        if (request.workspaceId != null && request.workspaceId != workspace) return null
        val match = live.entries.firstOrNull { (key, item) -> key.workspace == workspace && key.kind == Kind.PROVIDER &&
            resourceId(key, item.binding) == request.model.providerId && request.model.modelId in item.granted && valid(key, item) } ?: return null
        val delegate = match.value.backend ?: return null
        return object : ModelBackend by delegate {
            override fun generate(request: ModelGenerationRequest, onEvent: (ModelEvent) -> Unit): ModelCall {
                if (live[match.key] !== match.value || request.model.modelId !in match.value.granted || !valid(match.key, match.value)) {
                    onEvent(ModelEvent.Failed("route_revoked", "Workspace model route is no longer approved"))
                    return ModelCall {}
                }
                return delegate.generate(request, onEvent)
            }
        }
    }

    suspend fun revoke(key: Key) = lock.withLock {
        retire(key)
        audit(key, "REVOKE", AuditPhase.SUCCEEDED, AuditOutcome.SUCCEEDED)
    }

    private fun retire(key: Key) {
        val gone = live.remove(key) ?: return
        gone.granted = emptySet()
        try { gone.bridge?.dispose() } finally { runCatching { gone.mcp?.close() }; gone.backend?.cancelActiveCalls() }
    }

    private fun resolve(key: Key): Binding {
        val snapshot = repository.read(key.workspace, key.kind)
        val profile = app.credentialVaultRuntime.profileStore.profileFor(key.workspace)
        return Binding(snapshot.revision, requireNotNull(snapshot.observation) { "Stable file identity unavailable" },
            snapshot.drafts.single { it.id == key.id }, profile.profileRevision, profile.policyEpoch)
    }
    private fun valid(key: Key, item: Live): Boolean = runCatching { canUseNetwork(key.workspace) && resolve(key) == item.binding }.getOrDefault(false)
    private fun canUseNetwork(workspace: String): Boolean = app.workspaceManager.get(workspace)?.let { it.archivedAtMillis == null } == true &&
        app.credentialVaultRuntime.profileStore.profileFor(workspace).accessEnvelope != AccessEnvelope.READ_ONLY

    private suspend fun authorize(key: Key, binding: Binding, step: String, selection: String? = null) {
        val id = UUID.randomUUID().toString()
        val endpoint = URI(binding.draft.endpoint)
        val details = mapOf("workspace" to key.workspace, "origin" to "https://${endpoint.authority}", "endpointPath" to endpoint.rawPath.orEmpty(),
            "protocol" to binding.draft.protocol, "revision" to binding.revision.orEmpty()) +
            (selection?.let { mapOf("selection" to it) }.orEmpty())
        audit(key, step, AuditPhase.APPROVAL_ASKED, AuditOutcome.PENDING, id)
        val decision = runInterruptible { app.approvalGateway.requestApproval(ApprovalRequest(id, "workspace_connection", when (step) {
            "NETWORK_PROBE" -> "允许本次外连探测（不授予后续工具权限）"
            "CREDENTIAL_BINDING" -> "将本次输入的凭据绑定到已审批服务"
            "MCP_TOOL_GRANT" -> "授权所选 MCP 工具向该来源发送参数"
            else -> "为当前工作区启用所选模型路由"
        }, details, ApprovalRequest.RiskLevel.HIGH, owner = ApprovalOwner(key.workspace))) }
        audit(key, step, AuditPhase.APPROVAL_DECIDED, if (decision.approved) AuditOutcome.ALLOWED else AuditOutcome.DENIED, id)
        check(decision.approved && resolve(key) == binding && canUseNetwork(key.workspace)) { "Approval denied, expired or became stale" }
    }

    private fun audit(key: Key, step: String, phase: AuditPhase, outcome: AuditOutcome, id: String = UUID.randomUUID().toString()) {
        app.credentialVaultRuntime.database.append(AuditEventDraft(AuditCategory.APPROVAL, phase, outcome,
            "WORKSPACE_CONNECTION_$step", correlationId = id, approvalId = id, workspaceId = key.workspace))
    }

    companion object {
        private const val PROVIDER_PREFIX = "wsroute_"
        fun isScopedModel(providerId: String) = providerId.startsWith(PROVIDER_PREFIX)
        private fun resourceId(key: Key, binding: Binding): String {
            val hash = MessageDigest.getInstance("SHA-256").digest("${key.workspace}\u0000${key.kind}\u0000${key.id}\u0000${binding.observation}".encodeToByteArray())
            return (if (key.kind == Kind.PROVIDER) PROVIDER_PREFIX else "wsmcp_") + hash.take(11).joinToString("") { "%02x".format(it) }
        }
    }
}
