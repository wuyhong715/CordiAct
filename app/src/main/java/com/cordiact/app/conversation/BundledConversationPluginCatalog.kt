package com.cordiact.app.conversation

import com.cordiact.core.conversation.ModelAdapterPlugin
import com.cordiact.core.conversation.SessionManagerPlugin
import com.cordiact.core.conversation.modelAdapterPluginSpec
import com.cordiact.core.conversation.sessionManagerPluginSpec
import com.cordiact.core.plugin.BundledPluginVerifier
import com.cordiact.core.plugin.ManagedPlugin
import com.cordiact.core.sandbox.SandboxManagerPlugin
import com.cordiact.core.sandbox.sandboxManagerPluginSpec

/**
 * Host-owned allowlist for built-in conversation plugins. A plugin manifest cannot self-assign the
 * protected system origin; both exact id and bundled version/target/capability declaration are checked.
 */
object BundledConversationPluginCatalog {
    private val trustedSpecs = setOf(modelAdapterPluginSpec(), sessionManagerPluginSpec(), sandboxManagerPluginSpec())

    val verifier = BundledPluginVerifier { candidate -> candidate in trustedSpecs }

    fun create(sessionStore: AndroidConversationSessionStore): List<ManagedPlugin> = listOf(
        ModelAdapterPlugin(),
        SessionManagerPlugin(sessionStore),
    )

    /**
     * Registers the first-class orchestrator, but intentionally provides no executable local
     * runtime/catalog yet. All local launches remain fail-closed in v1 until S2/S3 pass.
     */
    fun createSandboxManager(appUid: Int): SandboxManagerPlugin = SandboxManagerPlugin(
        appUid = appUid,
        bundledVerifier = com.cordiact.core.sandbox.BundledPluginVerifier { false },
        capabilityPolicy = com.cordiact.core.sandbox.SandboxCapabilityPolicy { emptySet() },
        sessionRevoker = com.cordiact.core.sandbox.SandboxSessionRevoker {},
    )
}
