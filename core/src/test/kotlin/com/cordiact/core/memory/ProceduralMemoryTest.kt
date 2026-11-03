package com.cordiact.core.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProceduralMemoryPromoterTest {

    private val promoter = ProceduralMemoryPromoter()

    private fun pattern(
        name: String = "fix-gradle-build",
        sessions: Set<String> = setOf("s1", "s2"),
        steps: List<String> = listOf("Read the failing task", "Fix the reported error"),
        description: String = "Repair a broken Gradle build",
    ) = DetectedPattern(
        name = name,
        description = description,
        sessionIds = sessions,
        steps = steps,
    )

    private fun rejection(decision: PromotionDecision): PromotionDecision.Reject {
        check(decision is PromotionDecision.Reject) { "expected a rejection, got $decision" }
        return decision
    }

    @Test
    fun promotesARepeatedPattern() {
        val decision = promoter.decide(pattern(), existingGenerated = null, userSkillNames = emptySet())

        assertTrue(decision is PromotionDecision.Promote)
        assertEquals("memory/skills/fix-gradle-build/SKILL.md", decision.skill.path)
    }

    @Test
    fun rejectsASingleOccurrence() {
        // A one-off is a fact, not a procedure; promoting it fills the index with noise.
        val decision = promoter.decide(
            pattern(sessions = setOf("s1")),
            existingGenerated = null,
            userSkillNames = emptySet(),
        )

        assertEquals(
            PromotionDecision.Reject.Reason.TOO_FEW_REPEATS,
            rejection(decision).reason,
        )
    }

    @Test
    fun repeatsCountDistinctSessions() {
        // Restating a task twice in one session is still one occurrence of that task.
        val pattern = pattern(sessions = setOf("s1"))

        assertEquals(1, pattern.repeats)
        assertTrue(
            promoter.decide(pattern, null, emptySet()) is PromotionDecision.Reject,
        )
    }

    @Test
    fun neverOverwritesAUserAuthoredSkill() {
        // A memory pass that silently replaces the user's own file is worse than one that
        // leaves it alone.
        val decision = promoter.decide(
            pattern(),
            existingGenerated = null,
            userSkillNames = setOf("fix-gradle-build"),
        )

        assertEquals(
            PromotionDecision.Reject.Reason.CONFLICTS_WITH_USER_SKILL,
            rejection(decision).reason,
        )
    }

    @Test
    fun rejectsPatternWithoutSteps() {
        val decision = promoter.decide(
            pattern(steps = emptyList()),
            existingGenerated = null,
            userSkillNames = emptySet(),
        )

        assertEquals(PromotionDecision.Reject.Reason.NO_STEPS, rejection(decision).reason)
    }

    @Test
    fun rejectsInvalidName() {
        for (bad in listOf("Fix-Build", "fix_build", "-fix", "fix build")) {
            val decision = promoter.decide(
                pattern(name = bad),
                existingGenerated = null,
                userSkillNames = emptySet(),
            )
            assertEquals(
                PromotionDecision.Reject.Reason.INVALID_NAME,
                rejection(decision).reason,
                "expected $bad to be rejected",
            )
        }
    }

    @Test
    fun unchangedContentIsNotRewritten() {
        // Writing identical bytes would produce a commit with no information.
        val first = (promoter.decide(pattern(), null, emptySet()) as PromotionDecision.Promote).skill
        val existing = promoter.document(first)

        val second = promoter.decide(pattern(), existing, emptySet())

        assertEquals(PromotionDecision.Reject.Reason.UNCHANGED, rejection(second).reason)
    }

    @Test
    fun changedContentIsRewritten() {
        val first = (promoter.decide(pattern(), null, emptySet()) as PromotionDecision.Promote).skill
        val existing = promoter.document(first)

        val second = promoter.decide(
            pattern(steps = listOf("Read the failing task", "Bump the AGP version")),
            existing,
            emptySet(),
        )

        assertTrue(second is PromotionDecision.Promote)
    }

    @Test
    fun documentCarriesTheFrontmatterTheLoaderNeeds() {
        // The skill loader rejects a file without name and description, so the writer must
        // produce exactly that header.
        val skill = (promoter.decide(pattern(), null, emptySet()) as PromotionDecision.Promote).skill
        val document = promoter.document(skill)

        assertTrue(document.startsWith("---"))
        assertTrue("name: fix-gradle-build" in document)
        assertTrue("description: Repair a broken Gradle build" in document)
    }

    @Test
    fun renderIsDeterministic() {
        val first = promoter.render(pattern())
        val second = promoter.render(pattern())

        assertEquals(first.body, second.body)
    }

    @Test
    fun minRepeatsMustBePositive() {
        assertFailsWith<IllegalArgumentException> { ProceduralMemoryPromoter(minRepeats = 0) }
    }
}

class PatternDetectorTest {

    private val detector = PatternDetector()

    private fun candidate(session: String, name: String, steps: List<String> = listOf("a")) =
        PatternCandidate(
            sessionId = session,
            name = name,
            description = "does $name",
            steps = steps,
        )

    @Test
    fun groupsTheSameNameAcrossSessions() {
        val patterns = detector.detect(
            listOf(
                candidate("s1", "fix-build"),
                candidate("s2", "fix-build"),
                candidate("s1", "rename-file"),
            ),
        )

        val fix = patterns.single { it.name == "fix-build" }
        assertEquals(2, fix.repeats)
    }

    @Test
    fun differentNamesStaySeparate() {
        val patterns = detector.detect(listOf(candidate("s1", "a"), candidate("s2", "b")))

        assertEquals(2, patterns.size)
        assertTrue(patterns.all { it.repeats == 1 })
    }

    @Test
    fun orderedByRepeatsThenName() {
        val patterns = detector.detect(
            listOf(
                candidate("s1", "rare"),
                candidate("s1", "common"),
                candidate("s2", "common"),
                candidate("s3", "common"),
            ),
        )

        assertEquals(listOf("common", "rare"), patterns.map { it.name })
    }

    @Test
    fun stepsComeFromOneSessionRatherThanBeingMerged() {
        // Two sessions that disagree about the steps describe two procedures; merging them
        // would produce one nobody actually performed.
        val patterns = detector.detect(
            listOf(
                candidate("s1", "fix-build", listOf("step from s1")),
                candidate("s2", "fix-build", listOf("step from s2")),
            ),
        )

        assertEquals(listOf("step from s2"), patterns.single().steps)
    }
}

class ProceduralMemoryWriterTest {

    private class FakeStore : MemoryArtifactStore {
        val files = linkedMapOf<String, String>()
        override fun read(workspaceId: String, path: String, maxChars: Int) = files[path]?.take(maxChars)
        override fun write(workspaceId: String, path: String, content: String) {
            require(MemoryPaths.contains(path)) { "outside memory subtree: $path" }
            files[path] = content
        }
        override fun delete(workspaceId: String, path: String) { files.remove(path) }
        override fun exists(workspaceId: String, path: String) = files.containsKey(path)
        override fun evidenceFiles(workspaceId: String) = emptyList<String>()
    }

    private fun pattern(name: String = "fix-build", sessions: Set<String> = setOf("s1", "s2")) =
        DetectedPattern(
            name = name,
            description = "does $name",
            sessionIds = sessions,
            steps = listOf("step one"),
        )

    @Test
    fun writesPromotedSkillsUnderMemory() {
        val store = FakeStore()
        val result = ProceduralMemoryWriter(store).promote(WS, listOf(pattern()), emptySet())

        assertEquals(1, result.written.size)
        assertTrue(store.exists(WS, "memory/skills/fix-build/SKILL.md"))
    }

    @Test
    fun writesNothingForARejectedPattern() {
        val store = FakeStore()
        val result = ProceduralMemoryWriter(store).promote(
            WS,
            listOf(pattern(sessions = setOf("s1"))),
            emptySet(),
        )

        assertTrue(result.isEmpty)
        assertEquals(1, result.rejected.size)
        assertTrue(store.files.isEmpty())
    }

    @Test
    fun aSecondIdenticalPassWritesNothing() {
        val store = FakeStore()
        val writer = ProceduralMemoryWriter(store)
        writer.promote(WS, listOf(pattern()), emptySet())

        val second = writer.promote(WS, listOf(pattern()), emptySet())

        assertTrue(second.isEmpty, "an unchanged pattern must not be rewritten")
        assertEquals(
            PromotionDecision.Reject.Reason.UNCHANGED,
            (second.rejected.single().second as PromotionDecision.Reject).reason,
        )
    }

    private companion object {
        const val WS = "ws-1"
    }
}
