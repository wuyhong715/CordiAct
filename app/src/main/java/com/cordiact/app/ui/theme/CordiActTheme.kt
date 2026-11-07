package com.cordiact.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * CordiAct Agent 紧凑配色方案
 * 深空背景 + 极浅表面 + 语义色柔和化
 */
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF64B5F6),         // 柔和蓝
    secondary = Color(0xFF81C784),       // 柔和绿
    tertiary = Color(0xFFFFB74D),        // 柔和橙
    error = Color(0xFFE57373),           // 柔和红
    background = Color(0xFF0D1117),      // 深空黑（GitHub dark）
    surface = Color(0xFF161B22),         // 卡片底
    surfaceContainerLow = Color(0xFFFAFAFA),  // 代码块/表格浅灰底
    onBackground = Color(0xFFE6EDF3),    // 主文字
    onSurface = Color(0xFFE6EDF3),
    onSurfaceVariant = Color(0xFF8B949E) // 灰色辅助文字
)

/**
 * CordiAct 紧凑字号体系
 * 正文 14sp（可读性下限），代码 12sp（等宽字体舒适下限）
 */
private val CompactTypography = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        lineHeight = 24.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 18.sp
    )
)

@Composable
fun CordiActTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,  // Agent 工具强制深色
        typography = CompactTypography,
        content = content
    )
}
