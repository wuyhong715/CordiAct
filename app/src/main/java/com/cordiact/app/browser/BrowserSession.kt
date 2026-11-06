package com.cordiact.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * One offscreen WebView session.
 *
 * Deliberately no window: `WindowManager.addView` needs SYSTEM_ALERT_WINDOW and
 * a `VirtualDisplay` needs a MediaProjection token, and neither is acceptable
 * for a background automation session. Instead the WebView is parented to an
 * unattached FrameLayout whose `measure()`/`layout()` we drive by hand. WebView
 * runs scripts and paints into a software canvas without ever being on screen.
 *
 * Two consequences worth knowing:
 *  - Layer type must be SOFTWARE, otherwise `draw(Canvas)` captures nothing and
 *    screenshots come out blank.
 *  - Some sites gate behaviour on `document.hidden`; the visibility shim below
 *    is re-applied on every page start so headless sessions still run.
 *
 * All WebView calls are marshalled to the main thread; tool threads block on a
 * latch with a timeout.
 */
class BrowserSession(
    private val context: Context,
    val id: String,
) {
    private val main = Handler(Looper.getMainLooper())
    private val ready = CountDownLatch(1)

    private var webView: WebView? = null
    private var host: FrameLayout? = null
    private var viewportWidth = DEFAULT_WIDTH
    private var viewportHeight = DEFAULT_HEIGHT

    @Volatile
    var currentUrl: String = ""
        private set

    @Volatile
    var pageTitle: String? = null
        private set

    @Volatile
    var lastError: String? = null
        private set

    fun start(initialUrl: String?, width: Int = DEFAULT_WIDTH, height: Int = DEFAULT_HEIGHT) {
        viewportWidth = width
        viewportHeight = height
        main.post {
            try {
                val container = FrameLayout(context)
                val view = WebView(context)
                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    builtInZoomControls = false
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = userAgentString
                        .replace("; wv", "")
                        .replace("Version/4.0 ", "")
                }
                CookieManager.getInstance().setAcceptCookie(true)

                // Software layer: a hardware layer never reaches a software canvas.
                view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val target = request?.url ?: return false
                        // Never let page-initiated navigation reach file:// (app-private files).
                        return target.scheme.equals("file", ignoreCase = true)
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        applyVisibilityShim(view)
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        currentUrl = url
                        pageTitle = view.title
                        lastError = null
                        layout()
                        ready.countDown()
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: android.webkit.WebResourceError,
                    ) {
                        if (request.isForMainFrame) {
                            lastError = error.description?.toString()
                            ready.countDown()
                        }
                    }
                }

                container.addView(view, FrameLayout.LayoutParams(width, height))
                container.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                container.layout(0, 0, width, height)
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, width, height)

                host = container
                webView = view
                if (initialUrl != null) view.loadUrl(initialUrl)
            } catch (failure: Exception) {
                lastError = failure.message ?: "webview_create_failed"
                ready.countDown()
            }
        }
    }

    fun load(url: String): Boolean {
        val view = webView ?: return false
        currentUrl = url
        return onMain { view.loadUrl(url) } != null
    }

    fun awaitLoaded(timeoutMs: Long): Boolean = ready.await(timeoutMs, TimeUnit.MILLISECONDS)

    @SuppressLint("SetJavaScriptEnabled")
    fun evaluate(script: String, timeoutMs: Long = DEFAULT_SCRIPT_TIMEOUT_MS): String? {
        val view = webView ?: return null
        val result = AtomicReference<String?>()
        val latch = CountDownLatch(1)
        main.post {
            try {
                view.evaluateJavascript(script) { value ->
                    result.set(value)
                    latch.countDown()
                }
            } catch (failure: Exception) {
                lastError = failure.message
                latch.countDown()
            }
        }
        return if (latch.await(timeoutMs, TimeUnit.MILLISECONDS)) result.get() else null
    }

    fun capture(): Bitmap? {
        val view = webView ?: return null
        val result = AtomicReference<Bitmap?>()
        val latch = CountDownLatch(1)
        main.post {
            try {
                layout()
                val bitmap = Bitmap.createBitmap(viewportWidth, viewportHeight, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                result.set(bitmap)
            } catch (failure: Exception) {
                lastError = failure.message
            }
            latch.countDown()
        }
        return if (latch.await(5, TimeUnit.SECONDS)) result.get() else null
    }

    fun destroy() {
        main.post {
            runCatching { (webView?.parent as? android.view.ViewGroup)?.removeAllViews() }
            runCatching { webView?.destroy() }
            webView = null
            host = null
        }
    }

    private fun layout() {
        val container = host ?: return
        val view = webView ?: return
        container.measure(
            View.MeasureSpec.makeMeasureSpec(viewportWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(viewportHeight, View.MeasureSpec.EXACTLY),
        )
        container.layout(0, 0, viewportWidth, viewportHeight)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(viewportWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(viewportHeight, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, viewportWidth, viewportHeight)
    }

    private fun applyVisibilityShim(view: WebView?) {
        if (view == null) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            view.evaluateJavascript(VISIBILITY_SHIM, null)
            return
        }
        runCatching {
            WebViewCompat.addDocumentStartJavaScript(view, VISIBILITY_SHIM, setOf("*"))
        }.onFailure {
            view.evaluateJavascript(VISIBILITY_SHIM, null)
        }
    }

    private fun <T> onMain(block: () -> T): T? {
        val result = AtomicReference<T?>()
        val latch = CountDownLatch(1)
        main.post {
            runCatching(block).onSuccess(result::set).onFailure { lastError = it.message }
            latch.countDown()
        }
        return if (latch.await(5, TimeUnit.SECONDS)) result.get() else null
    }

    companion object {
        const val DEFAULT_WIDTH = 1080
        const val DEFAULT_HEIGHT = 1920
        const val DEFAULT_SCRIPT_TIMEOUT_MS = 10_000L

        private const val VISIBILITY_SHIM = """
            (function () {
              try {
                Object.defineProperty(document, 'visibilityState', { get: () => 'visible' });
                Object.defineProperty(document, 'hidden', { get: () => false });
                document.dispatchEvent(new Event('visibilitychange'));
              } catch (e) {}
            })();
        """

        /**
         * Injected before page scripts run. Namespaced, dependency-free, and
         * intentionally limited to read/act primitives — no network calls, no
         * storage access, so a page cannot use it as a proxy.
         */
        const val JS_BRIDGE = """
            (function () {
              if (window.__cordiact) return;
              const visible = (el) => {
                const r = el.getBoundingClientRect();
                if (r.width <= 0 || r.height <= 0) return false;
                const s = getComputedStyle(el);
                return s.visibility !== 'hidden' && s.display !== 'none' && s.opacity !== '0';
              };
              const label = (el) => (el.innerText || el.value || el.getAttribute('aria-label') ||
                el.getAttribute('placeholder') || el.title || '').toString().trim().slice(0, 80);
              window.__cordiact = {
                snapshot(max) {
                  const sel = 'a,button,input,textarea,select,[role=button],[role=link],[contenteditable=true]';
                  const out = [];
                  for (const el of document.querySelectorAll(sel)) {
                    if (!visible(el)) continue;
                    const r = el.getBoundingClientRect();
                    out.push({
                      tag: el.tagName.toLowerCase(),
                      type: el.getAttribute('type') || '',
                      text: label(el),
                      name: el.getAttribute('name') || '',
                      id: el.id || '',
                      href: el.getAttribute('href') || '',
                      x: Math.round(r.left), y: Math.round(r.top),
                      w: Math.round(r.width), h: Math.round(r.height)
                    });
                    if (out.length >= max) break;
                  }
                  return JSON.stringify({url: location.href, title: document.title, elements: out});
                },
                click(selector) {
                  const el = document.querySelector(selector);
                  if (!el) return JSON.stringify({ok: false, reason: 'not_found'});
                  el.scrollIntoView({block: 'center'});
                  el.click();
                  return JSON.stringify({ok: true});
                },
                type(selector, value) {
                  const el = document.querySelector(selector);
                  if (!el) return JSON.stringify({ok: false, reason: 'not_found'});
                  el.focus();
                  const proto = el instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                  const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                  if (setter) setter.call(el, value); else el.value = value;
                  el.dispatchEvent(new Event('input', {bubbles: true}));
                  el.dispatchEvent(new Event('change', {bubbles: true}));
                  return JSON.stringify({ok: true});
                },
                scroll(dy) { window.scrollBy(0, dy); return JSON.stringify({ok: true, y: window.scrollY}); },
                text(max) { return (document.body?.innerText || '').slice(0, max); }
              };
            })();
        """
    }
}
