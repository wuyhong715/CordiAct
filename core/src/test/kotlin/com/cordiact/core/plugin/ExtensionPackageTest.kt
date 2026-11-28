package com.cordiact.core.plugin

import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtensionPackageTest {
    @Test
    fun `turn policy requires schema twelve and explicit capability`() {
        val json = """
            {"schemaVersion":12,"id":"turn-policy","version":"1.0.0","title":"Policy","description":"Review turns",
            "host":{"api":1,"minVersion":"0.1.0"},"capabilities":["agent.turn-policy.publish","ui.conversation.publish"],
            "tools":[],"commands":[],"agent":{"beforeTurn":{"name":"check-turn","source":"(_) => 'continue'"}},
            "client":{"conversation":{"messages":[{"event":"user.message","title":"Review","lines":["Ready"]}]}}}
        """.trimIndent()
        val manifest = ExtensionPackageSource.compile(json.encodeToByteArray())
        assertEquals("check-turn", manifest.agent?.beforeTurn?.name)
        assertNotNull(manifest.client?.conversation)
        assertEquals(PluginTarget.ANDROID_ISOLATED_JS, extensionPackageSpec(manifest).target)
        for (invalid in listOf(
            json.replace("\"schemaVersion\":12", "\"schemaVersion\":11"),
            json.replace("\"agent.turn-policy.publish\",", ""),
            json.replace("\"schemaVersion\":12", "\"schemaVersion\":15"),
        )) {
            assertFailsWith<IllegalArgumentException> { ExtensionPackageSource.compile(invalid.encodeToByteArray()) }
        }
    }

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
        assertEquals(1, manifest.schemaVersion)
        assertEquals(1, manifest.hostApi)
        assertNull(manifest.client)
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
    fun `version two supports a settings only package with optional rows`() {
        val manifest = ExtensionPackageSource.compile(v2Source(client = """{"settingsPage": $settingsPage}"""))
        assertEquals(2, manifest.schemaVersion)
        assertEquals(1, manifest.hostApi)
        assertEquals(setOf(ExtensionPackageCapability.UI_SETTINGS_PUBLISH), manifest.capabilities)
        assertTrue(manifest.tools.isEmpty())
        assertTrue(manifest.commands.isEmpty())
        assertEquals(
            ExtensionPackageClient(settingsPage = ExtensionPackageSettingsPage("包设置", "只读信息")),
            manifest.client,
        )
        val emptyRows = settingsPage.replace("}", """, "rows": []}""")
        assertEquals(
            manifest.client,
            ExtensionPackageSource.compile(v2Source(client = """{"settingsPage": $emptyRows}""")).client,
        )
    }

    @Test
    fun `version two resolves session actions against primary names independent of field order`() {
        val manifest = ExtensionPackageSource.compile(
            v2Source(
                client = """{"sessionActions": [$sessionAction]}""",
                capabilities = listOf("command.publish", "ui.session-action.publish"),
                commands = command,
            ),
        )
        assertEquals(ExtensionPackageClient(sessionActions = listOf(ExtensionPackageSessionAction("show-note", "显示", "show-note"))), manifest.client)
        assertEquals(
            setOf(ExtensionPackageCapability.COMMAND_PUBLISH, ExtensionPackageCapability.UI_SESSION_ACTION_PUBLISH),
            manifest.capabilities,
        )
        assertEquals(setOf("sn"), manifest.commands.single().aliases)
    }

    @Test
    fun `version two accepts both client contributions at their limits with tools and commands`() {
        val rows = (1..4).map { ExtensionPackageSettingsRow("标签 $it", "值 $it") }
        val rowJson = rows.joinToString { """{"label": "${it.label}", "value": "${it.value}"}""" }
        val pageWithRows = settingsPage.replace("}", """, "rows": [$rowJson]}""")
        val secondAction = sessionAction.replace("\"id\": \"show-note\"", "\"id\": \"another\"")
        val manifest = ExtensionPackageSource.compile(
            v2Source(
                client = """{"settingsPage": $pageWithRows, "sessionActions": [$sessionAction, $secondAction]}""",
                capabilities = listOf("tool.publish", "command.publish", "ui.settings.publish", "ui.session-action.publish"),
                tools = """{"name": "checklist", "description": "A list", "kind": "static_text", "text": "list"}""",
                commands = command,
            ),
        )
        val client = assertNotNull(manifest.client)
        assertEquals(rows, assertNotNull(client.settingsPage).rows)
        assertEquals(listOf("show-note", "another"), client.sessionActions.map { it.id })
        assertEquals(listOf("show-note", "show-note"), client.sessionActions.map { it.command })
        assertEquals("checklist", manifest.tools.single().name)
        assertEquals(manifest.capabilities.map { it.wireName }.toSet(), extensionPackageSpec(manifest).requestedCapabilities)
    }

    @Test
    fun `client declarations require a compatible schema and all existing fields`() {
        val validClient = """{"settingsPage": $settingsPage}"""
        val validV2 = text(v2Source(client = validClient))
        val validV1 = text(source())
        for ((name, invalid) in mapOf(
            "missing client" to validV2.replace("\"client\": $validClient,", ""),
            "missing tools" to validV2.replace("\"tools\": [],", ""),
            "missing commands" to validV2.replace(",\n  \"commands\": []", ""),
            "null client" to text(v2Source(client = "null")),
            "array client" to text(v2Source(client = "[]")),
            "empty client" to text(v2Source(client = "{}")),
            "version one client" to validV1.replaceFirst("{", "{\"client\": $validClient,"),
            "version one UI capability" to validV1.replace("\"tool.publish\"", "\"tool.publish\", \"ui.settings.publish\""),
            "unsupported schema" to validV2.replace("\"schemaVersion\": 2", "\"schemaVersion\": 15"),
            "fractional schema" to validV2.replace("\"schemaVersion\": 2", "\"schemaVersion\": 2.0"),
            "string schema" to validV2.replace("\"schemaVersion\": 2", "\"schemaVersion\": \"2\""),
            "host API 2" to validV2.replace("\"api\": 1", "\"api\": 2"),
            "no version one contributions" to validV2.replace("\"client\": $validClient,", "")
                .replace("\"schemaVersion\": 2", "\"schemaVersion\": 1")
                .replace("\"ui.settings.publish\"", ""),
        )) {
            assertTrue(invalid != validV2 && invalid != validV1, "$name fixture must differ from valid sources")
            assertFailsWith<IllegalArgumentException>(name) { ExtensionPackageSource.compile(invalid.toByteArray()) }
        }
    }

    @Test
    fun `a workbench panel is explicitly authorized in schema three`() {
        val source = text(v2Source(
            client = """{"workbenchPanel": $settingsPage}""",
            capabilities = listOf("ui.workbench-panel.publish"),
        ))
        val versionThree = source.replace("\"schemaVersion\": 2", "\"schemaVersion\": 3")

        val manifest = ExtensionPackageSource.compile(versionThree.toByteArray())
        assertEquals(3, manifest.schemaVersion)
        assertEquals(ExtensionPackageSettingsPage("包设置", "只读信息"), manifest.client?.workbenchPanel)
        assertFailsWith<IllegalArgumentException> { ExtensionPackageSource.compile(source.toByteArray()) }
        assertFailsWith<IllegalArgumentException> {
            ExtensionPackageSource.compile(versionThree.replace("\"ui.workbench-panel.publish\"", "").toByteArray())
        }
        assertFailsWith<IllegalArgumentException> {
            ExtensionPackageSource.compile(versionThree.replace("workbenchPanel", "settingsPage").toByteArray())
        }
    }

    @Test
    fun `only schema four with a matching grant admits script source`() {
        val bytes = """{
            "schemaVersion":4,"id":"script-sample","version":"1.0.0","title":"Sample","description":"Script tool",
            "host":{"api":1,"minVersion":"0.1.0"},"capabilities":["tool.publish","tool.script.execute"],
            "tools":[{"name":"count-lines","description":"Count lines","kind":"text_script","source":"(text) => text"}],"commands":[]
        }""".trimIndent()
        val manifest = ExtensionPackageSource.compile(bytes.toByteArray())

        assertEquals("(text) => text", manifest.tools.single().script)
        assertEquals(PluginTarget.ANDROID_ISOLATED_JS, extensionPackageSpec(manifest).target)
        assertNull(manifest.client)
        listOf(
            bytes.replace("\"schemaVersion\":4", "\"schemaVersion\":1"),
            bytes.replace(",\"tool.script.execute\"", ""),
            bytes.replace("(text) => text", "x".repeat(PACKAGE_SCRIPT_MAX_SOURCE_BYTES + 1)),
            bytes.replace("(text) => text", "\\uD800"),
            bytes.replace("\"kind\":\"text_script\"", "\"kind\":\"text_script\",\"text\":\"shadow\""),
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { ExtensionPackageSource.compile(invalid.toByteArray()) }
        }
    }

    @Test
    fun `agent declarations require each exact grant and name`() {
        val contribution = """{"name":"review-plan","description":"Read a plan","body":"Review it"}"""
        val valid = """{
            "schemaVersion":5,"id":"review-kit","version":"1.0.0","title":"Review kit","description":"Review",
            "host":{"api":1,"minVersion":"0.1.0"},"capabilities":["agent.skill.publish","agent.context.publish"],
            "tools":[],"commands":[],"agent":{"skills":[$contribution],"context":"Reference"}
        }""".trimIndent()
        val manifest = ExtensionPackageSource.compile(valid.toByteArray())
        assertEquals("Reference", manifest.agent?.context)
        assertEquals("review-plan", manifest.agent?.skills?.single()?.name)
        for (invalid in listOf(
            valid.replace("\"schemaVersion\":5", "\"schemaVersion\":4"),
            valid.replace("\"agent.skill.publish\",", ""),
            valid.replace(",\"agent.context.publish\"", ""),
            valid.replace(contribution, "$contribution,$contribution"),
            valid.replace("Reference", "界".repeat(350)),
            valid.replace("review-plan", "a.b"),
        )) assertFailsWith<IllegalArgumentException> { ExtensionPackageSource.compile(invalid.toByteArray()) }
    }

    @Test
    fun `client settings reject invalid fields bounds and non single line text`() {
        val row = """{"label": "状态", "value": "正常"}"""
        for ((name, invalid) in mapOf(
            "unknown client field" to """{"settingsPage": $settingsPage, "extra": true}""",
            "duplicate settings page" to """{"settingsPage": $settingsPage, "settingsPage": $settingsPage}""",
            "null settings page" to """{"settingsPage": null}""",
            "missing description" to """{"settingsPage": {"title": "设置"}}""",
            "duplicate title" to """{"settingsPage": {"title": "设置", "title": "重复", "description": "说明"}}""",
            "unknown settings page field" to """{"settingsPage": {"title": "设置", "description": "说明", "href": "https://example.com"}}""",
            "non string title" to """{"settingsPage": {"title": 1, "description": "说明"}}""",
            "blank description" to """{"settingsPage": {"title": "设置", "description": " "}}""",
            "multiline page title" to """{"settingsPage": {"title": "a\nb", "description": "说明"}}""",
            "oversized UTF-8 description" to """{"settingsPage": {"title": "设置", "description": "${"值".repeat(161)}"}}""",
            "non array rows" to settingsClientWithRows("null"),
            "too many rows" to settingsClientWithRows(List(5) { row }.joinToString(prefix = "[", postfix = "]")),
            "non object row" to settingsClientWithRows("[1]"),
            "missing value" to settingsClientWithRows("""[{"label": "状态"}]"""),
            "duplicate label" to settingsClientWithRows("""[{"label": "状态", "label": "重复", "value": "正常"}]"""),
            "unknown row field" to settingsClientWithRows("""[{"label": "状态", "value": "正常", "action": "show-note"}]"""),
            "multiline row value" to settingsClientWithRows("""[{"label": "状态", "value": "a\nb"}]"""),
            "tab in row label" to settingsClientWithRows("""[{"label": "a\tb", "value": "正常"}]"""),
            "format control in row value" to settingsClientWithRows("""[{"label": "状态", "value": "a\u202Eb"}]"""),
            "unpaired surrogate in row value" to settingsClientWithRows("""[{"label": "状态", "value": "a\uD800b"}]"""),
            "oversized UTF-8 row label" to settingsClientWithRows("""[{"label": "${"值".repeat(41)}", "value": "正常"}]"""),
        )) {
            assertFailsWith<IllegalArgumentException>(name) { ExtensionPackageSource.compile(v2Source(client = invalid)) }
        }
    }

    @Test
    fun `session actions reject invalid ids shapes and command references`() {
        val secondAction = sessionAction.replace("\"id\": \"show-note\"", "\"id\": \"another\"")
        val thirdAction = sessionAction.replace("\"id\": \"show-note\"", "\"id\": \"third\"")
        for ((name, actions) in mapOf(
            "empty array" to "[]",
            "null actions" to "null",
            "not an array" to "{}",
            "not an object" to "[null]",
            "too many actions" to "[$sessionAction, $secondAction, $thirdAction]",
            "duplicate id" to "[$sessionAction, $sessionAction]",
            "invalid id" to """[{"id": "Bad ID", "label": "显示", "command": "show-note"}]""",
            "missing command" to """[{"id": "show-note", "label": "显示"}]""",
            "duplicate command field" to """[{"id": "show-note", "label": "显示", "command": "show-note", "command": "show-note"}]""",
            "unknown field" to """[{"id": "show-note", "label": "显示", "command": "show-note", "args": []}]""",
            "alias" to """[{"id": "show-note", "label": "显示", "command": "sn"}]""",
            "slash" to """[{"id": "show-note", "label": "显示", "command": "/show-note"}]""",
            "another package command" to """[{"id": "show-note", "label": "显示", "command": "other-command"}]""",
            "blank label" to """[{"id": "show-note", "label": " ", "command": "show-note"}]""",
            "line separator" to """[{"id": "show-note", "label": "a\u2028b", "command": "show-note"}]""",
        )) {
            assertFailsWith<IllegalArgumentException>(name) {
                ExtensionPackageSource.compile(
                    v2Source(
                        client = """{"settingsPage": $settingsPage, "sessionActions": $actions}""",
                        capabilities = listOf("command.publish", "ui.settings.publish", "ui.session-action.publish"),
                        commands = command,
                    ),
                )
            }
        }
        assertFailsWith<IllegalArgumentException>("session action without a package command") {
            ExtensionPackageSource.compile(
                v2Source(
                    client = """{"sessionActions": [$sessionAction]}""",
                    capabilities = listOf("ui.session-action.publish"),
                ),
            )
        }
    }

    @Test
    fun `version two capabilities match all actual declarations both ways`() {
        val settings = """{"settingsPage": $settingsPage}"""
        val actions = """{"sessionActions": [$sessionAction]}"""
        val both = """{"settingsPage": $settingsPage, "sessionActions": [$sessionAction]}"""
        val settingsCapability = "ui.settings.publish"
        val actionCapability = "ui.session-action.publish"
        val commandCapability = "command.publish"
        for ((name, invalid) in mapOf(
            "no settings permission" to v2Source(settings, emptyList()),
            "no action permission" to v2Source(both, listOf(settingsCapability, commandCapability), commands = command),
            "unneeded settings permission" to v2Source(actions, listOf(settingsCapability, actionCapability, commandCapability), commands = command),
            "unneeded action permission" to v2Source(settings, listOf(settingsCapability, actionCapability)),
            "unneeded tool permission" to v2Source(settings, listOf(settingsCapability, "tool.publish")),
            "unneeded command permission" to v2Source(settings, listOf(settingsCapability, commandCapability)),
            "missing command permission" to v2Source(actions, listOf(actionCapability), commands = command),
            "duplicate UI permission" to v2Source(settings, listOf(settingsCapability, settingsCapability)),
        )) {
            assertFailsWith<IllegalArgumentException>(name) { ExtensionPackageSource.compile(invalid) }
        }
    }

    @Test
    fun `version two uses exact bytes and keeps strict JSON UTF-8 and 32 KiB source limits`() {
        val bytes = v2Source(client = """{"settingsPage": $settingsPage}""")
        val maxBytes = bytes + ByteArray(ExtensionPackageSource.MAX_SOURCE_BYTES - bytes.size) { ' '.code.toByte() }
        val artifact = ExtensionPackageArtifact.decode(maxBytes)
        assertEquals(32 * 1024, artifact.manifest.sizeBytes)
        assertTrue(maxBytes.contentEquals(artifact.copySource()))
        val expected = MessageDigest.getInstance("SHA-256").digest(maxBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(expected, artifact.manifest.sha256)
        assertTrue(ExtensionPackageSource.compile(bytes).sha256 != expected)
        for ((name, invalid) in mapOf(
            "source too large" to (maxBytes + ' '.code.toByte()),
            "malformed UTF-8" to (bytes + 0xff.toByte()),
            "BOM" to ("\uFEFF".toByteArray() + bytes),
            "trailing JSON value" to (bytes + " {}".toByteArray()),
            "comment" to ("/* comment */".toByteArray() + bytes),
        )) {
            assertFailsWith<IllegalArgumentException>(name) { ExtensionPackageSource.compile(invalid) }
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

    private val settingsPage = """{"title": "包设置", "description": "只读信息"}"""
    private val sessionAction = """{"id": "show-note", "label": "显示", "command": "show-note"}"""
    private val command = """{"name": "show-note", "aliases": ["sn"], "description": "Show a note", "text": "note"}"""

    private fun settingsClientWithRows(rows: String): String =
        """{"settingsPage": {"title": "包设置", "description": "只读信息", "rows": $rows}}"""

    private fun v2Source(
        client: String,
        capabilities: List<String> = listOf("ui.settings.publish"),
        tools: String = "",
        commands: String = "",
    ): ByteArray = """
        {
          "client": $client,
          "schemaVersion": 2,
          "id": "quick-notes",
          "version": "1.0.0",
          "title": "Quick notes",
          "description": "Small declarative extensions",
          "host": {"api": 1, "minVersion": "0.1.0"},
          "capabilities": [${capabilities.joinToString { "\"$it\"" }}],
          "tools": [$tools],
          "commands": [$commands]
        }
    """.trimIndent().toByteArray()

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
