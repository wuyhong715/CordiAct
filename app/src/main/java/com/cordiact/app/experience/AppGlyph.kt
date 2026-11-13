package com.cordiact.app.experience

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

enum class GlyphName { Menu, Chat, Trace, Preview, Plus, Send, Stop, Close, ChevronDown, Check, Copy, ArrowDown, Expand, Terminal, Code, Globe, Back, Search, Settings, Link, Warning, Folder, More, Image, File, Camera, Bolt, Reset, Pin, Sparkle, Retry, Model, Role, Connections, Credential, Audit, ChevronRight, Shield, Info }

/** Cached original vectors: constant 24-unit viewport, no mutable Canvas scaling or per-frame paths. */
private val glyphs: Map<GlyphName, ImageVector> by lazy {
    val paths = mapOf(
        GlyphName.Image to "M4,3 H20 Q21,3 21,4 V20 Q21,21 20,21 H4 Q3,21 3,20 V4 Q3,3 4,3 M3,16 L9,10 L14,15 L17,12 L21,16 M15,7 A1,1 0,1 0,17,7 A1,1 0,1 0,15,7",
        GlyphName.File to "M5,3 H14 L20,9 V21 H5 Z M14,3 V9 H20 M8,13 H17 M8,17 H15",
        GlyphName.Camera to "M3,7 H7 L9,4 H15 L17,7 H21 V20 H3 Z M8,13 A4,4 0,1 0,16,13 A4,4 0,1 0,8,13",
        GlyphName.Bolt to "M14,2 L4,14 H11 L10,22 L20,9 H13 Z",
        GlyphName.Reset to "M4,10 A8,8 0,1 1,5,17 M4,4 V10 H10",
        GlyphName.Pin to "M8,3 H16 L15,9 L19,13 H13 V21 L11,19 V13 H5 L9,9 Z",
        GlyphName.Sparkle to "M12,3 L14,9 L21,12 L14,15 L12,21 L10,15 L3,12 L10,9 Z",
        GlyphName.Retry to "M4,10 A8,8 0,1 1,5,17 M4,4 V10 H10",
        GlyphName.Model to "M3,3 H9 V9 H3 Z M15,3 H21 V9 H15 Z M9,15 H15 V21 H9 Z M6,9 V15 M18,9 V15 M9,18 H15",
        GlyphName.Role to "M12,5 A3,3 0,1 0,12,11 A3,3 0,1 0,12,5 M5,20 C5,13 19,13 19,20",
        GlyphName.Connections to "M9,8 A3,3 0,1 0,9,14 A3,3 0,1 0,9,8 M15,10 A3,3 0,1 0,15,16 A3,3 0,1 0,15,10 M12,11 L12,13",
        GlyphName.Credential to "M8,8 A4,4 0,1 0,8,16 A4,4 0,1 0,8,8 M12,12 H21 V16 M17,12 V15",
        GlyphName.Audit to "M5,3 H19 V21 H5 Z M8,8 H16 M8,12 H16 M8,16 H13",
        GlyphName.Shield to "M12,3 L20,6 V11 C20,17 16,20 12,22 C8,20 4,17 4,11 V6 Z M8,12 L11,15 L16,9",
        GlyphName.Info to "M12,3 A9,9 0,1 0,12,21 A9,9 0,1 0,12,3 M12,11 V17 M12,7 V7.2",
        GlyphName.ChevronRight to "M9,5 L16,12 L9,19",
        GlyphName.Folder to "M3,6 Q3,4 5,4 H9 L12,7 H19 Q21,7 21,9 V18 Q21,20 19,20 H5 Q3,20 3,18 Z",
        GlyphName.More to "M5,12 H5.7 M12,12 H12.7 M19,12 H19.7",
        GlyphName.Menu to "M4,7 H20 M4,12 H16 M4,17 H20",
        GlyphName.Chat to "M7,4 H17 Q21,4 21,8 V14 Q21,18 17,18 H8 L5,21 V18 Q3,18 3,14 V8 Q3,4 7,4 M8,9 H16 M8,13 H13",
        GlyphName.Trace to "M5,4 V20 H21 M8,8 H13 M11,12 H19 M14,16 H21",
        GlyphName.Preview to "M6,4 H18 Q21,4 21,7 V17 Q21,20 18,20 H6 Q3,20 3,17 V7 Q3,4 6,4 M3,9 H21 M7,6.5 H7.1",
        GlyphName.Plus to "M12,5 V19 M5,12 H19",
        GlyphName.Send to "M12,19 V5 M6,11 L12,5 L18,11",
        GlyphName.Stop to "M8,6 H16 Q18,6 18,8 V16 Q18,18 16,18 H8 Q6,18 6,16 V8 Q6,6 8,6 Z",
        GlyphName.Close to "M6,6 L18,18 M6,18 L18,6",
        GlyphName.ChevronDown to "M6,9 L12,15 L18,9",
        GlyphName.Check to "M5,12 L10,17 L20,7",
        GlyphName.Copy to "M10,8 H18 Q20,8 20,10 V19 Q20,21 18,21 H10 Q8,21 8,19 V10 Q8,8 10,8 M15,3 H6 Q4,3 4,5 V15",
        GlyphName.ArrowDown to "M12,4 V20 M6,14 L12,20 L18,14",
        GlyphName.Expand to "M4,9 V4 H9 M15,20 H20 V15",
        GlyphName.Terminal to "M6,4 H18 Q21,4 21,7 V17 Q21,20 18,20 H6 Q3,20 3,17 V7 Q3,4 6,4 M7,9 L10,12 L7,15 M13,15 H17",
        GlyphName.Code to "M7,7 L2,12 L7,17 M17,7 L22,12 L17,17 M14,4 L10,20",
        GlyphName.Globe to "M21,12 A9,9 0,1 1,3 12 A9,9 0,1 1,21 12 M3,12 H21 M16,12 A4,9 0,1 1,8 12 A4,9 0,1 1,16 12",
        GlyphName.Back to "M15,5 L8,12 L15,19",
        GlyphName.Search to "M16.5,10 A6.5,6.5 0,1 1,3.5 10 A6.5,6.5 0,1 1,16.5 10 M15,15 L21,21",
        GlyphName.Settings to "M4,7 H20 M7,5 V9 M4,12 H20 M15,10 V14 M4,17 H20 M10,15 V19",
        GlyphName.Link to "M10,15 L14,9 M10,8 L12,6 Q16,2 20,6 Q23,10 19,13 L17,15 M7,9 L5,11 Q1,15 5,19 Q9,22 13,18 L15,16",
        GlyphName.Warning to "M12,3 L22,20 H2 Z M12,9 V13 M12,16.5 V16.6",
    )
    GlyphName.entries.associateWith { name ->
        ImageVector.Builder(name.name, 24.dp, 24.dp, 24f, 24f).addPath(
            pathData = addPathNodes(paths.getValue(name)),
            fill = if (name == GlyphName.Stop) SolidColor(Color.Black) else null,
            stroke = if (name == GlyphName.Stop) null else SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
    }
}

enum class GlyphSize(val dp: Int) { Small(16), Regular(20), Large(24) }

@Composable
fun AppGlyph(name: GlyphName, description: String? = null, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, size: GlyphSize = GlyphSize.Regular) {
    Icon(glyphs.getValue(name), description, modifier.size(size.dp.dp), tint)
}
