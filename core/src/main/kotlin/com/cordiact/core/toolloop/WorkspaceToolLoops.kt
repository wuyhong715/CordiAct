package com.cordiact.core.toolloop

import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelMessage
import com.cordiact.core.plugin.PluginContext
import com.cordiact.core.plugin.ServiceKey
import com.cordiact.core.tool.ToolInvocationContext
import java.util.concurrent.atomic.AtomicBoolean

val TOOL_LOOP_CONTRIBUTIONS = ServiceKey("conversation.tool-loops", WorkspaceToolLoops::class.java, applicationWide = true)

/** Routes foreground and delegated turns to a trusted workspace implementation or the application default. */
class WorkspaceToolLoops : ToolLoopService {
    private class Entry(val service: ToolLoopService, val active: () -> Boolean)
    private val entries = mutableMapOf<String?, Entry>()

    fun register(publisher: PluginContext, service: ToolLoopService) {
        publisher.scope.requireOpen()
        val owner = publisher.id
        require(owner.sessionId == null) { "Tool loops are registered for the application or a workspace" }
        require(service !== this) { "Cannot register the loop router as a provider" }
        val entry = Entry(service, publisher::isContributionActive)
        synchronized(entries) {
            check(owner.workspaceId !in entries) { "A tool loop is already registered in this scope" }
            entries[owner.workspaceId] = entry
        }
        publisher.effect { synchronized(entries) { entries.remove(owner.workspaceId, entry) } }
    }

    override fun run(
        context: ToolInvocationContext,
        initialMessages: List<ModelMessage>,
        dispatcher: ToolDispatcher,
        finalizeWithoutTools: ((List<ModelMessage>) -> ToolLoopRound)?,
        send: (List<ModelMessage>) -> ToolLoopRound,
    ): ToolLoopOutcome {
        val entry = synchronized(entries) {
            entries[context.workspaceId]?.takeIf { it.active() } ?: entries[null]?.takeIf { it.active() }
        } ?: return stopped("tool_loop_unavailable", "No tool loop is available for this workspace")
        val running = AtomicBoolean(true)
        fun requireCurrent() {
            check(running.get() && entry.active()) { "Tool loop is no longer active" }
        }
        fun guard(round: (List<ModelMessage>) -> ToolLoopRound): (List<ModelMessage>) -> ToolLoopRound = { messages ->
            requireCurrent()
            round(messages).also { requireCurrent() }
        }
        return try {
            requireCurrent()
            val outcome = entry.service.run(context, initialMessages, ToolDispatcher { supplied, call ->
                requireCurrent()
                val invocation = context.copy(
                    // The loop can attach provenance or suppress duplicate events without changing
                    // the host-supplied workspace, session, credentials or tool registrations.
                    onEvent = context.onEvent.takeIf { supplied.onEvent != null },
                    historyInvocation = supplied.historyInvocation,
                )
                dispatcher.dispatch(invocation, call).also { requireCurrent() }
            }, finalizeWithoutTools?.let(::guard), guard(send))
            requireCurrent()
            outcome
        } catch (failure: Exception) {
            if (entry.active()) throw failure
            stopped("tool_loop_revoked", "Tool loop was withdrawn during this turn")
        } finally {
            running.set(false)
        }
    }

    private fun stopped(code: String, message: String) =
        ToolLoopOutcome.Stopped(ToolLoopStop.FAILED, 0, "", failure = ModelEvent.Failed(code, message))
}
