package com.cordiact.app.ui.window.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cordiact.core.action.ApprovalRequest

/**
 * 审批卡片（人工介入确认）
 * - 显示高危操作的详细信息
 * - 三个按钮：拒绝 / 仅本次允许 / 信任工作区
 * - 根据风险等级显示不同颜色
 */
@Composable
fun ApprovalCard(
    request: ApprovalRequest,
    onApprove: (rememberChoice: Boolean) -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier
) {
    val riskColor = when (request.riskLevel) {
        ApprovalRequest.RiskLevel.CRITICAL -> Color(0xFFE53935)  // 深红
        ApprovalRequest.RiskLevel.HIGH -> Color(0xFFFF6F00)      // 橙红
        ApprovalRequest.RiskLevel.MEDIUM -> Color(0xFFFFB74D)    // 暖橙
        ApprovalRequest.RiskLevel.LOW -> Color(0xFFFDD835)       // 黄
    }
    
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = riskColor.copy(alpha = 0.1f),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 标题栏
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = riskColor
                )
                Text(
                    text = "需要确认",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = riskColor
                )
            }
            
            // 操作摘要
            Text(
                text = request.summary,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            
            // 工具名
            Text(
                text = "工具：${request.toolName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // 详细参数（如果有）
            if (request.details.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    request.details.forEach { (key, value) ->
                        Text(
                            text = "$key: $value",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 拒绝
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("拒绝")
                }
                
                // 仅本次允许
                Button(
                    onClick = { onApprove(false) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = riskColor.copy(alpha = 0.8f)
                    )
                ) {
                    Text("允许")
                }
                
                // 信任工作区
                Button(
                    onClick = { onApprove(true) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("信任")
                }
            }
        }
    }
}
