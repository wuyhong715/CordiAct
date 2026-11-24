package com.cordiact.app.experience.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cordiact.app.experience.ExperienceTheme
import com.cordiact.app.experience.SignedPackageDirectoryPanel
import com.cordiact.core.plugin.ExtensionPackageInstaller
import com.cordiact.core.plugin.InMemoryExtensionPackageStore
import com.cordiact.core.plugin.WorkspaceExtensionPackages
import com.cordiact.core.security.EffectiveSecurityProfile
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
        val visible = mutableStateOf(true)
        val preparing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val busy = CopyOnWriteArrayList<Boolean>()
        compose.setContent {
            ExperienceTheme {
                if (visible.value) SignedPackageDirectoryPanel("root", page, false, { busy.add(it) },
                    { error("unexpected preview") }, prepareTrust = {
                        preparing.countDown()
                        // Model blocking preparation which observes cancellation but is still unwinding.
                        try { release.await(5, TimeUnit.SECONDS) }
                        catch (_: InterruptedException) { release.await(5, TimeUnit.SECONDS) }
                        finally { finished.countDown() }
                        error("late failure")
                    })
            }
        }
        try {
            compose.onNodeWithText("刷新信任根").performClick()
            compose.waitUntil(5_000) { preparing.count == 0L }
            assertEquals(listOf(true), busy)
            compose.runOnIdle { visible.value = false }
            compose.waitUntil(5_000) { busy.size == 2 }
            assertEquals(1L, finished.count)
            assertEquals(listOf(true, false), busy)
            release.countDown()
            compose.waitUntil(5_000) { finished.count == 0L }
            compose.runOnIdle { visible.value = true }
            compose.onNodeWithText("请先刷新信任根。").assertExists()
            compose.onNodeWithText("无法刷新或校验信任根。").assertDoesNotExist()
            assertEquals(listOf(true, false), busy)
        } finally {
            release.countDown()
        }
    }
}
