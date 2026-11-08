package com.cordiact.app.ui.drawer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 侧边抽屉内容（参考 DeepSeek Harness）
 * - 顶部品牌区
 * - 快捷入口（新会话、插件、设置）
 * - 工作区列表（折叠式卡片）
 * - 底部「更多」扩展槽
 */
@Composable
fun DrawerContent(
    currentWorkspace: String,
    workspaces: List<WorkspaceItem>,
    onCreateSession: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlugins: () -> Unit,
    onSelectWorkspace: (String) -> Unit,
    onSelectSession: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth(0.85f)
            .padding(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 顶部品牌区
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "CordiAct",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "AGENT",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        
        item { Spacer(modifier = Modifier.height(8.dp)) }
        
        // 快捷入口：新会话
        item {
            Button(
                onClick = onCreateSession,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("新会话")
            }
        }
        
        // 快捷入口：插件
        item {
            QuickActionItem(
                icon = Icons.Outlined.FolderOpen,
                label = "插件",
                onClick = onOpenPlugins
            )
        }
        
        // 快捷入口：设置
        item {
            QuickActionItem(
                icon = Icons.Outlined.Settings,
                label = "设置",
                onClick = onOpenSettings
            )
        }
        
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item { HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp)) }
        item { Spacer(modifier = Modifier.height(8.dp)) }
        
        // 工作区列表标题
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "工作区",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.Outlined.Add,
                    contentDescription = "新建工作区",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        
        // 工作区卡片列表
        items(workspaces) { workspace ->
            WorkspaceCard(
                workspace = workspace,
                isActive = workspace.id == currentWorkspace,
                onSelectWorkspace = { onSelectWorkspace(workspace.id) },
                onSelectSession = onSelectSession
            )
        }
        
        item { Spacer(modifier = Modifier.height(16.dp)) }
        
        // 底部「更多」扩展槽
        item {
            TextButton(
                onClick = { /* 待实现 */ },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.MoreHoriz,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("更多")
            }
        }
    }
}

/**
 * 快捷操作项（插件、设置）
 */
@Composable
private fun QuickActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * 工作区条目（供外部传入）
 */
data class WorkspaceItem(
    val id: String,
    val name: String,
    val sessions: List<SessionItem>
)

/**
 * 会话条目（供外部传入）
 */
data class SessionItem(
    val id: String,
    val title: String
)
