package com.cordiact.app.conversation

import com.cordiact.core.conversation.ModelAdapterPlugin
import com.cordiact.core.conversation.SessionManagerPlugin
import com.cordiact.core.conversation.modelAdapterPluginSpec
import com.cordiact.core.conversation.sessionManagerPluginSpec
import com.cordiact.core.plugin.BundledPluginVerifier
import com.cordiact.core.plugin.ManagedPlugin

/**
 * Host-owned allowlist for built-in conversation plugins. A plugin manifest cannot self-assign the
 * protected system origin; both exact id and bundled version/target/capability declaration are checked.
 */
object BundledConversationPluginCatalog {
    private val trustedSpecs = setOf(modelAdapterPluginSpec(), sessionManagerPluginSpec())

    val verifier = BundledPluginVerifier { candidate -> candidate in trustedSpecs }

    fun create(sessionStore: AndroidConversationSessionStore): List<ManagedPlugin> = listOf(
        ModelAdapterPlugin(),
        SessionManagerPlugin(sessionStore),
    )
}
