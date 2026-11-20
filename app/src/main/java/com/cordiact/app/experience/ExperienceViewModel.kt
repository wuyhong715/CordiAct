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
    val decidingApprovals: Set<String> = emptySet(),
    val approvalErrors: Map<String, String> = emptyMap(),
    val error: String? = null,
    val stoppingSessions: Set<String> = emptySet(),
    val undoArchivedSessionId: String? = null,
) {
    val approvalOwners get() = approvals.mapNotNull { request -> request.owner?.takeIf { it.sessionId != null } }.toSet()
    val currentApprovals get() = approvals.filter { request ->
        val owner = request.owner ?: return@filter false
        workspace?.id == owner.workspaceId && sessionId != null && sessionId == owner.sessionId
    }
    val running get() = turns.any { it.isRunning }
    val stopping get() = sessionId in stoppingSessions
    val archived get() = sessions.any { it.id == sessionId && it.archivedAtMillis != null }
    val activeTools get() = turns.flatMap { it.tools }.count { it.phase == ToolPhase.RUNNING }
}

/** Lifecycle-owned coordinator. Disk/plugin work is IO; one channel serializes provider callbacks. */
class ExperienceViewModel(private val app: CordiActApplication) : ViewModel() {
    private val mutable = MutableStateFlow(ExperienceState())
    val state = mutable.asStateFlow()
    private val settledApprovals = linkedSetOf<String>()
    private val cache = LinkedHashMap<String, List<TranscriptTurn>>(16, 0.75f, true)
    private val stored = mutableMapOf<String, List<StoredMessage>>()

    private fun reconcile(session: String, messages: List<StoredMessage>): List<TranscriptTurn> {
        val current = cache[session]
        if (calls.containsKey(session) && current != null) return current
        if (current != null && stored[session] == messages) return current
        val restored = restoreTranscript(messages)
        cache[session] = restored
        stored[session] = messages
        while (cache.size > 12) {
            val eldest = cache.keys.firstOrNull { it != session && !calls.containsKey(it) } ?: break
            cache.remove(eldest)
            stored.remove(eldest)
        }
        return restored
    }
    private val calls = mutableMapOf<String, OwnedCall>()
    private val events = Channel<OwnedEvent>(Channel.UNLIMITED)
    private val selectionMutex = Mutex()
    private val metadataMutex = Mutex()
    private var selectionVersion = 0L
    private var loadingSession = false

    val composerController = com.cordiact.app.experience.composer.ComposerController(app, viewModelScope)
    val composer = composerController.state

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
                    refreshSessionMetadata()
                    mutable.update { it.copy(stoppingSessions = it.stoppingSessions - entry.session, runningSessionIds = it.runningSessionIds - entry.session) }
                }
                if (mutable.value.sessionId == entry.session) mutable.update { it.copy(turns = cache[entry.session].orEmpty()) }
            }
        }
        viewModelScope.launch {
            while (isActive) {
                val pending = withContext(Dispatchers.IO) { try { app.approvalGateway.pendingRequests() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null } }
                if (pending != null) mutable.update { current ->
                    val active = pending.filterNot { it.id in settledApprovals }
                    current.copy(approvals = active, approvalErrors = current.approvalErrors.filterKeys { id -> active.any { it.id == id } })
                }
                delay(400)
            }
        }
        viewModelScope.launch {
            app.transcriptChanges.collect {
                val state = mutable.value
                val session = state.sessionId ?: return@collect
                val workspace = state.workspace ?: return@collect
                if (calls.containsKey(session)) return@collect
                val version = selectionVersion
                val messages = try { withContext(Dispatchers.IO) { readTranscript(workspace.id, session) } }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { return@collect }
                if (version == selectionVersion && mutable.value.sessionId == session && !calls.containsKey(session)) {
                    val turns = reconcile(session, messages)
                    mutable.update { it.copy(turns = turns) }
                }
            }
        }
        refresh()
    }

    fun refresh() {
        if (loadingSession) return
        loadSession(null, null)
    }

    fun selectSession(id: String, onSelected: () -> Unit = {}) = loadSession(id, null, onSelected = onSelected)
    /** Used by landscape navigation: no speculative controller selection or silent failure. */
    internal fun selectWorkbenchSession(id: String, shouldCommit: () -> Boolean, complete: () -> Unit) =
        loadSession(id, null, onSelected = complete, onFailed = complete, shouldCommit = shouldCommit)

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

    fun renameSession(id: String, title: String, onSuccess: () -> Unit = {}) =
        mutateSession(id, { app.sessions.renameSession(id, title) }, onSuccess = onSuccess)

    fun pinSession(id: String, pinned: Boolean) =
        mutateSession(id, { app.sessions.setSessionPinned(id, pinned) })

    fun archiveSession(id: String) = mutateSession(id, { app.sessions.archiveSession(id) }, onSuccess = {
        mutable.update { it.copy(undoArchivedSessionId = id) }
        // A new foreground conversation does not cancel the archived call or its child tasks.
        if (mutable.value.sessionId == id) newSession(mutable.value.workspace?.id)
    })

    fun restoreSession(id: String, onSuccess: () -> Unit = {}) =
        mutateSession(id, { app.sessions.restoreSession(id) }, onSuccess = {
            mutable.update { it.copy(undoArchivedSessionId = it.undoArchivedSessionId?.takeUnless { saved -> saved == id }) }
            onSuccess()
        })

    fun undoArchive() {
        val id = mutable.value.undoArchivedSessionId ?: return
        restoreSession(id) { selectSession(id) }
    }

    fun dismissArchiveUndo() { mutable.update { it.copy(undoArchivedSessionId = null) } }

    private fun mutateSession(id: String, operation: () -> ConversationSession, onSuccess: () -> Unit = {}) {
        if (loadingSession) return
        loadingSession = true
        mutable.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            var succeeded = false
            try {
                withContext(Dispatchers.IO) {
                    requireNotNull(app.sessions.getSession(id)) { "会话不存在" }
                    operation()
                }
                reloadSessionMetadata()
                succeeded = true
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutable.update { it.copy(error = error.message ?: "更新会话失败") } }
            finally {
                loadingSession = false
                mutable.update { it.copy(loading = false) }
            }
            if (succeeded) onSuccess()
        }
    }

    private fun refreshSessionMetadata() {
        viewModelScope.launch {
            try { reloadSessionMetadata() }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { mutable.update { it.copy(error = error.message ?: "刷新会话列表失败") } }
        }
    }

    private suspend fun reloadSessionMetadata() = metadataMutex.withLock {
        val sidebar = withContext(Dispatchers.IO) {
            app.workspaceManager.list().map { workspace ->
                SidebarWorkspace(workspace.id, workspace.name, workspace.updatedAtMillis,
                    app.sessions.listSessions(workspace.id).filter { it.kind == SessionKind.USER }.map {
                        SidebarConversation(it.id, it.title, it.updatedAtMillis, it.archivedAtMillis, it.pinnedAtMillis)
                    })
            }
        }
        val workspaceId = mutable.value.workspace?.id
        val sessions = withContext(Dispatchers.IO) {
            workspaceId?.let { app.sessions.listSessions(it).filter { session -> session.kind == SessionKind.USER } }.orEmpty()
        }
        mutable.update { current -> current.copy(sidebar = sidebar,
            sessions = if (current.workspace?.id == workspaceId) sessions else current.sessions) }
    }

    private fun readTranscript(workspaceId: String, sessionId: String): List<StoredMessage> =
        app.sessions.messages(sessionId).map { StoredMessage(it.id, it.role.name, it.content, it.createdAtMillis,
                            it.parts.filterIsInstance<com.cordiact.core.attachment.MessagePart.Attachment>().mapNotNull { part ->
                                app.attachments.find(workspaceId, part.id)?.let { descriptor ->
                                    TranscriptAttachment(descriptor, part.interpretation, part.renditionId)
                                }
                            }, phase = it.metadataJson?.let { metadata ->
                                runCatching {
                                    val status = org.json.JSONObject(metadata)
                                    when {
                                        status.optBoolean("outputLimitReached") -> RunPhase.TRUNCATED
                                        status.optBoolean("toolLimitReached") -> RunPhase.PARTIAL
                                        else -> RunPhase.COMPLETE
                                    }
                                }.getOrDefault(RunPhase.COMPLETE)
                            } ?: RunPhase.COMPLETE) }

    private fun loadSession(
        sessionId: String?, workspaceId: String?, create: Boolean = false,
        newWorkspaceName: String? = null, onSelected: () -> Unit = {}, onFailed: () -> Unit = {},
        shouldCommit: () -> Boolean = { true },
    ) {
        // Serialize user navigation and mutations: a rapid double tap must not create two sessions.
        if (loadingSession) { onFailed(); return }
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
                        workspaceId != null -> sessions.firstOrNull { it.archivedAtMillis == null } ?: app.sessions.createSession(workspace.id, "新对话")
                        else -> app.activeSessionId?.let(app.sessions::getSession)?.takeIf { it.workspaceId == workspace.id && it.kind == SessionKind.USER && it.archivedAtMillis == null }
                            ?: sessions.firstOrNull { it.archivedAtMillis == null } ?: app.sessions.createSession(workspace.id, "新对话")
                    }
                    require(selected.workspaceId == workspace.id)
                    val workspaces = app.workspaceManager.list()
                    val sidebar = workspaces.map { item ->
                        SidebarWorkspace(item.id, item.name, item.updatedAtMillis,
                            app.sessions.listSessions(item.id).filter { it.kind == SessionKind.USER }.map {
                                SidebarConversation(it.id, it.title, it.updatedAtMillis, it.archivedAtMillis, it.pinnedAtMillis)
                            })
                    }
                    Loaded(workspace, workspaces, app.sessions.listSessions(workspace.id).filter { it.kind == SessionKind.USER }, selected,
                        app.modelAdapter.models(), readTranscript(workspace.id, selected.id), sidebar)
                }
                if (version != selectionVersion) return@launch
                reconcile(snapshot.selected.id, snapshot.messages)
                val preferred = composer.value.model ?: mutable.value.model ?: app.getSharedPreferences("cordiact.settings.models", android.content.Context.MODE_PRIVATE).let { prefs ->
                    snapshot.models.firstOrNull { it.id.providerId == prefs.getString("provider", null) && it.id.modelId == prefs.getString("model", null) }?.id
                }
                val model = snapshot.models.find { it.id == preferred }?.id
                    ?: snapshot.models.firstOrNull()?.id
                val prepared = composerController.prepareSession(snapshot.selected.id, snapshot.workspace.id, snapshot.models, model)
                if (version != selectionVersion || !shouldCommit()) {
                    mutable.update { it.copy(loading = false) }
                    loadingSession = false
                    onFailed()
                    return@launch
                }
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
                composerController.bindSession(snapshot.selected.id, snapshot.workspace.id, prepared)
                mutable.update { it.copy(loading = false, workspace = snapshot.workspace, workspaces = snapshot.workspaces,
                    sessions = snapshot.sessions, sidebar = snapshot.sidebar, sessionId = snapshot.selected.id, models = snapshot.models, providerNames = app.modelProviders.providers().associate { it.providerId to it.displayName }, model = composer.value.model,
                    turns = cache[snapshot.selected.id].orEmpty(), error = null) }
                val selected = prepared.model
                if (selected != null) viewModelScope.launch(Dispatchers.IO) {
                    runCatching { com.cordiact.app.model.ConversationModelSelectionStore(app).setForSession(snapshot.selected.id, selected) }
                }
                loadingSession = false
                onSelected()
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                if (version == selectionVersion) {
                    mutable.update { it.copy(loading = false, error = error.message ?: "读取会话失败") }
                    loadingSession = false
                    onFailed()
                }
            } finally { if (version == selectionVersion) loadingSession = false }
        }
    }

    private data class Loaded(val workspace: Workspace, val workspaces: List<Workspace>, val sessions: List<ConversationSession>,
        val selected: ConversationSession, val models: List<ModelDescriptor>, val messages: List<StoredMessage>, val sidebar: List<SidebarWorkspace>)

    fun refreshSettingsModels() {
        viewModelScope.launch {
            val models = withContext(Dispatchers.IO) { app.modelAdapter.models() }
            val prefs = app.getSharedPreferences("cordiact.settings.models", android.content.Context.MODE_PRIVATE)
            val preferred = models.firstOrNull { it.id.providerId == prefs.getString("provider", null) && it.id.modelId == prefs.getString("model", null) }?.id
            mutable.update { current -> current.copy(models = models,
                providerNames = app.modelProviders.providers().associate { it.providerId to it.displayName },
                model = preferred ?: current.model?.takeIf { model -> models.any { it.id == model } } ?: models.firstOrNull()?.id) }
            mutable.value.model?.let(::selectModel)
        }
    }

    fun selectModel(model: ModelId) {
        val descriptor = mutable.value.models.find { it.id == model } ?: return
        mutable.update { it.copy(model = model) }
        composerController.select(descriptor)
        mutable.value.sessionId?.let { sessionId -> viewModelScope.launch(Dispatchers.IO) {
            com.cordiact.app.model.ConversationModelSelectionStore(app).setForSession(sessionId, model)
        } }
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
        val draft = composer.value.copy(text = text)
        if (loadingSession) return false
        if (state.archived) { mutable.update { it.copy(error = "请先恢复此归档会话再继续") }; return false }
        val model = draft.model ?: state.model ?: run { mutable.update { it.copy(error = "请先配置并选择模型") }; return false }
        if (state.loading || state.running || state.stopping || (text.isBlank() && draft.sendableParts().isEmpty()) || calls.containsKey(session)) return false
        if (draft.sendAvailability != com.cordiact.core.composer.ComposerRules.SendAvailability.READY) return false
        val turnId = UUID.randomUUID().toString()
        val owner = OwnedCall(turnId)
        calls[session] = owner
        val turn = TranscriptTurn(turnId, text.trim(), phase = RunPhase.RUNNING, startedAt = System.currentTimeMillis(),
            attachments = draft.attachments.filter { it.ready }.map { chip ->
                TranscriptAttachment(chip.descriptor, chip.part()?.interpretation ?: com.cordiact.core.attachment.Interpretation.LOCAL_ONLY, chip.renditionId)
            })
        cache[session] = cache[session].orEmpty() + turn
        mutable.update { it.copy(turns = cache[session].orEmpty(), error = null, runningSessionIds = it.runningSessionIds + session) }
        val requestJob = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val requestContext = currentCoroutineContext()
                runInterruptible(Dispatchers.IO) {
                requestContext.ensureActive()
                val prepared = app.interactionService.prepare(text.trim(), InteractionContext(session, workspace.id), draft.sendableParts())
                require(prepared.diagnostics.isEmpty()) { prepared.diagnostics.joinToString("\n") { it.toString() } }
                val callback: (ModelEvent) -> Unit = { event ->
                    val now = System.currentTimeMillis()
                    val rendered = when (event) {
                        is ModelEvent.Delta -> TranscriptEvent.Answer(event.text)
                        is ModelEvent.Thinking -> TranscriptEvent.Reasoning(event.text)
                        is ModelEvent.ToolCallStarted -> TranscriptEvent.ToolStarted(event.callId, event.toolName, event.argumentsJson, now)
                        is ModelEvent.ToolCallCompleted -> TranscriptEvent.ToolFinished(event.callId, event.toolName, event.success, event.summary, event.durationMillis, now)
                        is ModelEvent.Completed -> TranscriptEvent.Finished(
                            when {
                                event.outputLimitReached -> RunPhase.TRUNCATED
                                event.toolLimitReached -> RunPhase.PARTIAL
                                else -> RunPhase.COMPLETE
                            },
                            now, input = event.inputTokens, output = event.outputTokens,
                        )
                        is ModelEvent.Failed -> TranscriptEvent.Finished(RunPhase.FAILED, now, "${event.code}: ${event.message}")
                        else -> null
                    }
                    if (rendered != null) events.trySend(OwnedEvent(session, turnId, rendered))
                }
                requestContext.ensureActive()
                if (prepared.envelope?.command == null) {
                    val call = app.interactionService.submitOrdinary(prepared, model,
                            parameters = com.cordiact.core.composer.ComposerRules.parameters(draft.tuning),
                            attachments = draft.sendableParts(),
                            onUserMessagePersisted = { viewModelScope.launch { composerController.submitted(session, draft); refreshSessionMetadata() } },
                            onEvent = callback)
                    owner.attach(call)
                }
                else {
                    require(draft.attachments.isEmpty()) { "行为指令不能携带附件，请分开发送" }
                    val result = app.interactionService.dispatch(prepared, model,
                        parameters = com.cordiact.core.composer.ComposerRules.parameters(draft.tuning), onEvent = callback)
                    viewModelScope.launch { composerController.clearSubmittedText(session, draft.text) }
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


    // Command deck (landscape): every open conversation plus its sub-agents, as lanes of one tree.
    private val graphState = MutableStateFlow<List<GraphLane>>(emptyList())
    val graph = graphState.asStateFlow()
    private var graphJob: Job? = null

    fun refreshGraph(sessionIds: List<String>) {
        if (sessionIds.isEmpty()) return
        val current = mutable.value
        // Cache is access-ordered: read it on the main thread only.
        val known = sessionIds.associateWith { id -> if (id == current.sessionId) current.turns else cache[id] }
        graphJob?.cancel()
        graphJob = viewModelScope.launch {
            val lanes = try {
                withContext(Dispatchers.IO) {
                    val out = mutableListOf<GraphLane>()
                    val seen = hashSetOf<String>()
                    fun lane(session: ConversationSession, turns: List<TranscriptTurn>?) {
                        if (!seen.add(session.id)) return
                        val resolved = turns ?: restoreTranscript(readTranscript(session.workspaceId, session.id))
                        out += GraphLane(session.id, session.agentLabel ?: session.title.ifBlank { "新对话" }, session.kind,
                            session.parentSessionId, session.createdAtMillis,
                            resolved.mapIndexed { i, turn -> GraphNode(session.id, turn, i) },
                            session.id in current.runningSessionIds || resolved.any { it.isRunning })
                    }
                    val roots = sessionIds.mapNotNull { app.sessions.getSession(it) }
                    roots.forEach { lane(it, known[it.id]) }
                    // Two levels of delegation are enough for a phone; deeper agents fold into their parent.
                    var parents = roots.map { it.id }.toSet()
                    repeat(2) {
                        val children = roots.map { it.workspaceId }.distinct().flatMap { ws -> app.sessions.listSessions(ws) }
                            .filter { it.parentSessionId in parents && it.kind != SessionKind.USER }
                        children.forEach { lane(it, null) }
                        parents = children.map { it.id }.toSet()
                    }
                    out
                }
            } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { return@launch }
            graphState.value = lanes
        }
    }

    fun decide(requestId: String, approved: Boolean) {
        val current = mutable.value
        if (requestId in current.decidingApprovals || current.approvals.none { it.id == requestId }) return
        mutable.update { it.copy(decidingApprovals = it.decidingApprovals + requestId, approvalErrors = it.approvalErrors - requestId) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.approvalGateway.respond(requestId, ApprovalDecision(approved = approved, rememberChoice = false))
                }
                settledApprovals.add(requestId)
                if (settledApprovals.size > 128) settledApprovals.remove(settledApprovals.first())
                mutable.update { state -> state.copy(
                    approvals = state.approvals.filterNot { it.id == requestId },
                    approvalErrors = state.approvalErrors - requestId,
                ) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                mutable.update { it.copy(approvalErrors = it.approvalErrors + (requestId to "审批提交失败，可以重试")) }
            } finally {
                mutable.update { it.copy(decidingApprovals = it.decidingApprovals - requestId) }
            }
        }
    }
    override fun onCleared() {
        composerController.flushOnClose()
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
