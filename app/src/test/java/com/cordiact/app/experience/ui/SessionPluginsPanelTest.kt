package com.cordiact.app.experience.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cordiact.app.experience.ExperienceState
import com.cordiact.app.experience.ExperienceTheme
import com.cordiact.app.experience.LandscapeWorkbench
import com.cordiact.app.experience.SessionPluginInventorySnapshot
import com.cordiact.app.experience.SessionPluginsPanel
import com.cordiact.app.experience.WorkbenchActivity
import com.cordiact.core.action.ApprovalRequest
import com.cordiact.core.plugin.AuthoredPluginReceipt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1060dp-h400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SessionPluginsPanelTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun sessionInventoryMovesBetweenDockAndBodyOverlayAndLeavesChromeAndApprovalAvailable() {
        val approval = ApprovalRequest("approval", "tool", "requires approval")
        var width by mutableStateOf(1_000.dp)
        var showInventory by mutableStateOf(false)
        var openedApproval: String? = null
        compose.setContent {
            ExperienceTheme {
                Box(Modifier.size(width, 260.dp)) {
                    LandscapeWorkbench(
                        state = ExperienceState(loading = false, sessionId = "test", approvals = listOf(approval)),
                        pendingSessionId = null, viewKey = "s:test", pluginPage = false, activity = WorkbenchActivity(),
                        onOverview = {}, onNew = {}, onOpenApproval = { openedApproval = it }, ownerLabel = { "" },
                        tabs = { Box(it.height(36.dp).testTag("test-tabs")) },
                        body = { _, _ -> Box(Modifier.fillMaxSize().testTag("test-body")) },
                        composer = { Box(Modifier.fillMaxWidth().height(48.dp).testTag("test-composer")) },
                        sessionPluginDetail = if (showInventory) ({
                            SessionPluginsPanel(SessionPluginInventorySnapshot.EMPTY, false, false, null, null,
                                onDismiss = { showInventory = false }, onRefresh = {}, onRevoke = {})
                        }) else null,
                        onCloseSessionPluginDetail = { showInventory = false },
                    )
                }
            }
        }
        compose.onNode(hasAnyAncestor(hasTestTag("landscape-top")) and hasText("子 Agent") and hasClickAction()).performClick()
        compose.onNodeWithTag("landscape-inspector").assertIsDisplayed()
        compose.runOnIdle { showInventory = true }
        compose.onNodeWithTag("landscape-inspector").assertDoesNotExist()
        assertWithinBody(docked = true)
        compose.onNodeWithTag("landscape-plugin-approval").performClick()
        compose.runOnIdle { assertEquals(approval.id, openedApproval); width = 840.dp }
        assertWithinBody(docked = false)
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithTag("session-plugins-panel").assertDoesNotExist()
        compose.onNodeWithTag("landscape-inspector").assertDoesNotExist()
    }

    @Test fun shortHighFontInventoryScrollsToEveryActionAndDoesNotConfirmAnEqualReplacementReceipt() {
        val receipt = AuthoredPluginReceipt("demo", "a".repeat(64), listOf("extension.authored.demo.show"))
        val replacement = receipt.copy()
        var inventory by mutableStateOf(SessionPluginInventorySnapshot(listOf(receipt)))
        var revoked: AuthoredPluginReceipt? = null
        val actions = mutableListOf<String>()
        compose.setContent {
            ExperienceTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.8f)) {
                    Box(Modifier.size(240.dp, 104.dp)) {
                        SessionPluginsPanel(inventory, false, false, null, null,
                            onDismiss = { actions += "close" }, onRefresh = { actions += "refresh" },
                            onRevoke = { revoked = it }, onOpenDrafts = { actions += "drafts" }, onOpenSaved = { actions += "saved" })
                    }
                }
            }
        }
        compose.onNodeWithTag("session-plugin-refresh").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("session-plugin-drafts").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("session-plugin-saved-versions").performScrollTo().assertIsDisplayed().performClick()
        val revoke = compose.onNodeWithTag("session-plugin-revoke-demo")
        val confirm = compose.onNodeWithTag("session-plugin-confirm-revoke")
        revoke.performScrollTo().assertIsDisplayed().performClick()
        confirm.assertIsEnabled()
        compose.runOnIdle {
            assertNull(revoked)
            inventory = SessionPluginInventorySnapshot(listOf(replacement))
        }
        confirm.assertIsNotEnabled()
        compose.onNodeWithText("返回").performClick()
        confirm.assertDoesNotExist()
        compose.runOnIdle { assertNull(revoked) }
        revoke.performScrollTo().assertIsDisplayed().performClick()
        confirm.assertIsEnabled().performClick()
        confirm.assertDoesNotExist()
        compose.onNodeWithTag("session-plugins-close").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertSame(replacement, revoked)
            assertEquals(listOf("refresh", "drafts", "saved", "close"), actions)
        }
    }

    private fun assertWithinBody(docked: Boolean) {
        val panel = compose.onNodeWithTag("session-plugins-panel").getUnclippedBoundsInRoot()
        val body = compose.onNodeWithTag("test-body").getUnclippedBoundsInRoot()
        val tabs = compose.onNodeWithTag("test-tabs").getUnclippedBoundsInRoot()
        val composer = compose.onNodeWithTag("test-composer").getUnclippedBoundsInRoot()
        assertTrue(panel.top >= tabs.bottom)
        assertTrue(panel.bottom <= composer.top)
        if (docked) assertTrue(panel.left >= body.right) else assertTrue(panel.left < body.right)
    }
}
