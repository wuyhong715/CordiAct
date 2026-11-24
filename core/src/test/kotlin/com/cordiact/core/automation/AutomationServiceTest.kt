package com.cordiact.core.automation

import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutomationServiceTest {

    @Test fun unresolvedToolSurvivesRestartAndIsNeverQueued() {
        val file = tempFile()
        val service = AutomationService(file)
        val definition = service.save(task(trigger = interval()))
        val run = service.runNow("w1", definition.id)
        assertNotNull(service.claim(run.id))
        val token = assertNotNull(service.beginTool(run.id))
        assertFailsWith<IllegalArgumentException> { service.finish(run.id, TaskRunState.SUCCEEDED) }
        service.executionStage(run.id, TaskExecutionStage.WAITING_USER)
        assertFailsWith<IllegalArgumentException> { service.executionStage(run.id, TaskExecutionStage.MODEL) }
        assertFalse(service.completeTool(run.id, "stale-token"))
        val reopened = AutomationService(file)
        assertEquals(token, reopened.runs("w1", definition.id).single().executionCheckpoint?.pendingToolToken)
        assertEquals(1, reopened.recoverInterrupted())
        val recovered = reopened.runs("w1", definition.id).single()
        assertEquals(TaskRunState.INTERRUPTED, recovered.state)
        assertTrue(recovered.diagnostic.orEmpty().contains("external effects unknown"))
        assertNull(reopened.claim(run.id))
        assertNull(reopened.beginTool(run.id))
        assertTrue(recovered.externalResultUnknown)
        assertFailsWith<UnconfirmedAutomationResultException> { reopened.runNow("w1", definition.id) }
        val confirmed = reopened.runNow("w1", definition.id, setOf(recovered.id))
        assertTrue(confirmed.id != recovered.id)
        assertEquals(2, reopened.runs("w1", definition.id).size)
    }

    @Test fun recoveredUnknownConsumesMissedIntervalsWithoutQueuingOrBlockingOtherTasks() {
        val file = tempFile()
        val initial = 1_700_000_000_000L
        val step = 15 * 60_000L
        var clock = initial
        val service = AutomationService(file) { clock }
        val blocked = service.save(task(trigger = interval()))
        clock += step
        val first = service.enqueueDue().single()
        assertNotNull(service.claim(first.id))
        assertNotNull(service.beginTool(first.id))
        val other = service.save(task(id = "t2", trigger = interval()))
        val otherWorkspace = service.save(task(id = "t3", workspaceId = "w2", trigger = interval()))

        clock += 3 * step + 1_000L
        val recovered = AutomationService(file) { clock }
        assertEquals(1, recovered.recoverInterrupted())
        val overdue = assertNotNull(recovered.task("w1", blocked.id))
        assertTrue(recovered.enqueueDue { false }.isEmpty())
        assertEquals(overdue, recovered.task("w1", blocked.id), "disabled workspaces do not consume a due occurrence")

        assertEquals(setOf(other.id, otherWorkspace.id), recovered.enqueueDue().map { it.taskId }.toSet())
        val advanced = assertNotNull(recovered.task("w1", blocked.id))
        assertTrue(advanced.enabled)
        assertEquals(initial + 5 * step, advanced.nextFireAt)
        assertEquals(overdue.revision + 1, advanced.revision)
        assertEquals(advanced.nextFireAt, recovered.nextWakeAt())
        assertTrue(recovered.enqueueDue().isEmpty())

        val reopened = AutomationService(file) { clock }
        assertEquals(advanced, reopened.task("w1", blocked.id))
        assertTrue(reopened.enqueueDue().isEmpty())
        clock = assertNotNull(advanced.nextFireAt)
        assertTrue(reopened.enqueueDue().isEmpty())
        assertEquals(initial + 6 * step, reopened.nextWakeAt())
        val prior = reopened.runs("w1", blocked.id).single()
        assertEquals(first.id, prior.id)
        assertTrue(prior.externalResultUnknown)
        assertNull(reopened.claim(first.id))
        assertFailsWith<UnconfirmedAutomationResultException> { reopened.runNow("w1", blocked.id) }
    }

    @Test fun unknownConsumesOneTimeOccurrenceDurably() {
        val file = tempFile()
        var clock = 1_700_000_000_000L
        val service = AutomationService(file) { clock }
        val at = clock + 60_000L
        val saved = service.save(task(trigger = TaskTrigger(TriggerKind.ONCE, atMillis = at)))
        val manual = service.runNow("w1", saved.id)
        assertNotNull(service.claim(manual.id))
        assertNotNull(service.beginTool(manual.id))
        assertTrue(assertNotNull(service.finish(manual.id, TaskRunState.FAILED)).externalResultUnknown)

        clock = at
        assertTrue(service.enqueueDue().isEmpty())
        val reopened = AutomationService(file) { clock }
        val spent = assertNotNull(reopened.task("w1", saved.id))
        assertFalse(spent.enabled)
        assertNull(spent.nextFireAt)
        assertNull(reopened.nextWakeAt())
        assertTrue(reopened.enqueueDue().isEmpty())
        assertEquals(listOf(manual.id), reopened.runs("w1", saved.id).map { it.id })
    }

    @Test fun manualConfirmationPersistsOnlyTheNewRunNotPermissionForLaterRuns() {
        val file = tempFile()
        var clock = 1_700_000_000_000L
        val service = AutomationService(file) { clock }
        val saved = service.save(task(trigger = interval()))
        val original = service.runNow("w1", saved.id)
        assertNotNull(service.claim(original.id))
        val token = assertNotNull(service.beginTool(original.id))
        assertTrue(assertNotNull(service.cancel("w1", original.id)).externalResultUnknown)
        assertFailsWith<UnconfirmedAutomationResultException> {
            service.runNow("w1", saved.id, setOf("stale-id"))
        }
        val confirmed = service.runNow("w1", saved.id, setOf(original.id))
        assertTrue(original.id != confirmed.id)

        val reopened = AutomationService(file) { clock }
        assertEquals(2, reopened.runs("w1", saved.id).size)
        assertEquals(confirmed, reopened.runs("w1", saved.id).single { it.id == confirmed.id })
        val prior = reopened.runs("w1", saved.id).single { it.id == original.id }
        assertTrue(prior.externalResultUnknown)
        assertEquals(token, prior.executionCheckpoint?.pendingToolToken)
        assertNull(reopened.claim(original.id))
        assertNotNull(reopened.claim(confirmed.id), "the explicitly queued new run remains legitimate after restart")
        assertNotNull(reopened.finish(confirmed.id, TaskRunState.SUCCEEDED))

        clock = assertNotNull(saved.nextFireAt)
        val afterSuccess = AutomationService(file) { clock }
        assertTrue(afterSuccess.enqueueDue().isEmpty(), "one manual confirmation and success cannot unblock automatic runs")
        assertFailsWith<UnconfirmedAutomationResultException> { afterSuccess.runNow("w1", saved.id) }
        assertEquals(2, afterSuccess.runs("w1", saved.id).size)
    }

    @Test fun lateToolReceiptCanRemoveBlockWithoutReplayingConsumedOccurrence() {
        val file = tempFile()
        var clock = 1_700_000_000_000L
        val service = AutomationService(file) { clock }
        val saved = service.save(task(trigger = interval()))
        val original = service.runNow("w1", saved.id)
        assertNotNull(service.claim(original.id))
        val token = assertNotNull(service.beginTool(original.id))
        assertTrue(assertNotNull(service.cancel("w1", original.id)).externalResultUnknown)

        clock = assertNotNull(saved.nextFireAt)
        assertTrue(service.enqueueDue().isEmpty())
        val next = assertNotNull(service.nextWakeAt())
        assertTrue(service.completeTool(original.id, token))
        val reopened = AutomationService(file) { clock }
        assertFalse(reopened.runs("w1", saved.id).single().externalResultUnknown)
        assertTrue(reopened.enqueueDue().isEmpty())
        clock = next
        val scheduled = reopened.enqueueDue().single()
        assertEquals(next, scheduled.scheduledAt)
        assertTrue(scheduled.id != original.id)
        assertNull(reopened.claim(original.id))
    }

    @Test fun historyRetentionCannotForgetUnknownAndUnblockTask() {
        val file = tempFile()
        var clock = 1_700_000_000_000L
        val service = AutomationService(file) { clock }
        val blocked = service.save(task(trigger = interval()))
        val other = service.save(task(id = "t2", workspaceId = "w2", trigger = interval()).copy(enabled = false))
        val original = service.runNow("w1", blocked.id)
        assertNotNull(service.claim(original.id))
        assertNotNull(service.beginTool(original.id))
        assertTrue(assertNotNull(service.finish(original.id, TaskRunState.FAILED)).externalResultUnknown)
        val ordinary = service.runNow("w2", other.id)
        assertNotNull(service.claim(ordinary.id))
        assertNotNull(service.finish(ordinary.id, TaskRunState.SUCCEEDED))

        // Seed an over-limit v1 file without thousands of fsyncs. The oldest
        // run (the unknown one) would be the first eviction under the old policy.
        val root = JsonParser.parseString(file.readText()).asJsonObject
        val history = root.getAsJsonArray("runs")
        val template = history.single { it.asJsonObject.get("id").asString == ordinary.id }.asJsonObject
        repeat(1000) { n ->
            history.add(template.deepCopy().apply {
                addProperty("id", "finished-$n")
                addProperty("finishedAt", clock + 1L + n)
            })
        }
        file.writeText(root.toString())

        clock = assertNotNull(blocked.nextFireAt)
        val reopened = AutomationService(file) { clock }
        assertEquals(999, reopened.runs("w2", other.id).size, "ordinary history still obeys the retention limit")
        assertTrue(reopened.enqueueDue().isEmpty())
        // enqueueDue saved the consumed schedule and the trimmed history again.
        val persisted = AutomationService(file) { clock }
        assertEquals(original.id, persisted.runs("w1", blocked.id).single().id)
        assertTrue(persisted.runs("w1", blocked.id).single().externalResultUnknown)
        assertFailsWith<UnconfirmedAutomationResultException> { persisted.runNow("w1", blocked.id) }
        assertEquals(999, persisted.runs("w2", other.id).size)
    }

    @Test fun completedDispatchClearsBoundaryButDoesNotReviveCancelledRun() {
        val service = AutomationService(tempFile())
        val definition = service.save(task(trigger = interval()))
        val run = service.runNow("w1", definition.id)
        service.claim(run.id)
        val token = assertNotNull(service.beginTool(run.id))
        assertFailsWith<IllegalStateException> { service.beginTool(run.id) }
        service.cancel("w1", run.id)
        assertTrue(service.completeTool(run.id, token))
        val cancelled = service.runs("w1", definition.id).single()
        assertEquals(TaskRunState.CANCELLED, cancelled.state)
        assertNull(cancelled.executionCheckpoint?.pendingToolToken)
        assertFalse(service.completeTool(run.id, token))
        assertFalse(service.executionStage(run.id, TaskExecutionStage.MODEL))
    }

    private fun tempFile(name: String = "automation.json"): File =
        Files.createTempDirectory("cordiact-automation-test").resolve(name).toFile()

    private fun task(
        id: String = "t1",
        workspaceId: String = "w1",
        name: String = "Task",
        prompt: String = "Do the thing",
        trigger: TaskTrigger,
        destination: TaskDestination = TaskDestination.NEW_SESSION,
        targetSessionId: String? = null,
        maxDurationMinutes: Int = 10,
        maxToolRounds: Int = 12,
    ): AutomationTask = AutomationTask(
        id = id,
        workspaceId = workspaceId,
        name = name,
        prompt = prompt,
        trigger = trigger,
        destination = destination,
        targetSessionId = targetSessionId,
        maxDurationMinutes = maxDurationMinutes,
        maxToolRounds = maxToolRounds,
    )

    private fun interval(minutes: Long = 15L) =
        TaskTrigger(TriggerKind.INTERVAL, intervalMinutes = minutes)

    @Test
    fun uiCreatesTaskFromBlankIdAndPersistsIt() {
        val file = tempFile()
        val service = AutomationService(file) { 1_700_000_000_000L }
        val created = service.save(task(trigger = interval()).copy(id = ""))
        assertTrue(created.id.isNotBlank())
        assertEquals(created, AutomationService(file).tasks("w1").single())
    }

    @Test
    fun targetCommandOnceRunCannotBeReplayedByClockWake() {
        val clock = 1_700_000_000_000L
        val service = AutomationService(tempFile()) { clock }
        val saved = service.save(task(trigger = TaskTrigger(TriggerKind.ONCE, atMillis = clock)).copy(enabled = false))
        val run = service.runNow(saved.workspaceId, saved.id)
        assertNotNull(service.claim(run.id))
        service.finish(run.id, TaskRunState.SUCCEEDED)
        assertTrue(service.enqueueDue().isEmpty())
        assertEquals(1, service.runs(saved.workspaceId, saved.id).size)
    }

    @Test
    fun runNowReservesNewSessionDurablyAndDeduplicatesActiveRun() {
        val file = tempFile()
        var clock = 1_700_000_000_000L
        val service = AutomationService(file) { clock }

        val saved = service.save(task(trigger = TaskTrigger(TriggerKind.ONCE, atMillis = clock + 3_600_000L)))
        val first = service.runNow("w1", saved.id)
        val second = service.runNow("w1", saved.id)

        assertEquals(first.id, second.id, "an active run must block a second one")
        assertEquals(TaskRunState.QUEUED, first.state)
        assertNotNull(first.sessionId)
        assertEquals(1, service.runs("w1", saved.id).size)

        // Reservation is on disk before runNow returns: a fresh process sees it.
        val reopened = AutomationService(file) { clock }
        val persisted = reopened.runs("w1").single()
        assertEquals(first.sessionId, persisted.sessionId)
        assertEquals(TaskRunState.QUEUED, persisted.state)
        assertTrue(file.readText().contains(first.sessionId!!))
    }

    @Test
    fun enqueueDueCollapsesMissedIntervalsToSingleCatchUpRun() {
        var clock = 1_700_000_000_000L
        val service = AutomationService(tempFile()) { clock }
        val saved = service.save(task(trigger = interval(15)))
        val firstNext = saved.nextFireAt!!
        assertEquals(clock + 15 * 60_000L, firstNext)

        clock += 10 * 60 * 60 * 1000L

        // A paused workspace neither enqueues nor burns the missed occurrence.
        assertEquals(0, service.enqueueDue { false }.size)
        assertEquals(firstNext, service.task("w1", saved.id)!!.nextFireAt)

        val created = service.enqueueDue()
        assertEquals(1, created.size, "missed occurrences must collapse into one run")
        assertEquals(firstNext, created.single().scheduledAt)

        val after = service.task("w1", saved.id)!!
        assertTrue(after.nextFireAt!! > clock)
        assertEquals(2L, after.revision)
        assertEquals(after.nextFireAt, service.nextWakeAt())

        assertEquals(0, service.enqueueDue().size, "same due time must not fire twice")
    }

    @Test
    fun oneTimeTaskDisablesAfterDueAndRecurringTargetStaysFuture() {
        var clock = 1_700_000_000_000L
        val service = AutomationService(tempFile()) { clock }
        val at = clock + 60_000L
        val saved = service.save(task(trigger = TaskTrigger(TriggerKind.ONCE, atMillis = at)))
        assertEquals(at, saved.nextFireAt)

        assertEquals(0, service.enqueueDue().size)
        clock = at
        val created = service.enqueueDue()
        assertEquals(1, created.size)
        assertEquals(at, created.single().scheduledAt)

        val after = service.task("w1", saved.id)!!
        assertFalse(after.enabled)
        assertNull(after.nextFireAt)
        assertEquals(0, service.enqueueDue().size)
        assertNull(service.nextWakeAt())
        assertFailsWith<IllegalArgumentException> { service.setEnabled("w1", saved.id, true) }
        assertTrue(service.enqueueDue().isEmpty())
    }

    @Test
    fun casRejectsStaleRevisionAndRenameKeepsSchedule() {
        var clock = 1_700_000_000_000L
        val service = AutomationService(tempFile()) { clock }
        val saved = service.save(task(trigger = interval(30)))
        val originalNext = saved.nextFireAt!!
        clock += 1_000L

        val renamed = service.save(saved.copy(name = "Renamed"), expectedRevision = saved.revision)
        assertEquals(2L, renamed.revision)
        assertEquals(originalNext, renamed.nextFireAt, "name-only edit must not move the schedule")

        assertFailsWith<TaskRevisionConflictException> {
            service.save(renamed.copy(name = "Stale"), expectedRevision = 1L)
        }
        assertFailsWith<IllegalArgumentException> {
            service.save(renamed.copy(name = "No CAS"))
        }

        // A name-only edit while paused also keeps the stored schedule.
        val paused = service.setEnabled("w1", saved.id, false)
        val pausedNext = paused.nextFireAt
        val pausedRenamed = service.save(paused.copy(name = "Paused rename"), expectedRevision = paused.revision)
        assertEquals(pausedNext, pausedRenamed.nextFireAt)

        val latest = service.task("w1", saved.id)!!
        assertEquals("Paused rename", latest.name)
        assertEquals(4L, latest.revision)
    }

    @Test
    fun recoverInterruptedMarksRunningAndRetainsQueued() {
        val file = tempFile()
        var clock = 1_700_000_000_000L
        val service = AutomationService(file) { clock }
        val taskA = service.save(task(id = "a", trigger = interval()))
        val taskB = service.save(task(id = "b", trigger = interval()))
        val runA = service.runNow("w1", taskA.id)
        val runB = service.runNow("w1", taskB.id)
        assertNotNull(service.claim(runA.id))

        val reopened = AutomationService(file) { clock }
        assertEquals(1, reopened.recoverInterrupted())

        val recoveredA = reopened.runs("w1", "a").single()
        assertEquals(TaskRunState.INTERRUPTED, recoveredA.state)
        assertNotNull(recoveredA.finishedAt)
        assertEquals(TaskRunState.QUEUED, reopened.runs("w1", "b").single().state)
        assertEquals(0, reopened.recoverInterrupted(), "QUEUED runs must not be interrupted")

        assertNotNull(reopened.claim(runB.id))
        assertEquals(TaskRunState.RUNNING, reopened.runs("w1", "b").single().state)
    }

    @Test
    fun pausingCancelsQueuedRunsButLeavesHistory() {
        val service = AutomationService(tempFile()) { 1_700_000_000_000L }
        service.save(task(trigger = interval()))
        val run = service.runNow("w1", "t1")
        service.setEnabled("w1", "t1", false)
        assertEquals(TaskRunState.CANCELLED, service.runs("w1").single().state)
        assertEquals(run.id, service.runs("w1").single().id)
    }

    @Test
    fun deleteRefusesActiveRunThenRemovesTerminalHistory() {
        val service = AutomationService(tempFile()) { 1_700_000_000_000L }
        service.save(task(trigger = interval()))
        val run = service.runNow("w1", "t1")
        assertFailsWith<IllegalArgumentException> { service.delete("w1", "t1") }
        service.cancel("w1", run.id)
        service.delete("w1", "t1")
        assertTrue(service.tasks("w1").isEmpty())
        assertTrue(service.runs("w1").isEmpty())
    }

    @Test
    fun cancelWinsOverLateFinish() {
        var clock = 1_700_000_000_000L
        val service = AutomationService(tempFile()) { clock }
        val saved = service.save(task(trigger = interval()))
        val run = service.runNow("w1", saved.id)
        assertNull(service.finish(run.id, TaskRunState.SUCCEEDED), "a queued run cannot finish")

        assertNotNull(service.claim(run.id))
        clock += 5_000L
        val cancelled = service.cancel("w1", run.id)
        assertNotNull(cancelled)
        assertEquals(TaskRunState.CANCELLED, cancelled.state)
        assertNotNull(cancelled.finishedAt)

        assertNull(service.finish(run.id, TaskRunState.SUCCEEDED, summary = "late"))
        assertEquals(TaskRunState.CANCELLED, service.runs("w1").single().state)
        assertNull(service.cancel("w1", run.id), "terminal runs cannot be cancelled again")
    }

    @Test
    fun rejectsInvalidTriggerAndTaskFieldsWithoutWriting() {
        val file = tempFile()
        val service = AutomationService(file) { 1_700_000_000_000L }
        var counter = 0

        fun reject(block: (String) -> AutomationTask) {
            val id = "bad-${counter++}"
            assertFailsWith<IllegalArgumentException> { service.save(block(id)) }
        }

        reject { task(id = it, trigger = interval(14)) }
        reject {
            task(
                id = it,
                trigger = TaskTrigger(TriggerKind.DAILY, zoneId = "Mars/Olympus", localTime = "09:00"),
            )
        }
        reject {
            task(
                id = it,
                trigger = TaskTrigger(TriggerKind.WEEKLY, weekdays = setOf(8)),
            )
        }
        reject { task(id = it, name = "  ", trigger = interval()) }
        reject { task(id = it, prompt = " ", trigger = interval()) }
        reject { task(id = it, trigger = interval(), maxDurationMinutes = 0) }
        reject { task(id = it, trigger = interval(), maxDurationMinutes = 61) }
        reject { task(id = it, trigger = interval(), maxToolRounds = 0) }
        reject { task(id = it, trigger = interval(), maxToolRounds = 51) }
        reject { task(id = it, trigger = TaskTrigger(TriggerKind.ONCE)) }
        reject {
            task(
                id = it,
                trigger = interval(),
                destination = TaskDestination.EXISTING_SESSION,
                targetSessionId = null,
            )
        }

        assertFalse(file.exists(), "rejected definitions must not create a store")
    }

    @Test
    fun corruptOrUnknownVersionStoreFailsClosedAndIsNotOverwritten() {
        val corrupt = tempFile("corrupt.json")
        val corruptText = "{ this is not valid json"
        corrupt.writeText(corruptText)
        assertFailsWith<IllegalStateException> { AutomationService(corrupt) }
        assertEquals(corruptText, corrupt.readText(), "unreadable store must not be overwritten")

        val future = tempFile("future.json")
        val futureText = """{"version":99,"tasks":[],"runs":[]}"""
        future.writeText(futureText)
        assertFailsWith<IllegalStateException> { AutomationService(future) }
        assertEquals(futureText, future.readText(), "unknown version must not be overwritten")
    }

    @Test
    fun existingSessionUsesTargetAndOperationsAreWorkspaceScoped() {
        var clock = 1_700_000_000_000L
        val service = AutomationService(tempFile()) { clock }
        val saved = service.save(
            task(
                trigger = interval(),
                destination = TaskDestination.EXISTING_SESSION,
                targetSessionId = "session-42",
            ),
        )
        val run = service.runNow("w1", saved.id)
        assertEquals("session-42", run.sessionId)

        assertNull(service.task("w2", saved.id))
        assertTrue(service.runs("w2").isEmpty())
        assertFailsWith<IllegalArgumentException> { service.runNow("w2", saved.id) }

        service.delete("w2", saved.id)
        assertNotNull(service.task("w1", saved.id))
    }
}
