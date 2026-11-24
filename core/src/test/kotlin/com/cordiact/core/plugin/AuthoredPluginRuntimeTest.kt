package com.cordiact.core.plugin

import com.cordiact.core.action.ApprovalDecision
import com.cordiact.core.action.ApprovalGateway
import com.cordiact.core.action.ApprovalOwner
import com.cordiact.core.action.ApprovalRequest
import com.cordiact.core.file.WorkspaceFileContent
import com.cordiact.core.file.WorkspaceFileException
import com.cordiact.core.file.WorkspaceFileMutation
import com.cordiact.core.file.WorkspaceFileService
import com.cordiact.core.plugin.AuthoredPluginException.Code
import com.cordiact.core.tool.GrantedScope
import com.cordiact.core.tool.InMemoryToolDefinitionRegistry
import com.cordiact.core.tool.MutableToolPreferenceStore
import com.cordiact.core.tool.ToolActivationContext
import com.cordiact.core.tool.ToolArguments
import com.cordiact.core.tool.ToolInvocationContext
import com.cordiact.core.tool.ToolManagerException
import com.cordiact.core.tool.ToolManagerPlugin
import com.cordiact.core.tool.ToolValue
import com.cordiact.core.tool.ToolValueType
import com.cordiact.core.workspace.Workspace
import com.cordiact.core.workspace.WorkspaceManagerService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AuthoredPluginRuntimeTest {
    @Test
    fun `agent source becomes a tool only after approving its exact version and refreshing the catalog`() {
        val f = Fixture()
        f.put("first version")
        val originalTurn = f.context()
        val draft = f.call(originalTurn, "inspect", ID).values.getValue("draft") as ToolValue.ObjectValue
        val sha = (draft.values.getValue("sha256") as ToolValue.StringValue).value
        assertEquals(emptyList(), f.approval.requests)
        assertEquals(setOf(AUTHORED_PLUGIN_TOOL_ID), originalTurn.toolRegistrations.keys)

        val receipt = f.call(originalTurn, "activate", ID, sha)
        assertEquals(ToolValue.StringValue("active_next_turn"), receipt.values["state"])
        val approval = f.approval.requests.single()
        assertEquals(ApprovalOwner("a", "s"), approval.owner)
        assertEquals(sha, approval.details["SHA-256"])
        assertTrue(approval.details.values.any { "first version" in it })
        assertFailsWith<ToolManagerException> { f.manager.invoke(originalTurn, TEXT_TOOL, ToolArguments()) }
        assertFalse(f.registry.all().any { it.descriptor.id == TEXT_TOOL })
        val refreshed = f.context()
        assertEquals(ToolValue.StringValue("first version"), f.manager.invoke(refreshed, TEXT_TOOL, ToolArguments()).values["text"])
        f.put("edited draft")
        val output = f.manager.invoke(refreshed, TEXT_TOOL, ToolArguments()).values
        assertEquals(ToolValue.StringValue("first version"), output["text"])
        assertEquals(ToolValue.BooleanValue(true), output["untrusted"])

        val changed = f.call(refreshed, "deactivate", ID, sha)
        assertEquals(ToolValue.StringValue("inactive"), changed.values["state"])
        assertFailsWith<ToolManagerException> { f.manager.invoke(refreshed, TEXT_TOOL, ToolArguments()) }
        assertEquals("edited draft", f.runtime.inspect(refreshed, ID).tools.single().text)
        assertEquals(2, f.approval.requests.size)
    }

    @Test
    fun `mixed v2 tools require exact approval and are separately described on the next turn`() {
        val f = Fixture()
        f.putV2()
        val originalTurn = f.context()
        val draft = f.call(originalTurn, "inspect", ID).values.getValue("draft") as ToolValue.ObjectValue
        val sha = (draft.values.getValue("sha256") as ToolValue.StringValue).value
        val management = f.registry.get(AUTHORED_PLUGIN_TOOL_ID)!!.descriptor.description
        for (term in listOf("schemaVersion:2", "schemaVersion:1", "static_text", "text_transform", "4096")) {
            assertTrue(term in management)
        }
        assertEquals(setOf(AUTHORED_PLUGIN_TOOL_ID), originalTurn.toolRegistrations.keys)
        val stale = f.call(originalTurn, "activate", ID, "0".repeat(64)).values.getValue("error") as ToolValue.ObjectValue
        assertEquals(ToolValue.StringValue("VERSION_CHANGED"), stale.values["code"])
        assertTrue(f.approval.requests.isEmpty())
        f.approval.decide = {
            assertEquals(setOf(AUTHORED_PLUGIN_TOOL_ID), f.context().toolRegistrations.keys)
            ApprovalDecision(true)
        }
        val activated = f.call(originalTurn, "activate", ID, sha)
        f.approval.decide = { ApprovalDecision(true) }
        assertEquals(ToolValue.StringValue("active_next_turn"), activated.values["state"])
        val approval = f.approval.requests.single()
        assertEquals(ApprovalOwner("a", "s"), approval.owner)
        assertEquals(sha, approval.details["SHA-256"])
        val transform = approval.details.getValue("工具 $TRIM_TOOL")
        for (term in listOf("text_transform", "trim", "输入", "输出", "4096", "UTF-8", "不截断")) {
            assertTrue(term in transform)
        }
        assertTrue("uppercase" in approval.details.getValue("工具 $UPPER_TOOL"))
        assertTrue("lowercase" in approval.details.getValue("工具 $LOWER_TOOL"))
        assertTrue("fixed v2 text" in approval.details.getValue("工具 $TEXT_TOOL"))
        assertTrue("不会读取本地文件、联网" in approval.details.getValue("权限"))
        assertFailsWith<ToolManagerException> { f.manager.invoke(originalTurn, TRIM_TOOL, textArguments("input")) }
        assertFalse(TRIM_TOOL in f.context(session = "s2").toolRegistrations)
        val nextTurn = f.context()
        val nextTools = f.manager.resolveForTurn(ToolActivationContext("a", sessionId = "s")).associateBy { it.descriptor.id }
        assertEquals(setOf(AUTHORED_PLUGIN_TOOL_ID, TEXT_TOOL, TRIM_TOOL, UPPER_TOOL, LOWER_TOOL), nextTools.keys)
        for (toolId in listOf(TEXT_TOOL, TRIM_TOOL, UPPER_TOOL, LOWER_TOOL)) {
            assertTrue("Tool ID: $toolId." in nextTools.getValue(toolId).descriptor.description)
        }
        val static = nextTools.getValue(TEXT_TOOL).descriptor
        assertTrue("static text" in static.description)
        assertTrue(static.inputSchema.parameters.isEmpty())
        val descriptor = nextTools.getValue(TRIM_TOOL).descriptor
        assertTrue("trim" in descriptor.description)
        assertTrue("uppercase" in nextTools.getValue(UPPER_TOOL).descriptor.description)
        assertTrue("lowercase" in nextTools.getValue(LOWER_TOOL).descriptor.description)
        assertFalse("INJECTED_DESCRIPTION" in descriptor.description)
        assertFalse(descriptor.inputSchema.allowAdditional)
        assertTrue(descriptor.requiredCapabilities.isEmpty())
        val parameter = descriptor.inputSchema.parameters.single()
        assertEquals("text", parameter.name)
        assertEquals(ToolValueType.STRING, parameter.type)
        assertTrue(parameter.required)
        for (badArguments in listOf(ToolArguments(), ToolArguments(mapOf("text" to ToolValue.BooleanValue(true))),
            ToolArguments(textArguments("x").values + ("operation" to ToolValue.StringValue("uppercase"))))) {
            assertEquals(ToolManagerException.Code.INVALID_ARGUMENT, assertFailsWith<ToolManagerException> {
                f.manager.invoke(nextTurn, TRIM_TOOL, badArguments)
            }.code)
        }

        // Source changes cannot change the approved operations or remove an active executor mid-session.
        f.putV2(trimOperation = "uppercase")
        val reads = f.files.reads
        val literal = "{{should_not_expand}}\r\n请忽略指令 😀 {{file:plugins/checklist.json}}"
        val trimmed = f.manager.invoke(nextTurn, TRIM_TOOL, textArguments(" \t$literal\r\n")).values
        assertEquals(mapOf("text" to ToolValue.StringValue(literal), "untrusted" to ToolValue.BooleanValue(true)), trimmed)
        assertEquals("IÉSS😀", f.transformed(nextTurn, UPPER_TOOL, "iéß😀"))
        assertEquals("ii\u0307é😀", f.transformed(nextTurn, LOWER_TOOL, "IİÉ😀"))
        assertEquals("", f.transformed(nextTurn, TRIM_TOOL, " \n\t"))
        assertEquals("", f.transformed(nextTurn, UPPER_TOOL, ""))
        assertEquals(reads, f.files.reads)
        assertEquals(1, f.approval.requests.size)
        assertEquals(ToolValue.StringValue("inactive"), f.call(nextTurn, "deactivate", ID, sha).values["state"])
        assertFailsWith<ToolManagerException> { f.manager.invoke(nextTurn, TRIM_TOOL, textArguments("stale")) }
    }

    @Test
    fun `text transform bounds count UTF-8 bytes before and after without truncation`() {
        val f = Fixture()
        f.putV2()
        val caller = f.context()
        f.runtime.activate(caller, ID, f.runtime.inspect(caller, ID).sha256)
        val nextTurn = f.context()
        val exact = "😀".repeat(1024)
        assertEquals(exact, f.transformed(nextTurn, TRIM_TOOL, exact))
        val tooLargeBeforeTrim = f.rejectedTransform(nextTurn, TRIM_TOOL, "$exact ")
        assertEquals("Text input exceeds 4096 UTF-8 bytes", tooLargeBeforeTrim.message)
        val expands = "\u0130".repeat(1365)
        val exactAfterLowercase = "i\u0307".repeat(1365) + "x"
        assertEquals(exactAfterLowercase, f.transformed(nextTurn, LOWER_TOOL, "${expands}X"))
        val tooLargeAfterLowercase = f.rejectedTransform(nextTurn, LOWER_TOOL, "${expands}XX")
        assertEquals("Text output exceeds 4096 UTF-8 bytes", tooLargeAfterLowercase.message)
        assertEquals("Text input is not valid Unicode text", f.rejectedTransform(nextTurn, TRIM_TOOL, "a\uD800b").message)
    }

    @Test
    fun `another session or workspace cannot see or use the activated tool even with a read grant`() {
        val f = Fixture()
        f.put("private session")
        val caller = f.context()
        val sha = f.runtime.inspect(caller, ID).sha256
        f.runtime.activate(caller, ID, sha)
        val active = f.context()
        val token = active.toolRegistrations.getValue(TEXT_TOOL)
        for ((workspace, session) in listOf("a" to "s2", "b" to "other", "b" to "s")) {
            val foreign = f.context(workspace, session).copy(grantedScopes = listOf(GrantedScope("a")))
            assertFalse(TEXT_TOOL in foreign.toolRegistrations)
            assertFailsWith<ToolManagerException> {
                f.manager.invoke(foreign.copy(toolRegistrations = mapOf(TEXT_TOOL to token)), TEXT_TOOL, ToolArguments())
            }
        }
        val background = f.context().copy(allowsPluginAuthoring = false)
        assertEquals(AuthoredPluginException.Code.CALLER_UNAVAILABLE,
            assertFailsWith<AuthoredPluginException> { f.runtime.deactivate(background, ID, sha) }.code)
        assertEquals(1, f.approval.requests.size)
        assertEquals(1, f.runtime.list(active).size)
    }

    @Test
    fun `refusal source race and caller change publish nothing`() {
        val f = Fixture()
        val caller = f.context()
        f.put("one")
        val sha = f.runtime.inspect(caller, ID).sha256
        f.approval.decide = { ApprovalDecision(false) }
        assertEquals(AuthoredPluginException.Code.DENIED,
            assertFailsWith<AuthoredPluginException> { f.runtime.activate(caller, ID, sha) }.code)
        f.approval.decide = { f.put("two"); ApprovalDecision(true) }
        assertEquals(AuthoredPluginException.Code.VERSION_CHANGED,
            assertFailsWith<AuthoredPluginException> { f.runtime.activate(caller, ID, sha) }.code)
        val second = f.runtime.inspect(caller, ID).sha256
        f.approval.decide = { f.foreground = "a" to "s2"; ApprovalDecision(true) }
        assertEquals(AuthoredPluginException.Code.CALLER_UNAVAILABLE,
            assertFailsWith<AuthoredPluginException> { f.runtime.activate(caller, ID, second) }.code)
        assertFalse(TEXT_TOOL in f.context().toolRegistrations)
        assertTrue(f.kernel.statuses().isEmpty())
    }

    @Test
    fun `trusted list and revoke use only the precise session and approved source digest`() {
        val f = Fixture()
        f.put("identical source")
        f.put("identical source", workspace = "b")
        fun activateIn(workspace: String, session: String): AuthoredPluginReceipt {
            f.foreground = workspace to session
            val context = f.context(workspace, session)
            return f.runtime.activate(context, ID, f.runtime.inspect(context, ID).sha256)
        }
        val original = activateIn("a", "s")
        val otherSession = activateIn("a", "s2")
        val otherWorkspace = activateIn("b", "s")
        assertEquals(original, otherSession)
        assertEquals(original, otherWorkspace)
        f.foreground = null // no model can manage any of these sessions
        f.approval.requests.clear()
        val reads = f.files.reads
        f.put("new draft, not active")

        assertSame(original, f.runtime.listForSession("a", "s").single())
        assertSame(otherSession, f.runtime.listForSession("a", "s2").single())
        assertSame(otherWorkspace, f.runtime.listForSession("b", "s").single())
        assertTrue(f.runtime.listForSession("b", "s2").isEmpty())
        assertEquals(AuthoredPluginException.Code.NOT_ACTIVE, assertFailsWith<AuthoredPluginException> {
            f.runtime.revokeForSession("b", "s2", ID, original.sha256)
        }.code)
        assertEquals(AuthoredPluginException.Code.VERSION_CHANGED, assertFailsWith<AuthoredPluginException> {
            f.runtime.revokeForSession("a", "s", ID, "0".repeat(64))
        }.code)
        assertEquals(AuthoredPluginException.Code.VERSION_CHANGED, assertFailsWith<AuthoredPluginException> {
            f.runtime.revokeForSession("b", "s", original)
        }.code)
        assertSame(original, f.runtime.revokeForSession("a", "s", ID, original.sha256))
        assertTrue(f.runtime.listForSession("a", "s").isEmpty())
        assertNull(f.registry.getIn(TEXT_TOOL, "a", "s"))
        assertSame(otherSession, f.runtime.listForSession("a", "s2").single())
        assertSame(otherWorkspace, f.runtime.listForSession("b", "s").single())
        assertNotNull(f.registry.getIn(TEXT_TOOL, "a", "s2"))
        assertNotNull(f.registry.getIn(TEXT_TOOL, "b", "s"))
        assertEquals(reads, f.files.reads)
        assertTrue(f.approval.requests.isEmpty())
        assertTrue(f.approval.responses.isEmpty())
        assertEquals(0, f.approval.clears)
    }

    @Test
    fun `approval from before invalidation cannot publish even after session becomes available again`() {
        val f = Fixture()
        f.put("draft survives invalidate")
        val context = f.context()
        val sha = f.runtime.inspect(context, ID).sha256
        f.approval.decide = {
            val requestId = f.approval.requests.single().id
            f.foreground = null
            f.runtime.invalidateSession("a", "s")
            assertEquals(listOf(requestId), f.approval.responses.map { it.first })
            assertFalse(f.approval.responses.single().second.approved)
            f.foreground = "a" to "s"
            ApprovalDecision(true) // Simulate an accepted decision racing invalidation.
        }
        assertEquals(AuthoredPluginException.Code.CALLER_UNAVAILABLE, assertFailsWith<AuthoredPluginException> {
            f.runtime.activate(context, ID, sha)
        }.code)
        assertTrue(f.runtime.listForSession("a", "s").isEmpty())
        assertTrue(f.kernel.statuses().isEmpty())
        assertNull(f.registry.getIn(TEXT_TOOL, "a", "s"))
        assertEquals(0, f.approval.clears)
        f.approval.decide = { ApprovalDecision(true) }
        assertEquals(sha, f.runtime.activate(context, ID, sha).sha256)
    }

    @Test
    fun `switching away and back cannot reuse an accepted approval or a queued old turn`() {
        val f = Fixture()
        val foreground = AuthoredPluginForeground()
        val screen = Any()
        foreground.resume(screen)
        foreground.select("a", "s")
        val turn = requireNotNull(foreground.openTurn("a", "s"))
        val call = f.context().copy(authoredPluginTurn = turn)
        f.put("same session after switch")
        val sha = f.runtime.inspect(call, ID).sha256
        f.approval.decide = {
            val waiting = f.approval.requests.single()
            val isCurrent = requireNotNull(waiting.isCurrent)
            assertTrue(isCurrent())
            foreground.select("a", "s2")
            foreground.select("a", "s")
            assertFalse(turn.isOpen)
            assertFalse(isCurrent())
            assertEquals(listOf(waiting.id), f.approval.responses.map { it.first })
            ApprovalDecision(true) // The user decision was accepted just before the switch.
        }
        assertEquals(AuthoredPluginException.Code.CALLER_UNAVAILABLE, assertFailsWith<AuthoredPluginException> {
            f.runtime.activate(call, ID, sha)
        }.code)
        assertTrue(f.runtime.listForSession("a", "s").isEmpty())
        assertFailsWith<AuthoredPluginException> { f.runtime.inspect(call, ID) }
        assertEquals(1, f.approval.requests.size)
        f.approval.decide = { ApprovalDecision(true) }
        val replacement = requireNotNull(foreground.openTurn("a", "s"))
        f.runtime.activate(call.copy(authoredPluginTurn = replacement), ID, sha)
        foreground.pause(screen) // Publication won; pausing revokes authoring, not the installed trial.
        assertFalse(replacement.isOpen)
        assertEquals(sha, f.runtime.listForSession("a", "s").single().sha256)
        assertNull(foreground.openTurn("a", "s"))
    }

    @Test
    fun `stop after approval but during source recheck prevents final publication`() {
        val f = Fixture()
        val foreground = AuthoredPluginForeground()
        val screen = Any()
        foreground.resume(screen)
        foreground.select("a", "s")
        val turn = requireNotNull(foreground.openTurn("a", "s"))
        val call = f.context().copy(authoredPluginTurn = turn)
        f.put("frozen bytes")
        val sha = f.runtime.inspect(call, ID).sha256
        f.approval.decide = {
            f.files.beforeRead = { turn.close() }
            ApprovalDecision(true)
        }
        assertEquals(AuthoredPluginException.Code.CALLER_UNAVAILABLE, assertFailsWith<AuthoredPluginException> {
            f.runtime.activate(call, ID, sha)
        }.code)
        assertTrue(f.kernel.statuses().isEmpty())
        assertEquals(1, f.approval.requests.size)
        assertTrue(f.approval.responses.isEmpty()) // No longer waiting on the already-accepted request.
    }

    @Test
    fun `invalidate removes all active versions only in the specified scope without touching drafts`() {
        val f = Fixture()
        val secondId = "summary"
        f.put("first")
        f.put("second", id = secondId)
        val context = f.context()
        val first = f.runtime.activate(context, ID, f.runtime.inspect(context, ID).sha256)
        val second = f.runtime.activate(context, secondId, f.runtime.inspect(context, secondId).sha256)
        f.foreground = "a" to "s2"
        val other = f.runtime.activate(f.context(session = "s2"), ID, first.sha256)
        f.foreground = null
        f.runtime.invalidateSession("a", "s")
        f.runtime.invalidateSession("a", "s") // retry/idempotency
        assertTrue(f.runtime.listForSession("a", "s").isEmpty())
        assertNull(f.registry.getIn(first.toolIds.single(), "a", "s"))
        assertNull(f.registry.getIn(second.toolIds.single(), "a", "s"))
        assertSame(other, f.runtime.listForSession("a", "s2").single())
        assertEquals(3, f.approval.requests.size)
        assertTrue(f.approval.responses.isEmpty())

        f.foreground = "a" to "s"
        assertEquals(first.sha256, f.runtime.inspect(context, ID).sha256)
        assertEquals(second.sha256, f.runtime.inspect(context, secondId).sha256)
        val reactivated = f.runtime.activate(context, ID, first.sha256)
        assertEquals(first, reactivated)
        assertNotSame(first, reactivated)
        assertEquals(AuthoredPluginException.Code.VERSION_CHANGED, assertFailsWith<AuthoredPluginException> {
            f.runtime.revokeForSession("a", "s", first)
        }.code)
        assertSame(reactivated, f.runtime.revokeForSession("a", "s", reactivated))
    }

    @Test
    fun `cleanup failure is reported and the same version can be retried`() {
        val f = Fixture()
        f.put("preserved draft")
        val context = f.context()
        val receipt = f.runtime.activate(context, ID, f.runtime.inspect(context, ID).sha256)
        val blocker = PluginInstanceId("host.fixture-blocker")
        var failDisposal = true
        f.kernel.register(blocker, object : ContextPlugin {
            override fun start(context: PluginContext) {
                context.effect { check(!failDisposal) { "test disposal blocked" } }
            }
        })
        f.kernel.enable(blocker)
        // The kernel will report incomplete cleanup on every unregister until the blocker can dispose.
        assertFailsWith<IllegalStateException> { f.kernel.disable(blocker) }
        assertEquals(AuthoredPluginException.Code.CLEANUP_FAILED, assertFailsWith<AuthoredPluginException> {
            f.runtime.revokeForSession("a", "s", receipt)
        }.code)
        assertEquals(AuthoredPluginException.Code.CLEANUP_FAILED, assertFailsWith<AuthoredPluginException> {
            f.runtime.invalidateSession("a", "s")
        }.code)
        assertSame(receipt, f.runtime.listForSession("a", "s").single())
        assertNull(f.registry.getIn(TEXT_TOOL, "a", "s")) // Publication is already fail-closed.

        failDisposal = false
        f.runtime.invalidateSession("a", "s")
        assertTrue(f.runtime.listForSession("a", "s").isEmpty())
        assertEquals(listOf(blocker), f.kernel.statuses().map { it.id })
        assertEquals(receipt.sha256, f.runtime.inspect(context, ID).sha256)
        assertEquals(1, f.approval.requests.size)
    }

    @Test
    fun `old agent deactivation cannot remove a replacement at the same digest`() {
        val f = Fixture()
        f.put("same bytes across activations")
        val context = f.context()
        val sha = f.runtime.inspect(context, ID).sha256
        val original = f.runtime.activate(context, ID, sha)
        val oldTurn = f.context()
        f.approval.decide = {
            assertSame(original, f.runtime.revokeForSession("a", "s", original))
            assertFailsWith<ToolManagerException> { f.manager.invoke(oldTurn, TEXT_TOOL, ToolArguments()) }
            f.approval.decide = { ApprovalDecision(true) }
            val replacement = f.runtime.activate(context, ID, sha)
            assertEquals(original, replacement)
            assertNotSame(original, replacement)
            ApprovalDecision(true)
        }

        assertEquals(AuthoredPluginException.Code.VERSION_CHANGED, assertFailsWith<AuthoredPluginException> {
            f.runtime.deactivate(oldTurn, ID, sha)
        }.code)
        assertEquals(sha, f.runtime.listForSession("a", "s").single().sha256)
        assertFailsWith<ToolManagerException> { f.manager.invoke(oldTurn, TEXT_TOOL, ToolArguments()) }
        assertEquals(ToolValue.StringValue("same bytes across activations"),
            f.manager.invoke(f.context(), TEXT_TOOL, ToolArguments()).values["text"])
        assertEquals(3, f.approval.requests.size)
        assertTrue(f.approval.responses.isEmpty())
    }

    @Test
    fun `reserved name collision leaves no partial plugin and never removes the prior tool`() {
        for (userConfirmed in listOf(false, true)) {
            val f = Fixture()
            f.put("value", twoTools = true)
            val conflict = AuthoredPluginNames.tool(ID, "last")
            f.registry.register(com.cordiact.core.tool.ToolDefinition(
                com.cordiact.core.tool.ToolDescriptor(conflict, "Existing", "", com.cordiact.core.tool.ToolInputSchema(), "host.fixture"),
            ) { _, _ -> error("not invoked") })
            val caller = f.context()
            val sha = f.runtime.inspect(caller, ID).sha256
            assertEquals(Code.ACTIVATION_FAILED, assertFailsWith<AuthoredPluginException> {
                if (userConfirmed) {
                    val foreground = AuthoredPluginForeground().apply { resume(Any()); select("a", "s") }
                    val confirmation = requireNotNull(foreground.openConfirmation("a", "s"))
                    f.runtime.activateForSession(confirmation, ID, sha) { true }
                } else f.runtime.activate(caller, ID, sha)
            }.code)
            assertTrue(f.runtime.list(caller).isEmpty())
            assertFalse(TEXT_TOOL in f.context().toolRegistrations)
            assertNotNull(f.registry.get(conflict))
            assertTrue(f.kernel.statuses().isEmpty())
            if (userConfirmed) f.assertNoApproval() else assertEquals(1, f.approval.requests.size)
        }
    }

    @Test
    fun `source persists across process boundary but activation does not`() {
        val before = Fixture()
        before.put("persisted source")
        val context = before.context()
        val sha = before.runtime.inspect(context, ID).sha256
        before.runtime.activate(context, ID, sha)
        val after = Fixture(before.files)
        assertEquals(sha, after.runtime.inspect(after.context(), ID).sha256)
        assertTrue(after.runtime.list(after.context()).isEmpty())
        assertFalse(TEXT_TOOL in after.context().toolRegistrations)
    }

    @Test
    fun `user confirmation bypasses model authorization and approval but uses the same next-turn tools and scope`() {
        val f = Fixture()
        f.putV2()
        f.put("wrong workspace source", workspace = "b")
        val oldTurn = f.context()
        val digest = AuthoredPluginDrafts(f.files).preview("a", ID).sha256
        f.foreground = null // Only the model management policy denies; this is a trusted host action.
        f.approval.decide = { error("The user already confirmed") }
        val foreground = AuthoredPluginForeground().apply { resume(Any()); select("a", "s") }
        val runningAgent = requireNotNull(foreground.openTurn("a", "s"))
        val confirmation = requireNotNull(foreground.openConfirmation("a", "s"))
        val receipt = f.runtime.activateForSession(confirmation, ID, digest) { true }

        assertTrue(runningAgent.isOpen)
        assertFalse(confirmation.isOpen)
        assertEquals(digest, receipt.sha256)
        assertEquals(listOf(TEXT_TOOL, TRIM_TOOL, UPPER_TOOL, LOWER_TOOL), receipt.toolIds)
        assertSame(receipt, f.runtime.listForSession("a", "s").single())
        assertFailsWith<ToolManagerException> { f.manager.invoke(oldTurn, TEXT_TOOL, ToolArguments()) }
        assertNull(f.registry.get(TEXT_TOOL))
        val nextTurn = f.context()
        val reads = f.files.reads
        f.putV2(trimOperation = "uppercase")
        assertEquals(mapOf("text" to ToolValue.StringValue("fixed v2 text"), "untrusted" to ToolValue.BooleanValue(true)),
            f.manager.invoke(nextTurn, TEXT_TOOL, ToolArguments()).values)
        val literal = "{{file:plugins/checklist.json}} 文字😀"
        assertEquals(literal, f.transformed(nextTurn, TRIM_TOOL, " \t$literal\n"))
        assertEquals("Text input exceeds 4096 UTF-8 bytes", f.rejectedTransform(nextTurn, TRIM_TOOL, "😀".repeat(1024) + " ").message)
        assertEquals(reads, f.files.reads)
        val token = nextTurn.toolRegistrations.getValue(TEXT_TOOL)
        for ((workspace, session) in listOf("a" to "s2", "b" to "other", "b" to "s")) {
            val foreign = f.context(workspace, session).copy(grantedScopes = listOf(GrantedScope("a")))
            assertTrue(f.runtime.listForSession(workspace, session).isEmpty())
            assertFalse(TEXT_TOOL in foreign.toolRegistrations)
            val forged = foreign.copy(toolRegistrations = mapOf(TEXT_TOOL to token))
            assertFailsWith<ToolManagerException> { f.manager.invoke(forged, TEXT_TOOL, ToolArguments()) }
        }
        f.assertNoApproval()
    }

    @Test
    fun `user confirmation refuses a changed source and preserves standard missing file error`() {
        val f = Fixture()
        val foreground = AuthoredPluginForeground().apply { resume(Any()); select("a", "s") }
        fun confirm() = requireNotNull(foreground.openConfirmation("a", "s"))
        f.put("reviewed")
        val digest = AuthoredPluginDrafts(f.files).preview("a", ID).sha256
        f.put("changed but valid")
        assertEquals(Code.VERSION_CHANGED, assertFailsWith<AuthoredPluginException> {
            f.runtime.activateForSession(confirm(), ID, digest) { true }
        }.code)
        val path = AuthoredPluginSource.path(ID)
        f.files.write("a", path, "not valid JSON".encodeToByteArray())
        assertEquals(Code.VERSION_CHANGED, assertFailsWith<AuthoredPluginException> {
            f.runtime.activateForSession(confirm(), ID, digest) { true }
        }.code)
        f.files.delete("a", path, "not-the-source-digest")
        assertEquals(WorkspaceFileException.Code.FILE_NOT_FOUND, assertFailsWith<WorkspaceFileException> {
            f.runtime.activateForSession(confirm(), ID, digest) { true }
        }.code)
        assertTrue(f.kernel.statuses().isEmpty())
        f.assertNoApproval()
    }

    @Test
    fun `host authorization is checked before source reads and again before commit`() {
        val f = Fixture()
        f.put("confirmed")
        val digest = AuthoredPluginDrafts(f.files).preview("a", ID).sha256
        val foreground = AuthoredPluginForeground().apply { resume(Any()); select("a", "s") }
        val denied = requireNotNull(foreground.openConfirmation("a", "s"))
        val reads = f.files.reads
        assertEquals(Code.CALLER_UNAVAILABLE, assertFailsWith<AuthoredPluginException> {
            f.runtime.activateForSession(denied, ID, digest) { false }
        }.code)
        assertEquals(reads, f.files.reads)
        assertFalse(denied.isOpen)

        var canActivate = true
        f.files.beforeRead = { canActivate = false }
        val allowedThenDenied = requireNotNull(foreground.openConfirmation("a", "s"))
        assertEquals(Code.CALLER_UNAVAILABLE, assertFailsWith<AuthoredPluginException> {
            f.runtime.activateForSession(allowedThenDenied, ID, digest) { canActivate }
        }.code)
        assertFalse(allowedThenDenied.isOpen)
        assertEquals(reads + 1, f.files.reads)
        assertTrue(f.kernel.statuses().isEmpty())
        f.assertNoApproval()
    }

    @Test
    fun `confirmation closure at the final policy check prevents permit commit`() {
        val f = Fixture()
        f.put("confirmed")
        val digest = AuthoredPluginDrafts(f.files).preview("a", ID).sha256
        val workbench = Any()
        val foreground = AuthoredPluginForeground().apply { resume(workbench); select("a", "s") }
        for (cancel in listOf<() -> Unit>(
            { foreground.select("a", "s2"); foreground.select("a", "s") },
            { foreground.pause(workbench); foreground.resume(workbench) },
            { foreground.invalidateSession("a", "s") },
        )) {
            val reads = f.files.reads
            val confirmation = requireNotNull(foreground.openConfirmation("a", "s"))
            assertEquals(Code.CALLER_UNAVAILABLE, assertFailsWith<AuthoredPluginException> {
                f.runtime.activateForSession(confirmation, ID, digest) {
                    if (f.files.reads > reads) cancel()
                    true // The host policy may allow again, but the exact permit has expired.
                }
            }.code)
            assertFalse(confirmation.isOpen)
            assertTrue(f.kernel.statuses().isEmpty())
        }
        f.assertNoApproval()
    }

    @Test
    fun `shared pending ticket is exclusive and rejects replacement after runtime invalidation`() {
        val f = Fixture()
        f.put("confirmed")
        val digest = AuthoredPluginDrafts(f.files).preview("a", ID).sha256
        val foreground = AuthoredPluginForeground().apply { resume(Any()); select("a", "s") }
        val confirmation = requireNotNull(foreground.openConfirmation("a", "s"))
        f.files.beforeRead = {
            f.files.beforeRead = null
            val agent = f.context().copy(authoredPluginTurn = requireNotNull(foreground.openTurn("a", "s")))
            assertEquals(Code.BUSY, assertFailsWith<AuthoredPluginException> {
                f.runtime.activate(agent, ID, digest)
            }.code)
            assertEquals(Code.CALLER_UNAVAILABLE, assertFailsWith<AuthoredPluginException> {
                f.runtime.activateForSession(confirmation, ID, digest) { true }
            }.code)
            assertTrue(confirmation.isOpen) // The competing caller cannot consume this claimant's lifetime.
            f.runtime.invalidateSession("a", "s") // Only the ticket is revoked here, not the permit.
            assertTrue(confirmation.isOpen)
            assertTrue(f.runtime.listForSession("a", "s").isEmpty())
        }
        assertEquals(Code.CALLER_UNAVAILABLE, assertFailsWith<AuthoredPluginException> {
            f.runtime.activateForSession(confirmation, ID, digest) { true }
        }.code)
        assertTrue(f.kernel.statuses().isEmpty())
        f.assertNoApproval()

        // In the other direction, an existing Agent request blocks local publication too.
        f.approval.decide = {
            val local = requireNotNull(foreground.openConfirmation("a", "s"))
            assertEquals(Code.BUSY, assertFailsWith<AuthoredPluginException> {
                f.runtime.activateForSession(local, ID, digest) { true }
            }.code)
            assertFalse(local.isOpen)
            ApprovalDecision(false)
        }
        assertEquals(Code.DENIED, assertFailsWith<AuthoredPluginException> {
            f.runtime.activate(f.context(), ID, digest)
        }.code)
        assertTrue(f.kernel.statuses().isEmpty())
        assertEquals(1, f.approval.requests.size)
        assertTrue(f.approval.responses.isEmpty())
    }

    @Test
    fun `ordinary model turn and local confirmation cannot be used in each other's entrypoints`() {
        val f = Fixture()
        f.put("confirmed")
        val digest = AuthoredPluginDrafts(f.files).preview("a", ID).sha256
        val foreground = AuthoredPluginForeground().apply { resume(Any()); select("a", "s") }
        val agent = requireNotNull(foreground.openTurn("a", "s"))
        val confirmation = requireNotNull(foreground.openConfirmation("a", "s"))
        val reads = f.files.reads
        assertEquals(Code.CALLER_UNAVAILABLE, assertFailsWith<AuthoredPluginException> {
            f.runtime.activateForSession(agent, ID, digest) { error("Not a confirmation permit") }
        }.code)
        assertEquals(Code.CALLER_UNAVAILABLE, assertFailsWith<AuthoredPluginException> {
            f.runtime.activate(f.context().copy(authoredPluginTurn = confirmation), ID, digest)
        }.code)
        assertTrue(agent.isOpen)
        assertTrue(confirmation.isOpen)
        assertEquals(reads, f.files.reads)
        assertTrue(f.kernel.statuses().isEmpty())
        f.assertNoApproval()
    }

    @Test
    fun `withdrawal confirmation expires on pause and cannot withdraw a replacement`() {
        val f = Fixture()
        val view = Any()
        val foreground = AuthoredPluginForeground().apply { resume(view); select("a", "s") }
        fun permit() = requireNotNull(foreground.openConfirmation("a", "s"))
        f.put("same bytes")
        val digest = AuthoredPluginDrafts(f.files).preview("a", ID).sha256
        fun activate() = f.runtime.activateForSession(permit(), ID, digest) { true }
        val first = activate()
        val expired = permit()
        foreground.pause(view)
        foreground.resume(view)
        assertEquals(Code.CALLER_UNAVAILABLE, assertFailsWith<AuthoredPluginException> {
            f.runtime.revokeConfirmed(expired, first)
        }.code)
        assertSame(first, f.runtime.listForSession("a", "s").single())
        assertSame(first, f.runtime.revokeConfirmed(permit(), first))
        val replacement = activate()
        assertEquals(Code.VERSION_CHANGED, assertFailsWith<AuthoredPluginException> {
            f.runtime.revokeConfirmed(permit(), first)
        }.code)
        assertSame(replacement, f.runtime.listForSession("a", "s").single())
    }

    @Test
    fun `user confirmation never replaces an active source version and shares the session quota`() {
        val f = Fixture()
        val foreground = AuthoredPluginForeground().apply { resume(Any()); select("a", "s") }
        fun activate(id: String): AuthoredPluginReceipt {
            val digest = AuthoredPluginDrafts(f.files).preview("a", id).sha256
            return f.runtime.activateForSession(requireNotNull(foreground.openConfirmation("a", "s")), id, digest) { true }
        }
        f.put("active version")
        val active = activate(ID)
        f.put("different source version")
        assertEquals(Code.ALREADY_ACTIVE, assertFailsWith<AuthoredPluginException> { activate(ID) }.code)
        assertSame(active, f.runtime.listForSession("a", "s").single())
        assertEquals(ToolValue.StringValue("active version"), f.manager.invoke(f.context(), TEXT_TOOL, ToolArguments()).values["text"])
        for (id in listOf("first", "second", "third")) {
            f.put("version for $id", id = id)
            activate(id)
        }
        f.put("one over limit", id = "fourth")
        assertEquals(Code.QUOTA, assertFailsWith<AuthoredPluginException> { activate("fourth") }.code)
        assertEquals(setOf(ID, "first", "second", "third"), f.runtime.listForSession("a", "s").map { it.id }.toSet())
        assertEquals(4, f.kernel.statuses().size)
        f.assertNoApproval()
    }

    private class Fixture(val files: Files = Files()) {
        val approval = Approval()
        val registry = InMemoryToolDefinitionRegistry()
        val kernel = PluginKernel()
        var foreground: Pair<String, String>? = "a" to "s"
        val runtime = AuthoredPluginRuntime(files, approval, kernel, PluginToolContributions(registry)) {
            it.allowsPluginAuthoring && (it.workspaceId to it.sessionId) == foreground
        }
        val manager = ToolManagerPlugin(registry, Preferences(), Workspaces(), { PluginState.ENABLED },
            sessionWorkspace = mapOf("s" to "a", "s2" to "a", "other" to "b")::get)

        init { registerAuthoredPluginTool(registry, runtime); manager.onEnable() }

        fun assertNoApproval() {
            assertTrue(approval.requests.isEmpty())
            assertTrue(approval.responses.isEmpty())
            assertEquals(0, approval.clears)
        }

        fun put(text: String, twoTools: Boolean = false, workspace: String = "a", id: String = ID) {
            val last = if (twoTools) "," + """{"name":"last","description":"Last","text":"last"}""" else ""
            files.write(workspace, AuthoredPluginSource.path(id),
                """{"schemaVersion":1,"id":"$id","title":"Checklist","tools":[{"name":"show","description":"Show checklist","text":"$text"}$last]}""".toByteArray())
        }

        fun putV2(trimOperation: String = "trim") {
            files.write("a", AuthoredPluginSource.path(ID), """
                {"schemaVersion":2,"id":"$ID","title":"Checklist","tools":[
                  {"name":"show","description":"Show checklist","kind":"static_text","text":"fixed v2 text"},
                  {"name":"cut","description":"INJECTED_DESCRIPTION","kind":"text_transform","operation":"$trimOperation"},
                  {"name":"up","description":"Upper","kind":"text_transform","operation":"uppercase"},
                  {"name":"down","description":"Lower","kind":"text_transform","operation":"lowercase"}
                ]}
            """.trimIndent().encodeToByteArray())
        }

        fun transformed(context: ToolInvocationContext, tool: String, input: String): String {
            val output = manager.invoke(context, tool, textArguments(input)).values
            assertEquals(ToolValue.BooleanValue(true), output["untrusted"])
            return (output.getValue("text") as ToolValue.StringValue).value
        }

        fun rejectedTransform(context: ToolInvocationContext, tool: String, input: String): ToolManagerException =
            assertFailsWith<ToolManagerException> { manager.invoke(context, tool, textArguments(input)) }.also {
                assertEquals(ToolManagerException.Code.INVALID_ARGUMENT, it.code)
            }

        fun context(workspace: String = "a", session: String = "s") = ToolInvocationContext(workspace, sessionId = session,
            allowsPluginAuthoring = true, toolRegistrations = manager.resolveForTurn(ToolActivationContext(workspace, sessionId = session))
                .associate { it.descriptor.id to requireNotNull(it.registration) })

        fun call(context: ToolInvocationContext, action: String, pluginId: String, sha: String? = null) = manager.invoke(
            context, AUTHORED_PLUGIN_TOOL_ID, ToolArguments(buildMap {
                put("action", ToolValue.StringValue(action))
                put("plugin_id", ToolValue.StringValue(pluginId))
                sha?.let { put("sha256", ToolValue.StringValue(it)) }
            }),
        )
    }

    private class Approval : ApprovalGateway {
        var decide: () -> ApprovalDecision = { ApprovalDecision(true) }
        val requests = mutableListOf<ApprovalRequest>()
        val responses = mutableListOf<Pair<String, ApprovalDecision>>()
        var clears = 0
            private set
        override fun requestApproval(request: ApprovalRequest, timeoutMillis: Long): ApprovalDecision {
            requests += request
            return decide()
        }
        override fun respond(requestId: String, decision: ApprovalDecision): Boolean {
            responses += requestId to decision
            return false // Simulate a decision/registration race with cancellation.
        }
        override fun pendingRequests() = emptyList<ApprovalRequest>()
        override fun clear() { clears++ }
    }

    private class Files : WorkspaceFileService {
        private val files = mutableMapOf<Pair<String, String>, ByteArray>()
        var reads = 0
            private set
        var beforeRead: (() -> Unit)? = null
        override fun read(workspaceId: String, relativePath: String, offset: Long, maxBytes: Int): WorkspaceFileContent {
            beforeRead?.invoke()
            reads++
            val bytes = files[workspaceId to relativePath]
                ?: throw WorkspaceFileException(WorkspaceFileException.Code.FILE_NOT_FOUND, "File does not exist")
            return WorkspaceFileContent(bytes.take(maxBytes).toByteArray(), "not-the-source-digest")
        }
        override fun write(workspaceId: String, relativePath: String, bytes: ByteArray, expectedRevision: String?): WorkspaceFileMutation {
            files[workspaceId to relativePath] = bytes.copyOf()
            return WorkspaceFileMutation()
        }
        override fun list(workspaceId: String, relativeDirectory: String, cursor: String?, limit: Int) = error("not called")
        override fun delete(workspaceId: String, relativePath: String, expectedRevision: String): WorkspaceFileMutation {
            check(expectedRevision == "not-the-source-digest")
            if (files.remove(workspaceId to relativePath) == null) {
                throw WorkspaceFileException(WorkspaceFileException.Code.FILE_NOT_FOUND, "File does not exist")
            }
            return WorkspaceFileMutation()
        }
        override fun move(workspaceId: String, sourceRelativePath: String, destinationRelativePath: String, expectedRevision: String) = error("not called")
    }

    private class Preferences : MutableToolPreferenceStore {
        override fun isEnabled(workspaceId: String, toolId: String): Boolean? = null
        override fun setEnabled(workspaceId: String, toolId: String, enabled: Boolean?) = error("not called")
    }

    private class Workspaces : WorkspaceManagerService {
        private val workspaces = listOf(Workspace("a", "A", 1L, 1L), Workspace("b", "B", 1L, 1L))
        override fun findSessionWorkspace(workspaceId: String) = get(workspaceId)
        override fun initializeDefaultIfEligible() = workspaces.first()
        override fun create(name: String) = error("not called")
        override fun get(workspaceId: String) = workspaces.find { it.id == workspaceId }
        override fun list(includeArchived: Boolean) = workspaces
        override fun rename(workspaceId: String, name: String) = error("not called")
        override fun archive(workspaceId: String) = error("not called")
        override fun restore(workspaceId: String) = error("not called")
        override fun select(workspaceId: String) = error("not called")
        override fun activeWorkspace() = workspaces.first()
        override fun resolveRoot(workspaceId: String) = error("not called")
        override fun resolveSessionWorkspace(workspaceId: String) = requireNotNull(get(workspaceId))
    }

    private companion object {
        const val ID = "checklist"
        const val TEXT_TOOL = "extension.authored.checklist.show"
        const val TRIM_TOOL = "extension.authored.checklist.cut"
        const val UPPER_TOOL = "extension.authored.checklist.up"
        const val LOWER_TOOL = "extension.authored.checklist.down"

        fun textArguments(text: String) = ToolArguments(mapOf("text" to ToolValue.StringValue(text)))
    }
}
