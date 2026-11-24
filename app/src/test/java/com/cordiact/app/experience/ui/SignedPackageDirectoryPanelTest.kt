package com.cordiact.app.experience.ui

import android.app.Application
import android.os.Looper
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cordiact.app.experience.ExperienceTheme
import com.cordiact.app.experience.SignedPackageDirectoryPanel
import com.cordiact.core.plugin.ExtensionPackageInstaller
import com.cordiact.core.plugin.InMemoryExtensionPackageStore
import com.cordiact.core.plugin.WorkspaceExtensionPackages
import com.cordiact.core.security.EffectiveSecurityProfile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w412dp-h892dp-420dpi")
class SignedPackageDirectoryPanelTest {
    @get:Rule val compose = createComposeRule()

    private val page = WorkspaceExtensionPackages(ExtensionPackageInstaller(InMemoryExtensionPackageStore()),
        { EffectiveSecurityProfile.fullAccess(it) }).also { it.resume(Any()) }.openPage("workspace")

    @Test fun rootRefreshIsExplicitAndFailuresDoNotExposeTransportDetails() {
        val blocked = mutableStateOf(true)
        val calls = AtomicInteger()
        val busy = CopyOnWriteArrayList<Boolean>()
        val detail = "https://catalog.invalid/private response body"
        compose.setContent {
            ExperienceTheme {
                SignedPackageDirectoryPanel("root", page, blocked.value, { busy.add(it) }, { error("unexpected preview") },
                    prepareTrust = { calls.incrementAndGet(); error(detail) })
            }
        }
        compose.onNodeWithText("刷新信任根").assertIsNotEnabled()
        compose.onNodeWithText("刷新所选签发者索引").assertDoesNotExist()
        assertEquals(0, calls.get())
        compose.runOnIdle { blocked.value = false }
        compose.onNodeWithText("刷新信任根").performClick()
        compose.waitUntil(5_000) { busy.size == 2 }
        compose.onNodeWithText("无法刷新或校验信任根。").assertExists()
        compose.onNodeWithText(detail, substring = true).assertDoesNotExist()
        assertEquals(1, calls.get())
        assertEquals(listOf(true, false), busy)
    }

    @Test fun leavingThePanelReleasesBusyWhilePreparationIsStillRunning() {
        val owner = compose.runOnUiThread {
            object : LifecycleOwner {
                override val lifecycle = LifecycleRegistry(this)
            }.also { it.lifecycle.currentState = Lifecycle.State.RESUMED }
        }
        val executor = Executors.newSingleThreadExecutor()
        executor.asCoroutineDispatcher().use { dispatcher ->
            val preparing = CountDownLatch(1)
            val release = Semaphore(0)
            val finished = CountDownLatch(1)
            val busy = CopyOnWriteArrayList<Boolean>()
            try {
                compose.setContent {
                    ExperienceTheme {
                        CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                            SignedPackageDirectoryPanel("root", page, false, { busy.add(it) },
                                { error("unexpected preview") }, prepareTrust = {
                                    preparing.countDown()
                                    // Only the test releases preparation, regardless of how often it is interrupted.
                                    release.acquireUninterruptibly()
                                    finished.countDown()
                                    error("late failure")
                                }, workDispatcher = dispatcher)
                        }
                    }
                }
                compose.onNodeWithText("刷新信任根").performClick()
                compose.waitUntil(5_000) { preparing.count == 0L }
                assertEquals(listOf(true), busy)
                // Leave via ON_PAUSE without remounting: a fresh composition would hide any stale UI write.
                compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED }
                assertEquals(listOf(true, false), busy)
                assertEquals(1L, finished.count)

                compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
                compose.onNodeWithText("刷新信任根").assertIsEnabled()
                release.release()
                // The FIFO barrier follows the IO task, including its dispatch back to the main thread.
                executor.submit {}.get(5, TimeUnit.SECONDS)
                // Drain the queued continuation even if Compose itself already considers the UI idle.
                shadowOf(Looper.getMainLooper()).idle()
                compose.waitForIdle()
                assertEquals(0L, finished.count)
                compose.onNodeWithText("请先刷新信任根。").assertExists()
                compose.onNodeWithText("无法刷新或校验信任根。").assertDoesNotExist()
                assertEquals(listOf(true, false), busy)
            } finally {
                release.release()
            }
        }
    }
}
