package com.cordiact.app.render

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.cordiact.core.render.UiSnapshot
import com.cordiact.core.render.UiSnapshotChecks
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * Owns the one WebView that renders previews, and speaks the command protocol implemented by
 * `assets/render/preview.js`.
 *
 * Deliberate choices, all of them load-bearing:
 *
 *  - **Assets over a fake https origin.** `file://` is neither a secure context (so WebCodecs
 *    would be missing later) nor a usable origin for ES modules. [ASSET_ORIGIN] fixes both and
 *    is the only origin the bridge accepts.
 *  - **No `addJavascriptInterface`.** The page can only talk through
 *    `WebViewCompat.addWebMessageListener`, which is origin-scoped, and the host treats every
 *    payload as data.
 *  - **No external navigation.** `shouldOverrideUrlLoading` returns `true` for everything:
 *    a preview that silently opens the browser is both a security hole and a product bug.
 *  - **Network off by default.** Requests are served by [assetLoader]; anything that would
 *    leave the app is blocked unless the caller opted in.
 *
 * One instance per conversation, not per message: creating a WebView per preview card is the
 * fastest way to run a phone out of memory.
 */
class RenderSurfaceHost(
    private val context: Context,
    private val onEvent: (RenderEvent) -> Unit,
    private val allowNetwork: Boolean = false,
) {

    companion object {
        const val ASSET_ORIGIN = "https://appassets.androidplatform.net"
        const val PAGE_URL = "$ASSET_ORIGIN/assets/render/preview.html"
        private const val BRIDGE_OBJECT = "CordiActHost"
        private const val TAG = "RenderSurface"

        /** Commands issued before the page announces itself are replayed, but only these many. */
        private const val MAX_PENDING = 8
    }

    /** False when the device's WebView is too old for the bridge; [setContent] then no-ops. */
    val isSupported: Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    val view: WebView = WebView(context)

    /**
     * A plain main-thread handler, not [WebView.post]: an unattached View queues its posted
     * runnables until it is attached, so a surface created before it is displayed (preloading,
     * or an instrumented test) would silently drop every command.
     */
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<String>()
    private var ready = false
    private var destroyed = false
    private var viewport = PreviewViewport.Phone

    // Frame-series driver: the page holds one document, so stops are walked one at a time
    // instead of firing five probes at once.
    private var seriesTimes: List<Double> = emptyList()
    private var seriesIndex = 0
    private var seriesOut: MutableList<UiSnapshot> = mutableListOf()
    private var seriesDone: ((List<UiSnapshot>) -> Unit)? = null

    private val assetLoader = WebViewAssetLoader.Builder()
        // AssetsPathHandler strips the registered prefix, so "/assets/" maps straight onto
        // the APK's assets/ root: /assets/render/preview.html -> assets/render/preview.html.
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()

    init {
        require(Looper.myLooper() == Looper.getMainLooper()) {
            "RenderSurfaceHost must be created on the main thread"
        }
        configureView()
        configureBridge()
        view.loadUrl(PAGE_URL)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureView() {
        view.setBackgroundColor(Color.TRANSPARENT)
        view.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        WebView.setWebContentsDebuggingEnabled(debuggable)

        with(view.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            // A preview never touches the filesystem: content arrives as a string.
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            // Animations must start on their own; a preview is not a page the user asked for.
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            blockNetworkLoads = !allowNetwork
            // We control the palette of the preview; forced darkening would invert agent CSS.
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, false)
            }
        }

        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = true

            /**
             * A missing or mis-pathed asset otherwise shows up as a silent blank surface: the
             * page simply never announces itself. Surface the load error instead of letting the
             * caller sit on a timeout.
             */
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame) return
                onEvent(
                    RenderEvent.Failed(
                        where = "load",
                        message = "${error.errorCode} ${error.description} for ${request.url}",
                    ),
                )
            }
        }
    }

    private fun configureBridge() {
        if (!isSupported) {
            onEvent(
                RenderEvent.Failed(
                    where = "bridge",
                    message = "WebView on this device does not support WEB_MESSAGE_LISTENER",
                ),
            )
            return
        }
        WebViewCompat.addWebMessageListener(
            view,
            BRIDGE_OBJECT,
            setOf(ASSET_ORIGIN),
            object : WebViewCompat.WebMessageListener {
                override fun onPostMessage(
                    view: WebView,
                    message: WebMessageCompat,
                    sourceOrigin: Uri,
                    isMainFrame: Boolean,
                    replyProxy: JavaScriptReplyProxy,
                ) {
                    message.data?.let(::dispatch)
                }
            },
        )
    }

    // ---- command surface -----------------------------------------------------------

    fun setContent(
        html: String,
        css: String = "",
        viewport: PreviewViewport = this.viewport,
    ) {
        this.viewport = viewport.clamped()
        send(
            JSONObject()
                .put("type", "setContent")
                .put("html", html)
                .put("css", css)
                .put("viewport", viewportJson(this.viewport)),
        )
    }

    /** Style/text-only update: keeps scroll position and running animations alive. */
    fun patch(css: String? = null, html: String? = null) {
        val command = JSONObject().put("type", "patch")
        css?.let { command.put("css", it) }
        html?.let { command.put("html", it) }
        send(command)
    }

    fun setViewport(viewport: PreviewViewport) {
        this.viewport = viewport.clamped()
        send(
            JSONObject()
                .put("type", "setViewport")
                .put("w", this.viewport.widthDp)
                .put("h", this.viewport.heightDp)
                .put("scale", this.viewport.scale.toDouble()),
        )
    }

    /** Freezes the page at an absolute time so a still can be inspected or captured. */
    fun seek(timeMs: Double) = send(JSONObject().put("type", "seek").put("t", timeMs))

    fun step(deltaMs: Double) = send(JSONObject().put("type", "step").put("ms", deltaMs))

    fun play() = send(JSONObject().put("type", "play"))

    fun pause() = send(JSONObject().put("type", "pause"))

    fun requestSnapshot() = send(JSONObject().put("type", "snapshot"))

    /** Asks the frame what its environment actually looks like; see [RenderEvent.Probed]. */
    fun probe() = send(JSONObject().put("type", "probe"))

    /**
     * Walks [timesMs] and reports one structural snapshot per stop.
     *
     * This is how an animation becomes legible to the agent: a single snapshot says "the button
     * is at y=300", five snapshots say "it started at y=300 and ended at y=120, and at t=1000 it
     * overlapped the caption". No pixels, no visible window, no per-frame capture budget.
     */
    fun captureSeries(timesMs: List<Double>, onSeries: (List<UiSnapshot>) -> Unit) {
        if (timesMs.isEmpty()) {
            onSeries(emptyList())
            return
        }
        seriesTimes = timesMs
        seriesIndex = 0
        seriesOut = mutableListOf()
        seriesDone = onSeries
        seek(seriesTimes[0])
    }

    // ---- lifecycle -----------------------------------------------------------------

    /** WebView keeps its own clock; pausing the view stops rAF and timers. */
    fun onHostPause() {
        if (destroyed) return
        view.onPause()
        view.pauseTimers()
    }

    fun onHostResume() {
        if (destroyed) return
        view.resumeTimers()
        view.onResume()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        pending.clear()
        (view.parent as? ViewGroup)?.removeView(view)
        view.stopLoading()
        view.webViewClient = WebViewClient()
        view.destroy()
    }

    // ---- protocol plumbing ---------------------------------------------------------

    private fun viewportJson(viewport: PreviewViewport): JSONObject = JSONObject()
        .put("w", viewport.widthDp)
        .put("h", viewport.heightDp)
        .put("scale", viewport.scale.toDouble())

    private fun send(command: JSONObject) {
        if (destroyed) return
        if (!isSupported) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { send(command) }
            return
        }
        val payload = command.toString()
        if (!ready) {
            // The page announces itself with `ready`; replaying is cheaper than a handshake
            // state machine per caller.
            if (pending.size > MAX_PENDING) pending.removeFirst()
            pending.addLast(payload)
            return
        }
        evaluate(payload)
    }

    private fun evaluate(payload: String) {
        // Quote through JSON so any HTML/CSS the agent wrote cannot break out of the call.
        val literal = JSONObject.quote(payload)
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
        view.evaluateJavascript("window.__cordiact && window.__cordiact.handle($literal);", null)
    }

    private fun dispatch(raw: String) {
        val json = try {
            JSONObject(raw)
        } catch (error: Exception) {
            onEvent(RenderEvent.Failed("bridge", "malformed message: ${error.message}"))
            return
        }
        when (json.optString("type")) {
            "ready" -> {
                ready = true
                while (pending.isNotEmpty()) evaluate(pending.removeFirst())
                onEvent(RenderEvent.Ready(json.optString("ua")))
            }

            "mounted" -> onEvent(RenderEvent.Mounted(json.optJSONObject("viewport")?.toViewport() ?: viewport))

            "patched" -> onEvent(RenderEvent.Patched)

            "seeked" -> {
                onEvent(RenderEvent.Seeked(json.optDouble("t", 0.0)))
                if (seriesDone != null) requestSnapshot()
            }

            "playing" -> onEvent(RenderEvent.Playing)

            "paused" -> onEvent(RenderEvent.Paused(json.optDouble("t", 0.0)))

            "snapshot" -> {
                val snapshot = SnapshotParser.parse(json)
                if (snapshot == null) {
                    onEvent(RenderEvent.Failed("snapshot", "snapshot carried no viewport"))
                } else {
                    onEvent(RenderEvent.SnapshotReady(snapshot, UiSnapshotChecks.check(snapshot)))
                    seriesDone?.let { done ->
                        seriesOut.add(snapshot)
                        seriesIndex += 1
                        if (seriesIndex >= seriesTimes.size) {
                            seriesDone = null
                            done(seriesOut.toList())
                        } else {
                            seek(seriesTimes[seriesIndex])
                        }
                    }
                }
            }

            "probe" -> onEvent(
                RenderEvent.Probed(
                    hasClock = json.optBoolean("hasClock", false),
                    frozen = json.optBoolean("frozen", false),
                    pageSawClock = json.optBoolean("pageSawClock", false),
                    animationsApi = json.optBoolean("animationsApi", false),
                    animationCount = json.optInt("animationCount", 0),
                    animationTimes = json.optJSONArray("animationTimes")?.let { times ->
                        (0 until times.length()).map { index ->
                            if (times.isNull(index)) null else times.optDouble(index)
                        }
                    } ?: emptyList(),
                    nodeCount = json.optInt("nodeCount", 0),
                ),
            )

            "error" -> onEvent(
                RenderEvent.Failed(
                    where = json.optString("where", "page"),
                    message = json.optString("message", "unknown page error"),
                    stack = json.optString("stack").takeIf { it.isNotEmpty() },
                ),
            )

            else -> {
                Log.d(TAG, "unhandled event: ${json.optString("type")}")
                onEvent(RenderEvent.Unknown(json.optString("type", "?"), raw.take(500)))
            }
        }
    }

    private fun JSONObject.toViewport(): PreviewViewport = PreviewViewport(
        widthDp = optInt("w", viewport.widthDp),
        heightDp = optInt("h", viewport.heightDp),
        scale = optDouble("scale", viewport.scale.toDouble()).toFloat(),
    ).clamped()
}
