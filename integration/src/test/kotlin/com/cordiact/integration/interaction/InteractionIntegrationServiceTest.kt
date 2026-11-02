package com.cordiact.integration.interaction

import com.cordiact.core.conversation.ModelCall
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class InteractionIntegrationServiceTest {
    private val context = InteractionContext("session-1", "workspace-1")
    private val directory = object : MentionDirectory {
        override fun children(context: InteractionContext, parentId: String?) = emptyList<MentionDescriptor>()
        override fun find(context: InteractionContext, id: String): MentionDescriptor? = null
    }
    private val fills = InMemoryFillValueStore()
    private val modelId = ModelId("deepseek", "deepseek-flash")

    @Test
    fun missingSubmitterDoesNotCreateRunningTask() {
        val tracker = InMemoryInteractionTaskTracker()
        val service = service(tracker, commandExecutor = { _, _, _, _ ->
            error("executor must not run without a configured model submitter")
        })
        service.setEnabled(true)

        val prepared = service.prepare("/run: process", context)
        assertTrue(prepared.valid, prepared.diagnostics.toString())
        assertThrows(IllegalArgumentException::class.java) {
            service.dispatch(prepared, modelId) { }
        }

        assertTrue(tracker.active().isEmpty())
    }

    @Test
    fun synchronousCompletionDoesNotLeaveCallRegistered() {
        val tracker = InMemoryInteractionTaskTracker()
        val cancelCount = AtomicInteger()
        val submitter = InteractionModelSubmitter { _, _, onEvent ->
            onEvent(ModelEvent.Completed())
            ModelCall { cancelCount.incrementAndGet() }
        }
        val service = service(tracker, submitter) { envelope, model, submit, onEvent ->
            CommandExecutionResult(
                completed = false,
                modelCall = submit.submit(envelope, model, onEvent),
            )
        }
        service.setEnabled(true)

        val result = service.dispatch(service.prepare("/run process", context), modelId) { }
        service.cancelActiveCalls()

        assertTrue(tracker.active().isEmpty())
        assertEquals(1, cancelCount.get())
        assertNull(result.modelCall)
    }

    @Test
    fun disablingCancelsOrdinaryModelCalls() {
        val tracker = InMemoryInteractionTaskTracker()
        val cancelCount = AtomicInteger()
        val service = service(
            tracker = tracker,
            submitter = InteractionModelSubmitter { _, _, _ ->
                ModelCall { cancelCount.incrementAndGet() }
            },
            commandExecutor = { _, _, _, _ -> error("ordinary input must not dispatch commands") },
        )
        service.setEnabled(true)
        val prepared = service.prepare("hello", context)
        assertTrue(prepared.valid, prepared.diagnostics.toString())

        val call = service.submitOrdinary(prepared, modelId) { }
        service.setEnabled(false)
        call.cancel()

        assertEquals(1, cancelCount.get())
    }

    @Test
    fun disablingDuringSubmissionCancelsLateCallAndSettlesTask() {
        val tracker = InMemoryInteractionTaskTracker()
        val cancelCount = AtomicInteger()
        lateinit var service: InteractionIntegrationService
        val submitter = InteractionModelSubmitter { _, _, _ ->
            ModelCall { cancelCount.incrementAndGet() }
        }
        service = service(tracker, submitter) { envelope, model, submit, onEvent ->
            val call = submit.submit(envelope, model, onEvent)
            service.setEnabled(false)
            CommandExecutionResult(completed = false, modelCall = call)
        }
        service.setEnabled(true)
        val prepared = service.prepare("/run process", context)

        val result = service.dispatch(prepared, modelId) { }

        assertEquals(null, result.modelCall)
        assertEquals(1, cancelCount.get())
        assertTrue(tracker.active().isEmpty())
    }

    private fun service(
        tracker: InMemoryInteractionTaskTracker,
        submitter: InteractionModelSubmitter? = null,
        commandExecutor: InteractionCommandExecutor,
    ): InteractionIntegrationService {
        val registry = InteractionCommandRegistry().apply {
            register(
                CommandDescriptor(
                    id = "command.run",
                    name = "run",
                    ownerPluginId = "plugin.test",
                    executor = commandExecutor,
                ),
            )
            setOwnerEnabled("plugin.test", true)
        }
        return InteractionIntegrationService(
            commands = registry,
            mentions = directory,
            fills = fills,
            authorization = InteractionAuthorization { _, _, _ -> AuthorizationDecision(allowed = true) },
            modelSubmitter = submitter,
            taskTracker = tracker,
        )
    }
}
