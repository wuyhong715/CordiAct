package com.cordiact.app.experience

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cordiact.app.CordiActApplication
import com.cordiact.app.experience.model.*
import com.cordiact.core.action.ApprovalDecision
import com.cordiact.core.action.ApprovalRequest
import com.cordiact.core.conversation.*
import com.cordiact.core.workspace.Workspace
import com.cordiact.interaction.InteractionContext
import com.cordiact.model.providers.ModelProtocol
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class ExperienceState(
    val loading: Boolean = true,
    val workspace: Workspace? = null,
    val workspaces: List<Workspace> = emptyList(),
    val sessions: List<ConversationSession> = emptyList(),
    val sidebar: List<SidebarWorkspace> = emptyList(),
    val runningSessionIds: Set<String> = emptySet(),
    val sessionId: String? = null,
    val models: List<ModelDescriptor> = emptyList(),
    val providerNames: Map<String, String> = emptyMap(),
    val model: ModelId? = null,
    val turns: List<TranscriptTurn> = emptyList(),
    val approvals: List<ApprovalRequest> = emptyList(),
    val error: String? = null,
    val stoppingSessions: Set<String> = emptySet(),
) {
    val running get() = turns.any { it.isRunning }
    val stopping get() = sessionId in stoppingSessions
    val activeTools get() = turns.flatMap { it.tools }.count { it.phase == ToolPhase.RUNNING }
}

/** Lifecycle-owned coordinator. Disk/plugin work is IO; one channel serializes provider callbacks. */
class ExperienceViewModel(private val app: CordiActApplication) : ViewModel() {
    private val mutable = MutableStateFlow(ExperienceState())
    val state = mutable.asStateFlow()
    private val cache = mutableMapOf<String, List<TranscriptTurn>>()
    private val calls = mutableMapOf<String, OwnedCall>()
    private val events = Channel<OwnedEvent>(Channel.UNLIMITED)
    private val selectionMutex = Mutex()
    private var selectionVersion = 0L
    private var loadingSession = false

    private class OwnedCall(val turnId: String) {
        private val cancelled = AtomicBoolean(false)
        private val handle = AtomicReference<ModelCall?>(null)
        private val job = AtomicReference<Job?>(null)
        fun attach(call: ModelCall) { handle.set(call); if (cancelled.get()) call.cancel() }
        fun attachJob(value: Job) { job.set(value); if (cancelled.get()) value.cancel() }
        fun cancel() { cancelled.set(true); job.get()?.cancel(); handle.get()?.cancel() }
    }
    private data class OwnedEvent(val session: String, val turn: String, val event: TranscriptEvent)

    init {
        viewModelScope.launch {
            for (entry in events) {
                val turns = cache[entry.session] ?: continue
                cache[entry.session] = turns.map { if (it.id == entry.turn) it.reduce(entry.event) else it }
                val owned = calls[entry.session]?.turnId == entry.turn
                if (entry.event is TranscriptEvent.Finished && owned) {
                    calls.remove(entry.session)
                    mutable.update { it.copy(stoppingSessions = it.stoppingSessions - entry.session, runningSessionIds = it.runningSessionIds - entry.session) }
                }
                if (mutable.value.sessionId == entry.session) mutable.update { it.copy(turns = cache[entry.session].orEmpty()) }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                val pending = withContext(Dispatchers.IO) { runCatching { app.approvalGateway.pendingRequests() }.getOrDefault(emptyList()) }
                mutable.update { it.copy(approvals = pending) }
                delay(400)
            }
        }
        refresh()
    }

    fun refresh() {
        if (loadingSession) return
        loadSession(null, null)
    }

    fun selectSession(id: String, onSelected: () -> Unit = {}) = loadSession(id, null, onSelected = onSelected)
    fun selectWorkspace(id: String, onSelected: () -> Unit = {}) = loadSession(null, id, onSelected = onSelected)
    fun newSession(workspaceId: String? = mutable.value.workspace?.id, onSelected: () -> Unit = {}) =
        loadSession(null, workspaceId, create = true, onSelected = onSelected)
    fun createWorkspace(name: String, onSelected: () -> Unit = {}) =
        loadSession(null, null, create = true, newWorkspaceName = name, onSelected = onSelected)
    fun renameWorkspace(id: String, name: String, onSuccess: () -> Unit = {}) {
        if (loadingSession) return
        val error = sidebarNameError(name)
        if (error != null) { mutable.update { it.copy(error = error) }; return }
        loadingSession = true
        mutable.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val renamed = withContext(Dispatchers.IO) { app.workspaceManager.rename(id, name.trim()) }
                mutable.update { state -> state.copy(loading = false,
                    workspace = if (state.workspace?.id == id) renamed else state.workspace,
                    workspaces = state.workspaces.map { if (it.id == id) renamed else it },
                    sidebar = state.sidebar.map { if (it.id == id) it.copy(name = renamed.name, updatedAtMillis = renamed.updatedAtMillis) else it },
                ) }
                onSuccess()
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { mutable.update { it.copy(loading = false, error = failure.message ?: "重命名失败") } }
            finally { loadingSession = false }
        }
    }

    private fun loadSession(
        sessionId: String?, workspaceId: String?, create: Boolean = false,
        newWorkspaceName: String? = null, onSelected: () -> Unit = {},
    ) {
        // Serialize user navigation and mutations: a rapid double tap must not create two sessions.
        if (loadingSession) return
        if (newWorkspaceName != null) {
            val error = sidebarNameError(newWorkspaceName)
            if (error != null) { mutable.update { it.copy(error = error) }; return }
        }
        val version = ++selectionVersion
        loadingSession = true
        mutable.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    val requested = sessionId?.let { requireNotNull(app.sessions.getSession(it)) { "会话不存在" } }
                    val workspace = when {
                        newWorkspaceName != null -> app.workspaceManager.create(newWorkspaceName.trim())
                        workspaceId != null -> requireNotNull(app.workspaceManager.get(workspaceId)) { "工作区不存在" }
                        requested != null -> requireNotNull(app.workspaceManager.get(requested.workspaceId)) { "工作区不存在" }
                        else -> app.workspaceManager.activeWorkspace()
                    }
                    require(workspace.archivedAtMillis == null) { "工作区已归档" }
                    val sessions = app.sessions.listSessions(workspace.id).filter { it.kind == SessionKind.USER }
                        .sortedWith(compareByDescending<ConversationSession> { it.updatedAtMillis }.thenBy { it.id })
                    val selected = when {
                        create -> app.sessions.createSession(workspace.id, "新对话")
                        requested != null -> requested.also { require(it.kind == SessionKind.USER) { "无法选择内部执行会话" } }
                        workspaceId != null -> sessions.firstOrNull() ?: app.sessions.createSession(workspace.id, "新对话")
                        else -> app.activeSessionId?.let(app.sessions::getSession)?.takeIf { it.workspaceId == workspace.id && it.kind == SessionKind.USER }
                            ?: sessions.firstOrNull() ?: app.sessions.createSession(workspace.id, "新对话")
                    }
                    require(selected.workspaceId == workspace.id)
                    val workspaces = app.workspaceManager.list()
                    val sidebar = workspaces.map { item ->
                        SidebarWorkspace(item.id, item.name, item.updatedAtMillis,
                            app.sessions.listSessions(item.id).filter { it.kind == SessionKind.USER }.map {
                                SidebarConversation(it.id, it.title, it.updatedAtMillis)
                            })
                    }
                    Loaded(workspace, workspaces, app.sessions.listSessions(workspace.id).filter { it.kind == SessionKind.USER }, selected,
                        app.modelAdapter.models(), app.sessions.messages(selected.id).map { StoredMessage(it.id, it.role.name, it.content, it.createdAtMillis) }, sidebar)
                }
                if (version != selectionVersion) return@launch
                selectionMutex.withLock {
                    withContext(Dispatchers.IO) {
                        val previous = app.workspaceManager.activeWorkspace().id
                        try {
                            app.workspaceManager.select(snapshot.workspace.id)
                            app.selectForegroundSession(snapshot.selected.id)
                        } catch (failure: Exception) {
                            runCatching { app.workspaceManager.select(previous) }
                            throw failure
                        }
                    }
                }
                cache.getOrPut(snapshot.selected.id) { restoreTranscript(snapshot.messages) }
                val preferred = mutable.value.model ?: readModel()
                val model = snapshot.models.find { it.id == preferred }?.id
                    ?: snapshot.models.firstOrNull()?.id
                mutable.update { it.copy(loading = false, workspace = snapshot.workspace, workspaces = snapshot.workspaces,
                    sessions = snapshot.sessions, sidebar = snapshot.sidebar, sessionId = snapshot.selected.id, models = snapshot.models, providerNames = app.modelProviders.providers().associate { it.providerId to it.displayName }, model = model,
                    turns = cache[snapshot.selected.id].orEmpty(), error = null) }
                onSelected()
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (version == selectionVersion) mutable.update { it.copy(loading = false, error = error.message ?: "读取会话失败") } }
            finally { if (version == selectionVersion) loadingSession = false }
        }
    }

    private data class Loaded(val workspace: Workspace, val workspaces: List<Workspace>, val sessions: List<ConversationSession>,
        val selected: ConversationSession, val models: List<ModelDescriptor>, val messages: List<StoredMessage>, val sidebar: List<SidebarWorkspace>)

    private fun readModel(): ModelId? {
        val prefs = app.getSharedPreferences("experience", 0)
        val provider = prefs.getString("provider", null) ?: return null
        return prefs.getString("model", null)?.let { ModelId(provider, it) }
    }
    fun selectModel(model: ModelId) {
        if (mutable.value.models.none { it.id == model }) return
        mutable.update { it.copy(model = model) }
        app.getSharedPreferences("experience", 0).edit().putString("provider", model.providerId).putString("model", model.modelId).apply()
    }
    /** Explicit user connection: credentials never enter UI state, config or drafts. */
    fun addProvider(name: String, baseUrl: String, protocol: ModelProtocol, apiKey: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val models = withContext(Dispatchers.IO) {
                    val id = "provider-${UUID.randomUUID()}"
                    val displayName = name.trim().ifBlank { runCatching { java.net.URI(baseUrl.trim().replace("{model}", "model")).host }.getOrNull() ?: "API Provider" }
                    val connection = com.cordiact.model.providers.ModelProviderConnection(id, displayName, protocol, baseUrl)
                    app.modelProviders.discoverAndAddProvider(connection, apiKey.trim())
                    app.modelAdapter.models()
                }
                mutable.update { current -> current.copy(models = models,
                    providerNames = app.modelProviders.providers().associate { config -> config.providerId to config.displayName },
                    model = current.model?.takeIf { selected -> models.any { it.id == selected } } ?: models.firstOrNull()?.id) }
                done(null)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { done(failure.message ?: "拉取模型失败") }
        }
    }

    fun refreshProvider(providerId: String) {
        viewModelScope.launch {
            try {
                val models = withContext(Dispatchers.IO) {
                    app.modelProviders.refreshModels(providerId)
                    app.modelAdapter.models()
                }
                mutable.update { current -> current.copy(models = models,
                    model = current.model?.takeIf { selected -> models.any { it.id == selected } } ?: models.firstOrNull()?.id) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { mutable.update { it.copy(error = failure.message ?: "刷新模型失败") } }
        }
    }

    fun dismissError() { mutable.update { it.copy(error = null) } }

    /** Returns false when not accepted; the composer then keeps its draft. */
    fun send(text: String): Boolean {
        val state = mutable.value
        val session = state.sessionId ?: return false
        val workspace = state.workspace ?: return false
        val model = state.model ?: run { mutable.update { it.copy(error = "请先配置并选择模型") }; return false }
        if (state.loading || state.running || state.stopping || text.isBlank() || calls.containsKey(session)) return false
        val turnId = UUID.randomUUID().toString()
        val owner = OwnedCall(turnId)
        calls[session] = owner
        val turn = TranscriptTurn(turnId, text.trim(), phase = RunPhase.RUNNING, startedAt = System.currentTimeMillis())
        cache[session] = cache[session].orEmpty() + turn
        mutable.update { it.copy(turns = cache[session].orEmpty(), error = null, runningSessionIds = it.runningSessionIds + session) }
        val requestJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val requestContext = currentCoroutineContext()
                runInterruptible(Dispatchers.IO) {
                requestContext.ensureActive()
                val prepared = app.interactionService.prepare(text.trim(), InteractionContext(session, workspace.id))
                require(prepared.diagnostics.isEmpty()) { prepared.diagnostics.joinToString("\n") { it.toString() } }
                val callback: (ModelEvent) -> Unit = { event ->
                    val now = System.currentTimeMillis()
                    val rendered = when (event) {
                        is ModelEvent.Delta -> TranscriptEvent.Answer(event.text)
                        is ModelEvent.Thinking -> TranscriptEvent.Reasoning(event.text)
                        is ModelEvent.ToolCallStarted -> TranscriptEvent.ToolStarted(event.callId, event.toolName, event.argumentsJson, now)
                        is ModelEvent.ToolCallCompleted -> TranscriptEvent.ToolFinished(event.callId, event.toolName, event.success, event.summary, event.durationMillis, now)
                        is ModelEvent.Completed -> TranscriptEvent.Finished(RunPhase.COMPLETE, now, input = event.inputTokens, output = event.outputTokens)
                        is ModelEvent.Failed -> TranscriptEvent.Finished(RunPhase.FAILED, now, "${event.code}: ${event.message}")
                        else -> null
                    }
                    if (rendered != null) events.trySend(OwnedEvent(session, turnId, rendered))
                }
                requestContext.ensureActive()
                if (prepared.envelope?.command == null) owner.attach(app.interactionService.submitOrdinary(prepared, model, onEvent = callback))
                else {
                    val result = app.interactionService.dispatch(prepared, model, onEvent = callback)
                    result.modelCall?.let(owner::attach) ?: run {
                        result.message?.let { events.trySend(OwnedEvent(session, turnId, TranscriptEvent.Answer(it))) }
                        events.trySend(OwnedEvent(session, turnId, TranscriptEvent.Finished(if (result.completed) RunPhase.COMPLETE else RunPhase.FAILED, System.currentTimeMillis(), if (result.completed) null else result.message)))
                    }
                }
                }
            } catch (cancel: CancellationException) { owner.cancel(); throw cancel }
            catch (error: Exception) { events.trySend(OwnedEvent(session, turnId, TranscriptEvent.Finished(RunPhase.FAILED, System.currentTimeMillis(), error.message ?: "请求失败"))) }
        }
        owner.attachJob(requestJob)
        requestJob.invokeOnCompletion { cause ->
            if (cause != null) events.trySend(OwnedEvent(session, turnId, TranscriptEvent.Finished(RunPhase.CANCELLED, System.currentTimeMillis())))
        }
        requestJob.start()
        return true
    }

    fun stop() {
        val session = mutable.value.sessionId ?: return
        val owner = calls[session] ?: return
        if (mutable.value.stopping) return
        cache[session] = cache[session].orEmpty().map { if (it.id == owner.turnId) it.reduce(TranscriptEvent.Finished(RunPhase.CANCELLED, System.currentTimeMillis())) else it }
        mutable.update { it.copy(turns = cache[session].orEmpty(), stoppingSessions = it.stoppingSessions + session) }
        viewModelScope.launch(Dispatchers.IO) {
            app.turnService.cancelTurn(session)
            owner.cancel()
            events.trySend(OwnedEvent(session, owner.turnId, TranscriptEvent.Finished(RunPhase.CANCELLED, System.currentTimeMillis())))
        }
    }

    fun decide(requestId: String, approved: Boolean) {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { app.approvalGateway.respond(requestId, ApprovalDecision(approved = approved, rememberChoice = false)) } }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutable.update { it.copy(error = error.message ?: "审批提交失败") } }
        }
    }
    override fun onCleared() {
        // Cancel the session-level controller, including calls whose submitter hasn't returned yet.
        calls.forEach { (session, call) -> runCatching { call.cancel(); app.turnService.cancelTurn(session) } }
        events.close()
        super.onCleared()
    }
    companion object {
        fun factory(app: CordiActApplication) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(ExperienceViewModel::class.java))
                return ExperienceViewModel(app) as T
            }
        }
    }
}
