package com.cordiact.app.ui.window.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 紧凑代码块
 * - 6dp 圆角（从大厂 8dp 降低）
 * - 12sp 字号（从 14sp 降低）
 * - 8dp 内边距（从 12dp 降低）
 * - 顶部标签栏 4dp 上下边距（从 6dp 降低）
 */
@Composable
fun CodeBlock(
    code: String,
    language: String = "",
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    
    Surface(
        color = Color(0xFFFAFAFA),  // 极浅灰（比 #F5F5F5 更浅）
        shape = RoundedCornerShape(6.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column {
            // 顶部标签栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFEEEEEE))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = language.ifEmpty { "Plain Text" },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
                IconButton(
                    onClick = { clipboardManager.setText(AnnotatedString(code)) },
                    modifier = Modifier.size(16.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = "复制",
                        tint = Color.Gray,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            
            // 代码内容
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = Color.Black,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}
