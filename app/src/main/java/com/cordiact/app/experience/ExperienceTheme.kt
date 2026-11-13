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
    primary = Color(0xFF5A58B8), onPrimary = Color.White,
    primaryContainer = Color(0xFFECEBFF), onPrimaryContainer = Color(0xFF373F97),
    secondary = Color(0xFF54655D), secondaryContainer = Color(0xFFE1EEE6),
    tertiary = Color(0xFF99682E), tertiaryContainer = Color(0xFFFFEACD),
    background = Color(0xFFF7F7F9), onBackground = Color(0xFF1D1D22),
    surface = Color(0xFFFEFEFF), onSurface = Color(0xFF1D1D22),
    surfaceVariant = Color(0xFFEEEFF4), onSurfaceVariant = Color(0xFF62626D),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF3F3F8),
    surfaceContainer = Color(0xFFEEEFF5), surfaceContainerHigh = Color(0xFFE8EAF1),
    surfaceContainerHighest = Color(0xFFE2E4EC),
    outline = Color(0xFF8A8F9D), outlineVariant = Color(0xFFE0E2EB),
    error = Color(0xFFAD4544), errorContainer = Color(0xFFFFEAE7),
)
private val DarkPalette = darkColorScheme(
    primary = Color(0xFFAAA7FF), primaryContainer = Color(0xFF343B73),
    background = Color(0xFF141419), surface = Color(0xFF1D1D24),
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
            headlineLarge = TextStyle(fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
            headlineSmall = TextStyle(fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
            titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
            labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
            labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
            labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
        ),
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(30.dp)),
        content = content,
    )
}
