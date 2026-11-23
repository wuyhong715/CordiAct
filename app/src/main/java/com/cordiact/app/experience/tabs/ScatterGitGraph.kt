package com.cordiact.app.experience.tabs

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cordiact.app.experience.AppGlyph
import com.cordiact.app.experience.GlyphName
import com.cordiact.app.experience.GlyphSize
import com.cordiact.core.workspace.WorkspaceGitCommit
import com.cordiact.core.workspace.WorkspaceGitSnapshot
import kotlin.math.*

private data class Dot(val commit: WorkspaceGitCommit, val lane: Int)

// Walk youngest to oldest. Keep a lane for each still-unresolved parent.
private fun layout(data: WorkspaceGitSnapshot): List<Dot> {
    val pending = mutableListOf<String?>()
    val visible = data.commits.mapTo(HashSet()) { it.id }
    return data.commits.map { commit ->
        fun reserve(id: String): Int {
            val existing = pending.indexOf(id)
            if (existing >= 0) return existing
            val free = pending.indexOf(null)
            if (free >= 0) { pending[free] = id; return free }
            pending.add(id)
            return pending.lastIndex
        }
        val lane = reserve(commit.id)
        pending[lane] = null
        commit.parents.filter { it in visible }.forEachIndexed { index, id ->
            if (id !in pending) {
                if (index == 0) pending[lane] = id else reserve(id)
            }
        }
        Dot(commit, lane)
    }.asReversed()
}

@Composable internal fun ScatterGraph(data: WorkspaceGitSnapshot, onlyHead: Boolean, open: (WorkspaceGitCommit) -> Unit, modifier: Modifier) {
    val dots = remember(data) { layout(data) }
    val indexes = remember(dots) { dots.withIndex().associate { it.value.commit.id to it.index } }
    val headOnly = onlyHead && data.head != null
    val colors = MaterialTheme.colorScheme
    val text = rememberTextMeasurer(cacheSize = 64)
    val density = LocalDensity.current
    var frame by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember(data) { mutableFloatStateOf(1f) }
    var pan by remember(data) { mutableStateOf(Offset.Zero) }
    val step = with(density) { 68.dp.toPx() }
    val side = with(density) { 36.dp.toPx() }
    val axis = with(density) { 30.dp.toPx() }
    val peak = (dots.maxOfOrNull { it.lane } ?: 0).coerceAtLeast(1)
    val w = max(frame.width.toFloat(), (dots.size - 1) * step + side * 2)
    val h = max(frame.height.toFloat(), with(density) { (122 + (peak - 3).coerceAtLeast(0) * 43).dp.toPx() })
    fun constrain(raw: Offset, z: Float): Offset {
        val dx = w * z - frame.width; val dy = h * z - frame.height
        return Offset(if (dx < 0) -dx / 2 else raw.x.coerceIn(-dx, 0f), if (dy < 0) -dy / 2 else raw.y.coerceIn(-dy, 0f))
    }
    fun reset() { zoom = 1f; pan = constrain(Offset(frame.width - w, 0f), zoom) }
    fun move(next: Float, center: Offset, delta: Offset = Offset.Zero) {
        val clamped = next.coerceIn(.5f, 2.5f)
        pan = constrain(center - (center - pan) * (clamped / zoom) + delta, clamped)
        zoom = clamped
    }
    val latestOpen by rememberUpdatedState(open)
    val visibleW = frame.width.toFloat()
    val actual = (w - side * 2) / (dots.size - 1).coerceAtLeast(1)
    fun pos(i: Int): Offset {
        val lane = dots[i].lane
        val signed = if (lane % 2 == 0) -lane / 2 else (lane + 1) / 2
        val y = (.5f + signed.toFloat() / ((peak + 1) / 2f) * .30f + sin(i * 1.28f) * .025f)
        return Offset(if (dots.size == 1) w * .5f else side + i * actual, (h - axis) * y + axis * .30f)
    }
    LaunchedEffect(frame, data) { if (frame.width > 0) reset() }
    val sage = Color(0xFF788F84); val slate = Color(0xFF8293A4)
    val gold = colors.primary
    fun tint(i: Int): Color = when {
        dots[i].commit.id == data.head -> gold
        dots[i].lane == 0 -> colors.onSurfaceVariant
        dots[i].lane % 2 == 0 -> slate
        else -> sage
    }
    val position by rememberUpdatedState<(Int) -> Offset>({ i -> pos(i) * zoom + pan })
    val transformer by rememberUpdatedState<(Offset, Offset, Float) -> Unit>({ center, delta, factor -> move(zoom * factor, center, delta) })
    Column(modifier) {
        GitPanel(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(Modifier.fillMaxSize().onSizeChanged { frame = it }
                .semantics { contentDescription = "Git 提交图谱，拖动平移，双指缩放；可切换列表逐条查看提交" }
                .pointerInput(data) { detectTransformGestures { c, p, z, _ -> transformer(c, p, z) } }
                .pointerInput(data, headOnly) { detectTapGestures { tap ->
                    val near = dots.indices.filter { !headOnly || dots[it].commit.onHead }.minByOrNull { (position(it) - tap).getDistance() }
                    if (near != null && (position(near) - tap).getDistance() < 24.dp.toPx()) latestOpen(dots[near].commit)
                } }) {
                val px = 1.dp.toPx(); val r = 4.dp.toPx()
                val axisY = (size.height - axis * .72f).coerceAtLeast(0f)
                val style = TextStyle(color = colors.onSurfaceVariant, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                fun onScreen(i: Int) = position(i).x in -side..size.width + side
                var lastX = -1000f
                var lastDate = ""
                for (i in dots.indices) if (onScreen(i)) {
                    val x = position(i).x
                    if (x < 12.dp.toPx() || x > size.width - 28.dp.toPx() || x - lastX < 66.dp.toPx()) continue
                    val date = gitDate(dots[i].commit.timeMillis, "MM.dd")
                    if (date == lastDate) continue
                    lastDate = date; lastX = x
                    drawLine(colors.outlineVariant.copy(alpha = .53f), Offset(x, axis), Offset(x, axisY - 4*px), px,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(2*px, 4*px)))
                    drawText(text, date, topLeft = Offset(x - 14.dp.toPx(), axisY), style = style)
                }
                for ((i, dot) in dots.withIndex()) {
                    val end = position(i)
                    for (parent in dot.commit.parents) {
                        val j = indexes[parent] ?: continue
                        val start = position(j)
                        if (end.x < 0 || start.x > size.width) continue
                        val horizontal = (end.x - start.x) * .51f
                        val path = Path().apply { moveTo(start.x, start.y); cubicTo(start.x + horizontal, start.y, end.x - horizontal, end.y, end.x, end.y) }
                        val lane = if (parent == dot.commit.parents.first()) i else j
                        drawPath(path, tint(lane).copy(alpha = if (headOnly && !dot.commit.onHead) .15f else .77f),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(if (dots[lane].lane == 0) 1.8f*px else 1.5f*px,
                                pathEffect = if (dots[lane].lane > 0 && dots[lane].lane % 2 == 0) PathEffect.dashPathEffect(floatArrayOf(4*px, 3*px)) else null))
                    }
                }
                for ((i, dot) in dots.withIndex()) {
                    if (!onScreen(i)) continue
                    val p = position(i); val head = dot.commit.id == data.head
                    val dim = headOnly && !dot.commit.onHead
                    val color = tint(i).copy(alpha = if (dim) .20f else 1f)
                    if (head) drawCircle(gold.copy(alpha = .15f), 13*px, p)
                    drawCircle(color, if (head) 7*px else r + px, p)
                    if (!head) drawCircle(colors.surfaceContainerLowest, r - px*.55f, p)
                    if (dot.commit.parents.size > 1) drawCircle(color, 1.9f*px, p)
                    if (dim || zoom < .65f) continue
                    val label = if (head) "HEAD · ${data.headBranch ?: "detached"}" else dot.commit.refs.firstOrNull()?.take(24)
                    if (label != null) {
                        val layout = text.measure(label.take(31), style.copy(color = color))
                        val width = layout.size.width + 16*px
                        val x = (p.x - width/2).coerceIn(3*px, max(3*px, size.width - width - 3*px))
                        val y = (p.y + if (dot.lane % 2 == 0) -33*px else 13*px).coerceAtLeast(2*px)
                        drawRoundRect(if (head) gold.copy(alpha = .12f) else colors.surfaceContainer, Offset(x,y), Size(width,21*px), CornerRadius(10*px))
                        drawText(layout, topLeft = Offset(x+8*px,y+(21*px-layout.size.height)/2))
                    } else if (dot.commit.parents.size > 1 && zoom >= .9f) {
                        drawText(text, "合并", style = style, topLeft = Offset(p.x - 11*px, p.y+10*px))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(historySummary(data, onlyHead), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Text("○ 提交   ⊙ 合并   ● HEAD", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, maxLines = 1)
            }
            Surface(shape = RoundedCornerShape(100), border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant.copy(alpha = .58f))) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ move(zoom - .2f, Offset(visibleW/2, frame.height/2f)) }, Modifier.width(38.dp), enabled = zoom > .5f,
                        contentPadding = PaddingValues(0.dp)) { Text("−") }
                    Text("${(zoom*100).roundToInt()}%", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall)
                    TextButton({ move(zoom + .2f, Offset(visibleW/2, frame.height/2f)) }, Modifier.width(38.dp), enabled = zoom < 2.5f,
                        contentPadding = PaddingValues(0.dp)) { Text("+") }
                    IconButton(::reset, Modifier.size(37.dp)) { AppGlyph(GlyphName.Expand, size = GlyphSize.Small, description = "重置视图") }
                }
            }
        }
    }
}
