package com.cordiact.core.plugin

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthoredPluginForegroundTest {
    @Test fun `policy write closes old permits and prevents minting replacements until it settles`() {
        val foreground = AuthoredPluginForeground().apply { resume(Any()); select("w", "s") }
        val turn = assertNotNull(foreground.openTurn("w", "s"))
        val confirmation = assertNotNull(foreground.openConfirmation("w", "s"))
        foreground.whileUpdatingPolicy("other") {
            assertTrue(turn.isOpen)
            assertTrue(confirmation.isOpen)
        }
        assertFailsWith<IllegalArgumentException> {
            foreground.whileUpdatingPolicy("w") {
                assertFalse(turn.isOpen)
                assertFalse(confirmation.isOpen)
                assertNull(foreground.openTurn("w", "s"))
                assertNull(foreground.openConfirmation("w", "s"))
                errorInSettingsWrite()
            }
        }
        assertNotNull(foreground.openConfirmation("w", "s"))
        assertFalse(confirmation.isOpen)
        assertFalse(turn.isOpen)
    }

    private fun errorInSettingsWrite(): Nothing = throw IllegalArgumentException("test settings write failed")
}
