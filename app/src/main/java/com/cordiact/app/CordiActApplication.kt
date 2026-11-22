package com.cordiact.app

import android.app.Application
import android.util.Log
import android.os.Process
import com.cordiact.app.automation.AutomationRuntime
import com.cordiact.app.conversation.AndroidConversationSessionStore
import com.cordiact.app.conversation.BundledConversationPluginCatalog
import com.cordiact.app.conversation.BundledConversationPlugins
import com.cordiact.app.plugin.AndroidAuthoredPluginInstallStore
import com.cordiact.app.plugin.AndroidPluginLifecycle
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
import com.cordiact.core.plugin.AuthoredPluginDrafts
import com.cordiact.core.plugin.AuthoredPluginForeground
import com.cordiact.core.plugin.AuthoredPluginInstallations
import com.cordiact.core.plugin.AuthoredPluginInstalledRecord
import com.cordiact.core.plugin.AuthoredPluginReceipt
import com.cordiact.core.plugin.AuthoredPluginTurn
import com.cordiact.core.security.AccessEnvelope
import com.cordiact.core.security.EffectiveSecurityProfile
import com.cordiact.core.plugin.PluginKernel
import com.cordiact.core.plugin.AuthoredPluginRuntime
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
class CordiActApplication : Application(), androidx.work.Configuration.Provider {
    // Supports on-demand initialization when AndroidX Startup is not installed by the host.
    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().build()

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
    lateinit var providerApiKeyStore: AndroidProviderApiKeyStore
        private set
    val automationTasks by lazy { com.cordiact.app.automation.tasks.AutomationTaskRuntime(this) }
    fun automationToolSpecs(workspaceId: String, model: ModelId) = availableToolSpecs(workspaceId, model)
    fun automationToolDispatcher(): com.cordiact.core.toolloop.ToolDispatcher = toolDispatcher

    lateinit var turnService: ConversationTurnService
        private set
    lateinit var sessionManagerPlugin: SessionManagerPlugin
        private set
    private lateinit var authoredPlugins: AuthoredPluginRuntime
    internal val authoredPluginForeground = AuthoredPluginForeground()
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

    @Volatile var activeSessionId: String? = null
        private set

    val sandboxManager: SandboxManagerService get() = sandboxManagerPlugin
    val interactionService: com.cordiact.interaction.InteractionService
        get() = conversationCatalog.interactionService
    val fillValues: MutableFillValueStore
        get() = conversationCatalog.fillValueStore
    /** Attachment catalogue (drafts included) and the import pipeline, for the composer. */
    val attachments: com.cordiact.app.attachment.AndroidAttachmentCatalog get() = conversationCatalog.attachmentCatalog
    val attachmentImporter: com.cordiact.app.attachment.AndroidAttachmentImportService
        get() = conversationCatalog.attachmentImportService

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
            startTurn = {},
            tryStartTurn = ::wakeSession,
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

    /** The user's choice of summarisation model; unset means rule-based summaries. */
    private val summaryModelStore by lazy { com.cordiact.app.context.SummaryModelSelectionStore(this) }

    /**
     * Where the user's summarisation-model choice lives.
     *
     * Exposed so the settings surface (designed later, in one pass) has one entry point to read and
     * set it, instead of a second bespoke picker grown here.
     */
    val summaryModelSelection: com.cordiact.app.context.SummaryModelSelectionStore
        get() = summaryModelStore

    internal val contextPolicy = com.cordiact.core.context.ContextPolicy()
    internal val toolOutputGate by lazy {
        com.cordiact.core.context.DefaultToolOutputGate(
            conversationCatalog.spillStore, headChars = contextPolicy.toolHeadChars, tailChars = contextPolicy.toolTailChars,
        )
    }

    private val toolLoopPlugin by lazy {
        ToolLoopPlugin(
            limits = com.cordiact.core.toolloop.ToolLoopLimits(
                maxResultChars = contextPolicy.maxInlineToolChars, foldToolResultsAfter = contextPolicy.foldAfterRounds,
            ),
            gate = toolOutputGate,
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

    val approvalGateway = com.cordiact.core.action.ObservingApprovalGateway(com.cordiact.core.action.DefaultApprovalGateway())

    /** Credential vault + mandatory security gate; lazily opens its private no-backup database. */
    val credentialVaultRuntime: com.cordiact.app.security.CredentialVaultRuntime by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        com.cordiact.app.security.CredentialVaultRuntime.get(this, approvalGateway)
    }

    private val toolDispatcher by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AndroidToolDispatcher(tools, credentialVaultRuntime.sensitivePayloads)
    }

    /**
     * Tools the model may call this turn.
     *
     * Filtered by `dispatchable` rather than merely enabled: a tool whose owner is unavailable
     * or whose capability is missing cannot run, so offering it would only produce a failing
     * call the model has to recover from.
     */
    private fun availableToolSpecs(
        workspaceId: String, model: ModelId, sessionId: String? = null, allowAuthoring: Boolean = false,
    ): List<ModelToolSpec> =
        try {
            AndroidToolDispatcher.turnSpecs(
                tools.resolveForTurn(ToolActivationContext(workspaceId, model.providerId, model.modelId, sessionId)),
            ).filter { allowAuthoring || it.name != com.cordiact.core.plugin.AUTHORED_PLUGIN_TOOL_ID }
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
        val model = when (val result = summaryModelStore.resolve(modelAdapterPlugin.models().map { it.id }.toSet(), com.cordiact.app.model.ConversationModelSelectionStore(this).forSession(sessionId))) {
            is com.cordiact.app.experience.settings.ModelRoleResolution.Resolved -> result.model
            com.cordiact.app.experience.settings.ModelRoleResolution.Disabled -> return
            is com.cordiact.app.experience.settings.ModelRoleResolution.Invalid -> error("Summary model reference invalid: ${result.reason}")
        }
        runCatching { turnService.prepareCompactionCandidate(sessionId, model) }
            .onFailure { Log.e(TAG, "Preparing a compaction candidate for $sessionId failed", it) }
    }

    /**
     * Starts a turn the runtime asked for, not the user.
     *
     * Refuses when no background model is configured rather than picking one: an automatic wake
     * that quietly runs on an unexpected model would be a surprising bill.
     */
    internal val transcriptChanges = kotlinx.coroutines.flow.MutableStateFlow(0L)

    private fun wakeSession(sessionId: String): Boolean {
        if (turnService.isTurnActive(sessionId)) {
            Log.d(TAG, "Session $sessionId is already active; report stays queued for in-flight/subsequent round")
            return false
        }
        val model = runCatching {
            com.cordiact.app.memory.ExtractionModelSelectionStore(this).selectedModel()
        }.getOrNull()
        if (model == null) {
            Log.d(TAG, "No background model configured; report for $sessionId stays queued")
            return false
        }
        return runCatching {
            turnService.submitWoken(
                sessionId = sessionId,
                model = model,
                systemContext = null,
            ) { }
        }.onFailure { Log.e(TAG, "Woken turn for $sessionId failed to start", it) }.isSuccess
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

    internal val modelUsageStore by lazy {
        com.cordiact.app.statistics.AndroidModelUsageStore(this) { sessionId ->
            sessionManagerPlugin.getSession(sessionId)?.workspaceId
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Process.isIsolated()) {
            // Android creates the Application class in every process. The sandbox UID must not
            // initialize the host graph, SQLite helpers, providers, credentials, or plugin registry.
            Log.i(TAG, "Isolated service process: skipping host application initialization")
            return
        }
        pluginLifecycle = AndroidPluginLifecycle(this, BundledConversationPluginCatalog.verifier)
        providerApiKeyStore = AndroidProviderApiKeyStore(this)
        conversationCatalog = BundledConversationPlugins.create(
            this,
            // Attachment access is audited in the same tamper-evident log as other security
            // events. The vault is resolved lazily, on the first attachment event.
            attachmentAudit = { event ->
                com.cordiact.core.security.SecurityAuditAttachmentSink(credentialVaultRuntime.database).record(event)
            },
        )
        // Reference-aware attachment retention runs off the startup path; it only removes objects
        // that no message and no draft reference and that are at least a day old.
        kotlin.concurrent.thread(name = "attachment-gc", isDaemon = true) {
            runCatching { conversationCatalog.runAttachmentGc() }
            runCatching {
                val cameraDir = java.io.File(cacheDir, "composer_camera")
                val cutoff = System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(1)
                cameraDir.listFiles().orEmpty().filter {
                    it.isFile && it.name.startsWith("capture-") && it.name.endsWith(".jpg") && it.lastModified() < cutoff
                }.forEach { it.delete() }
            }
            runCatching {
                (conversationCatalog.spillStore as? com.cordiact.core.context.StandardSpillStore)?.pruneOlderThan(
                    System.currentTimeMillis() - java.util.concurrent.TimeUnit.DAYS.toMillis(contextPolicy.spillRetentionDays.toLong()),
                    conversationCatalog.jobRegistry.listActive().mapNotNull { it.outputPath }.toSet(),
                )
            }
        }
        automationRuntime = AutomationRuntime(this)
        automationRuntime.registerTools(conversationCatalog.toolRegistry, conversationCatalog.jobRegistry)
        val capabilitySettings = com.cordiact.app.settings.CapabilitySettingsStore(this)
        val automationReadiness = com.cordiact.app.settings.AutomationReadiness(this, automationRuntime)


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

        val credentialRuntime = credentialVaultRuntime
        authoredPlugins = AuthoredPluginRuntime(
            // These generated plugins have no services or foreign lifecycle hooks. Keep their
            // commit kernel separate from the interaction kernel, whose stop can cancel turns.
            workspaceFiles, approvalGateway, PluginKernel(), conversationCatalog.toolContributions,
        ) { context ->
            val session = context.sessionId
            val turn = context.authoredPluginTurn
            context.allowsPluginAuthoring && turn != null && turn.isOpen && session != null && session == activeSessionId &&
                workspaceManager.findSessionWorkspace(context.workspaceId) != null &&
                context.securityContext?.let(credentialRuntime::permitsAuthoredPluginChanges) == true &&
                sessions.getSession(session)?.let { it.workspaceId == context.workspaceId &&
                    it.kind == com.cordiact.core.conversation.SessionKind.USER && it.archivedAtMillis == null } == true
        }
        val plugins = conversationCatalog.create(
            stateLookup = { pluginId ->
                // The protected host must be enabled. The exact authored instance is separately
                // checked by its scoped registry binding; no user spec is admitted to this lane.
                val owner = if (com.cordiact.core.plugin.AuthoredPluginNames.isOwner(pluginId))
                    com.cordiact.core.plugin.AUTHORED_PLUGIN_HOST_ID else pluginId
                pluginLifecycle.manager.get(owner)?.state
            },
            capabilityGate = object : com.cordiact.core.tool.ToolCapabilityGate {
                override fun grantsAll(workspaceId: String, capabilityIds: Set<String>): Boolean = capabilityIds.isEmpty()
                override fun grantsTool(workspaceId: String, descriptor: com.cordiact.core.tool.ToolDescriptor): Boolean {
                    val required = descriptor.requiredCapabilities
                    if (required.isEmpty()) return true
                    val record = pluginLifecycle.manager.get(descriptor.ownerPluginId) ?: return false
                    if (record.state != PluginState.ENABLED || !record.spec.requestedCapabilities.containsAll(required)) return false
                    // Host runtime delegation is separate from device/connection consent.
                    val hostRuntime = record.origin == com.cordiact.core.plugin.PluginOrigin.BUNDLED_SYSTEM &&
                        record.spec.id in setOf("core.subagent", "core.subagent-orchestration", "core.subagent-background")
                    if (hostRuntime) return required.all { it in BundledConversationPluginCatalog.BUNDLED_GRANTED_CAPABILITIES }
                    return pluginLifecycle.store.workspaceConsents(workspaceId, record.spec.id).containsAll(required) &&
                        pluginLifecycle.store.activeConsents(record.spec.id).containsAll(required)
                }
            },
            approvalGateway = approvalGateway,
            extraPlugins = listOf(credentialRuntime.credentialPlugin, credentialRuntime.auditPlugin),
            autoReview = credentialRuntime::reviewTool,
            invalidateSessionResources = { session ->
                authoredPluginForeground.invalidateSession(session.workspaceId, session.id)
                authoredPlugins.invalidateSession(session.workspaceId, session.id)
            },
        )
        conversationCatalog.toolManagerPlugin.setWorkspaceVisibilityPolicy { workspaceId, descriptor ->
            com.cordiact.core.settings.WorkspaceCapabilityPolicy.visible(descriptor.ownerPluginId,
                { capabilitySettings.enabled(workspaceId, it) },
                { id -> if (id.startsWith("mcp.")) pluginLifecycle.manager.get("core.$id")?.state == PluginState.ENABLED else id == "search" || id == "privilege" && automationRuntime.privilegeSettings.read().root && automationRuntime.rootChannel.peekReady() || automationReadiness.probe(id).state == com.cordiact.core.settings.ReadinessState.READY })
        }
        modelAdapterPlugin = plugins.filterIsInstance<ModelAdapterPlugin>().single()
        credentialRuntime.attachReviewerClient(
            com.cordiact.model.providers.ModelReviewerClient { modelAdapter },
        )
        sessionManagerPlugin = plugins.filterIsInstance<SessionManagerPlugin>().single()
        // Accounting is observational: the store queues the record and resolves the
        // workspace on its own IO worker, so no lookup runs on the model callback thread.
        modelAdapterPlugin.usageObserver = modelUsageStore::enqueue

        turnService = ConversationTurnService(
            sessions = sessionManagerPlugin,
            models = modelAdapterPlugin,
            notices = backgroundSubagents,
            compactionStore = conversationCatalog.conversationStore,
            contextPolicy = contextPolicy,
            inbox = asyncTurnInbox,
            summaryModelResolver = { conversation ->
                when (val result = summaryModelStore.resolve(modelAdapterPlugin.models().map { it.id }.toSet(), conversation)) {
                    is com.cordiact.app.experience.settings.ModelRoleResolution.Resolved -> result.model
                    com.cordiact.app.experience.settings.ModelRoleResolution.Disabled -> null
                    is com.cordiact.app.experience.settings.ModelRoleResolution.Invalid -> error("Summary model reference invalid: ${result.reason}")
                }
            },
            sensitivePayloads = credentialRuntime.sensitivePayloads,
        )
        // A finished turn is the cheapest moment to think about the next one: the summary can be
        // prepared while nobody is waiting, and installed only if it still applies.
        turnService.onTurnCompleted = { sessionId ->
            synchronized(transcriptChanges) { transcriptChanges.value++ }
            com.cordiact.app.context.ContextCompactionWorker.enqueue(this, sessionId)
        }
        // Registered here because the tool list is built from the shared registry, and the
        // delegation tools must be in it before the first turn assembles its specs.
        // Always published: the preview window is a session surface, not an integration.
        registerPreviewTools(conversationCatalog.toolRegistry)
        com.cordiact.app.image.registerImageGenerationTools(this, conversationCatalog.toolRegistry)
        registerSubagentTools(conversationCatalog.toolRegistry, subagentRuntime)
        registerOrchestrationTools(conversationCatalog.toolRegistry, orchestrationPlugin)
        registerBackgroundSubagentTools(conversationCatalog.toolRegistry, backgroundSubagents)
        credentialVaultRuntime.registerTools(conversationCatalog.toolRegistry)
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
            val authoring = envelope.authoredPluginTurn?.takeIf {
                envelope.command == null && it.isOpen && it.matches(envelope.context.workspaceId, envelope.context.sessionId)
            }
            val security = credentialVaultRuntime.newSecurityContext(
                workspaceId = envelope.context.workspaceId,
                sessionId = envelope.context.sessionId,
                modelProviderId = modelId.providerId,
                modelId = modelId.modelId,
            )
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
                allowsPluginAuthoring = authoring != null && credentialVaultRuntime.permitsAuthoredPluginChanges(security),
                authoredPluginTurn = authoring,
                securityContext = security,
            )
            turnService.submitWithTools(
                sessionId = envelope.context.sessionId,
                model = modelId,
                text = prompt,
                tools = availableToolSpecs(envelope.context.workspaceId, modelId, envelope.context.sessionId, toolContext.allowsPluginAuthoring),
                toolLoop = toolLoopPlugin,
                dispatcher = toolDispatcher,
                toolContext = toolContext,
                parameters = parameters,
                systemContext = skillIndexFor(envelope.context.workspaceId),
                parts = envelope.attachments,
                userMetadataJson = org.json.JSONObject().apply {
                    put("input_version", 1)
                    put("original_input", envelope.originalText)
                    put("expanded_input", envelope.submittedText)
                }.toString(),
                onUserMessagePersisted = {
                    sessions.updateFirstUserTitleFromOriginal(envelope.context.sessionId, envelope.originalText)
                    envelope.onUserMessagePersisted?.invoke()
                },
                onModelCallStarted = envelope.onModelCallStarted,
                onEvent = onEvent,
            )
        })
        sandboxManagerPlugin = BundledConversationPluginCatalog.createSandboxManager(applicationInfo.uid)
        val pluginManagerPlugin = object : ManagedPlugin {
            override val spec: PluginSpec = pluginManagerPluginSpec()
        }
        val authoredPluginHost = object : ManagedPlugin {
            override val spec: PluginSpec = com.cordiact.core.plugin.authoredPluginHostSpec()
        }
        com.cordiact.core.plugin.registerAuthoredPluginTool(conversationCatalog.toolRegistry, authoredPlugins)
        (plugins + automationRuntime.plugins() + sandboxManagerPlugin + subagentRuntime + orchestrationPlugin + backgroundSubagents + pluginManagerPlugin + authoredPluginHost)
            .forEach(::registerAndEnableSystemPlugin)
        com.cordiact.app.automation.tasks.registerAutomationTaskTools(
            conversationCatalog.toolRegistry, { automationTasks }, sessions,
        )
        conversationCatalog.manualCompactionExecutor = com.cordiact.interaction.InteractionCommandExecutor { envelope, model, _, onEvent ->
            val result = turnService.compactManually(envelope.context.sessionId, model, envelope.commandInput)
            if (result != null) onEvent(com.cordiact.core.conversation.ModelEvent.ContextCompacted(
                (result.tokensBefore - result.tokensAfter).toLong(), result.summary,
            ))
            com.cordiact.interaction.CommandExecutionResult(
                completed = true,
                message = if (result == null) "当前历史不足以压缩，原始记录未改变" else
                    "上下文压缩已生效：${result.tokensBefore} → ${result.tokensAfter} tokens；原始记录保留",
            )
        }
        conversationCatalog.targetTaskExecutor = com.cordiact.interaction.InteractionCommandExecutor { envelope, model, _, _ ->
            require(envelope.commandInput.isNotBlank()) { "请输入目标，例如：/目标 检查当前项目并总结待办" }
            require(envelope.attachments.isEmpty() && envelope.mentions.isEmpty()) {
                "一次性任务暂不支持附件或 @ 引用；请将必要内容写入目标正文"
            }
            val sourceSession = sessions.getSession(envelope.context.sessionId)
            check(sourceSession != null && sourceSession.workspaceId == envelope.context.workspaceId &&
                sourceSession.depth == 0 && sourceSession.archivedAtMillis == null) { "当前会话不可创建任务" }
            val runtime = automationTasks
            check(runtime.enabled(envelope.context.workspaceId)) { "请先启用当前工作区的自动化任务插件" }
            // Keep the definition disabled: runNow creates the sole occurrence without a clock replay.
            val task = runtime.service.save(com.cordiact.core.automation.AutomationTask(
                id = "", workspaceId = envelope.context.workspaceId,
                name = envelope.commandInput.lineSequence().first().trim().take(60).ifBlank { "一次性目标" },
                prompt = envelope.commandInput,
                trigger = com.cordiact.core.automation.TaskTrigger(
                    kind = com.cordiact.core.automation.TriggerKind.ONCE,
                    atMillis = System.currentTimeMillis(),
                ),
                enabled = false, maxDurationMinutes = 8,
            ))
            val run = runtime.service.runNow(task.workspaceId, task.id)
            com.cordiact.app.model.ConversationModelSelectionStore(this)
                .setForSession(requireNotNull(run.sessionId), model)
            runtime.kick()
            val receipt = "已创建一次性任务：${task.name}\n任务编号：${task.id}\n可在侧边栏「自动化任务」查看进度与结果。"
            sessions.appendMessage(envelope.context.sessionId, com.cordiact.core.conversation.MessageRole.ASSISTANT, receipt)
            com.cordiact.interaction.CommandExecutionResult(completed = true, message = receipt)
        }
        com.cordiact.app.automation.tasks.AutomationTaskScheduling.install(this)
        // Nothing can legitimately be RUNNING at startup, so anything that says it is belongs to
        // the process that died. Recovering here queues a notice so the parent learns a child was
        // interrupted instead of waiting for it forever.
        runCatching { backgroundSubagents.recoverAbandonedRuns() }
            .onFailure { Log.e(TAG, "Recovering abandoned delegations failed", it) }
        conversationCatalog.mcpCredentialWriter = { name, header, value ->
            com.cordiact.app.settings.McpCredentialStore(this).set(name, com.cordiact.core.mcp.McpCredential(header, value))
        }
        conversationCatalog.mcpInstallApproval = { context, config ->
            approvalGateway.requestApproval(com.cordiact.core.action.ApprovalRequest(
                id = java.util.UUID.randomUUID().toString(), toolName = "mcp.install",
                summary = "安装 MCP ${config.serverName}：${java.net.URI(config.url).host}。服务器将提供可由模型调用的工具。",
                details = mapOf("workspace" to context.workspaceId, "server" to config.serverName),
                owner = com.cordiact.core.action.ApprovalOwner(context.workspaceId, context.sessionId),
                riskLevel = com.cordiact.core.action.ApprovalRequest.RiskLevel.HIGH,
            )).approved
        }
        conversationCatalog.pluginManagerService = pluginManagerService()
        // Restore user plugins in the background so slow/offline MCP servers do not block Application.onCreate() or trigger NetworkOnMainThreadException.
        java.util.concurrent.Executors.newSingleThreadExecutor().execute {
            restoreUserPlugins()
        }
        val workspace = workspaceManager.initializeDefaultIfEligible()
        val initialSessionId = sessions.listSessions(workspace.id).firstOrNull()?.id
            ?: sessions.createSession(workspace.id, "New conversation").id
        selectForegroundSession(initialSessionId)
    }

    /** Workspace of the session currently open in the UI; null before a session exists. */
    /** Select the UI's foreground session only after resolving its workspace. */
    fun selectForegroundSession(sessionId: String) {
        val session = requireNotNull(sessions.getSession(sessionId)) { "Session does not exist" }
        require(workspaceManager.activeWorkspace().id == session.workspaceId) { "Session is outside active workspace" }
        authoredPluginForeground.select(session.workspaceId, sessionId)
        activeSessionId = sessionId
    }

    fun activeSessionWorkspaceId(): String? =
        activeSessionId?.let { id -> sessions.getSession(id)?.workspaceId }

    private val authoredPluginDrafts by lazy { AuthoredPluginDrafts(workspaceFiles) }
    private val authoredPluginInstallations by lazy {
        AuthoredPluginInstallations(workspaceFiles, AndroidAuthoredPluginInstallStore(this))
    }

    /** Trusted workbench actions. The UI supplies only the currently open, persisted user session. */
    internal fun listSessionPluginDrafts(context: InteractionContext, cursor: String? = null) =
        withOpenPluginSession(context) { authoredPluginDrafts.list(context.workspaceId, cursor) }

    internal fun previewSessionPluginDraft(context: InteractionContext, pluginId: String) =
        withOpenPluginSession(context) { authoredPluginDrafts.preview(context.workspaceId, pluginId) }

    internal fun sessionPluginProfile(context: InteractionContext): EffectiveSecurityProfile =
        withOpenPluginSession(context) { credentialVaultRuntime.profileStore.profileFor(context.workspaceId) }

    /** Only the actual preview dialog may call this, after its explicit confirmation. */
    internal fun activateSessionPlugin(
        context: InteractionContext,
        confirmation: AuthoredPluginTurn,
        expectedProfile: EffectiveSecurityProfile,
        pluginId: String,
        sha256: String,
    ): AuthoredPluginReceipt {
        require(confirmation.matches(context.workspaceId, context.sessionId)) { "插件确认不属于该会话" }
        return authoredPlugins.activateForSession(confirmation, pluginId, sha256) {
            canConfirmPluginChange(context, expectedProfile)
        }
    }

    internal fun saveSessionPluginVersion(
        context: InteractionContext,
        confirmation: AuthoredPluginTurn,
        expectedProfile: EffectiveSecurityProfile,
        pluginId: String,
        sha256: String,
    ): AuthoredPluginInstalledRecord {
        require(confirmation.matches(context.workspaceId, context.sessionId)) { "插件确认不属于该会话" }
        return authoredPluginInstallations.saveConfirmed(confirmation, pluginId, sha256) {
            canConfirmPluginChange(context, expectedProfile)
        }
    }

    internal fun listWorkspacePluginVersions(context: InteractionContext) =
        withOpenPluginSession(context) { authoredPluginInstallations.list(context.workspaceId) }

    internal fun previewSavedPluginVersion(context: InteractionContext, record: AuthoredPluginInstalledRecord) =
        withOpenPluginSession(context) {
            require(record.workspaceId == context.workspaceId)
            authoredPluginInstallations.previewSaved(context.workspaceId, record.id, record.sha256, record.installationId)
        }

    internal fun activateSavedPluginVersion(
        context: InteractionContext,
        confirmation: AuthoredPluginTurn,
        expectedProfile: EffectiveSecurityProfile,
        record: AuthoredPluginInstalledRecord,
    ): AuthoredPluginReceipt {
        require(confirmation.matches(context.workspaceId, context.sessionId) && record.workspaceId == context.workspaceId)
        return authoredPlugins.activateSavedForSession(authoredPluginInstallations, confirmation, record.id, record.sha256, record.installationId) {
            canConfirmPluginChange(context, expectedProfile)
        }
    }

    internal fun removeSavedPluginVersion(
        context: InteractionContext, confirmation: AuthoredPluginTurn,
        expectedProfile: EffectiveSecurityProfile, expected: AuthoredPluginInstalledRecord,
    ) {
        require(confirmation.matches(context.workspaceId, context.sessionId) && expected.workspaceId == context.workspaceId)
        authoredPluginInstallations.removeConfirmed(confirmation, expected) { canConfirmPluginChange(context, expectedProfile) }
    }

    internal fun replaceSavedPluginVersion(
        context: InteractionContext, confirmation: AuthoredPluginTurn,
        expectedProfile: EffectiveSecurityProfile, expected: AuthoredPluginInstalledRecord, newSha256: String,
    ): AuthoredPluginInstalledRecord {
        require(confirmation.matches(context.workspaceId, context.sessionId) && expected.workspaceId == context.workspaceId)
        return authoredPluginInstallations.replaceConfirmed(confirmation, expected, newSha256) { canConfirmPluginChange(context, expectedProfile) }
    }

    private fun canConfirmPluginChange(context: InteractionContext, expectedProfile: EffectiveSecurityProfile): Boolean =
        isOpenPluginSession(context) && expectedProfile.workspaceId == context.workspaceId &&
            expectedProfile.accessEnvelope != AccessEnvelope.READ_ONLY &&
            credentialVaultRuntime.profileStore.profileFor(context.workspaceId) == expectedProfile

    internal fun listSessionPlugins(context: InteractionContext): List<AuthoredPluginReceipt> =
        withOpenPluginSession(context) { authoredPlugins.listForSession(context.workspaceId, context.sessionId) }

    private inline fun <T> withOpenPluginSession(context: InteractionContext, read: () -> T): T {
        requireOpenPluginSession(context)
        return read().also { requireOpenPluginSession(context) }
    }

    internal fun revokeSessionPlugin(context: InteractionContext, receipt: AuthoredPluginReceipt) {
        requireOpenPluginSession(context)
        authoredPlugins.revokeForSession(context.workspaceId, context.sessionId, receipt)
    }

    private fun requireOpenPluginSession(context: InteractionContext) {
        require(isOpenPluginSession(context)) { "该会话已离开、归档或不可用" }
    }

    private fun isOpenPluginSession(context: InteractionContext): Boolean {
        val session = sessions.getSession(context.sessionId) ?: return false
        return session.id == activeSessionId && session.workspaceId == context.workspaceId &&
            session.kind == com.cordiact.core.conversation.SessionKind.USER && session.archivedAtMillis == null &&
            workspaceManager.findSessionWorkspace(context.workspaceId) != null
    }

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
    private val builtinCapabilityInventory by lazy {
        val settings = com.cordiact.app.settings.CapabilitySettingsStore(this)
        val readiness = com.cordiact.app.settings.AutomationReadiness(this, automationRuntime)
        com.cordiact.app.plugin.BuiltinCapabilityInventory(conversationCatalog.toolRegistry, settings::enabled, readiness::probe)
    }

    fun hasPluginCapabilityConsent(workspaceId: String, capability: String): Boolean {
        val records = pluginLifecycle.manager.all().filter {
            com.cordiact.core.settings.WorkspaceCapabilityPolicy.capabilityFor(it.spec.id) == capability
        }
        return records.isNotEmpty() && records.all { record ->
            pluginLifecycle.store.workspaceConsents(workspaceId, record.spec.id).containsAll(record.spec.requestedCapabilities)
        }
    }

    /** Settings-only consent entry point; never called by model/plugin-manager tools. */
    fun setPluginCapabilityConsent(workspaceId: String, capability: String, enabled: Boolean) {
        require(workspaceManager.get(workspaceId) != null) { "WORKSPACE_UNAVAILABLE" }
        pluginLifecycle.manager.all().filter {
            com.cordiact.core.settings.WorkspaceCapabilityPolicy.capabilityFor(it.spec.id) == capability
        }.forEach { pluginLifecycle.store.setWorkspaceConsent(workspaceId, it.spec.id, enabled) }
    }

    fun pluginManagerService(): PluginManagerService = AndroidPluginManagerService(
        bundledInventory = builtinCapabilityInventory::entries,
        workspaceProjection = builtinCapabilityInventory::project,
        manager = pluginLifecycle.manager,
        publishedTools = { pluginId -> userPlugins[pluginId]?.publishedTools()?.values?.toList().orEmpty() },
        onSpecInstalled = ::installUserPlugin,
        probeMcp = { config ->
            val credential = com.cordiact.app.settings.McpCredentialStore(this).readForExecution(config.serverName)
            val connection = HttpMcpClientFactory.connect(config, credential)
            try { connection.listTools() } finally { connection.close() }
        },
        afterRemoved = { id ->
            if (id.startsWith("$MCP_PLUGIN_ID.")) {
                userPlugins.remove(id)
                com.cordiact.app.settings.McpCredentialStore(this).set(id.removePrefix("$MCP_PLUGIN_ID."), null)
            }
        },
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
            McpPlugin(spec, conversationCatalog.toolRegistry, HttpMcpClientFactory,
                credentialProvider = { name -> com.cordiact.app.settings.McpCredentialStore(this).readForExecution(name) })
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
                    McpPlugin(spec, conversationCatalog.toolRegistry, HttpMcpClientFactory,
                credentialProvider = { name -> com.cordiact.app.settings.McpCredentialStore(this).readForExecution(name) }).also {
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
