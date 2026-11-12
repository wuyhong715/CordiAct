package com.cordiact.app.experience.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsNavigationTest {
    @Test fun systemAndToolbarBackShareParentStack() {
        val nav = SettingsNavigation().push(SettingsRoute.PROVIDERS).push(SettingsRoute.DETAIL).push(SettingsRoute.CREDENTIALS)
        assertEquals(SettingsRoute.DETAIL, nav.pop()!!.current)
        assertEquals(SettingsRoute.PROVIDERS, nav.pop()!!.pop()!!.current)
        assertEquals(SettingsRoute.HOME, nav.pop()!!.pop()!!.pop()!!.current)
        assertNull(SettingsNavigation().pop())
    }
    @Test fun duplicateDestinationDoesNotGrowStackAndHomeResetsIt() {
        val nav = SettingsNavigation().push(SettingsRoute.PERMISSIONS)
        assertEquals(nav, nav.push(SettingsRoute.PERMISSIONS))
        assertEquals(SettingsNavigation(), nav.push(SettingsRoute.HOME))
    }
    @Test fun providerSaveReturnsToExistingParentRatherThanAppending() {
        val nav = SettingsNavigation().push(SettingsRoute.PROVIDERS).push(SettingsRoute.DETAIL)
        assertEquals(listOf(SettingsRoute.HOME, SettingsRoute.PROVIDERS), nav.providerList().entries)
        assertEquals(SettingsRoute.PROVIDERS, SettingsNavigation().push(SettingsRoute.ADD).providerList().current)
    }
    @Test fun restoreHandlesProcessRecreationAndUnknownOldRoutes() {
        assertEquals(SettingsRoute.DETAIL, SettingsNavigation.restore(listOf("HOME", "PROVIDERS", "DETAIL")).current)
        assertEquals(SettingsRoute.HOME, SettingsNavigation.restore(listOf("OBSOLETE")).current)
        assertEquals(SettingsRoute.HOME, SettingsNavigation.restore(emptyList()).current)
    }
    @Test fun termuxAndAccessibilityLinksTargetCorrectConnection() {
        assertEquals("termux", SettingsLink.parse("settings://connections/termux")!!.connectionId)
        assertEquals("screen", SettingsLink.parse("settings://connections/accessibility")!!.connectionId)
        assertEquals("privilege", SettingsLink.parse("settings://connections/shizuku")!!.connectionId)
        assertEquals(SettingsRoute.CONNECTIONS, SettingsLink.parse("connections")!!.route)
    }
    @Test fun providerLinkHasListParentAndCarriesOwnerId() {
        val link = SettingsLink.parse("settings://models/providers/provider-123")!!
        assertEquals("provider-123", link.providerId)
        assertEquals(SettingsRoute.PROVIDERS, link.navigation().pop()!!.current)
    }
    @Test fun readinessAndDataLinksResolve() {
        val expected = mapOf("models/roles" to SettingsRoute.ROLES, "models/providers" to SettingsRoute.PROVIDERS,
            "security/permissions" to SettingsRoute.PERMISSIONS, "security/privilege" to SettingsRoute.PRIVILEGE,
            "security/credentials" to SettingsRoute.CREDENTIALS, "security/audit" to SettingsRoute.AUDIT,
            "search" to SettingsRoute.SEARCH, "mcp" to SettingsRoute.MCP, "ssh" to SettingsRoute.SSH,
            "attachments" to SettingsRoute.ATTACHMENTS)
        expected.forEach { (path, route) -> assertEquals(path, route, SettingsLink.parse("settings://$path")!!.route) }
    }
    @Test fun unknownAndExternalLinksAreNotNavigationInstructions() {
        listOf<String?>(null, "", "https://connections/termux", "file:///etc/passwd", "settings://unknown", "settings://connections/unknown",
            "settings://user@connections/termux", "settings://connections:80/termux", "settings://connections/termux?execute=x", "settings://mcp#x",
            "settings://models/providers/one/extra").forEach { assertNull(it, SettingsLink.parse(it)) }
    }
}
