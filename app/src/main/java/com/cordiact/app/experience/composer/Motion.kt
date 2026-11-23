package com.cordiact.app.experience.composer

import android.provider.Settings
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.*

internal object Motion {
    const val Fast = 120
    const val Standard = 180
    val easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
}

@Composable
internal fun motionDuration(value: Int): Int {
    val context = LocalContext.current
    return if (Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) 0 else value
}

/** Compact popup anchored to the trigger. Bounds and flip preference use the visible window. */
@Composable
internal fun AnchoredPopover(visible: Boolean, onDismiss: () -> Unit, width: Dp, maxHeight: Dp,
    alignEnd: Boolean = false, preferBelow: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    if (!visible) return
    val density = LocalDensity.current
    val view = LocalView.current
    val gap = with(density) { 6.dp.roundToPx() }
    val edge = with(density) { 8.dp.roundToPx() }
    val position = remember(gap, edge, alignEnd, preferBelow, view) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
            val frame = android.graphics.Rect().also(view::getWindowVisibleDisplayFrame)
            val location = IntArray(2).also(view::getLocationOnScreen)
            val origin = IntArray(2).also(view::getLocationInWindow)
            val windowTop = location[1] - origin[1]
            val top = (frame.top - windowTop).coerceIn(0, windowSize.height) + edge
            val bottom = (frame.bottom - windowTop).coerceIn(top.coerceAtMost(windowSize.height), windowSize.height) - edge
            val above = anchorBounds.top - top - gap
            val below = bottom - anchorBounds.bottom - gap
            val h = popupContentSize.height
            val down = if (preferBelow) below >= h || below >= above else above < h && (below >= h || below > above)
            val y = if (down) anchorBounds.bottom + gap else anchorBounds.top - gap - h
            val fromRight = alignEnd != (layoutDirection == LayoutDirection.Rtl)
            val x = if (fromRight) anchorBounds.right - popupContentSize.width else anchorBounds.left
            return IntOffset(x.coerceIn(edge, (windowSize.width - popupContentSize.width - edge).coerceAtLeast(edge)),
                y.coerceIn(top, (bottom - h).coerceAtLeast(top)))
        }
    } }
    var available by remember { mutableStateOf(maxHeight) }
    Box(Modifier.onGloballyPositioned { coordinates ->
        val location = IntArray(2).also(view::getLocationOnScreen)
        val origin = IntArray(2).also(view::getLocationInWindow)
        val windowTop = location[1] - origin[1]
        val frame = android.graphics.Rect().also(view::getWindowVisibleDisplayFrame)
        val top = coordinates.positionInWindow().y + windowTop
        val above = top - frame.top - gap - edge
        val below = frame.bottom - top - gap - edge
        available = with(density) { maxOf(above, below).coerceAtLeast(48.dp.toPx()).toDp() }
    })
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, clippingEnabled = true)) {
        val shape = RoundedCornerShape(14.dp)
        Surface(Modifier.width(width).heightIn(max = minOf(maxHeight, available)), shape = shape,
            color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 3.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
            Column(Modifier.padding(4.dp), content = content)
        }
    }
}
