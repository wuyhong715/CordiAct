package com.cordiact.app.experience

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

// v3 "Lumen": iOS system grays; the single brand signal is reserved for agent activity.
internal object Lumen {
    val Signal = Color(0xFF5B5BF6)
    val SignalDark = Color(0xFF7B7BFF)
    val Aurora = listOf(Color(0xFF5B5BF6), Color(0xFF22C3EE), Color(0xFFA78BFA))
    val Approve = Color(0xFFFF9F0A)
    val Ok = Color(0xFF34C759)
    val Error = Color(0xFFFF3B30)
}
private val LightPalette = lightColorScheme(
    primary = Lumen.Signal, onPrimary = Color.White,
    primaryContainer = Color(0xFFECECFE), onPrimaryContainer = Color(0xFF2E2EA8),
    secondary = Color(0xFF3C3C43), secondaryContainer = Color(0xFFE5E5EA), onSecondaryContainer = Color(0xFF1C1C1E),
    tertiary = Lumen.Approve, tertiaryContainer = Color(0xFFFFF1DC), onTertiaryContainer = Color(0xFF6B4100),
    background = Color(0xFFF2F2F7), onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFE5E5EA), onSurfaceVariant = Color(0x993C3C43),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F7FA),
    surfaceContainer = Color(0xFFEFEFF4), surfaceContainerHigh = Color(0xFFE5E5EA),
    surfaceContainerHighest = Color(0xFFD1D1D6),
    outline = Color(0xFFAEAEB2), outlineVariant = Color(0x4A3C3C43),
    error = Lumen.Error, errorContainer = Color(0xFFFFE5E3),
)
private val DarkPalette = darkColorScheme(
    primary = Lumen.SignalDark, onPrimary = Color.White, primaryContainer = Color(0xFF2A2A6B),
    tertiary = Color(0xFFFFB340), tertiaryContainer = Color(0xFF4A3000),
    background = Color(0xFF000000), onBackground = Color.White, surface = Color(0xFF1C1C1E), onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2E), surfaceContainerLow = Color(0xFF1C1C1E), surfaceContainer = Color(0xFF2C2C2E),
    surfaceContainerHigh = Color(0xFF3A3A3C), surfaceContainerHighest = Color(0xFF48484A),
    outlineVariant = Color(0x99545458), onSurfaceVariant = Color(0x99EBEBF5), error = Color(0xFFFF453A),
)

/** Explicit light default. Surface translucency never blurs its foreground content. */
@Composable
fun ExperienceTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkPalette else LightPalette,
        typography = Typography(
            displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
            headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
            headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
            titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 27.sp),
            bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
            labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
        ),
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(28.dp), extraLarge = RoundedCornerShape(34.dp)),
        content = content,
    )
}
