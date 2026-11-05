package com.cordiact.app.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Creates the single preview host and ties its lifetime to the composition and to the host
 * lifecycle.
 *
 * The host is *not* recreated when [onEvent] changes — a lambda identity change must never
 * tear down a WebView, or every recomposition would restart the preview. The latest lambda is
 * read through [rememberUpdatedState] instead.
 */
@Composable
fun rememberRenderSurfaceHost(
    allowNetwork: Boolean = false,
    onEvent: (RenderEvent) -> Unit,
): RenderSurfaceHost {
    val context = LocalContext.current
    val latest by rememberUpdatedState(onEvent)
    val host = remember { RenderSurfaceHost(context, { event -> latest(event) }, allowNetwork) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, host) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> host.onHostPause()
                Lifecycle.Event.ON_RESUME -> host.onHostResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            host.destroy()
        }
    }
    return host
}

/** The preview surface itself. Callers keep it on screen; hiding it stops rendering. */
@Composable
fun RenderSurface(
    host: RenderSurfaceHost,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { host.view },
        modifier = modifier,
    )
}
