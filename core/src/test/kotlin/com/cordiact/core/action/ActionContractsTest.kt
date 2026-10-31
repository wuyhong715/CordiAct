package com.cordiact.core.action

import kotlin.test.Test
import kotlin.test.assertEquals

class ActionContractsTest {
    @Test
    fun unknownActionIsDenied() {
        val result = DenyByDefaultPolicy().evaluate(ActionRequest("a-1", "filesystem.delete", "ws-1"))
        assertEquals(PolicyDecision.DENY, result.decision)
    }

    @Test
    fun registeredActionNeedsConfirmation() {
        val result = DenyByDefaultPolicy(setOf("workspace.inspect"))
            .evaluate(ActionRequest("a-2", "workspace.inspect", "ws-1"))
        assertEquals(PolicyDecision.CONFIRM, result.decision)
    }
}
