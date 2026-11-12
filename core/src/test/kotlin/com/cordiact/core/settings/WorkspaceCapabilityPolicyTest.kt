package com.cordiact.core.settings

import kotlin.test.*

class WorkspaceCapabilityPolicyTest {
    @Test fun disabledHidesAllToolsInOwner() {
        for (owner in listOf("core.ssh", "core.termux", "core.screen", "core.browser", "core.search", "core.privilege", "core.mcp.github", "com.cordiact.ssh", "com.cordiact.termux", "com.cordiact.screen", "com.cordiact.browser", "com.cordiact.priv", "com.cordiact.display")) {
            assertFalse(WorkspaceCapabilityPolicy.visible(owner, { false }, { true }))
            assertFalse(WorkspaceCapabilityPolicy.visible(owner, { true }, { false }))
            assertTrue(WorkspaceCapabilityPolicy.visible(owner, { true }, { true }))
        }
    }
    @Test fun unclassifiedOwnersRemainUnderExistingToolPolicy() {
        assertTrue(WorkspaceCapabilityPolicy.visible("core.file", { false }, { false }))
    }
}
