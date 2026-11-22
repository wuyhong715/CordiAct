package com.cordiact.app.plugin

import com.cordiact.core.plugin.ExtensionPackageCapability
import com.cordiact.core.plugin.extensionPackageSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BundledDeclarativePackagesTest {
    @Test fun manifestPublishesBothContributionKinds() {
        val manifest = BundledDeclarativePackages.manifest
        assertEquals("core-quick-notes", manifest.id)
        assertEquals(1, manifest.tools.size)
        assertEquals(1, manifest.commands.size)
        assertTrue(manifest.capabilities.containsAll(
            setOf(ExtensionPackageCapability.TOOL_PUBLISH, ExtensionPackageCapability.COMMAND_PUBLISH),
        ))
        val spec = extensionPackageSpec(manifest)
        assertEquals("pkg.core-quick-notes", spec.id)
        assertEquals(manifest.version, spec.version)
        assertEquals(manifest.capabilities.map { it.wireName }.toSet(), spec.requestedCapabilities)
    }
}
