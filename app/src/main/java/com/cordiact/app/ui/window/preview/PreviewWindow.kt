package com.cordiact.app.ui.window.preview

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cordiact.app.ui.window.WindowDescriptor
import java.text.SimpleDateFormat
import java.util.*

/**
 * 预览窗口
 * 
 * 展示渲染输出：
 * - 代码 Diff
 * - Web 页面（离屏 WebView）
 * - 屏幕自动化映射（虚拟屏截图）
 */
class PreviewWindow : WindowDescriptor {
    override val id: String = "preview"
    override val title: String = "预览"
    override val icon: ImageVector = Icons.Default.Preview
    
    @Composable
    override fun Badge(sessionId: String) {
        val viewModel: PreviewViewModel = viewModel()
        val items by viewModel.previewItems.collectAsState()
        
        if (items.isNotEmpty()) {
            Badge(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Text(
                    text = items.size.toString(),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
    
    @Composable
    override fun Content(
        sessionId: String,
        onNavigateToWindow: (windowId: String) -> Unit
    ) {
        val viewModel: PreviewViewModel = viewModel()
        val items by viewModel.previewItems.collectAsState()
        val selectedItemId by viewModel.selectedItemId.collectAsState()
        val context = LocalContext.current
        
        if (items.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "暂无预览内容",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // 顶部：预览项列表（横向滚动）
                PreviewItemList(
                    items = items,
                    selectedItemId = selectedItemId,
                    onSelectItem = { viewModel.selectItem(it) },
                    onRemoveItem = { viewModel.removeItem(it) },
                    modifier = Modifier.fillMaxWidth()
                )
                
                Divider(
                    modifier = Modifier.fillMaxWidth(),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                
                // 主内容区：当前选中的预览项
                val selectedItem = items.find { it.id == selectedItemId }
                selectedItem?.let { item ->
                    when (item) {
                        is PreviewItem.CodeDiff -> {
                            CodeDiffView(
                                diff = item,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        }
                        is PreviewItem.WebPage -> {
                            WebPageView(
                                webPage = item,
                                onOpenInBrowser = { url ->
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        }
                        is PreviewItem.Screenshot -> {
                            ScreenshotView(
                                screenshot = item,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewItemList(
    items: List<PreviewItem>,
    selectedItemId: String?,
    onSelectItem: (String) -> Unit,
    onRemoveItem: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(items, key = { it.id }) { item ->
            PreviewItemChip(
                item = item,
                isSelected = item.id == selectedItemId,
                onClick = { onSelectItem(item.id) },
                onRemove = { onRemoveItem(item.id) }
            )
        }
    }
}

@Composable
private fun PreviewItemChip(
    item: PreviewItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val timeText = dateFormat.format(Date(item.timestamp))
    
    Surface(
        modifier = Modifier
            .width(180.dp)
            .height(80.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = if (isSelected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = if (isSelected) 4.dp else 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = if (isSelected)
                        MaterialTheme.colorScheme.onPrimaryContainer
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "移除",
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            Text(
                text = timeText,
                style = MaterialTheme.typography.labelSmall,
                color = if (isSelected)
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

private val PreviewItem.icon: ImageVector
    get() = when (this) {
        is PreviewItem.CodeDiff -> Icons.Default.Code
        is PreviewItem.WebPage -> Icons.Default.Language
        is PreviewItem.Screenshot -> Icons.Default.Image
    }
