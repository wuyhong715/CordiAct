package com.cordiact.app.ui.window.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 代码 Diff 视图
 * 
 * 设计要点：
 * - 紧凑展示 +12 / -4，点击滑动查看差异
 * - 等宽字体，行号对齐
 * - 绿色/红色/灰色三种行类型
 */
@Composable
fun CodeDiffView(
    diff: PreviewItem.CodeDiff,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            // 文件头（文件路径 + 统计）
            DiffHeader(
                filePath = diff.filePath,
                additions = diff.additions,
                deletions = diff.deletions
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Diff 内容（横向 + 纵向滚动）
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp)
                ) {
                    diff.hunks.forEach { hunk ->
                        DiffHunkView(hunk = hunk)
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun DiffHeader(
    filePath: String,
    additions: Int,
    deletions: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = filePath,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFF2E7D32).copy(alpha = 0.2f)
            ) {
                Text(
                    text = "+$additions",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF4CAF50),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFC62828).copy(alpha = 0.2f)
            ) {
                Text(
                    text = "-$deletions",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFF44336),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun DiffHunkView(hunk: DiffHunk) {
    Column {
        // Hunk 头（@@ -45,5 +45,8 @@）
        Text(
            text = "@@ -${hunk.oldStart},${hunk.oldLines} +${hunk.newStart},${hunk.newLines} @@",
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        
        // Diff 行
        hunk.lines.forEach { line ->
            DiffLineView(line = line)
        }
    }
}

@Composable
private fun DiffLineView(line: DiffLine) {
    val (backgroundColor, textColor, prefix) = when (line.type) {
        DiffLineType.ADDED -> Triple(
            Color(0xFF2E7D32).copy(alpha = 0.15f),
            Color(0xFF4CAF50),
            "+"
        )
        DiffLineType.REMOVED -> Triple(
            Color(0xFFC62828).copy(alpha = 0.15f),
            Color(0xFFF44336),
            "-"
        )
        DiffLineType.CONTEXT -> Triple(
            Color.Transparent,
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            " "
        )
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(vertical = 2.dp, horizontal = 4.dp)
    ) {
        Text(
            text = prefix,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            ),
            color = textColor,
            modifier = Modifier.width(16.dp)
        )
        Text(
            text = line.content,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            ),
            color = textColor
        )
    }
}
