package com.cordiact.app

import android.app.Application
import android.util.Log
import android.os.Process
import com.cordiact.app.conversation.AndroidConversationSessionStore
import com.cordiact.app.conversation.BundledConversationPluginCatalog
import com.cordiact.app.conversation.BundledConversationPlugins
import com.cordiact.app.plugin.AndroidPluginLifecycle
import com.cordiact.app.model.AndroidDeepSeekApiKeyStore
import com.cordiact.app.model.AndroidProviderApiKeyStore
import com.cordiact.core.conversation.ModelAdapterPlugin
import com.cordiact.core.conversation.ModelCall
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelId
import com.cordiact.model.deepseek.ConversationTurnService
import com.cordiact.core.conversation.ModelAdapterService
import com.cordiact.model.deepseek.ModelProviderRegistryService
import com.cordiact.core.conversation.SessionManagerPlugin
import com.cordiact.core.conversation.SessionManagerService
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginLifecycleException
import com.cordiact.core.plugin.PluginState
import com.cordiact.core.skill.SkillIndexRenderer
import com.cordiact.core.tool.ToolManagerService
import com.cordiact.core.file.WorkspaceFileService
import com.cordiact.core.sandbox.SandboxManagerPlugin
import com.cordiact.core.sandbox.SandboxManagerService
import com.cordiact.core.workspace.WorkspaceManagerService
import com.cordiact.integration.interaction.InteractionContext
import com.cordiact.integration.interaction.InteractionModelSubmitter
import com.cordiact.integration.interaction.MentionType
import com.cordiact.core.tool.GrantedScope
import com.cordiact.integration.interaction.MutableFillValueStore

/** Opens the plugin host, recovers durable state, then registers and enables core conversation plugins. */
class CordiActApplication : Application() {
    lateinit var pluginLifecycle: AndroidPluginLifecycle
        private set
    lateinit var conversationStore: AndroidConversationSessionStore
        private set
    lateinit var conversationCatalog: BundledConversationPlugins
        private set
    lateinit var workspaceManager: WorkspaceManagerService
        private set
    lateinit var modelAdapterPlugin: ModelAdapterPlugin
        private set
    lateinit var deepSeekApiKeyStore: AndroidDeepSeekApiKeyStore
        private set
    lateinit var providerApiKeyStore: AndroidProviderApiKeyStore
        private set
    lateinit var turnService: ConversationTurnService
        private set
    lateinit var sessionManagerPlugin: SessionManagerPlugin
        private set
    lateinit var sandboxManagerPlugin: SandboxManagerPlugin
        private set
    lateinit var interactionPlugin: com.cordiact.integration.interaction.InteractionIntegrationPlugin
        private set

    /**
     * Scopes granted by the most recent `@` reference, keyed by session.
     *
     * Keyed rather than global: two sessions can be mid-turn at once, and a session's grant
     * must never become another session's grant. Held here rather than passed through a model
     * call because the tool layer is the only consumer — nothing is read up front, so the grant
     * has to outlive the submit and be visible when a tool actually runs.
     */
    private val turnGrants = java.util.concurrent.ConcurrentHashMap<String, List<GrantedScope>>()

    /** The grant set to attach to a tool invocation for [sessionId]. */
    fun currentTurnGrants(sessionId: String): List<GrantedScope> = turnGrants[sessionId] ?: emptyList()

    private fun recordTurnGrants(sessionId: String, grants: List<GrantedScope>) {
        if (grants.isEmpty()) turnGrants.remove(sessionId) else turnGrants[sessionId] = grants
    }

    @Volatile
    var activeSessionId: String? = null
        private set

    val sandboxManager: SandboxManagerService get() = sandboxManagerPlugin
    val interactionService: com.cordiact.integration.interaction.InteractionIntegrationService
        get() = conversationCatalog.interactionService
    val fillValues: MutableFillValueStore
        get() = conversationCatalog.fillValueStore
    val modelAdapter: ModelAdapterService get() = modelAdapterPlugin
    val modelProviders: ModelProviderRegistryService get() = conversationCatalog.modelProviderRegistryPlugin
    val sessions: SessionManagerService get() = sessionManagerPlugin
    val tools: ToolManagerService get() = conversationCatalog.toolManagerService

    private val skillIndexRenderer = SkillIndexRenderer()

    /**
     * The skill catalog for a Workspace, as a system-prompt block.
     *
     * Rebuilt per turn rather than cached for the session: a skill added or removed between
     * turns must take effect immediately, and a stale index would offer the model a file that
     * no longer exists.
     */
    private fun skillIndexFor(workspaceId: String): String? = try {
        // Refresh between turns so Workspace file edits affect the next request.
        conversationCatalog.skillPlugin.invalidate(workspaceId)
        val catalog = conversationCatalog.skillPlugin.catalog(workspaceId)
        val index = skillIndexRenderer.render(catalog)
        index.text.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        // A broken catalog must not block the user's message.
        null
    }
    val workspaceFiles: WorkspaceFileService get() = conversationCatalog.fileManager

    override fun onCreate() {
        super.onCreate()
        if (Process.isIsolated()) {
            // Android creates the Application class in every process. The sandbox UID must not
            // initialize the host graph, SQLite helpers, providers, credentials, or plugin registry.
            Log.i(TAG, "Isolated service process: skipping host application initialization")
            return
        }
        pluginLifecycle = AndroidPluginLifecycle(this, BundledConversationPluginCatalog.verifier)
        deepSeekApiKeyStore = AndroidDeepSeekApiKeyStore(this)
        providerApiKeyStore = AndroidProviderApiKeyStore(this)
        conversationCatalog = BundledConversationPlugins.create(this, deepSeekApiKeyStore)
        conversationStore = conversationCatalog.conversationStore
        workspaceManager = conversationCatalog.workspacePlugin
        val needsRepair = pluginLifecycle.recoverBeforeDispatch()
        if (needsRepair.isNotEmpty()) {
            Log.e(TAG, "Plugin lifecycle recovery remains incomplete; blocked plugin ids: $needsRepair")
        }

        val plugins = conversationCatalog.create { pluginId -> pluginLifecycle.manager.get(pluginId)?.state }
        modelAdapterPlugin = plugins.filterIsInstance<ModelAdapterPlugin>().single()
        sessionManagerPlugin = plugins.filterIsInstance<SessionManagerPlugin>().single()
        turnService = ConversationTurnService(sessionManagerPlugin, modelAdapterPlugin)
        interactionPlugin = conversationCatalog.interactionPlugin
        interactionPlugin.service.attachModelSubmitter(InteractionModelSubmitter { envelope, modelId, parameters, onEvent ->
            // `@` is an authorization, not an attachment. Nothing is read here: the reference
            // opens a range, the model then decides what to fetch through tools, and the grant
            // travels with those calls. Pre-reading would decide the model's research for it and
            // would pay for content the turn never needed.
            val grants = envelope.mentions
                .flatMap { it.entities }
                .filter { it.type == MentionType.WORKSPACE || it.type == MentionType.MEMORY }
                .distinctBy { it.workspaceId to it.authorizedPrefix }
                .map { descriptor ->
                    GrantedScope(
                        workspaceId = descriptor.workspaceId,
                        // Only a memory scope narrows; a whole-Workspace reference grants the
                        // entire tree, which is what its name says.
                        prefix = if (descriptor.type == MentionType.MEMORY) {
                            descriptor.authorizedPrefix ?: MEMORY_PREFIX
                        } else {
                            null
                        },
                    )
                }
            recordTurnGrants(envelope.context.sessionId, grants)
            val prompt = if (envelope.command == null) envelope.submittedText else envelope.commandInput
            turnService.submit(
                sessionId = envelope.context.sessionId,
                model = modelId,
                text = prompt,
                onEvent = onEvent,
                parameters = parameters,
                systemContext = skillIndexFor(envelope.context.workspaceId),
            )
        })
        sandboxManagerPlugin = BundledConversationPluginCatalog.createSandboxManager(applicationInfo.uid)
        (plugins + sandboxManagerPlugin).forEach(::registerAndEnableSystemPlugin)
        val workspace = workspaceManager.initializeDefaultIfEligible()
        activeSessionId = sessions.listSessions(workspace.id).firstOrNull()?.id
            ?: sessions.createSession(workspace.id, "New conversation").id
    }

    private fun registerAndEnableSystemPlugin(plugin: ManagedPlugin) {
        try {
            val current = pluginLifecycle.manager.registerBundledSystem(plugin)
            if (current.state != PluginState.ENABLED) {
                pluginLifecycle.manager.enable(plugin.spec.id)
            } else {
                // Rebind in-memory services after process recovery; hooks are required to be idempotent.
                plugin.onEnable()
            }
        } catch (failure: PluginLifecycleException) {
            Log.e(TAG, "Protected conversation plugin '${plugin.spec.id}' failed to start", failure)
        }
    }

    private companion object {
        const val TAG = "CordiActPluginHost"

        /** The only subtree a memory reference narrows to. */
        const val MEMORY_PREFIX = "memory/"
    }
}
