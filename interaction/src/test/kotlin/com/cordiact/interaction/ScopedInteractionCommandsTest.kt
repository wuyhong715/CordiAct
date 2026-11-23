package com.cordiact.interaction

import com.cordiact.core.conversation.ModelId
import com.cordiact.core.plugin.ContextPlugin
import com.cordiact.core.plugin.PluginContext
import com.cordiact.core.plugin.PluginInstanceId
import com.cordiact.core.plugin.PluginInstanceState
import com.cordiact.core.plugin.PluginKernel
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ScopedInteractionCommandsTest {
    private val a1 = InteractionContext("a1", "a")
    private val a2 = InteractionContext("a2", "a")
    private val b1 = InteractionContext("b1", "b")
    private val live = mutableSetOf(a1, a2, b1)
    private val registry = InteractionCommandRegistry(live::contains)
    private val adapter = PluginCommandContributions(registry)
    private val kernel = PluginKernel()
    private val directory = object : MentionDirectory {
        override fun children(context: InteractionContext, parentId: String?) = emptyList<MentionDescriptor>()
        override fun find(context: InteractionContext, id: String): MentionDescriptor? = null
    }
    private val fills = InMemoryFillValueStore()
    private val model = ModelId("fixture", "fixture")

    @Test fun `completion parsing and dispatch share the persisted workspace and session view`() {
        registry.register(command("host", "global", "global"))
        mount(PluginInstanceId("demo", "a"), command("demo", "project", "workspace-a"))
        mount(PluginInstanceId("demo", "a", "a1"), command("demo", "status", "session-a1"))
        mount(PluginInstanceId("demo", "b", "b1"), command("demo", "status", "session-b1"))
        val service = service()
        assertEquals(listOf("global"), service.commandCandidates().map { it.name })
        assertEquals(listOf("global", "project", "status"), service.commandCandidates(a1).map { it.name })
        assertEquals(setOf("/project", "/project-alias"), completions("project", a2))
        assertEquals(emptySet<String>(), completions("project", b1))
        assertEquals(emptySet<String>(), completions("status", a2))
        assertEquals("session-a1", service.dispatch(service.prepare("/status-alias", a1), model) { }.message)
        assertEquals("session-b1", service.dispatch(service.prepare("/status-alias", b1), model) { }.message)
        assertFalse(service.prepare("/status-alias", a2).valid)
        val wrongWorkspace = InteractionContext("b1", "a")
        assertEquals(listOf("global"), service.commandCandidates(wrongWorkspace).map { it.name })
        assertFalse(service.prepare("/project", wrongWorkspace).valid)
        val prepared = service.prepare("/project", a2)
        live.remove(a2)
        assertEquals(emptySet<String>(), completions("project", a2))
        assertThrows(IllegalStateException::class.java) { service.dispatch(prepared, model) { } }
    }

    @Test fun `bundled commands retain their global names and get a new registration on reenable`() {
        val service = service()
        val plugin = InteractionPlugin(service, registry, kernel, listOf(
            command(INTERACTION_PLUGIN_ID, "compact", "local").copy(aliases = setOf("压缩")),
        ))
        assertNull(registry.find("压缩"))
        plugin.onEnable()
        val first = service.prepare("/压缩", a1)
        assertEquals("local", service.dispatch(first, model) { }.message)
        plugin.onDisable()
        assertNull(registry.find("compact"))
        assertNull(registry.find("压缩"))
        plugin.onEnable()
        assertThrows(IllegalStateException::class.java) { service.dispatch(first, model) { } }
        assertEquals("local", service.dispatch(service.prepare("/compact", b1), model) { }.message)
    }

    @Test fun `partially registered commands and aliases stay hidden on startup failure`() {
        registry.register(command("host", "global", "global").copy(aliases = setOf("occupied")))
        val id = PluginInstanceId("demo", "a")
        kernel.register(id, object : ContextPlugin {
            override fun start(context: PluginContext) {
                adapter.register(context, command("demo", "first", "first"))
                assertNull(registry.find("first", a1))
                assertNull(registry.find("first-alias", a1))
                adapter.register(context, command("demo", "second", "second").copy(aliases = setOf("occupied")))
            }
        })
        kernel.enable(id)
        assertEquals(PluginInstanceState.FAILED, kernel.status(id).state)
        assertEquals(listOf("global"), registry.candidates(a1).map { it.name })
        assertEquals("global", registry.find("occupied", a1)?.name)
        mount(PluginInstanceId("demo", "a", "a1"), command("demo", "first", "replacement"))
        assertEquals("first", registry.find("first-alias", a1)?.name)
    }

    @Test fun `overlapping workspace and session registrations cannot shadow canonical names or ids`() {
        mount(PluginInstanceId("demo", "a"), command("demo", "project", "old"))
        val one = PluginInstanceId("demo", "a", "a1")
        mount(one, command("demo", "project-alias", "conflict"))
        assertEquals(PluginInstanceState.FAILED, kernel.status(one).state)
        val two = PluginInstanceId("demo", "a", "a2")
        mount(two, command("demo", "different", "conflict").copy(id = "extension.demo.project"))
        assertEquals(PluginInstanceState.FAILED, kernel.status(two).state)
        assertFalse(registry.unregister("extension.demo.project"))
        assertEquals("project", registry.find("project-alias", a2)?.name)
    }

    @Test fun `a value-equal replacement does not admit a stale prepared command`() {
        val id = PluginInstanceId("demo", "a", "a1")
        val descriptor = command("demo", "status", "session")
        mount(id, descriptor)
        val service = service()
        val stale = service.prepare("/status-alias", a1)
        kernel.restart(id)
        val current = service.prepare("/status-alias", a1)
        assertEquals(stale.envelope?.command, current.envelope?.command)
        assertNotSame(stale.envelope?.command, current.envelope?.command)
        assertThrows(IllegalStateException::class.java) { service.dispatch(stale, model) { } }
        assertEquals("session", service.dispatch(current, model) { }.message)
    }

    @Test fun `withdrawal while dispatch authorization is in progress never enters the executor`() {
        val id = PluginInstanceId("demo", "a", "a1")
        val descriptor = command("demo", "status", "session").copy(executor =
            InteractionCommandExecutor { _, _, _, _ -> error("revoked command must not run") })
        mount(id, descriptor)
        val service = service(InteractionAuthorization { _, _, _ ->
            kernel.disable(id)
            AuthorizationDecision(true)
        })
        val prepared = service.prepare("/status", a1)
        val failure = assertThrows(IllegalStateException::class.java) { service.dispatch(prepared, model) { } }
        assertTrue(failure.message.orEmpty().contains("registration is no longer active"))
        assertNull(registry.find("status-alias", a1))
    }

    @Test fun `kernel closes the contribution before any slow plugin cleanup is invoked`() {
        val id = PluginInstanceId("demo", "a", "a1")
        kernel.register(id, object : ContextPlugin {
            override fun start(context: PluginContext) {
                adapter.register(context, command("demo", "status", "session"))
                context.effect {
                    assertNull(registry.find("status", a1))
                    assertNull(registry.find("status-alias", a1))
                }
            }
        })
        kernel.enable(id)
        assertNotNull(registry.find("status-alias", a1))
        kernel.disable(id)
    }

    @Test fun `withdrawn command cannot use a retained submitter after its executor is entered`() {
        val id = PluginInstanceId("demo", "a", "a1")
        mount(id, command("demo", "status", "session").copy(executor =
            InteractionCommandExecutor { envelope, model, submitter, onEvent ->
                kernel.disable(id)
                CommandExecutionResult(false, modelCall = submitter.submit(envelope, model, null, onEvent))
            }))
        val service = service()
        val prepared = service.prepare("/status", a1)
        val failure = assertThrows(IllegalStateException::class.java) { service.dispatch(prepared, model) { } }
        assertTrue(failure.message.orEmpty().contains("registration is no longer active"))
    }

    @Test fun `only exact host-owned local registration runs without a model or executor`() {
        val id = PluginInstanceId("demo", "a")
        val descriptor = command("demo", "project", "must not call executor").copy(executor =
            InteractionCommandExecutor { _, _, _, _ -> error("local path must not call executor") })
        kernel.register(id, object : ContextPlugin {
            override fun start(context: PluginContext) = adapter.registerDeclarativePackageCommand(context, descriptor, { true }, "literal @not-a-reference")
        })
        kernel.enable(id)
        registry.register(command("host", "global", "not local"))
        val service = InteractionService(registry, directory, fills, InteractionAuthorization { _, _, _ -> AuthorizationDecision(true) }).also { it.setEnabled(true) }
        for (text in listOf("ordinary text", "/global", "/pro", "/project @other", "/project:body", "/project:")) {
            assertNull(service.prepareLocalCommand(text, a1), text)
        }
        assertNull(service.prepareLocalCommand("/project", b1))
        val prepared = requireNotNull(service.prepareLocalCommand("  /project-alias  ", a2))
        assertEquals("literal @not-a-reference", service.dispatchLocalCommand(prepared).message)
        val copied = prepared.copy(envelope = prepared.envelope!!.copy(command = prepared.envelope.command!!.copy()))
        assertThrows(IllegalStateException::class.java) { service.dispatchLocalCommand(copied) }
        kernel.restart(id)
        assertThrows(IllegalStateException::class.java) { service.dispatchLocalCommand(prepared) }
        assertNotNull(service.prepareLocalCommand("/project", a2))
    }

    @Test fun `local dispatch rechecks the registration after authorization`() {
        val id = PluginInstanceId("demo", "a")
        kernel.register(id, object : ContextPlugin {
            override fun start(context: PluginContext) = adapter.registerDeclarativePackageCommand(context, command("demo", "project", "executor"), { true }, "literal")
        })
        kernel.enable(id)
        val denied = InteractionService(registry, directory, fills, InteractionAuthorization { _, _, _ -> AuthorizationDecision(true, requiresConfirmation = true) }).also { it.setEnabled(true) }
        val prepared = requireNotNull(denied.prepareLocalCommand("/project", a1))
        assertThrows(IllegalStateException::class.java) { denied.dispatchLocalCommand(prepared) }
        val service = InteractionService(registry, directory, fills, InteractionAuthorization { _, _, _ ->
            kernel.disable(id)
            AuthorizationDecision(true)
        }).also { it.setEnabled(true) }
        assertThrows(IllegalStateException::class.java) { service.dispatchLocalCommand(prepared) }
        assertNull(service.prepareLocalCommand("/project", a1))
    }

    private fun mount(id: PluginInstanceId, descriptor: CommandDescriptor) {
        kernel.register(id, object : ContextPlugin {
            override fun start(context: PluginContext) = adapter.register(context, descriptor)
        })
        kernel.enable(id)
    }

    private fun command(owner: String, name: String, result: String) = CommandDescriptor(
        "extension.$owner.$name", name, owner, aliases = setOf("$name-alias"), allowsEmptyBody = true,
        executor = InteractionCommandExecutor { _, _, _, _ -> CommandExecutionResult(true, result) },
    )

    private fun completions(query: String, context: InteractionContext) = completionCandidates(
        InputCompletionTarget('/', 0, 1, query), registry, directory, fills, context,
    ).map { it.label }.toSet()

    private fun service(auth: InteractionAuthorization = InteractionAuthorization { _, _, _ -> AuthorizationDecision(true) }) =
        InteractionService(registry, directory, fills, auth, InteractionModelSubmitter { _, _, _, _ ->
            error("fixture commands do not submit to the model")
        }).also { it.setEnabled(true) }

    init {
        registry.setOwnerEnabled("host", true)
        registry.setOwnerEnabled("demo", true)
    }
}
