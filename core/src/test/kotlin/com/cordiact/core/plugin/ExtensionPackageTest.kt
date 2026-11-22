package com.cordiact.core.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionPackageTest {
    @Test
    fun `valid package compiles tools commands and digest`() {
        val manifest = ExtensionPackageSource.compile(source())
        assertEquals("quick-notes", manifest.id)
        assertEquals("1.0.0", manifest.version)
        assertEquals(setOf(ExtensionPackageCapability.TOOL_PUBLISH, ExtensionPackageCapability.COMMAND_PUBLISH), manifest.capabilities)
        assertEquals(1, manifest.tools.size)
        assertEquals(1, manifest.commands.size)
        assertEquals("checklist", manifest.tools.single().name)
        assertEquals(setOf("cl"), manifest.commands.single().aliases)
        assertEquals(64, manifest.sha256.length)
        assertTrue(manifest.sizeBytes > 0)
    }

    @Test
    fun `manifest rejects unknown or inconsistent declarations`() {
        assertFailsWith<IllegalArgumentException> {
            ExtensionPackageSource.compile(text(source()).replace("\"schemaVersion\": 1", "\"schemaVersion\": 2").toByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            ExtensionPackageSource.compile(text(source()).replace("\"command.publish\"", "\"unknown.publish\"").toByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            ExtensionPackageSource.compile(source(commandCapability = false))
        }
        assertFailsWith<IllegalArgumentException> {
            ExtensionPackageSource.compile(text(source()).replace("\"aliases\": [\"cl\"]", "\"aliases\": [\"cl\", \"command\"]").toByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            ExtensionPackageSource.compile(source(), hostVersion = "0.0.9")
        }
        assertFailsWith<IllegalArgumentException> {
            ExtensionPackageSource.compile((text(source()) + " trailing").toByteArray())
        }
    }

    @Test
    fun `upgrade keeps one previous version and requires new capability consent`() {
        val store = InMemoryExtensionPackageStore()
        val ids = ArrayDeque(listOf("install-1", "install-2", "install-3"))
        val installer = ExtensionPackageInstaller(
            store = store,
            clock = { 1_000L },
            newInstallationId = { ids.removeFirst() },
        )
        val v1 = source(version = "1.0.0", commands = false)
        val v2 = source(version = "1.1.0", commands = true)

        val installed = installer.install("ws-1", v1, setOf(ExtensionPackageCapability.TOOL_PUBLISH))
        assertTrue(installed is ExtensionPackageChange.Applied)
        assertEquals("install-1", installed.record.installationId)

        val needsConsent = installer.install("ws-1", v2, setOf(ExtensionPackageCapability.TOOL_PUBLISH))
        assertTrue(needsConsent is ExtensionPackageChange.ConsentRequired)
        assertEquals(setOf(ExtensionPackageCapability.COMMAND_PUBLISH), needsConsent.missing)
        assertEquals("1.0.0", assertNotNull(store.get("ws-1", "quick-notes")).manifest.version)

        val upgraded = installer.install(
            "ws-1",
            v2,
            setOf(ExtensionPackageCapability.TOOL_PUBLISH, ExtensionPackageCapability.COMMAND_PUBLISH),
        )
        assertTrue(upgraded is ExtensionPackageChange.Applied)
        assertTrue(upgraded.upgraded)
        assertEquals("1.1.0", upgraded.record.manifest.version)
        assertEquals("1.0.0", assertNotNull(upgraded.record.previous).manifest.version)

        val rolledBack = installer.rollback("ws-1", "quick-notes")
        assertTrue(rolledBack is ExtensionPackageChange.Applied)
        assertEquals("1.0.0", rolledBack.record.manifest.version)
        assertNull(rolledBack.record.previous)
        assertTrue(installer.rollback("ws-1", "quick-notes") is ExtensionPackageChange.Rejected)

        assertTrue(installer.remove("ws-1", "quick-notes") is ExtensionPackageChange.Removed)
        assertNull(store.get("ws-1", "quick-notes"))
    }

    @Test
    fun `first install without capability consent writes nothing`() {
        val store = InMemoryExtensionPackageStore()
        val installer = ExtensionPackageInstaller(store = store)
        val result = installer.install("ws-1", source(), emptySet())
        assertTrue(result is ExtensionPackageChange.ConsentRequired)
        assertNull(store.get("ws-1", "quick-notes"))
    }

    @Test
    fun `same version with different bytes is a conflict`() {
        val store = InMemoryExtensionPackageStore()
        val installed = ExtensionPackageInstaller(store = store).install(
            "ws-1",
            source(),
            setOf(ExtensionPackageCapability.TOOL_PUBLISH, ExtensionPackageCapability.COMMAND_PUBLISH),
        )
        assertTrue(installed is ExtensionPackageChange.Applied)
        val changed = ExtensionPackageInstaller(store = store).install(
            "ws-1",
            source(title = "Different title"),
            setOf(ExtensionPackageCapability.TOOL_PUBLISH, ExtensionPackageCapability.COMMAND_PUBLISH),
        )
        assertTrue(changed is ExtensionPackageChange.Rejected)
        assertEquals(ExtensionPackageChange.Code.CONFLICT, changed.code)
    }

    @Test
    fun `legacy authored plugin migrates to a durable package`() {
        val authored = """
            {
              "schemaVersion": 2,
              "id": "release-checklist",
              "title": "发布检查",
              "tools": [
                {"name": "show", "description": "显示清单", "kind": "static_text", "text": "运行测试"}
              ]
            }
        """.trimIndent().toByteArray()
        val store = InMemoryExtensionPackageStore()
        val installer = ExtensionPackageInstaller(store = store)
        val migrated = installer.importLegacyAuthored(
            "ws-1",
            "release-checklist",
            authored,
            setOf(ExtensionPackageCapability.TOOL_PUBLISH),
        )
        assertTrue(migrated is ExtensionPackageChange.Applied)
        assertEquals("release-checklist", migrated.record.manifest.id)
        assertEquals("0.0.1", migrated.record.manifest.version)
        assertEquals(1, migrated.record.manifest.tools.size)
        assertTrue(migrated.record.manifest.commands.isEmpty())
    }

    private fun text(bytes: ByteArray) = bytes.toString(Charsets.UTF_8)

    private fun source(
        version: String = "1.0.0",
        title: String = "Quick notes",
        commands: Boolean = true,
        commandCapability: Boolean = commands,
    ): ByteArray {
        val command = if (commands) {
            """,
    "commands": [
      {"name": "command", "aliases": ["cl"], "description": "Show a note", "text": "note"}
    ]"""
        } else {
            """,
    "commands": []"""
        }
        val capabilities = if (commandCapability) {
            "\"tool.publish\",\n    \"command.publish\""
        } else {
            "\"tool.publish\""
        }
        return """
            {
              "schemaVersion": 1,
              "id": "quick-notes",
              "version": "$version",
              "title": "$title",
              "description": "Small declarative extensions",
              "host": {"api": 1, "minVersion": "0.1.0"},
              "capabilities": [$capabilities],
              "tools": [
                {"name": "checklist", "description": "Show the checklist", "kind": "static_text", "text": "run tests"}
              ]$command
            }
        """.trimIndent().toByteArray()
    }
}
