package com.cordiact.app

import android.app.Application
import android.util.Log
import android.os.Process
import com.cordiact.app.automation.AutomationRuntime
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
import com.cordiact.mcp.client.HttpMcpClientFactory
import com.cordiact.mcp.client.McpPlugin
import com.cordiact.model.providers.ModelProviderRegistryService
import com.cordiact.core.conversation.SessionManagerPlugin
import com.cordiact.core.conversation.SessionManagerService
import com.cordiact.core.mcp.MCP_PLUGIN_ID
import com.cordiact.app.plugin.AndroidPluginManagerService
import com.cordiact.core.plugin.PluginManagerService
import com.cordiact.core.plugin.pluginManagerPluginSpec
import com.cordiact.core.plugin.PluginSpec
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
import com.cordiact.core.subagent.ChildNotice
import com.cordiact.core.subagent.ChildToolPlanner
import com.cordiact.core.subagent.ChildTurnOutcome
import com.cordiact.core.subagent.ChildTurnRunner
import com.cordiact.core.toolloop.ToolLoopOutcome
import com.cordiact.core.toolloop.ToolLoopStop
import com.cordiact.subagent.SubagentRuntime
import com.cordiact.subagent.BackgroundSubagentRuntime
import com.cordiact.subagent.OrchestrationPlugin
import com.cordiact.app.render.registerPreviewTools
import com.cordiact.subagent.registerSubagentTools
import com.cordiact.subagent.registerBackgroundSubagentTools
import com.cordiact.subagent.registerOrchestrationTools
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

    /**
     * Live user-installed plugins, keyed by plugin id.
     *
     * Needed because "which tools does this plugin publish" is a question about the live object,
     * not about the durable record, and the inventory answers it for the user and the model.
     */
    private val userPlugins = java.util.concurrent.ConcurrentHashMap<String, McpPlugin>()
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

    /** Screen / browser / display / privilege plugins. Created once per process. */
    lateinit var automationRuntime: AutomationRuntime
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
    /**
     * Reports waiting to reach a parent Session.
     *
     * One FIFO, ordered by a single sequence: a child's message and its settlement can never be
     * reordered, which is the failure mode two queues with different drain priorities produce.
     */
    private val asyncTurnInbox = com.cordiact.core.inbox.DefaultAgentInbox()

    private val wakePolicy = com.cordiact.core.inbox.WakePolicy()

    private val asyncTurnCoordinator by lazy {
        com.cordiact.core.inbox.AsyncTurnCoordinator(
            policy = wakePolicy,
            ledger = com.cordiact.core.inbox.SlidingWindowWakeLedger(wakePolicy),
            activity = com.cordiact.core.inbox.SessionActivity { sessionId -> turnService.isTurnActive(sessionId) },
            startTurn = { sessionId -> wakeSession(sessionId) },
        )
    }

    /**
     * Bounds how many child Sessions keep their working set in memory.
     *
     * Only a closed child is evictable: an open one can still be continued, and dropping it would
     * silently turn "continue this child" into "that child never existed".
     */
    private val residentSessions = com.cordiact.core.subagent.ResidentSessions(maxResident = 8) { childSessionId ->
        backgroundSubagents.isForgettable(childSessionId)
    }

    private val toolLoopPlugin by lazy {
        val gate = com.cordiact.core.context.DefaultToolOutputGate(conversationCatalog.spillStore)
        ToolLoopPlugin(
            gate = gate,
            inbox = asyncTurnInbox,
            onReportsDelivered = { items ->
                // Mid-turn delivery consumed the report; the durable queue must drop it or the
                // next turn would repeat a child's outcome the model already saw.
                val bySession = items.groupBy { it.sessionId }
                bySession.forEach { (sessionId, delivered) ->
                    runCatching { backgroundSubagents.acknowledgeNotices(sessionId, delivered.map { it.sourceId }) }
                }
            },
        ).apply { onEnable() }
    }

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
     * Runs one delegated child turn through the same loop the user's turn uses.
     *
     * Deliberately not a second, simpler turn path: a child that skipped the tool loop would be
     * a model with no tools, and a child that skipped persistence would leave no transcript for
     * the user to open. The only difference is that the outcome is returned rather than streamed.
     */
    private val childTurnRunner = ChildTurnRunner { request ->
        val outcome = turnService.runTurnToCompletion(
            sessionId = request.sessionId,
            model = request.model,
            text = request.prompt,
            tools = request.tools,
            toolLoop = toolLoopPlugin,
            dispatcher = toolDispatcher,
            toolContext = request.context,
            systemContext = request.systemContext,
        )
        when (outcome) {
            is ToolLoopOutcome.Completed -> ChildTurnOutcome(outcome.text, ToolLoopStop.COMPLETED)
            is ToolLoopOutcome.Stopped -> ChildTurnOutcome(outcome.lastText, outcome.reason)
        }
    }

    /**
     * The delegation runtime.
     *
     * Built here rather than inside [BundledConversationPlugins] because it needs the turn
     * service and the tool list, which only the host has. The tool planner reuses
     * [availableToolSpecs] instead of assembling a list of its own, so a child can never be
     * offered a tool its parent could not call.
     */
    private val subagentRuntime: SubagentRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SubagentRuntime(
            sessions = sessionManagerPlugin,
            runner = childTurnRunner,
            toolPlanner = ChildToolPlanner { workspaceId, model -> availableToolSpecs(workspaceId, model) },
            systemContextFor = { workspaceId -> skillIndexFor(workspaceId) },
        )
    }

    /**
     * Plan execution over the delegation runtime.
     *
     * Shares [subagentRuntime] rather than starting children itself, so an orchestrated child is
     * admitted by exactly the same checks as a one-off delegation.
     */
    private val orchestrationPlugin: OrchestrationPlugin by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OrchestrationPlugin(subagentRuntime)
    }

    /**
     * Background delegation.
     *
     * Shares [subagentRuntime] for admission, so a child that runs unattended is admitted by the
     * same rules as one that blocks its parent. Its run records live in the same SQLite file as
     * the Sessions, which is what makes "the app was killed mid-run" a recoverable state rather
     * than an invisible one.
     */
    private val backgroundSubagents: BackgroundSubagentRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        BackgroundSubagentRuntime(
            runtime = subagentRuntime,
            runs = conversationStore,
            onReportQueued = ::onChildReportQueued,
        )
    }

    /**
     * A child settled (or spoke). Queue it for the parent and decide whether to wake it.
     *
     * The durable queue is the authority; the inbox is the mid-turn channel. Both are fed here so
     * a report reaches the parent whether it is busy or idle.
     */
    private fun onChildReportQueued(notice: ChildNotice) {
        val item = asyncTurnInbox.enqueue(
            kind = com.cordiact.core.inbox.InboxItemKind.CHILD_SETTLED,
            sessionId = notice.parentSessionId,
            sourceId = notice.id,
            heading = notice.summary,
            body = notice.body,
        )
        val decision = asyncTurnCoordinator.onEnqueued(item)
        if (decision != com.cordiact.core.inbox.WakeDecision.WOKE) {
            Log.d(TAG, "Child report ${notice.id} queued for ${notice.parentSessionId}: $decision")
        }
        // A settled child is a candidate for leaving memory once the parent is done with it.
        residentSessions.touch(notice.childSessionId)
        residentSessions.reap().forEach { evicted ->
            backgroundSubagents.forget(evicted)
            Log.d(TAG, "Evicted child session $evicted from memory; its transcript stays in the database")
        }
    }

    /**
     * Prepares a compaction summary for [sessionId] while the app is idle.
     *
     * Called from [com.cordiact.app.context.ContextCompactionWorker], never from a turn: producing
     * a summary is the one part of compaction that costs the user wall-clock time.
     */
    fun prepareCompactionCandidate(sessionId: String) {
        val model = runCatching {
            com.cordiact.app.memory.ExtractionModelSelectionStore(this).selectedModel()
        }.getOrNull() ?: return
        runCatching { turnService.prepareCompactionCandidate(sessionId, model) }
            .onFailure { Log.e(TAG, "Preparing a compaction candidate for $sessionId failed", it) }
    }

    /**
     * Starts a turn the runtime asked for, not the user.
     *
     * Refuses when no background model is configured rather than picking one: an automatic wake
     * that quietly runs on an unexpected model would be a surprising bill.
     */
    private fun wakeSession(sessionId: String) {
        val model = runCatching {
            com.cordiact.app.memory.ExtractionModelSelectionStore(this).selectedModel()
        }.getOrNull()
        if (model == null) {
            Log.d(TAG, "No background model configured; report for $sessionId stays queued")
            return
        }
        runCatching {
            turnService.submitWoken(
                sessionId = sessionId,
                model = model,
                systemContext = null,
            ) { }
        }.onFailure { Log.e(TAG, "Woken turn for $sessionId failed to start", it) }
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
        automationRuntime = AutomationRuntime(this)
        automationRuntime.registerTools(conversationCatalog.toolRegistry, conversationCatalog.jobRegistry)
        // Jobs left RUNNING by the previous process must be classified before anything reads them:
        // a local job is provably dead, a detached remote one is merely unknown.
        runCatching { conversationCatalog.jobRegistry.reconcileOnStartup() }
            .onFailure { Log.e(TAG, "Job reconciliation failed", it) }
        val executionManager = com.cordiact.app.service.AgentExecutionManager(this, conversationCatalog.jobRegistry)
        executionManager.start()

        conversationStore = conversationCatalog.conversationStore
        workspaceManager = conversationCatalog.workspacePlugin
        val needsRepair = pluginLifecycle.recoverBeforeDispatch()
        if (needsRepair.isNotEmpty()) {
            Log.e(TAG, "Plugin lifecycle recovery remains incomplete; blocked plugin ids: $needsRepair")
        }

        val plugins = conversationCatalog.create { pluginId -> pluginLifecycle.manager.get(pluginId)?.state }
        modelAdapterPlugin = plugins.filterIsInstance<ModelAdapterPlugin>().single()
        sessionManagerPlugin = plugins.filterIsInstance<SessionManagerPlugin>().single()
        turnService = ConversationTurnService(
            sessions = sessionManagerPlugin,
            models = modelAdapterPlugin,
            notices = backgroundSubagents,
            compactionStore = conversationCatalog.conversationStore,
            inbox = asyncTurnInbox,
        )
        // A finished turn is the cheapest moment to think about the next one: the summary can be
        // prepared while nobody is waiting, and installed only if it still applies.
        turnService.onTurnCompleted = { sessionId ->
            com.cordiact.app.context.ContextCompactionWorker.enqueue(this, sessionId)
        }
        // Registered here because the tool list is built from the shared registry, and the
        // delegation tools must be in it before the first turn assembles its specs.
        // Always published: the preview window is a session surface, not an integration.
        registerPreviewTools(conversationCatalog.toolRegistry)
        registerSubagentTools(conversationCatalog.toolRegistry, subagentRuntime)
        registerOrchestrationTools(conversationCatalog.toolRegistry, orchestrationPlugin)
        registerBackgroundSubagentTools(conversationCatalog.toolRegistry, backgroundSubagents)
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
                // Delegation addresses its caller by Session. Carried rather than inferred: a
                // tool that guesses which Session called it would attach children to the wrong
                // parent, and the depth check would read the wrong row.
                sessionId = envelope.context.sessionId,
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
        val pluginManagerPlugin = object : ManagedPlugin {
            override val spec: PluginSpec = pluginManagerPluginSpec()
        }
        (plugins + automationRuntime.plugins() + sandboxManagerPlugin + subagentRuntime + orchestrationPlugin + backgroundSubagents + pluginManagerPlugin)
            .forEach(::registerAndEnableSystemPlugin)
        // Nothing can legitimately be RUNNING at startup, so anything that says it is belongs to
        // the process that died. Recovering here queues a notice so the parent learns a child was
        // interrupted instead of waiting for it forever.
        runCatching { backgroundSubagents.recoverAbandonedRuns() }
            .onFailure { Log.e(TAG, "Recovering abandoned delegations failed", it) }
        conversationCatalog.pluginManagerService = pluginManagerService()
        // Restore user plugins in the background so slow/offline MCP servers do not block Application.onCreate() or trigger NetworkOnMainThreadException.
        java.util.concurrent.Executors.newSingleThreadExecutor().execute {
            restoreUserPlugins()
        }
        val workspace = workspaceManager.initializeDefaultIfEligible()
        activeSessionId = sessions.listSessions(workspace.id).firstOrNull()?.id
            ?: sessions.createSession(workspace.id, "New conversation").id
    }

    /** Workspace of the session currently open in the UI; null before a session exists. */
    fun activeSessionWorkspaceId(): String? =
        activeSessionId?.let { id -> sessions.getSession(id)?.workspaceId }

    /**
     * Bring back whatever the user installed in an earlier process.
     *
     * Order matters: bundled plugins register first so a user plugin's declared dependencies
     * already exist, and a user plugin must never be able to claim a bundled id (the verifier
     * only trusts the APK catalog, and this path never registers as bundled).
     *
     * A plugin this build cannot host stays `FAILED` with a reason instead of disappearing, so
     * the settings screen can show it rather than silently losing the user's configuration.
     */
    /**
     * Build the management service lazily: it needs the lifecycle manager (created earlier) and
     * the live plugin map (filled by [restoreUserPlugins] and by installs).
     */
    private fun pluginManagerService(): PluginManagerService = AndroidPluginManagerService(
        manager = pluginLifecycle.manager,
        publishedTools = { pluginId -> userPlugins[pluginId]?.publishedTools()?.values?.toList().orEmpty() },
        onSpecInstalled = ::installUserPlugin,
    )

    /**
     * Install one user plugin: construct it, register it, enable it, and remember the live object.
     *
     * @return false when this build cannot construct the plugin, so the manager can report
     *   "unsupported" instead of leaving a half-installed record.
     */
    private fun installUserPlugin(spec: PluginSpec): Boolean {
        if (!spec.id.startsWith(MCP_PLUGIN_ID)) return false
        val plugin = try {
            McpPlugin(spec, conversationCatalog.toolRegistry, HttpMcpClientFactory)
        } catch (failure: Exception) {
            Log.e(TAG, "Cannot construct plugin ${spec.id}", failure)
            return false
        }
        return try {
            pluginLifecycle.manager.register(plugin)
            userPlugins[spec.id] = plugin
            pluginLifecycle.manager.enable(spec.id)
            true
        } catch (failure: Exception) {
            Log.e(TAG, "Installing plugin ${spec.id} failed", failure)
            userPlugins.remove(spec.id)
            runCatching { plugin.onDisable() }
            runCatching { pluginLifecycle.manager.uninstall(spec.id) }
            false
        }
    }

    private fun restoreUserPlugins() {
        val report = try {
            pluginLifecycle.manager.rebuildUserPlugins { spec ->
                if (spec.id.startsWith(MCP_PLUGIN_ID)) {
                    McpPlugin(spec, conversationCatalog.toolRegistry, HttpMcpClientFactory).also {
                        userPlugins[spec.id] = it
                    }
                } else {
                    null
                }
            }
        } catch (failure: Exception) {
            Log.e(TAG, "Restoring user plugins failed", failure)
            return
        }
        if (report.unsupported.isNotEmpty()) {
            Log.e(TAG, "User plugins this build cannot host: ${report.unsupported}")
        }
        if (report.pendingCleanup.isNotEmpty()) {
            Log.e(TAG, "User plugins still need cleanup: ${report.pendingCleanup}")
        }
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
