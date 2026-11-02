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
import com.cordiact.core.tool.ToolManagerService
import com.cordiact.core.file.WorkspaceFileService
import com.cordiact.core.sandbox.SandboxManagerPlugin
import com.cordiact.core.sandbox.SandboxManagerService
import com.cordiact.core.workspace.WorkspaceManagerService
import com.cordiact.integration.interaction.InteractionContext
import com.cordiact.integration.interaction.InteractionModelSubmitter
import com.cordiact.integration.interaction.ReferenceContextAssembler
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

    /**
     * Turns mentions into bounded, labelled context.
     *
     * Owned by the application rather than the directory: the directory answers what is
     * visible, this decides how much of it enters one turn.
     */
    private val referenceAssembler = ReferenceContextAssembler()
        private set
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
            // Assembly (budget, truncation, labelling) and the Workspace boundary live in the
            // assembler rather than here, so one unreadable reference degrades to a skip
            // instead of aborting the whole turn.
            val assembly = referenceAssembler.assembleAuthorized(
                mentions = envelope.mentions,
                context = envelope.context,
            ) { descriptor -> conversationCatalog.interactionDirectory.readText(envelope.context, descriptor) }
            val referencedFiles = assembly.render()?.let { listOf(it) } ?: emptyList()
            val prompt = if (envelope.command == null) envelope.submittedText else envelope.commandInput
            turnService.submit(
                sessionId = envelope.context.sessionId,
                model = modelId,
                text = prompt,
                onEvent = onEvent,
                transientContext = referencedFiles,
                parameters = parameters,
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

    private companion object { const val TAG = "CordiActPluginHost" }
}
