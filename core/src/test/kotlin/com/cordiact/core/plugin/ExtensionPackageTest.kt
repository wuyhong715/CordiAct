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

        val installed = installer.install("ws-1", installer.inspect(v1), null, setOf(ExtensionPackageCapability.TOOL_PUBLISH))
        assertTrue(installed is ExtensionPackageChange.Applied)
        assertEquals("install-1", installed.record.installationId)

        val needsConsent = installer.install("ws-1", installer.inspect(v2), installed.record.installationId, setOf(ExtensionPackageCapability.TOOL_PUBLISH))
        assertTrue(needsConsent is ExtensionPackageChange.ConsentRequired)
        assertEquals(setOf(ExtensionPackageCapability.COMMAND_PUBLISH), needsConsent.missing)
        assertEquals("1.0.0", assertNotNull(store.get("ws-1", "quick-notes")).manifest.version)

        val upgraded = installer.install(
            "ws-1",
            installer.inspect(v2),
            installed.record.installationId,
            setOf(ExtensionPackageCapability.TOOL_PUBLISH, ExtensionPackageCapability.COMMAND_PUBLISH),
        )
        assertTrue(upgraded is ExtensionPackageChange.Applied)
        assertTrue(upgraded.upgraded)
        assertEquals("1.1.0", upgraded.record.manifest.version)
        assertEquals("1.0.0", assertNotNull(upgraded.record.previous).manifest.version)

        val rolledBack = installer.rollback("ws-1", "quick-notes", upgraded.record.installationId)
        assertTrue(rolledBack is ExtensionPackageChange.Applied)
        assertEquals("1.0.0", rolledBack.record.manifest.version)
        assertNull(rolledBack.record.previous)
        assertTrue(installer.rollback("ws-1", "quick-notes", rolledBack.record.installationId) is ExtensionPackageChange.Rejected)

        assertTrue(installer.remove("ws-1", "quick-notes", rolledBack.record.installationId) is ExtensionPackageChange.Removed)
        assertNull(store.get("ws-1", "quick-notes"))
    }

    @Test
    fun `first install without capability consent writes nothing`() {
        val store = InMemoryExtensionPackageStore()
        val installer = ExtensionPackageInstaller(store = store)
        val result = installer.install("ws-1", installer.inspect(source()), null, emptySet())
        assertTrue(result is ExtensionPackageChange.ConsentRequired)
        assertNull(store.get("ws-1", "quick-notes"))
    }

    @Test
    fun `same version with different bytes is a conflict`() {
        val store = InMemoryExtensionPackageStore()
        val installer = ExtensionPackageInstaller(store = store)
        val installed = installer.install(
            "ws-1",
            installer.inspect(source()),
            null,
            setOf(ExtensionPackageCapability.TOOL_PUBLISH, ExtensionPackageCapability.COMMAND_PUBLISH),
        )
        assertTrue(installed is ExtensionPackageChange.Applied)
        val changed = installer.install(
            "ws-1",
            installer.inspect(source(title = "Different title")),
            installed.record.installationId,
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
        val migrated = installer.install(
            "ws-1",
            installer.inspectLegacy("release-checklist", authored),
            null,
            setOf(ExtensionPackageCapability.TOOL_PUBLISH),
        )
        assertTrue(migrated is ExtensionPackageChange.Applied)
        assertEquals("release-checklist", migrated.record.manifest.id)
        assertEquals("0.0.1", migrated.record.manifest.version)
        assertEquals(1, migrated.record.manifest.tools.size)
        assertTrue(migrated.record.manifest.commands.isEmpty())
    }

    @Test
    fun `preview bytes and workspaces are independent and old confirmation cannot act on reinstallation`() {
        val installer = ExtensionPackageInstaller(InMemoryExtensionPackageStore())
        val original = source()
        val expected = original.copyOf()
        val candidate = installer.inspect(original)
        original.fill(0)
        candidate.copySource().fill(0)
        assertTrue(expected.contentEquals(candidate.copySource()))
        fun install(workspace: String) = (installer.install(workspace, candidate, null, candidate.manifest.capabilities)
            as ExtensionPackageChange.Applied).record
        val first = install("ws-1")
        val other = install("ws-2")
        assertEquals(ExtensionPackageChange.Removed, installer.remove("ws-1", "quick-notes", first.installationId))
        val reinstalled = install("ws-1")
        val oldRemoval = installer.remove("ws-1", "quick-notes", first.installationId) as ExtensionPackageChange.Rejected
        assertEquals(ExtensionPackageChange.Code.STALE, oldRemoval.code)
        assertEquals(reinstalled, installer.list("ws-1").single())
        assertEquals(other, installer.list("ws-2").single())
    }

    @Test
    fun `storage comparison rejects change raced after the read across installers`() {
        val backing = InMemoryExtensionPackageStore()
        val other = ExtensionPackageInstaller(backing)
        val first = other.inspect(source())
        val current = (other.install("ws", first, null, first.manifest.capabilities) as ExtensionPackageChange.Applied).record
        val raced = other.inspect(source(version = "1.1.0"))
        val racingStore = object : ExtensionPackageStore by backing {
            override fun compareAndSet(
                workspaceId: String, packageId: String, expectedInstallationId: String?, replacement: ExtensionPackageRecord?,
                publish: (() -> Unit) -> Unit,
            ): ExtensionPackageStoreWrite {
                assertTrue(other.install("ws", raced, current.installationId, raced.manifest.capabilities) is ExtensionPackageChange.Applied)
                return backing.compareAndSet(workspaceId, packageId, expectedInstallationId, replacement, publish)
            }
        }
        val installer = ExtensionPackageInstaller(racingStore)
        val intended = installer.inspect(source(version = "2.0.0"))
        val result = installer.install("ws", intended, current.installationId, intended.manifest.capabilities)
        assertEquals(ExtensionPackageChange.Code.STALE, (result as ExtensionPackageChange.Rejected).code)
        assertEquals("1.1.0", installer.list("ws").single().manifest.version)
    }

    @Test
    fun `enable intent is exact and a replacement does not inherit it`() {
        val installer = ExtensionPackageInstaller(InMemoryExtensionPackageStore())
        val artifact = installer.inspect(source())
        val first = (installer.install("ws", artifact, null, artifact.manifest.capabilities) as ExtensionPackageChange.Applied).record
        val enabled = (installer.setEnableIntent("ws", artifact.manifest.id, first.installationId, true) as ExtensionPackageChange.Applied).record
        assertTrue(enabled.enableIntent)
        val stale = installer.setEnableIntent("ws", artifact.manifest.id, first.installationId, false) as ExtensionPackageChange.Rejected
        assertEquals(ExtensionPackageChange.Code.STALE, stale.code)
        val upgrade = installer.inspect(source(version = "1.1.0"))
        val installed = installer.install("ws", upgrade, enabled.installationId, upgrade.manifest.capabilities) as ExtensionPackageChange.Applied
        assertEquals(false, installed.record.enableIntent)
        val rolled = installer.rollback("ws", artifact.manifest.id, installed.record.installationId) as ExtensionPackageChange.Applied
        assertEquals(false, rolled.record.enableIntent)
    }

    @Test
    fun `unknown commit prompts reconciliation without replaying`() {
        val backing = InMemoryExtensionPackageStore()
        var writes = 0
        val installer = ExtensionPackageInstaller(object : ExtensionPackageStore by backing {
            override fun compareAndSet(
                workspaceId: String, packageId: String, expectedInstallationId: String?, replacement: ExtensionPackageRecord?,
                publish: (() -> Unit) -> Unit,
            ): ExtensionPackageStoreWrite {
                writes++
                assertEquals(ExtensionPackageStoreWrite.CHANGED, backing.compareAndSet(workspaceId, packageId, expectedInstallationId, replacement, publish))
                throw ExtensionPackageStoreException(commitUnknown = true)
            }
        })
        val candidate = installer.inspect(source())
        val result = installer.install("ws", candidate, null, candidate.manifest.capabilities) as ExtensionPackageChange.Rejected
        assertEquals(ExtensionPackageChange.Code.COMMIT_UNKNOWN, result.code)
        assertEquals(1, writes)
        assertEquals(candidate.manifest.sha256, installer.list("ws").single().manifest.sha256)
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
