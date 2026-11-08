package com.cordiact.app.ui.window.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 思考链卡片（推理过程可折叠）
 * - 默认折叠：一行紫色胶囊「🧠 思考过程 (3.8s) ›」
 * - 点击展开：显示完整思维链文本
 * - 支持流式更新（墨染渐出效果）
 */
@Composable
fun ThinkingCard(
    thinkingText: String,
    durationMs: Long? = null,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    
    if (thinkingText.isEmpty()) return
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        color = Color(0xFFF3E5F5),  // 浅紫底
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 折叠态标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🧠",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "思考过程",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF6A1B9A)  // 紫色
                    )
                    if (durationMs != null && !isStreaming) {
                        Text(
                            text = "(${durationMs / 1000.0}s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                }
                
                Icon(
                    imageVector = if (expanded) 
                        Icons.Outlined.KeyboardArrowUp 
                    else 
                        Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = Color(0xFF6A1B9A)
                )
            }
            
            // 展开态：完整思维链文本
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                StreamingText(
                    text = thinkingText,
                    isStreaming = isStreaming,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
