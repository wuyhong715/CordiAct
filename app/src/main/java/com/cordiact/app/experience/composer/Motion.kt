package com.cordiact.app.experience.composer

import android.provider.Settings
import androidx.compose.animation.*
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

/** Popup stays composed until its exit finishes. Its child is measured against actual IME-safe bounds. */
@Composable
internal fun AnchoredPopover(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val enter = motionDuration(Motion.Standard)
    val exit = motionDuration(Motion.Fast)
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = visible
    if (!transition.currentState && !transition.targetState) return
    val position = remember { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
            val x = anchorBounds.left.coerceIn(8, (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8))
            val y = (anchorBounds.top - popupContentSize.height - 12).coerceAtLeast(8)
            return IntOffset(x, y)
        }
    } }
    val context = LocalContext.current
    val density = LocalDensity.current
    var available by remember { mutableStateOf(360.dp) }
    Box(Modifier.onGloballyPositioned { coordinates ->
        val top = coordinates.positionInWindow().y
        val insetTop = android.graphics.Rect().also { rect ->
            (context as? android.app.Activity)?.window?.decorView?.getWindowVisibleDisplayFrame(rect)
        }.top
        available = with(density) { (top - insetTop - 20.dp.toPx()).coerceAtLeast(48.dp.toPx()).toDp().coerceAtMost(360.dp) }
    }) {
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, clippingEnabled = true)) {
        AnimatedVisibility(transition, enter = fadeIn(tween(enter)) + scaleIn(tween(enter, easing = Motion.easing), initialScale = .92f),
            exit = fadeOut(tween(exit)) + scaleOut(tween(exit), targetScale = .96f)) {
            Surface(Modifier.widthIn(max = 350.dp).padding(4.dp), shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLowest, shadowElevation = 6.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
                Column(Modifier.widthIn(min = 240.dp, max = 340.dp).heightIn(max = available).padding(12.dp), content = content)
            }
        }
    }
}
}
