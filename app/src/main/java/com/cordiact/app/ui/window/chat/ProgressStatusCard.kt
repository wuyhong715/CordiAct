package com.cordiact.app.ui.window.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 工具状态枚举
 */
enum class ToolStatus {
    RUNNING,
    SUCCESS,
    FAILED
}

/**
 * 工具执行步骤
 */
data class ToolStep(
    val summary: String,        // "ssh_exec (首尔): git pull"
    val status: ToolStatus,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 长任务进度状态卡（原位覆盖更新）
 * - 默认只显示当前步骤（1 行，32dp 高度）
 * - 点击展开历史步骤
 * - 避免长任务过程占满一屏
 */
@Composable
fun ProgressStatusCard(
    steps: List<ToolStep>,
    modifier: Modifier = Modifier
) {
    if (steps.isEmpty()) return
    
    var expanded by remember { mutableStateOf(false) }
    val currentStep = steps.last()
    
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // 当前步骤摘要（一行）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // 状态图标
                    when (currentStep.status) {
                        ToolStatus.RUNNING -> CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF64B5F6)
                        )
                        ToolStatus.SUCCESS -> Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(16.dp)
                        )
                        ToolStatus.FAILED -> Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = Color(0xFFE53935),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    
                    // 当前步骤文字
                    Text(
                        text = currentStep.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                
                // 展开/收起按钮
                if (steps.size > 1) {
                    TextButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text(
                            text = if (expanded) "收起 ▴" else "${steps.size} 步 ▾",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF64B5F6)
                        )
                    }
                }
            }
            
            // 展开后显示历史步骤
            AnimatedVisibility(expanded && steps.size > 1) {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    steps.dropLast(1).forEach { step ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (step.status == ToolStatus.SUCCESS)
                                    Icons.Default.CheckCircle
                                else
                                    Icons.Default.Error,
                                contentDescription = null,
                                tint = if (step.status == ToolStatus.SUCCESS)
                                    Color(0xFF4CAF50)
                                else
                                    Color(0xFFE53935),
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = step.summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
            }
        }
    }
}
