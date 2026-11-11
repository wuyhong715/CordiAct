package com.cordiact.interaction

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

class InteractionServiceTest {
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
        val submitter = InteractionModelSubmitter { _, _, _, onEvent ->
            onEvent(ModelEvent.Completed())
            ModelCall { cancelCount.incrementAndGet() }
        }
        val service = service(tracker, submitter) { envelope, model, submit, onEvent ->
            CommandExecutionResult(
                completed = false,
                modelCall = submit.submit(envelope, model, null, onEvent),
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
            submitter = InteractionModelSubmitter { _, _, _, _ ->
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
        lateinit var service: InteractionService
        val submitter = InteractionModelSubmitter { _, _, _, _ ->
            ModelCall { cancelCount.incrementAndGet() }
        }
        service = service(tracker, submitter) { envelope, model, submit, onEvent ->
            val call = submit.submit(envelope, model, null, onEvent)
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

    @Test
    fun ordinarySubmissionCarriesSamplingOverridesToTheSubmitter() {
        val tracker = InMemoryInteractionTaskTracker()
        val seen = java.util.concurrent.atomic.AtomicReference<com.cordiact.core.conversation.GenerationParameters?>()
        val submitter = InteractionModelSubmitter { _, _, parameters, _ ->
            seen.set(parameters)
            ModelCall { }
        }
        val service = service(tracker, submitter) { _, _, _, _ -> error("command path not used") }
        service.setEnabled(true)
        val prepared = service.prepare("hello", context)
        val overrides = com.cordiact.core.conversation.GenerationParameters(
            temperature = 0.6,
            maxOutputTokens = 700,
            topP = 0.75,
        )

        service.submitOrdinary(prepared, modelId, overrides) { }

        assertEquals(overrides, seen.get())
    }

    @Test
    fun ordinarySubmissionWithoutOverridesPassesNullSoProviderDefaultsApply() {
        val tracker = InMemoryInteractionTaskTracker()
        val seen = java.util.concurrent.atomic.AtomicReference<com.cordiact.core.conversation.GenerationParameters?>(
            com.cordiact.core.conversation.GenerationParameters(temperature = 1.5),
        )
        var called = false
        val submitter = InteractionModelSubmitter { _, _, parameters, _ ->
            called = true
            seen.set(parameters)
            ModelCall { }
        }
        val service = service(tracker, submitter) { _, _, _, _ -> error("command path not used") }
        service.setEnabled(true)
        val prepared = service.prepare("hello", context)

        service.submitOrdinary(prepared, modelId) { }

        assertTrue(called)
        assertNull(seen.get())
    }

    @Test
    fun attachmentOnlySubmissionForwardsPartsAndTuning() {
        val tracker = InMemoryInteractionTaskTracker()
        val seen = java.util.concurrent.atomic.AtomicReference<InteractionEnvelope>()
        val seenParameters = java.util.concurrent.atomic.AtomicReference<com.cordiact.core.conversation.GenerationParameters?>()
        val submitter = InteractionModelSubmitter { envelope, _, overrides, _ ->
            seen.set(envelope); seenParameters.set(overrides); ModelCall { }
        }
        val service = service(tracker, submitter) { _, _, _, _ -> error("command path not used") }
        service.setEnabled(true)
        val attachment = com.cordiact.core.attachment.MessagePart.Attachment(
            com.cordiact.core.attachment.AttachmentId("att-test"), com.cordiact.core.attachment.Interpretation.VISION)
        val prepared = service.prepare("", context, listOf(attachment))
        assertTrue(prepared.valid)
        val overrides = com.cordiact.core.conversation.GenerationParameters(reasoningEffort = com.cordiact.core.conversation.ReasoningEffort.MAX)
        service.submitOrdinary(prepared, modelId, overrides, attachments = listOf(attachment)) { }
        assertEquals(listOf(attachment), seen.get().attachments)
        assertEquals(overrides, seenParameters.get())
        assertTrue(service.prepare("", context).diagnostics.any { it.code == "message_empty" })
    }

    private fun service(
        tracker: InMemoryInteractionTaskTracker,
        submitter: InteractionModelSubmitter? = null,
        commandExecutor: InteractionCommandExecutor,
    ): InteractionService {
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
        return InteractionService(
            commands = registry,
            mentions = directory,
            fills = fills,
            authorization = InteractionAuthorization { _, _, _ -> AuthorizationDecision(allowed = true) },
            modelSubmitter = submitter,
            taskTracker = tracker,
        )
    }
}
