package com.cordiact.core.memory

import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelCall
import com.cordiact.core.conversation.ModelEvent
import com.cordiact.core.conversation.ModelGenerationRequest
import com.cordiact.core.conversation.ModelId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WS = "ws-1"
private const val GB = 1024L * 1024 * 1024
private const val NOW = 1_800_000_000_000L

class MemoryPipelineTest {

    private val model = ModelId("p", "m")
    private val ok = DeviceConditions(freeBytes = 4 * GB, thermalStatus = 0)

    private fun turn(session: String) = SessionTurn(MessageRole.USER, "fact from $session", session)

    private class FakeLeases : ExtractionLeaseStore {
        val held = mutableMapOf<String, ExtractionLease>()
        override fun active(workspaceId: String, sessionId: String) = held[sessionId]
        override fun claim(lease: ExtractionLease): Boolean {
            val existing = held[lease.sessionId]
            if (existing != null && existing.owner != lease.owner && !existing.isExpired(lease.claimedAtMillis)) return false
            held[lease.sessionId] = lease
            return true
        }
        override fun release(workspaceId: String, sessionId: String, owner: String) {
            if (held[sessionId]?.owner == owner) held.remove(sessionId)
        }
    }

    private class FakeStore : MemoryArtifactStore {
        val files = linkedMapOf<String, String>()
        override fun read(workspaceId: String, path: String, maxChars: Int) = files[path]?.take(maxChars)
        override fun write(workspaceId: String, path: String, content: String) {
            require(MemoryPaths.contains(path))
            files[path] = content
        }
        override fun delete(workspaceId: String, path: String) { files.remove(path) }
        override fun exists(workspaceId: String, path: String) = files.containsKey(path)
        override fun evidenceFiles(workspaceId: String) = files.keys.filter { it.startsWith(MemoryPaths.EVIDENCE_PREFIX) }
    }

    private fun pipeline(
        store: FakeStore = FakeStore(),
        leases: FakeLeases = FakeLeases(),
        reply: String = """{"raw_memory":"a fact","rollout_summary":"a summary"}""",
        selection: ModelId? = ModelId("p", "m"),
    ): MemoryPipeline {
        val extraction = MemoryExtractionRunner(
            loadMessages = { sid -> listOf(turn(sid)) },
            generate = { _: ModelGenerationRequest, emit: (ModelEvent) -> Unit ->
                emit(ModelEvent.Delta(reply))
                emit(ModelEvent.Completed())
                ModelCall { }
            },
            selection = { selection },
            timeoutMillis = 5_000,
        )
        return MemoryPipeline(
            extraction = extraction,
            consolidation = MemoryConsolidationRunner(store, clockMillis = { NOW }),
            leaseCoordinator = ExtractionLeaseCoordinator(leases, clockMillis = { NOW }),
            clockMillis = { NOW },
        )
    }

    @Test
    fun defersBeforeAnyModelCallWhenConditionsBlock() {
        val pipeline = pipeline()
        val hot = DeviceConditions(freeBytes = 4 * GB, thermalStatus = 3)

        val report = pipeline.run(WS, listOf("s1"), hot, "owner", true, ConsolidationDiff())

        assertTrue(report.decision is SchedulingDecision.Defer)
        assertTrue(report.sessions.isEmpty(), "nothing should be attempted")
        assertEquals(null, report.consolidation, "consolidation must not run either")
    }

    @Test
    fun defersWhenNoModelSelected() {
        val report = pipeline(selection = null).run(WS, listOf("s1"), ok, "owner", false, ConsolidationDiff())

        assertTrue(report.decision is SchedulingDecision.Defer)
        assertTrue(report.sessions.isEmpty())
    }

    @Test
    fun extractsAndConsolidates() {
        val store = FakeStore()
        val report = pipeline(store).run(
            WS,
            listOf("s1"),
            ok,
            "owner",
            true,
            ConsolidationDiff(added = setOf("s1")),
        )

        assertEquals(1, report.extractedCount)
        assertTrue(report.consolidation is ConsolidationOutcome.Committed)
        assertTrue(store.exists(WS, MemoryPaths.MANUAL))
        assertTrue(store.exists(WS, MemoryPaths.evidencePath("s1")))
    }

    @Test
    fun busySessionIsSkippedNotFailed() {
        // A concurrent run holding the lease is not an error, and must not burn backoff.
        val leases = FakeLeases().apply {
            held["s1"] = ExtractionLease(WS, "s1", "other-owner", NOW, NOW + 60_000)
        }

        val report = pipeline(leases = leases).run(WS, listOf("s1"), ok, "me", true, ConsolidationDiff())

        val outcome = report.sessions.single()
        assertTrue(outcome is SessionMemoryOutcome.Skipped, "got $outcome")
    }

    @Test
    fun leaseIsReleasedEvenWhenExtractionFails() {
        // Otherwise one bad session blocks itself until the lease expires.
        val leases = FakeLeases()
        val extraction = MemoryExtractionRunner(
            loadMessages = { throw IllegalStateException("db locked") },
            generate = { _, _ -> ModelCall { } },
            selection = { model },
        )
        val pipeline = MemoryPipeline(
            extraction = extraction,
            consolidation = MemoryConsolidationRunner(FakeStore(), clockMillis = { NOW }),
            leaseCoordinator = ExtractionLeaseCoordinator(leases, clockMillis = { NOW }),
            clockMillis = { NOW },
        )

        val report = pipeline.run(WS, listOf("s1"), ok, "owner", true, ConsolidationDiff())

        assertTrue(report.sessions.single() is SessionMemoryOutcome.Failed)
        assertEquals(null, leases.active(WS, "s1"), "the lease must not survive a failure")
    }

    @Test
    fun noContentIsNotAFailure() {
        val extraction = MemoryExtractionRunner(
            loadMessages = { listOf(SessionTurn(MessageRole.USER, "<cordiact-memory>x</cordiact-memory>", "s1")) },
            generate = { _, _ -> ModelCall { } },
            selection = { model },
        )
        val pipeline = MemoryPipeline(
            extraction = extraction,
            consolidation = MemoryConsolidationRunner(FakeStore(), clockMillis = { NOW }),
            leaseCoordinator = ExtractionLeaseCoordinator(FakeLeases(), clockMillis = { NOW }),
            clockMillis = { NOW },
        )

        val report = pipeline.run(WS, listOf("s1"), ok, "owner", true, ConsolidationDiff())

        assertEquals(SessionMemoryOutcome.NoContent, report.sessions.single())
    }

    @Test
    fun consolidationStillRunsWhenNothingWasExtracted() {
        // The diff set may carry removals; skipping consolidation would leave the evidence of a
        // forgotten session on disk forever.
        val store = FakeStore().apply {
            files[MemoryPaths.evidencePath("gone")] = "old"
            files[MemoryPaths.MANUAL] = "# Memory"
            files[MemoryPaths.SUMMARY] = "v1"
        }

        val report = pipeline(store).run(
            WS,
            emptyList(),
            ok,
            "owner",
            true,
            ConsolidationDiff(removed = setOf("gone")),
        )

        assertTrue(report.consolidation != null, "consolidation must run for removals")
        assertTrue(!store.exists(WS, MemoryPaths.evidencePath("gone")))
    }

    @Test
    fun diffForMarksAddedRetainedAndRemoved() {
        val pipeline = pipeline()

        val diff = pipeline.diffFor(
            present = setOf("a", "b", "c"),
            extracted = setOf("a"),
            previouslyKnown = setOf("b", "c", "d"),
        )

        assertEquals(setOf("a"), diff.added)
        assertEquals(setOf("b", "c"), diff.retained)
        assertEquals(setOf("d"), diff.removed)
    }

    @Test
    fun diffForIgnoresExtractionsThatAreNoLongerPresent() {
        val pipeline = pipeline()

        val diff = pipeline.diffFor(
            present = setOf("a"),
            extracted = setOf("a", "vanished"),
            previouslyKnown = emptySet(),
        )

        assertEquals(setOf("a"), diff.added)
        assertTrue("vanished" !in diff.added)
    }
}
