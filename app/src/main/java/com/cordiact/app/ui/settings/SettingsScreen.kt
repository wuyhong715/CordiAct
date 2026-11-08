package com.cordiact.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 设置页面（冷功能）
 * - 模型管理：添加新模型、设置默认模型
 * - 工具开关：SSH/Termux/屏幕自动化/浏览器
 * - 审批策略：总是审批 / 信任工作区 / 自动批准
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    models: List<ModelItem>,
    defaultModelId: String,
    toolStates: Map<String, Boolean>,
    approvalPolicy: String,
    onBack: () -> Unit,
    onAddModel: () -> Unit,
    onEditModel: (String) -> Unit,
    onSetDefaultModel: (String) -> Unit,
    onToggleTool: (String, Boolean) -> Unit,
    onChangeApprovalPolicy: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // === 模型管理 ===
            item {
                SectionTitle("模型管理")
            }
            
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onAddModel)
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null
                    )
                    Text(
                        text = "添加新模型",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            items(models) { model ->
                ModelListItem(
                    model = model,
                    isDefault = model.id == defaultModelId,
                    onSetDefault = { onSetDefaultModel(model.id) },
                    onEdit = { onEditModel(model.id) }
                )
            }
            
            item { HorizontalDivider() }
            
            // === 工具开关 ===
            item {
                SectionTitle("工具开关")
            }
            
            item {
                ToolSwitchItem(
                    label = "SSH 后端",
                    description = "远程命令执行与文件传输",
                    enabled = toolStates["ssh"] ?: false,
                    onToggle = { onToggleTool("ssh", it) }
                )
            }
            
            item {
                ToolSwitchItem(
                    label = "Termux 后端",
                    description = "本地终端与交互式会话",
                    enabled = toolStates["termux"] ?: false,
                    onToggle = { onToggleTool("termux", it) }
                )
            }
            
            item {
                ToolSwitchItem(
                    label = "屏幕自动化",
                    description = "无障碍服务与虚拟屏幕控制",
                    enabled = toolStates["screen"] ?: false,
                    onToggle = { onToggleTool("screen", it) }
                )
            }
            
            item {
                ToolSwitchItem(
                    label = "浏览器自动化",
                    description = "离屏 WebView 与网页提取",
                    enabled = toolStates["browser"] ?: false,
                    onToggle = { onToggleTool("browser", it) }
                )
            }
            
            item { HorizontalDivider() }
            
            // === 审批策略 ===
            item {
                SectionTitle("审批策略")
            }
            
            item {
                Text(
                    text = "当前策略：$approvalPolicy",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            item {
                Text(
                    text = "注：审批策略控制高危命令（rm -rf、覆盖文件等）的执行前确认。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun ModelListItem(
    model: ModelItem,
    isDefault: Boolean,
    onSetDefault: () -> Unit,
    onEdit: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEdit)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isDefault) FontWeight.Medium else FontWeight.Normal
            )
            Text(
                text = model.provider,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        if (isDefault) {
            Text(
                text = "默认",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        } else {
            TextButton(onClick = onSetDefault) {
                Text("设为默认")
            }
        }
    }
}

@Composable
private fun ToolSwitchItem(
    label: String,
    description: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Switch(
            checked = enabled,
            onCheckedChange = onToggle
        )
    }
}

/**
 * 模型条目（供外部传入）
 */
data class ModelItem(
    val id: String,
    val displayName: String,
    val provider: String
)
