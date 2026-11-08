package com.cordiact.app.ui.composer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * 智能输入栏（Agent Composer）
 * 
 * 设计要点：
 * - `@` 文件/上下文、`#` 密钥、`/` 命令快捷 Token
 * - 随输入自适应形变（单行 → 多行展开）
 * - 单手可及的黄金热区（底部 56dp 基准高度）
 * - 快捷 Token 水平滚动选择器
 */
@Composable
fun AgentComposer(
    input: String,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    selectedModel: String?,
    onSelectModel: () -> Unit,
    contextTokens: List<ContextToken>,
    onAddContextToken: (TokenType) -> Unit,
    onRemoveContextToken: (ContextToken) -> Unit,
    isProcessing: Boolean,
    modifier: Modifier = Modifier
) {
    // 自适应高度（单行 56dp → 多行最大 120dp）
    val lineCount = input.count { it == '\n' } + 1
    val targetHeight = when {
        lineCount <= 1 -> 56.dp
        lineCount <= 2 -> 80.dp
        else -> 120.dp
    }
    val animatedHeight by animateDpAsState(
        targetValue = targetHeight,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "composer_height"
    )
    
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Column {
            // 上下文 Token 栏（横向滚动微型药丸）
            AnimatedVisibility(
                visible = contextTokens.isNotEmpty(),
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                ContextTokenRow(
                    tokens = contextTokens,
                    onRemove = onRemoveContextToken,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            
            // 主输入栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(animatedHeight)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 快捷 Token 触发器（@ # /）
                TokenTriggerRow(
                    onAddToken = onAddContextToken,
                    enabled = !isProcessing
                )
                
                // 输入框
                BasicTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(20.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    enabled = !isProcessing,
                    maxLines = 5,
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (input.isEmpty()) {
                                Text(
                                    text = "输入消息...",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                
                // 发送按钮
                IconButton(
                    onClick = onSend,
                    enabled = !isProcessing && input.isNotBlank(),
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            if (!isProcessing && input.isNotBlank()) 
                                MaterialTheme.colorScheme.primary
                            else 
                                MaterialTheme.colorScheme.surfaceVariant
                        )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "发送",
                        tint = if (!isProcessing && input.isNotBlank()) 
                            MaterialTheme.colorScheme.onPrimary
                        else 
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

@Composable
private fun TokenTriggerRow(
    onAddToken: (TokenType) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TokenTrigger(
            symbol = "@",
            color = MaterialTheme.colorScheme.primary,
            onClick = { onAddToken(TokenType.CONTEXT) },
            enabled = enabled
        )
        TokenTrigger(
            symbol = "#",
            color = MaterialTheme.colorScheme.tertiary,
            onClick = { onAddToken(TokenType.SECRET) },
            enabled = enabled
        )
        TokenTrigger(
            symbol = "/",
            color = MaterialTheme.colorScheme.secondary,
            onClick = { onAddToken(TokenType.COMMAND) },
            enabled = enabled
        )
    }
}

@Composable
private fun TokenTrigger(
    symbol: String,
    color: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    enabled: Boolean
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(32.dp),
        shape = CircleShape,
        color = if (enabled) color.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = symbol,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun ContextTokenRow(
    tokens: List<ContextToken>,
    onRemove: (ContextToken) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(tokens, key = { it.id }) { token ->
            ContextTokenChip(
                token = token,
                onRemove = { onRemove(token) }
            )
        }
    }
}

@Composable
private fun ContextTokenChip(
    token: ContextToken,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = token.type.color.copy(alpha = 0.15f),
        contentColor = token.type.color,
        modifier = Modifier.height(24.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "${token.type.symbol}${token.label}",
                style = MaterialTheme.typography.labelSmall
            )
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "移除",
                modifier = Modifier
                    .size(14.dp)
                    .clickable(onClick = onRemove)
            )
        }
    }
}

/**
 * 上下文 Token
 * 
 * @param id 唯一 ID
 * @param type Token 类型（@文件 / #密钥 / /命令）
 * @param label 显示标签
 * @param value 实际值
 */
data class ContextToken(
    val id: String,
    val type: TokenType,
    val label: String,
    val value: String
)

enum class TokenType(val symbol: String) {
    CONTEXT("@"),   // 文件/上下文
    SECRET("#"),    // 密钥
    COMMAND("/");   // 命令
    
    val color: androidx.compose.ui.graphics.Color
        @Composable
        get() = when (this) {
            CONTEXT -> MaterialTheme.colorScheme.primary
            SECRET -> MaterialTheme.colorScheme.tertiary
            COMMAND -> MaterialTheme.colorScheme.secondary
        }
}
