package com.cordiact.app.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cordiact.core.render.SnapshotIssueKind
import com.cordiact.core.render.UiSnapshot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Device-side proof of the four things that cannot be settled by reading documentation:
 *
 *  1. the asset domain is reachable while the network is blocked (the page announces itself),
 *  2. the clock shim really runs *before* the page's own scripts,
 *  3. CSS/WAAPI animations inside the iframe are discoverable from the frame document,
 *  4. seeking freezes and aligns them, repeatably, and the snapshot pipeline flags a broken
 *     layout end to end.
 *
 * The WebView runs on the instrumentation main thread and is never attached to a window: none
 * of these assertions depend on pixels, which is the whole reason the snapshot design exists.
 */
@RunWith(AndroidJUnit4::class)
class RenderSurfaceHostTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val events = CopyOnWriteArrayList<RenderEvent>()
    private lateinit var host: RenderSurfaceHost

    @Before
    fun setUp() {
        instrumentation.runOnMainSync {
            host = RenderSurfaceHost(
                context = instrumentation.targetContext,
                onEvent = { event -> events.add(event) },
            )
        }
        // Older WebView builds predate WEB_MESSAGE_LISTENER; on those the surface is inert by
        // design, so the test reports "skipped" rather than a failure.
        assumeTrue("WebView lacks WEB_MESSAGE_LISTENER", host.isSupported)
        await("ready", READY_TIMEOUT_MS) { it as? RenderEvent.Ready }
    }

    @After
    fun tearDown() {
        if (::host.isInitialized) {
            instrumentation.runOnMainSync { host.destroy() }
        }
    }

    @Test
    fun assetsLoadAndTheBridgeAnswersWhileTheNetworkIsBlocked() {
        // setUp already waited for `ready`, which can only arrive over the appassets origin.
        val ready = events.filterIsInstance<RenderEvent.Ready>()
        assertEquals(1, ready.size)
        assertTrue("user agent should not be empty", ready.single().userAgent.isNotEmpty())
    }

    @Test
    fun theClockShimRunsBeforeThePageScripts() {
        val mark = events.size
        host.setContent(
            html = """
                <script>
                  window.__sawClockAtParse = (typeof window.__cordiactClock === 'object');
                </script>
                <p id="hello">hi</p>
            """.trimIndent(),
        )
        await("mounted", since = mark) { it as? RenderEvent.Mounted }

        val probeMark = events.size
        host.probe()
        val probe = await("probe", since = probeMark) { it as? RenderEvent.Probed }

        assertTrue("clock shim missing from the frame", probe.hasClock)
        assertTrue("page scripts ran before the shim was installed", probe.pageSawClock)
        assertTrue("getAnimations missing from the frame document", probe.animationsApi)
        assertTrue("snapshot should see at least the body and the paragraph", probe.nodeCount >= 2)
    }

    @Test
    fun seekingFreezesAndAlignsAnimationsRepeatably() {
        host.setContent(
            html = """
                <div id="box" style="width:80px;height:80px;background:#4f46e5"></div>
                <script>
                  document.getElementById('box').animate(
                    [{opacity: 0}, {opacity: 1}], {duration: 1000, iterations: Infinity});
                </script>
            """.trimIndent(),
            css = """
                @keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
                #box { animation: spin 1s linear infinite; }
            """.trimIndent(),
        )
        await("mounted") { it as? RenderEvent.Mounted }
        Thread.sleep(SETTLE_MS)

        val firstMark = events.size
        host.seek(300.0)
        val seeked = await("seek", since = firstMark) { it as? RenderEvent.Seeked }
        assertEquals(300.0, seeked.timeMs, 1.0)

        val firstProbeMark = events.size
        host.probe()
        val first = await("first probe", since = firstProbeMark) { it as? RenderEvent.Probed }
        assertTrue("seek must freeze the clock", first.frozen)
        assertTrue("no animations found in the frame", first.animationCount >= 1)
        first.animationTimes.forEach { time ->
            assertTrue(
                "animation not aligned to the seek target: $time",
                time == null || abs(time - 300.0) <= 1.0,
            )
        }

        // Determinism: the same target reached from a different starting point must land the
        // same way. This is the property the agent's whole iteration loop rests on.
        val rewindMark = events.size
        host.seek(0.0)
        await("rewind", since = rewindMark) { event ->
            (event as? RenderEvent.Seeked)?.takeIf { abs(it.timeMs) <= 1.0 }
        }

        val againMark = events.size
        host.seek(300.0)
        await("seek again", since = againMark) { event ->
            (event as? RenderEvent.Seeked)?.takeIf { abs(it.timeMs - 300.0) <= 1.0 }
        }

        val secondProbeMark = events.size
        host.probe()
        val second = await("second probe", since = secondProbeMark) { it as? RenderEvent.Probed }
        assertEquals(first.animationCount, second.animationCount)
        second.animationTimes.forEach { time ->
            assertTrue("repeat seek drifted: $time", time == null || abs(time - 300.0) <= 1.0)
        }
    }

    @Test
    fun snapshotPipelineFlagsABrokenLayoutEndToEnd() {
        host.setContent(html = BROKEN_HTML, css = BROKEN_CSS)
        await("mounted") { it as? RenderEvent.Mounted }
        Thread.sleep(SETTLE_MS)

        val mark = events.size
        host.requestSnapshot()
        val snapshot = await("snapshot", ANIMATION_TIMEOUT_MS, since = mark) {
            it as? RenderEvent.SnapshotReady
        }

        assertTrue("snapshot carried no nodes", snapshot.snapshot.nodes.isNotEmpty())
        val kinds = snapshot.findings.map { it.kind }.toSet()
        assertTrue("overflow not flagged: $kinds", SnapshotIssueKind.OVERFLOW_X in kinds)
        assertTrue("small tap target not flagged: $kinds", SnapshotIssueKind.SMALL_TAP_TARGET in kinds)
        assertTrue("low contrast not flagged: $kinds", SnapshotIssueKind.LOW_CONTRAST in kinds)
    }

    @Test
    fun frameSeriesMakesAnAnimationLegibleWithoutPixels() {
        host.setContent(
            html = """<div id="dot" style="width:24px;height:24px;background:#111"></div>""",
            css = """
                @keyframes drift {
                  from { transform: translateY(0); }
                  to   { transform: translateY(200px); }
                }
                #dot { animation: drift 2s linear infinite; }
            """.trimIndent(),
        )
        await("mounted") { it as? RenderEvent.Mounted }
        Thread.sleep(SETTLE_MS)

        val done = CountDownLatch(1)
        var stops: List<UiSnapshot> = emptyList()
        host.captureSeries(listOf(0.0, 500.0, 1000.0)) { series ->
            stops = series
            done.countDown()
        }
        assertTrue("frame series never completed", done.await(30, TimeUnit.SECONDS))
        assertEquals("one snapshot per requested stop", 3, stops.size)

        val positions = stops.mapNotNull { snapshot ->
            snapshot.nodes.firstOrNull { it.selector.contains("dot") }?.rect?.y
        }
        assertEquals("every stop should contain the animated node", 3, positions.size)
        assertTrue(
            "the node must be reported at different positions across stops, got $positions",
            positions.distinct().size >= 3,
        )
    }

    @Test
    fun compareLayoutMountsEveryBreakpoint() {
        host.setContent(html = "<main><h1>hi</h1></main>")
        await("mounted") { it as? RenderEvent.Mounted }

        val layoutMark = events.size
        host.setLayout(PreviewLayout.Compare3)
        await("layout", since = layoutMark) { event ->
            (event as? RenderEvent.LayoutApplied)?.takeIf { it.frames == 3 }
        }

        val probeMark = events.size
        host.probe()
        val probe = await("probe", since = probeMark) { it as? RenderEvent.Probed }
        assertEquals("compare layout must show three frames", 3, probe.frameCount)
        assertEquals(
            "each frame must keep its declared breakpoint width",
            listOf(360, 390, 768),
            probe.frameWidths,
        )
        assertEquals("the document must be mounted in every frame", true, probe.hasClock)
        assertTrue("primary frame should see the paragraph", probe.nodeCount >= 2)
    }

    @Test
    fun versionCompareReportsBothSidesWithoutDisturbingThePreview() {
        host.setContent(html = """<main><p id="a">v2</p></main>""", css = "p{color:#111}")
        await("mounted") { it as? RenderEvent.Mounted }
        Thread.sleep(SETTLE_MS)

        val beforeMark = events.size
        host.probe()
        val before = await("probe", since = beforeMark) { it as? RenderEvent.Probed }

        val compareMark = events.size
        host.compare(
            html = """<main><p id="a">v1</p><div style="height:40px"></div></main>""",
            css = "p{color:#111}",
        )
        val compared = await(
            describe = "compare",
            timeoutMs = ANIMATION_TIMEOUT_MS,
            since = compareMark,
        ) { it as? RenderEvent.Compared }

        assertTrue("candidate snapshot came back empty", compared.previous.nodes.isNotEmpty())
        assertTrue("live snapshot came back empty", compared.current.nodes.isNotEmpty())
        assertTrue(
            "the candidate has an extra element, so it must report more nodes",
            compared.previous.nodes.size > compared.current.nodes.size,
        )

        val afterMark = events.size
        host.probe()
        val after = await("probe after compare", ANIMATION_TIMEOUT_MS, since = afterMark) {
            it as? RenderEvent.Probed
        }
        assertEquals(
            "comparing must not disturb the document on screen",
            before.nodeCount,
            after.nodeCount,
        )
    }

    /**
     * [since] exists because events accumulate for the whole test: without it, "wait for a
     * seek to 300ms" would be satisfied by the seek issued three lines earlier.
     */
    private fun <T> await(
        describe: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        since: Int = 0,
        select: (RenderEvent) -> T?,
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            for (index in since until events.size) {
                val event = events[index]
                if (event is RenderEvent.Failed) {
                    throw AssertionError("page reported ${event.where}: ${event.message}")
                }
                select(event)?.let { return it }
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError(
            "timed out waiting for $describe; saw " +
                events.drop(since).map { it::class.simpleName }.distinct(),
        )
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
        const val READY_TIMEOUT_MS = 40_000L
        const val ANIMATION_TIMEOUT_MS = 20_000L
        const val POLL_INTERVAL_MS = 50L
        const val SETTLE_MS = 400L

        /** Same shape as the bench sample: a 40dp button, a low-contrast caption, an overflowing row. */
        val BROKEN_CSS = """
            body { margin: 0; padding: 16px; font-family: system-ui, sans-serif; background: #ffffff; }
            .card { border-radius: 16px; padding: 16px; border: 1px solid #e5e7eb; }
            .caption { color: #a8a8a8; font-size: 13px; }
            .row { display: flex; gap: 8px; width: 720px; }
            .pill { padding: 6px 12px; border-radius: 999px; background: #111111; color: #ffffff; }
            .cta { width: 40px; height: 40px; border-radius: 50%; border: 0; background: #4f46e5; }
        """.trimIndent()

        val BROKEN_HTML = """
            <div class="card">
              <p class="caption">自动生成 · 点击查看详情</p>
              <div class="row">
                <span class="pill">12 次提交</span>
                <span class="pill">3 个发布</span>
                <span class="pill">1 次回滚</span>
                <span class="pill">7 个新任务</span>
                <span class="pill">2 个阻塞项</span>
              </div>
              <button class="cta">→</button>
            </div>
        """.trimIndent()
    }
}
