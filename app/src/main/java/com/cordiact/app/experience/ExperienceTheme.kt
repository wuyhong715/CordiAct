package com.cordiact.app.experience

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

private val LightPalette = lightColorScheme(
    primary = Color(0xFF535ACB), onPrimary = Color.White,
    primaryContainer = Color(0xFFECEBFF), onPrimaryContainer = Color(0xFF373F97),
    secondary = Color(0xFF54655D), secondaryContainer = Color(0xFFE1EEE6),
    tertiary = Color(0xFF99682E), tertiaryContainer = Color(0xFFFFEACD),
    background = Color(0xFFF8F8FA), onBackground = Color(0xFF222631),
    surface = Color(0xFFFEFEFF), onSurface = Color(0xFF222631),
    surfaceVariant = Color(0xFFEEEFF4), onSurfaceVariant = Color(0xFF6B7080),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF3F3F8),
    surfaceContainer = Color(0xFFEEEFF5), surfaceContainerHigh = Color(0xFFE8EAF1),
    surfaceContainerHighest = Color(0xFFE2E4EC),
    outline = Color(0xFF8A8F9D), outlineVariant = Color(0xFFE0E2EB),
    error = Color(0xFFAD4544), errorContainer = Color(0xFFFFEAE7),
)
private val DarkPalette = darkColorScheme(
    primary = Color(0xFFBBC1FF), primaryContainer = Color(0xFF343B73),
    background = Color(0xFF11141C), surface = Color(0xFF171B25),
    surfaceContainerLow = Color(0xFF1C202C), surfaceContainer = Color(0xFF232837),
    surfaceContainerHigh = Color(0xFF2B3141), outlineVariant = Color(0xFF353C4E),
    onSurface = Color(0xFFE7EAF3), onSurfaceVariant = Color(0xFFA3ADC0),
)

/** Explicit light default. Surface translucency never blurs its foreground content. */
@Composable
fun ExperienceTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkPalette else LightPalette,
        typography = Typography(
            headlineLarge = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.7).sp),
            headlineSmall = TextStyle(fontSize = 23.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
            titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            titleSmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 26.sp, letterSpacing = 0.1.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
            labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
            labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
        ),
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(30.dp)),
        content = content,
    )
}
