package com.cordiact.app.experience

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

enum class GlyphName { Menu, Chat, Trace, Preview, Plus, Send, Stop, Close, ChevronDown, Check, Copy, ArrowDown, Expand, Terminal, Code, Globe, Back, Search, Settings, Link, Warning }

/** Original 24-unit geometric glyphs. No icon dependency, consistent stroke and optical weight. */
@Composable
fun AppGlyph(name: GlyphName, description: String? = null, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    Canvas(modifier.size(22.dp).semantics { if (description != null) contentDescription = description }) {
        scale(size.width / 24f, size.height / 24f, Offset.Zero) {
            val stroke = Stroke(1.7f, cap = StrokeCap.Round)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, Offset(x1,y1), Offset(x2,y2), 1.7f, StrokeCap.Round)
            fun box(x: Float, y: Float, w: Float, h: Float, r: Float = 3f) = drawRoundRect(tint, Offset(x,y), Size(w,h), CornerRadius(r), style = stroke)
            when (name) {
                GlyphName.Menu -> { line(4f,7f,20f,7f); line(4f,12f,16f,12f); line(4f,17f,20f,17f) }
                GlyphName.Chat -> { box(3f,4f,18f,14f,4f); line(7f,18f,5f,21f); line(8f,9f,16f,9f); line(8f,13f,13f,13f) }
                GlyphName.Trace -> { line(5f,4f,5f,20f); line(5f,20f,21f,20f); line(8f,8f,13f,8f); line(11f,12f,19f,12f); line(14f,16f,21f,16f) }
                GlyphName.Preview -> { box(3f,4f,18f,16f); line(3f,9f,21f,9f); line(7f,6.5f,7.1f,6.5f) }
                GlyphName.Plus -> { line(12f,5f,12f,19f); line(5f,12f,19f,12f) }
                GlyphName.Send -> { line(12f,19f,12f,5f); line(6f,11f,12f,5f); line(12f,5f,18f,11f) }
                GlyphName.Stop -> drawRoundRect(tint, Offset(6f,6f), Size(12f,12f), CornerRadius(2f))
                GlyphName.Close -> { line(6f,6f,18f,18f); line(6f,18f,18f,6f) }
                GlyphName.ChevronDown -> { line(6f,9f,12f,15f); line(12f,15f,18f,9f) }
                GlyphName.Check -> { line(5f,12f,10f,17f); line(10f,17f,20f,7f) }
                GlyphName.Copy -> { box(8f,8f,12f,13f,2f); line(15f,3f,4f,3f); line(4f,3f,4f,15f) }
                GlyphName.ArrowDown -> { line(12f,4f,12f,20f); line(6f,14f,12f,20f); line(12f,20f,18f,14f) }
                GlyphName.Expand -> { line(4f,9f,4f,4f); line(4f,4f,9f,4f); line(15f,20f,20f,20f); line(20f,20f,20f,15f) }
                GlyphName.Terminal -> { box(3f,4f,18f,16f); line(7f,9f,10f,12f); line(10f,12f,7f,15f); line(13f,15f,17f,15f) }
                GlyphName.Code -> { line(7f,7f,2f,12f); line(2f,12f,7f,17f); line(17f,7f,22f,12f); line(22f,12f,17f,17f); line(14f,4f,10f,20f) }
                GlyphName.Globe -> { drawCircle(tint,9f,Offset(12f,12f),style=stroke); line(3f,12f,21f,12f); drawOval(tint,Offset(8f,3f),Size(8f,18f),style=stroke) }
                GlyphName.Back -> { line(15f,5f,8f,12f); line(8f,12f,15f,19f) }
                GlyphName.Search -> { drawCircle(tint,6.5f,Offset(10f,10f),style=stroke); line(15f,15f,21f,21f) }
                GlyphName.Settings -> { drawCircle(tint,8f,Offset(12f,12f),style=stroke); drawCircle(tint,3f,Offset(12f,12f),style=stroke) }
                GlyphName.Link -> { box(3f,9f,11f,7f,3.5f); box(10f,5f,11f,7f,3.5f) }
                GlyphName.Warning -> {
                    drawPath(Path().apply { moveTo(12f,3f); lineTo(22f,20f); lineTo(2f,20f); close() }, tint,style=stroke)
                    line(12f,9f,12f,13f); line(12f,16.5f,12f,16.6f)
                }
            }
        }
    }
}
