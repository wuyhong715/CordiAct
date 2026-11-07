package com.cordiact.app.ui.window.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cordiact.core.conversation.ConversationMessage
import com.cordiact.core.conversation.MessageRole

/**
 * 单条消息卡片
 * - 紧凑间距（8dp 上下边距，从 16dp 降低）
 * - 用户/模型标签（labelMedium）
 * - 支持流式文本渲染（墨染渐出）
 */
@Composable
fun MessageCard(
    message: ConversationMessage,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 16.dp)
    ) {
        // 角色标签
        Text(
            text = if (message.role == MessageRole.USER) "你" else "模型",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(4.dp))
        
        // 消息内容（支持流式渲染）
        StreamingText(
            text = message.content,
            isStreaming = isStreaming
        )
    }
}
