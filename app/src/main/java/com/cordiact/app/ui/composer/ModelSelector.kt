package com.cordiact.app.ui.composer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.KeyboardArrowDown
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 输入框热区：模型快速切换选择器
 * - 点击当前模型标签展开/收起
 * - 选择模型后立即生效并收起
 * - 用于对话中快速切换模型（热功能）
 */
@Composable
fun ModelSelector(
    currentModelId: String,
    models: List<ModelOption>,
    onSelectModel: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val currentModel = models.find { it.id == currentModelId }
    
    Column(modifier = modifier) {
        // 当前模型标签（可点击展开）
        Surface(
            modifier = Modifier
                .clickable { expanded = !expanded },
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(6.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentModel?.displayName ?: "选择模型",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium
                )
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 2.dp)
                )
            }
        }
        
        // 模型列表（展开时显示）
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 4.dp
            ) {
                LazyColumn(
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    items(models) { model ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectModel(model.id)
                                    expanded = false
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = model.displayName,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (model.id == currentModelId) 
                                        FontWeight.Medium 
                                    else 
                                        FontWeight.Normal
                                )
                                Text(
                                    text = model.provider,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            if (model.id == currentModelId) {
                                Icon(
                                    imageVector = Icons.Outlined.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 模型选项（供外部传入）
 */
data class ModelOption(
    val id: String,
    val displayName: String,
    val provider: String
)
