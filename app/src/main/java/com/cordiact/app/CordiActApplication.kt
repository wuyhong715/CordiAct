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
import com.cordiact.model.providers.ConversationTurnService
import com.cordiact.core.conversation.ModelAdapterService
import com.cordiact.model.providers.ModelProviderRegistryService
import com.cordiact.core.conversation.SessionManagerPlugin
import com.cordiact.core.conversation.SessionManagerService
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.plugin.PluginLifecycleException
import com.cordiact.core.plugin.PluginState
import com.cordiact.core.skill.SkillIndexRenderer
import com.cordiact.core.tool.ToolManagerService
import com.cordiact.core.tool.ToolActivationContext
import com.cordiact.core.file.WorkspaceFileService
import com.cordiact.core.sandbox.SandboxManagerPlugin
import com.cordiact.core.sandbox.SandboxManagerService
import com.cordiact.core.workspace.WorkspaceManagerService
import com.cordiact.interaction.InteractionContext
import com.cordiact.interaction.InteractionModelSubmitter
import com.cordiact.interaction.MentionType
import com.cordiact.app.tool.AndroidToolDispatcher
import com.cordiact.core.toolloop.ToolLoopPlugin
import com.cordiact.core.conversation.ModelToolSpec
import com.cordiact.app.memory.AndroidDeviceConditions
import com.cordiact.app.memory.SharedPreferencesLeaseStore
import com.cordiact.app.memory.WorkspaceMemoryArtifactStore
import com.cordiact.core.memory.ConsolidationDiff
import com.cordiact.core.memory.DeviceConditions
import com.cordiact.core.memory.ExtractionLeaseCoordinator
import com.cordiact.core.memory.MemoryConsolidationRunner
import com.cordiact.core.memory.MemoryExtractionRunner
import com.cordiact.core.memory.MemoryPipeline
import com.cordiact.core.memory.MemoryPaths
import com.cordiact.core.memory.MemoryPipelineReport
import com.cordiact.core.memory.SessionTurn
import com.cordiact.core.workspace.JGitWorkspaceGitService
import com.cordiact.core.workspace.WorkspaceGitService
import com.cordiact.core.tool.GrantedScope
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.interaction.MutableFillValueStore

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
    lateinit var interactionPlugin: com.cordiact.interaction.InteractionPlugin
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

    /** Runs one memory pass over the active Workspace's sessions.
     *
     * The pipeline is assembled here rather than in the worker because it needs services the
     * application owns; the worker only asks for a pass and maps the outcome onto retry policy.
     */
    fun runMemoryPass(
        workspaceId: String,
        conditions: DeviceConditions,
        modelSelected: (String) -> ModelId?,
    ): MemoryPipelineReport {
        val store = WorkspaceMemoryArtifactStore(conversationCatalog.fileManager)
        val extraction = MemoryExtractionRunner(
            loadMessages = { sessionId ->
                sessions.messages(sessionId).map {
                    SessionTurn(it.role, it.content, sessionId)
                }
            },
            generate = { request, emit -> modelAdapterPlugin.generate(request, emit) },
            selection = { modelSelected(it) },
        )
        val root = try {
            workspaceManager.resolveRoot(workspaceId)
        } catch (e: Exception) {
            null
        }
        val pipeline = MemoryPipeline(
            extraction = extraction,
            consolidation = MemoryConsolidationRunner(store),
            leaseCoordinator = ExtractionLeaseCoordinator(
                SharedPreferencesLeaseStore(this),
            ),
        )
        // Only recent sessions are candidates: memory is built from conversation tails, and
        // walking an entire history would re-extract sessions already covered.
        val sessionIds = sessions.listSessions(workspaceId).take(MEMORY_SESSION_WINDOW).map { it.id }
        val present = sessionIds.toSet()

        // Sessions that already have evidence are "known". Sessions without it are candidates,
        // and a known session that is no longer present is a removal. Passing every session as
        // extracted would re-extract the whole window on every pass.
        val known = try {
            store.evidenceFiles(workspaceId)
                .map { it.removePrefix(MemoryPaths.EVIDENCE_PREFIX).removeSuffix(".md") }
                .toSet()
        } catch (e: Exception) {
            emptySet()
        }
        val candidates = sessionIds.filter { it !in known }

        return pipeline.run(
            workspaceId = workspaceId,
            sessionIds = candidates,
            conditions = conditions,
            owner = "worker-${System.currentTimeMillis()}",
            modelSelected = modelSelected(workspaceId) != null,
            diff = ConsolidationDiff(
                added = candidates.toSet(),
                retained = present.intersect(known),
                removed = known - present,
            ),
            git = workspaceGit,
            workspaceRoot = root,
        )
    }

    /** Device conditions the memory policy consults. */
    fun deviceConditions(): DeviceConditions =
        AndroidDeviceConditions(this, filesDir).read()

    /** The grant set to attach to a tool invocation for [sessionId]. */
    fun currentTurnGrants(sessionId: String): List<GrantedScope> = turnGrants[sessionId] ?: emptyList()

    private fun recordTurnGrants(sessionId: String, grants: List<GrantedScope>) {
        if (grants.isEmpty()) turnGrants.remove(sessionId) else turnGrants[sessionId] = grants
    }

    @Volatile
    var activeSessionId: String? = null
        private set

    val sandboxManager: SandboxManagerService get() = sandboxManagerPlugin
    val interactionService: com.cordiact.interaction.InteractionService
        get() = conversationCatalog.interactionService
    val fillValues: MutableFillValueStore
        get() = conversationCatalog.fillValueStore
    val modelAdapter: ModelAdapterService get() = modelAdapterPlugin
    val modelProviders: ModelProviderRegistryService get() = conversationCatalog.modelProviderRegistryPlugin
    val sessions: SessionManagerService get() = sessionManagerPlugin
    val tools: ToolManagerService get() = conversationCatalog.toolManagerService

    private val skillIndexRenderer = SkillIndexRenderer()

    /** Runs model/tool rounds. Owned by the application, not by the turn service. */
    private val toolLoopPlugin = ToolLoopPlugin().apply { onEnable() }

    /**
     * Version control for memory artifacts.
     *
     * Created eagerly because consolidation needs it on the first pass; the repository itself is
     * created lazily inside the service, so this costs nothing until memory actually runs.
     */
    private val workspaceGit: WorkspaceGitService by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        JGitWorkspaceGitService()
    }

    private val toolDispatcher by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidToolDispatcher(tools)
    }

    /**
     * Tools the model may call this turn.
     *
     * Filtered by `dispatchable` rather than merely enabled: a tool whose owner is unavailable
     * or whose capability is missing cannot run, so offering it would only produce a failing
     * call the model has to recover from.
     */
    private fun availableToolSpecs(workspaceId: String, model: ModelId): List<ModelToolSpec> =
        try {
            AndroidToolDispatcher.specs(
                tools.resolveEnabled(
                    workspaceId,
                    ToolActivationContext(workspaceId, model.providerId, model.modelId),
                ),
            )
        } catch (e: Exception) {
            emptyList()
        }

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
            val toolContext = ToolInvocationContext(
                workspaceId = envelope.context.workspaceId,
                grantedScopes = grants,
                modelProviderId = modelId.providerId,
                modelId = modelId.modelId,
                requestText = prompt,
            )
            turnService.submitWithTools(
                sessionId = envelope.context.sessionId,
                model = modelId,
                text = prompt,
                tools = availableToolSpecs(envelope.context.workspaceId, modelId),
                toolLoop = toolLoopPlugin,
                dispatcher = toolDispatcher,
                toolContext = toolContext,
                parameters = parameters,
                systemContext = skillIndexFor(envelope.context.workspaceId),
                onEvent = onEvent,
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

        /** How many recent sessions one pass considers. */
        const val MEMORY_SESSION_WINDOW = 20

        /** The only subtree a memory reference narrows to. */
        const val MEMORY_PREFIX = "memory/"
    }
}
