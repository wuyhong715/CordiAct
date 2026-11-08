package com.cordiact.app.ui.window.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.MessageRole

/**
 * 对话转录列表（Chat Transcript List）
 *
 * 设计要点：
 * - 智能贴底防打扰（手指上滑 ≥10px 解除贴底锁定）
 * - 未读计数角标（`↓ 3条新动态`）
 * - 工具活动聚合（连续工具调用折叠为单张卡片）
 * - 审批卡片 + 思考链卡片
 */
@Composable
fun ChatTranscriptList(
    messages: List<ChatMessage>,
    onApprove: (String, Boolean) -> Unit,
    onReject: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scrollController = remember { SmartScrollController(listState) }

    // 监听消息数变化，触发自动贴底
    LaunchedEffect(messages.size) {
        scrollController.onItemsChanged(messages.size)
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(scrollController.nestedScrollConnection),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                when (message) {
                    is ChatMessage.Text -> {
                        MessageCard(
                            message = ConversationMessage(
                                id = message.id,
                                sessionId = "active",
                                role = message.role,
                                content = message.content,
                                createdAtMillis = 0L
                            )
                        )
                    }
                    is ChatMessage.Thinking -> {
                        ThinkingCard(
                            thinkingText = message.content,
                            durationMs = (message.durationSeconds * 1000).toLong()
                        )
                    }
                    is ChatMessage.ActivityGroup -> {
                        ActivityGroupCard(activities = message.activities)
                    }
                    is ChatMessage.Approval -> {
                        ApprovalCard(
                            request = message.request,
                            onApprove = { rememberChoice ->
                                onApprove(message.id, rememberChoice)
                            },
                            onReject = {
                                onReject(message.id)
                            }
                        )
                    }
                    is ChatMessage.Code -> {
                        CodeBlock(
                            code = message.code,
                            language = message.language ?: ""
                        )
                    }
                    is ChatMessage.Table -> {
                        val headers = message.data.firstOrNull().orEmpty()
                        val rows = if (message.data.size > 1) message.data.drop(1) else emptyList()
                        TableView(
                            headers = headers,
                            rows = rows
                        )
                    }
                }
            }
        }

        // 贴底小药丸（未读计数 > 0 时显示）
        AnimatedVisibility(
            visible = scrollController.unreadCount > 0 && !scrollController.isPinned,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Surface(
                onClick = { scrollController.scrollToBottom() },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                tonalElevation = 4.dp,
                shadowElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "滚动到底部",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "${scrollController.unreadCount}条新动态",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

/**
 * 聊天消息
 */
sealed class ChatMessage {
    abstract val id: String

    data class Text(
        override val id: String,
        val content: String,
        val role: MessageRole = MessageRole.ASSISTANT
    ) : ChatMessage()

    data class Thinking(
        override val id: String,
        val content: String,
        val durationSeconds: Float
    ) : ChatMessage()

    data class ActivityGroup(
        override val id: String,
        val activities: List<ActivityItem>
    ) : ChatMessage()

    data class Approval(
        override val id: String,
        val request: com.cordiact.core.action.ApprovalRequest
    ) : ChatMessage()

    data class Code(
        override val id: String,
        val code: String,
        val language: String?
    ) : ChatMessage()

    data class Table(
        override val id: String,
        val data: List<List<String>>
    ) : ChatMessage()
}
