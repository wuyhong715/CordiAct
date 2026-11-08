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
import com.cordiact.model.providers.ModelProviderConfig
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
    val sessionId: String? = null,
    val models: List<ModelDescriptor> = emptyList(),
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
                    mutable.update { it.copy(stoppingSessions = it.stoppingSessions - entry.session) }
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

    fun selectSession(id: String) = loadSession(id, null)
    fun selectWorkspace(id: String) = loadSession(null, id)
    fun newSession() = loadSession(null, mutable.value.workspace?.id, create = true)

    private fun loadSession(sessionId: String?, workspaceId: String?, create: Boolean = false) {
        val version = ++selectionVersion
        loadingSession = true
        mutable.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    val workspace = workspaceId?.let { app.workspaceManager.get(it) }
                        ?: sessionId?.let { app.sessions.getSession(it)?.workspaceId }?.let(app.workspaceManager::get)
                        ?: app.workspaceManager.activeWorkspace()
                    val sessions = app.sessions.listSessions(workspace.id)
                    val selected = when {
                        create -> app.sessions.createSession(workspace.id, "新对话")
                        sessionId != null -> requireNotNull(app.sessions.getSession(sessionId)) { "会话不存在" }
                        workspaceId != null -> sessions.firstOrNull() ?: app.sessions.createSession(workspace.id, "新对话")
                        else -> app.activeSessionId?.let(app.sessions::getSession)?.takeIf { it.workspaceId == workspace.id }
                            ?: sessions.firstOrNull() ?: app.sessions.createSession(workspace.id, "新对话")
                    }
                    require(selected.workspaceId == workspace.id)
                    Loaded(workspace, app.workspaceManager.list(), app.sessions.listSessions(workspace.id), selected,
                        app.modelAdapter.models(), app.sessions.messages(selected.id).map { StoredMessage(it.id, it.role.name, it.content, it.createdAtMillis) })
                }
                if (version != selectionVersion) return@launch
                selectionMutex.withLock {
                    if (version != selectionVersion) return@launch
                    withContext(Dispatchers.IO) {
                        app.workspaceManager.select(snapshot.workspace.id)
                        app.selectForegroundSession(snapshot.selected.id)
                    }
                }
                if (version != selectionVersion) return@launch
                cache.getOrPut(snapshot.selected.id) { restoreTranscript(snapshot.messages) }
                val preferred = mutable.value.model ?: readModel()
                val model = snapshot.models.find { it.id == preferred }?.id
                    ?: snapshot.models.firstOrNull { it.id.modelId == "deepseek-flash" }?.id ?: snapshot.models.firstOrNull()?.id
                mutable.update { it.copy(loading = false, workspace = snapshot.workspace, workspaces = snapshot.workspaces,
                    sessions = snapshot.sessions, sessionId = snapshot.selected.id, models = snapshot.models, model = model,
                    turns = cache[snapshot.selected.id].orEmpty(), error = null) }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { if (version == selectionVersion) mutable.update { it.copy(loading = false, error = error.message ?: "读取会话失败") } }
            finally { if (version == selectionVersion) loadingSession = false }
        }
    }

    private data class Loaded(val workspace: Workspace, val workspaces: List<Workspace>, val sessions: List<ConversationSession>,
        val selected: ConversationSession, val models: List<ModelDescriptor>, val messages: List<StoredMessage>)

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
    /** Explicit user save only. Credentials go to Keystore storage, never config/state/drafts. */
    fun addProvider(name: String, baseUrl: String, modelIds: List<String>, protocol: ModelProtocol, apiKey: String, done: (String?) -> Unit) {
        viewModelScope.launch {
            try {
                val models = withContext(Dispatchers.IO) {
                    require(name.isNotBlank()) { "请输入来源名称" }
                    require(baseUrl.startsWith("https://")) { "模型地址必须使用 HTTPS" }
                    require(modelIds.isNotEmpty() && modelIds.none { it.isBlank() }) { "至少填写一个模型 ID" }
                    require(apiKey.isNotBlank()) { "请输入 API Key" }
                    val id = "custom-${UUID.randomUUID().toString().take(12)}"
                    app.providerApiKeyStore.set(id, apiKey.trim())
                    try {
                        app.modelProviders.addProvider(ModelProviderConfig(id, name.trim(), protocol, baseUrl.trim(), modelIds.distinct()))
                    } catch (error: Exception) {
                        app.providerApiKeyStore.clear(id)
                        throw error
                    }
                    app.modelAdapter.models()
                }
                mutable.update { it.copy(models = models) }
                done(null)
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { done(error.message ?: "保存来源失败") }
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
        mutable.update { it.copy(turns = cache[session].orEmpty(), error = null) }
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
