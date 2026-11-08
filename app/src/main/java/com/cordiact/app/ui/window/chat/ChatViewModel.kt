package com.cordiact.app.ui.window.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cordiact.app.CordiActApplication
import com.cordiact.core.action.ApprovalDecision
import com.cordiact.core.action.ApprovalGateway
import com.cordiact.core.action.ApprovalRequest
import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 聊天窗口 ViewModel（连接真实会话、审批网关、事件流）
 */
class ChatViewModel(
    private val application: CordiActApplication? = null,
    private val approvalGateway: ApprovalGateway? = application?.approvalGateway
) : ViewModel() {

    // 对话转录消息流（供 ChatTranscriptList 渲染）
    private val _messages = MutableStateFlow<List<ChatMessage>>(defaultDemoMessages())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    // 当前长任务步骤列表（覆盖式更新）
    private val _currentSteps = MutableStateFlow<List<ToolStep>>(emptyList())
    val currentSteps: StateFlow<List<ToolStep>> = _currentSteps.asStateFlow()

    // 当前流式输出状态
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    // 当前选中的模型
    private val _selectedModel = MutableStateFlow<ModelId?>(null)
    val selectedModel: StateFlow<ModelId?> = _selectedModel.asStateFlow()

    // 待审批请求列表
    private val _pendingApprovals = MutableStateFlow<List<ApprovalRequest>>(emptyList())
    val pendingApprovals: StateFlow<List<ApprovalRequest>> = _pendingApprovals.asStateFlow()

    // 从回复中提取的代码块和表格（供预览窗口消费）
    private val _extractedArtifacts = MutableStateFlow<List<Artifact>>(emptyList())
    val extractedArtifacts: StateFlow<List<Artifact>> = _extractedArtifacts.asStateFlow()

    // 当前思考链文本（流式累积）
    private val _thinkingText = MutableStateFlow("")
    val thinkingText: StateFlow<String> = _thinkingText.asStateFlow()

    // 思考链耗时
    private val _thinkingDurationMs = MutableStateFlow<Long?>(null)
    val thinkingDurationMs: StateFlow<Long?> = _thinkingDurationMs.asStateFlow()

    // 实时工具事件列表（供轨迹窗口消费）
    private val _recordedToolEvents = MutableStateFlow<List<RecordedToolEvent>>(emptyList())
    val recordedToolEvents: StateFlow<List<RecordedToolEvent>> = _recordedToolEvents.asStateFlow()

    private var currentSessionId: String? = null
    private var thinkingStartMs: Long? = null
    private val toolStartTimes = mutableMapOf<String, Long>()
    private val currentTurnActivities = mutableListOf<ActivityItem>()

    init {
        loadSessionMessages()
        observePendingApprovals()
    }

    /**
     * 从 SessionManagerService 加载当前活跃会话的真实历史消息
     */
    fun loadSessionMessages(sessionId: String? = null) {
        val app = application ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val targetSessionId = sessionId
                    ?: currentSessionId
                    ?: app.activeSessionId
                    ?: runCatching {
                        val ws = app.workspaceManager.activeWorkspace()
                        app.sessions.listSessions(ws.id).firstOrNull()?.id
                    }.getOrNull()

                if (!targetSessionId.isNullOrBlank()) {
                    currentSessionId = targetSessionId
                    val stored = app.sessions.messages(targetSessionId)
                    if (stored.isNotEmpty()) {
                        _messages.value = stored.map { msg ->
                            ChatMessage.Text(
                                id = msg.id,
                                content = msg.content,
                                role = msg.role
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * 轮询 ApprovalGateway 待审批请求，实时同步到 UI 和动态微胶囊
     */
    private fun observePendingApprovals() {
        val gateway = approvalGateway ?: return
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val pending = runCatching { gateway.pendingRequests() }.getOrDefault(emptyList())
                if (pending != _pendingApprovals.value) {
                    _pendingApprovals.value = pending
                    val existingApprovalIds = _messages.value
                        .filterIsInstance<ChatMessage.Approval>()
                        .map { it.request.id }
                        .toSet()
                    val newApprovalMessages = pending
                        .filter { it.id !in existingApprovalIds }
                        .map { req -> ChatMessage.Approval(id = "approval-${req.id}", request = req) }
                    if (newApprovalMessages.isNotEmpty()) {
                        _messages.value = _messages.value + newApprovalMessages
                    }
                }
                delay(500)
            }
        }
    }

    /**
     * 发送用户消息到真实后端（ConversationTurnService）并订阅 ModelEvent 流
     */
    fun sendMessage(
        input: String,
        modelIdString: String? = null,
        onEventUpdate: ((String) -> Unit)? = null
    ) {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return

        val userMsg = ChatMessage.Text(
            id = "user-${System.currentTimeMillis()}",
            content = trimmed,
            role = MessageRole.USER
        )
        _messages.value = _messages.value + userMsg

        val app = application
        if (app == null) {
            viewModelScope.launch {
                _isStreaming.value = true
                delay(600)
                val reply = "已收到：$trimmed"
                _messages.value = _messages.value + ChatMessage.Text(
                    id = "assistant-${System.currentTimeMillis()}",
                    content = reply,
                    role = MessageRole.ASSISTANT
                )
                extractArtifactsFromText(reply)
                _isStreaming.value = false
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _isStreaming.value = true
            resetSteps()
            currentTurnActivities.clear()

            val sessionId = currentSessionId ?: app.activeSessionId ?: runCatching {
                val ws = app.workspaceManager.activeWorkspace()
                app.sessions.listSessions(ws.id).firstOrNull()?.id
                    ?: app.sessions.createSession(ws.id, trimmed.take(24)).id
            }.getOrNull()

            if (sessionId == null) {
                _isStreaming.value = false
                return@launch
            }
            currentSessionId = sessionId

            val workspaceId = runCatching {
                app.sessions.getSession(sessionId)?.workspaceId
                    ?: app.workspaceManager.activeWorkspace().id
            }.getOrDefault("")
            val resolvedModel = resolveModelId(app, modelIdString)
            val streamingMsgId = "stream-${System.currentTimeMillis()}"
            val streamedContent = StringBuilder()

            try {
                app.interaction.submit(
                    context = com.cordiact.interaction.InteractionContext(
                        workspaceId = workspaceId,
                        sessionId = sessionId
                    ),
                    model = resolvedModel,
                    rawInput = trimmed,
                    onEvent = { event: ModelEvent ->
                        handleModelEvent(event)
                        when (event) {
                            is ModelEvent.Delta -> {
                                streamedContent.append(event.text)
                                upsertStreamingAssistantMessage(streamingMsgId, streamedContent.toString())
                            }
                            is ModelEvent.ToolCallStarted -> {
                                onEventUpdate?.invoke("正在执行: ${event.toolName}")
                            }
                            is ModelEvent.Thinking -> {
                                onEventUpdate?.invoke("正在深度思考...")
                            }
                            else -> {}
                        }
                    }
                )

                // 聚合本轮工具调用卡片
                if (currentTurnActivities.isNotEmpty()) {
                    val groupMsg = ChatMessage.ActivityGroup(
                        id = "group-${System.currentTimeMillis()}",
                        activities = currentTurnActivities.toList()
                    )
                    _messages.value = _messages.value + groupMsg
                }

                // 思考链卡片
                val finalThinking = _thinkingText.value
                if (finalThinking.isNotBlank()) {
                    val durationSec = (_thinkingDurationMs.value ?: 1000L) / 1000f
                    _messages.value = _messages.value + ChatMessage.Thinking(
                        id = "think-${System.currentTimeMillis()}",
                        content = finalThinking,
                        durationSeconds = durationSec
                    )
                }

                // 最终回复文本与产物提取
                val finalReply = streamedContent.toString()
                if (finalReply.isNotBlank()) {
                    upsertStreamingAssistantMessage(streamingMsgId, finalReply)
                    extractArtifactsFromText(finalReply)
                }
            } catch (e: Exception) {
                _messages.value = _messages.value + ChatMessage.Text(
                    id = "err-${System.currentTimeMillis()}",
                    content = "请求执行异常：${e.message ?: e::class.java.simpleName}",
                    role = MessageRole.ASSISTANT
                )
            } finally {
                _isStreaming.value = false
            }
        }
    }

    private fun resolveModelId(app: CordiActApplication, requestedModelId: String?): ModelId {
        _selectedModel.value?.let { return it }
        val descriptors = runCatching { app.modelAdapter.models() }.getOrDefault(emptyList())
        if (!requestedModelId.isNullOrBlank()) {
            descriptors.firstOrNull { it.id.modelId == requestedModelId }?.let { return it.id }
        }
        return descriptors.firstOrNull()?.id ?: ModelId("deepseek", requestedModelId ?: "deepseek-chat")
    }

    private fun upsertStreamingAssistantMessage(msgId: String, content: String) {
        val current = _messages.value.toMutableList()
        val idx = current.indexOfFirst { it.id == msgId }
        val updated = ChatMessage.Text(id = msgId, content = content, role = MessageRole.ASSISTANT)
        if (idx >= 0) {
            current[idx] = updated
        } else {
            current.add(updated)
        }
        _messages.value = current
    }

    /**
     * 处理 ModelEvent 流
     */
    fun handleModelEvent(event: ModelEvent) {
        when (event) {
            is ModelEvent.Thinking -> {
                if (thinkingStartMs == null) {
                    thinkingStartMs = System.currentTimeMillis()
                }
                _thinkingText.value += event.text
            }
            is ModelEvent.ToolCallStarted -> {
                val startTime = System.currentTimeMillis()
                toolStartTimes[event.callId] = startTime
                onToolStart(
                    toolName = event.toolName,
                    target = extractTarget(event.argumentsJson)
                )
            }
            is ModelEvent.ToolCallCompleted -> {
                val durationMs = event.durationMillis
                val startMs = toolStartTimes.remove(event.callId) ?: (System.currentTimeMillis() - durationMs)
                onToolComplete(
                    success = event.success,
                    durationMs = durationMs
                )
                val targetSummary = (event.summary ?: "").take(60)
                val activity = when {
                    event.toolName.contains("file") || event.toolName.contains("read") ->
                        ActivityItem.FileRead(durationMs = durationMs, path = targetSummary.ifBlank { event.toolName })
                    event.toolName.contains("screen") || event.toolName.contains("tap") || event.toolName.contains("click") ->
                        ActivityItem.ScreenAction(durationMs = durationMs, target = targetSummary.ifBlank { event.toolName })
                    event.toolName.contains("web") || event.toolName.contains("fetch") || event.toolName.contains("browser") ->
                        ActivityItem.WebFetch(durationMs = durationMs, url = targetSummary.ifBlank { event.toolName })
                    else ->
                        ActivityItem.Terminal(
                            durationMs = durationMs,
                            command = "${event.toolName} $targetSummary".trim(),
                            exitCode = if (event.success) 0 else 1
                        )
                }
                currentTurnActivities.add(activity)
                _recordedToolEvents.value = _recordedToolEvents.value + RecordedToolEvent(
                    id = event.callId,
                    name = event.toolName,
                    startMs = startMs,
                    endMs = startMs + durationMs.coerceAtLeast(100L),
                    isError = !event.success,
                    summary = targetSummary
                )
            }
            is ModelEvent.Completed -> {
                thinkingStartMs?.let { start ->
                    _thinkingDurationMs.value = System.currentTimeMillis() - start
                    thinkingStartMs = null
                }
                stopStreaming()
            }
            else -> {}
        }
    }

    private fun extractTarget(argumentsJson: String): String {
        val pathMatch = Regex(""""path"\s*:\s*"([^"]+)"""").find(argumentsJson)
        if (pathMatch != null) return pathMatch.groupValues[1]

        val cmdMatch = Regex(""""command"\s*:\s*"([^"]+)"""").find(argumentsJson)
        if (cmdMatch != null) return cmdMatch.groupValues[1].take(30)

        val hostMatch = Regex(""""host"\s*:\s*"([^"]+)"""").find(argumentsJson)
        if (hostMatch != null) return hostMatch.groupValues[1]

        return ""
    }

    fun onToolStart(toolName: String, target: String = "") {
        val summary = if (target.isNotEmpty()) "$toolName: $target" else toolName
        _currentSteps.value = _currentSteps.value + ToolStep(
            toolName = toolName,
            summary = summary,
            status = ToolStatus.RUNNING
        )
    }

    fun onToolComplete(success: Boolean = true, durationMs: Long? = null) {
        val steps = _currentSteps.value.toMutableList()
        val lastRunningIndex = steps.indexOfLast { it.status == ToolStatus.RUNNING }
        if (lastRunningIndex >= 0) {
            steps[lastRunningIndex] = steps[lastRunningIndex].copy(
                status = if (success) ToolStatus.SUCCESS else ToolStatus.FAILED,
                durationMs = durationMs
            )
            _currentSteps.value = steps
        }
    }

    fun resetSteps() {
        _currentSteps.value = emptyList()
        _thinkingText.value = ""
        _thinkingDurationMs.value = null
        thinkingStartMs = null
    }

    fun startStreaming() {
        _isStreaming.value = true
    }

    fun stopStreaming() {
        _isStreaming.value = false
    }

    fun selectModel(modelId: ModelId) {
        _selectedModel.value = modelId
    }

    fun approveRequest(requestId: String, rememberChoice: Boolean = false) {
        val cleanId = requestId.removePrefix("approval-")
        approvalGateway?.respond(
            requestId = cleanId,
            decision = ApprovalDecision(
                approved = true,
                rememberChoice = rememberChoice
            )
        )
        _pendingApprovals.value = _pendingApprovals.value.filter { it.id != cleanId }
        _messages.value = _messages.value.filterNot {
            it is ChatMessage.Approval && (it.id == requestId || it.request.id == cleanId)
        }
    }

    fun rejectRequest(requestId: String, reason: String? = null) {
        val cleanId = requestId.removePrefix("approval-")
        approvalGateway?.respond(
            requestId = cleanId,
            decision = ApprovalDecision(
                approved = false,
                reason = reason
            )
        )
        _pendingApprovals.value = _pendingApprovals.value.filter { it.id != cleanId }
        _messages.value = _messages.value.filterNot {
            it is ChatMessage.Approval && (it.id == requestId || it.request.id == cleanId)
        }
    }

    fun extractArtifactsFromText(messageText: String) {
        val artifacts = mutableListOf<Artifact>()

        val codeBlockRegex = Regex("```(\\w*)\\n([\\s\\S]*?)```")
        codeBlockRegex.findAll(messageText).forEach { match ->
            val language = match.groupValues[1].ifEmpty { "text" }
            val code = match.groupValues[2].trim()
            artifacts.add(
                Artifact.CodeBlock(
                    language = language,
                    code = code
                )
            )
        }

        val tableRegex = Regex("(\\|[^\\n]+\\|\\n)+")
        tableRegex.findAll(messageText).forEach { match ->
            val lines = match.value.trim().lines()
            if (lines.size >= 2) {
                val headers = lines[0].split("|").map { it.trim() }.filter { it.isNotEmpty() }
                val rows = lines.drop(2).map { line ->
                    line.split("|").map { it.trim() }.filter { it.isNotEmpty() }
                }
                if (headers.isNotEmpty() && rows.isNotEmpty()) {
                    artifacts.add(
                        Artifact.Table(
                            headers = headers,
                            rows = rows
                        )
                    )
                }
            }
        }

        if (artifacts.isNotEmpty()) {
            _extractedArtifacts.value = _extractedArtifacts.value + artifacts
        }
    }

    companion object {
        fun factory(application: CordiActApplication?): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ChatViewModel(application) as T
                }
            }

        private fun defaultDemoMessages(): List<ChatMessage> = listOf(
            ChatMessage.Text(id = "1", content = "欢迎使用 CordiAct 紧凑 UI"),
            ChatMessage.Thinking(
                id = "2",
                content = "分析任务需求...",
                durationSeconds = 2.3f
            ),
            ChatMessage.ActivityGroup(
                id = "3",
                activities = listOf(
                    ActivityItem.FileRead(
                        durationMs = 120,
                        path = "/workspace/config.yml"
                    ),
                    ActivityItem.FileRead(
                        durationMs = 85,
                        path = "/workspace/database.yml"
                    ),
                    ActivityItem.Terminal(
                        durationMs = 1800,
                        command = "./gradlew test",
                        exitCode = 0
                    )
                )
            ),
            ChatMessage.Approval(
                id = "4",
                request = ApprovalRequest(
                    id = "approval-1",
                    toolName = "ssh_exec",
                    summary = "清理远端临时缓存目录",
                    details = mapOf(
                        "host" to "首尔",
                        "path" to "/tmp/old_cache"
                    ),
                    riskLevel = ApprovalRequest.RiskLevel.HIGH
                )
            ),
            ChatMessage.Code(
                id = "5",
                code = """
                    fun main() {
                        println("Hello CordiAct")
                    }
                """.trimIndent(),
                language = "Kotlin"
            )
        )
    }
}

data class RecordedToolEvent(
    val id: String,
    val name: String,
    val startMs: Long,
    val endMs: Long,
    val isError: Boolean,
    val summary: String
)

/**
 * 单个工具执行步骤
 */
data class ToolStep(
    val toolName: String,
    val summary: String,
    val status: ToolStatus,
    val durationMs: Long? = null
)

enum class ToolStatus {
    RUNNING,
    SUCCESS,
    FAILED
}

/**
 * 从对话中提取的结构化内容（供预览窗口展示）
 */
sealed class Artifact {
    data class CodeBlock(
        val language: String,
        val code: String
    ) : Artifact()

    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>
    ) : Artifact()
}
