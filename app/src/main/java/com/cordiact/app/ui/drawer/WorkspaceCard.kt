package com.cordiact.app.ui.drawer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
 * 工作区卡片（折叠式）
 * - 点击工作区标题：切换工作区并展开
 * - 点击会话条目：切换到该会话
 * - 活跃工作区默认展开
 */
@Composable
fun WorkspaceCard(
    workspace: WorkspaceItem,
    isActive: Boolean,
    onSelectWorkspace: () -> Unit,
    onSelectSession: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember(isActive) { mutableStateOf(isActive) }
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        color = if (isActive) 
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else
            MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp)
    ) {
        Column {
            // 工作区标题栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onSelectWorkspace()
                        expanded = !expanded
                    }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 文件夹图标（简化为色块）
                    Surface(
                        modifier = Modifier.size(8.dp),
                        color = if (isActive) 
                            MaterialTheme.colorScheme.primary 
                        else 
                            Color.Gray,
                        shape = RoundedCornerShape(2.dp)
                    ) {}
                    
                    Text(
                        text = workspace.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal
                    )
                }
                
                // 展开/收起图标
                Icon(
                    imageVector = if (expanded) 
                        Icons.Outlined.KeyboardArrowUp 
                    else 
                        Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // 会话列表（展开时显示）
            AnimatedVisibility(expanded) {
                Column(
                    modifier = Modifier.padding(start = 28.dp, end = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (workspace.sessions.isEmpty()) {
                        Text(
                            text = "无会话",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else {
                        workspace.sessions.forEach { session ->
                            Text(
                                text = session.title,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelectSession(session.id) }
                                    .background(
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
