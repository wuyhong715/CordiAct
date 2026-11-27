package com.cordiact.app.experience

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.cordiact.core.workbench.WorkbenchAccent

// v3 "Lumen": warm parchment neutrals (Claude-like); pale honey is the brand signal,
// reserved for agent activity. Pale yellow never carries text: onPrimary is dark ink.
internal object Lumen {
    val Signal = Color(0xFFE9B949)        // honey: dots, beads, running send button
    val SignalDark = Color(0xFFF2CC6B)
    val Pale = Color(0xFFFBEFC8)          // brand container / highlights
    val Ink = Color(0xFF2B2416)           // text/icons on brand
    val Aurora = listOf(Color(0xFFF2CC6B), Color(0xFFE9B949), Color(0xFFF7E2A4))
    val Approve = Color(0xFFD9653B)       // approvals: coral, clearly apart from honey
    val Ok = Color(0xFF3F9B5A)
    val Error = Color(0xFFD13B2E)
}
private val LightPalette = lightColorScheme(
    primary = Color(0xFF8A6510), onPrimary = Color.White,  // readable honey for text/icons on cream
    primaryContainer = Color(0xFFF7EFD9), onPrimaryContainer = Color(0xFF5A4410),  // cream accent
    secondary = Color(0xFF5E5C57), secondaryContainer = Color(0xFFF7EFD9), onSecondaryContainer = Color(0xFF2A2721),
    tertiary = Lumen.Approve, tertiaryContainer = Color(0xFFFBE4DA), onTertiaryContainer = Color(0xFF6B2A12),
    background = Color(0xFFFFFFFF), onBackground = Color(0xFF1C1C1A),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1C1C1A),
    surfaceVariant = Color(0xFFF2F2F0), onSurfaceVariant = Color(0xFF76746E),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8F8F6),
    surfaceContainer = Color(0xFFF3F3F1), surfaceContainerHigh = Color(0xFFEBEBE8),
    surfaceContainerHighest = Color(0xFFE0DFDB),
    outline = Color(0xFFB4B2AC), outlineVariant = Color(0xFFE7E6E2),
    error = Lumen.Error, errorContainer = Color(0xFFFBE3E0),
)
private val DarkPalette = darkColorScheme(
    primary = Lumen.SignalDark, onPrimary = Lumen.Ink, primaryContainer = Color(0xFF4A3B14), onPrimaryContainer = Lumen.Pale,
    tertiary = Color(0xFFE8825E), tertiaryContainer = Color(0xFF4F2312),
    background = Color(0xFF1A1915), onBackground = Color(0xFFF2EFE6), surface = Color(0xFF24231E), onSurface = Color(0xFFF2EFE6),
    surfaceVariant = Color(0xFF302E28), surfaceContainerLow = Color(0xFF22211C), surfaceContainer = Color(0xFF2B2A24),
    surfaceContainerHigh = Color(0xFF35332C), surfaceContainerHighest = Color(0xFF403E36),
    outlineVariant = Color(0xFF3D3B33), onSurfaceVariant = Color(0xFFADA796), error = Color(0xFFF26B5E),
)

@Composable
fun ExperienceTheme(darkTheme: Boolean = false, accent: WorkbenchAccent = WorkbenchAccent.AMBER, content: @Composable () -> Unit) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    val colors = when (accent) {
        WorkbenchAccent.AMBER -> palette
        WorkbenchAccent.INDIGO -> if (darkTheme) palette.copy(
            primary = Color(0xFFC4C5FF), onPrimary = Color(0xFF222460),
            primaryContainer = Color(0xFF383981), onPrimaryContainer = Color(0xFFE2DFFF),
        ) else palette.copy(
            primary = Color(0xFF4D4EA0), onPrimary = Color.White,
            primaryContainer = Color(0xFFE2DFFF), onPrimaryContainer = Color(0xFF1B1C59),
        )
        WorkbenchAccent.TEAL -> if (darkTheme) palette.copy(
            primary = Color(0xFF8CD5C4), onPrimary = Color(0xFF00382F),
            primaryContainer = Color(0xFF005045), onPrimaryContainer = Color(0xFFAAF2E0),
        ) else palette.copy(
            primary = Color(0xFF00685B), onPrimary = Color.White,
            primaryContainer = Color(0xFFAAF2E0), onPrimaryContainer = Color(0xFF00201A),
        )
    }
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(
            displaySmall = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
            headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.Bold),
            headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
            titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 15.5.sp, lineHeight = 25.sp),
            bodyMedium = TextStyle(fontSize = 14.5.sp, lineHeight = 22.sp),
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
