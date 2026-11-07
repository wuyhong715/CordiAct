package com.cordiact.app.ui.window.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.tween

/**
 * 流式输出墨染渐出效果
 * - 稳定文字（>20 字符）保持实色
 * - 最新 20 字符带 300ms 淡入
 * - 输出完成后全部实色
 */
@Composable
fun StreamingText(
    text: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier
) {
    val stableText = if (isStreaming && text.length > 20) {
        text.dropLast(20)
    } else {
        ""
    }
    
    val fadingText = if (isStreaming && text.length > 20) {
        text.takeLast(20)
    } else if (isStreaming) {
        text
    } else {
        text.removePrefix(stableText)
    }
    
    Column(modifier = modifier) {
        // 稳定文字（不动画）
        if (stableText.isNotEmpty()) {
            Text(
                text = stableText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        
        // 渐入文字（淡入动画）
        AnimatedVisibility(
            visible = fadingText.isNotEmpty(),
            enter = fadeIn(animationSpec = tween(300))
        ) {
            Text(
                text = fadingText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
