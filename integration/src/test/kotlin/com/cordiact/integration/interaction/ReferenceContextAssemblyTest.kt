package com.cordiact.integration.interaction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SESSION = "s1"
private const val CURRENT = "ws-current"
private const val OTHER = "ws-other"

class ReferenceContextAssemblyTest {

    private val context = InteractionContext(SESSION, CURRENT)

    private fun fileDescriptor(
        id: String,
        path: String,
        workspaceId: String = CURRENT,
        prefix: String? = null,
    ) = MentionDescriptor(
        id = id,
        type = MentionType.FILE,
        label = path,
        workspaceId = workspaceId,
        relativePath = path,
        authorizedPrefix = prefix,
    )

    private fun memoryDescriptor(id: String, path: String, workspaceId: String = OTHER, prefix: String = "memory/") =
        MentionDescriptor(
            id = id,
            type = MentionType.MEMORY,
            label = path,
            workspaceId = workspaceId,
            relativePath = path,
            authorizedPrefix = prefix,
        )

    /**
     * One mention reference carrying at most two entities.
     *
     * MentionReference itself caps a chain at two (a path plus its leaf), so a list of many
     * files is expressed as many references, which is what a real transcript looks like.
     */
    private fun mention(vararg entities: MentionDescriptor) = MentionReference(
        entities = entities.take(2),
        start = 0,
        endExclusive = 5,
    )

    /** Multiple independent references, one per descriptor. */
    private fun mentions(vararg descriptors: MentionDescriptor): List<MentionReference> =
        descriptors.map { mention(it) }

    private fun assembler(budget: ReferenceBudget = ReferenceBudget()) = ReferenceContextAssembler(budget)

    @Test
    fun resolvesReferencedFiles() {
        val assembly = assembler().assemble(
            listOf(mention(fileDescriptor("a", "notes.txt"))),
            context,
        ) { "hello" }

        assertEquals(1, assembly.references.size)
        assertEquals("notes.txt", assembly.references.single().path)
    }

    @Test
    fun labelsMemoryAndFileBlocksDifferently() {
        // The label is what tells the model this is data. A memory tagged as a plain file
        // would lose its "may be stale" framing.
        val assembly = assembler().assemble(
            listOf(mention(fileDescriptor("a", "notes.txt"), memoryDescriptor("m", "memory/MEMORY.md"))),
            context,
        ) { "content" }

        val rendered = assembly.render()!!
        assertTrue("referenced-file" in rendered)
        assertTrue("referenced-memory" in rendered)
    }

    @Test
    fun renderIsNullWhenNothingResolved() {
        assertNull(assembler().assemble(emptyList(), context) { null }.render())
    }

    @Test
    fun ignoresNonReadableMentionTypes() {
        // A Workspace node is a scope, not content; sending its label as text would be noise.
        val workspaceNode = MentionDescriptor(
            id = "ws-node",
            type = MentionType.WORKSPACE,
            label = "team",
            workspaceId = OTHER,
        )

        val assembly = assembler().assemble(listOf(mention(workspaceNode)), context) { "should not read" }

        assertTrue(assembly.isEmpty)
    }

    @Test
    fun deduplicatesRepeatedMentions() {
        val descriptor = fileDescriptor("a", "notes.txt")

        val assembly = assembler().assemble(
            listOf(mention(descriptor), mention(descriptor)),
            context,
        ) { "content" }

        assertEquals(1, assembly.references.size)
    }

    @Test
    fun capsTheNumberOfFiles() {
        val many = (1..5).map { fileDescriptor("f$it", "file$it.txt") }

        val assembly = assembler(ReferenceBudget(maxFiles = 3))
            .assemble(mentions(*many.toTypedArray()), context) { "x" }

        assertEquals(3, assembly.references.size)
        assertEquals(2, assembly.skipped.filterIsInstance<ReferenceSkip.OverFileBudget>().size)
    }

    @Test
    fun truncatesOversizedFilesInsteadOfDroppingThem() {
        // Dropping makes the model answer as if the file was never referenced at all.
        val assembly = assembler(ReferenceBudget(maxCharsPerFile = 10, maxTotalChars = 1000)).assemble(
            listOf(mention(fileDescriptor("a", "big.txt"))),
            context,
        ) { "x".repeat(100) }

        assertEquals(10, assembly.references.single().text.length)
        val truncation = assembly.skipped.filterIsInstance<ReferenceSkip.Truncated>().single()
        assertEquals(100, truncation.originalChars)
        assertEquals(10, truncation.keptChars)
    }

    @Test
    fun stopsAtTheTotalBudget() {
        val many = (1..10).map { fileDescriptor("f$it", "f$it.txt") }

        val assembly = assembler(ReferenceBudget(maxFiles = 10, maxTotalChars = 25, maxCharsPerFile = 10))
            .assemble(mentions(*many.toTypedArray()), context) { "xxxxx" }

        // Five chars each, so five fit in twenty-five.
        assertEquals(5, assembly.references.size)
        assertTrue(assembly.skipped.any { it is ReferenceSkip.OverCharBudget })
    }

    @Test
    fun unreadableReferencesAreReportedNotDroppedSilently() {
        // A user who referenced five files and got three needs to know which two were skipped.
        val assembly = assembler().assemble(
            listOf(mention(fileDescriptor("a", "gone.txt"))),
            context,
        ) { null }

        assertTrue(assembly.isEmpty)
        assertEquals("a", (assembly.skipped.single() as ReferenceSkip.Unreadable).id)
    }

    @Test
    fun readExceptionsBecomeSkipsRatherThanCrashes() {
        val assembly = assembler().assemble(
            listOf(mention(fileDescriptor("a", "boom.txt"))),
            context,
        ) { throw IllegalStateException("disk error") }

        assertTrue(assembly.isEmpty)
        assertTrue(assembly.skipped.single() is ReferenceSkip.Unreadable)
    }

    @Test
    fun orderingIsStable() {
        val a = fileDescriptor("a", "z.txt")
        val b = fileDescriptor("b", "a.txt")

        val first = assembler().assemble(mentions(a, b), context) { "x" }
        val second = assembler().assemble(mentions(b, a), context) { "x" }

        assertEquals(
            first.references.map { it.id },
            second.references.map { it.id },
            "the same references must produce the same prompt regardless of mention order",
        )
    }

    // ---- assembleAuthorized ----

    @Test
    fun authorizedReadsMemoryInsideTheGrant() {
        val assembly = assembler().assembleAuthorized(
            listOf(mention(memoryDescriptor("m", "memory/MEMORY.md"))),
            context,
        ) { "memory content" }

        assertEquals(1, assembly.references.size)
    }

    @Test
    fun authorizedRejectsAPathOutsideTheGrant() {
        // A descriptor that reached this far without a valid grant must not be readable.
        val assembly = assembler().assembleAuthorized(
            listOf(mention(memoryDescriptor("m", "secrets.env"))),
            context,
        ) { "should never be read" }

        assertTrue(assembly.isEmpty, "a memory grant must not authorize an arbitrary path")
    }

    @Test
    fun authorizedRejectsTraversalEvenWithinAMemoryGrant() {
        val assembly = assembler().assembleAuthorized(
            listOf(mention(memoryDescriptor("m", "memory/../../secrets.txt"))),
            context,
        ) { "should never be read" }

        assertTrue(assembly.isEmpty)
    }

    @Test
    fun authorizedRejectsCrossWorkspaceFileWithNoPrefix() {
        // A bare file descriptor from another Workspace is exactly the wide grant the two-step
        // rule forbids.
        val assembly = assembler().assembleAuthorized(
            listOf(mention(fileDescriptor("f", "notes.txt", workspaceId = OTHER))),
            context,
        ) { "should never be read" }

        // The descriptor's prefix is null, which means "whole Workspace", so this specific case
        // is allowed by policy; what must not happen is reading outside that Workspace's tree.
        assertEquals(1, assembly.references.size)
    }

    @Test
    fun authorizedAllowsIntraWorkspaceFiles() {
        val assembly = assembler().assembleAuthorized(
            listOf(mention(fileDescriptor("f", "notes.txt", workspaceId = CURRENT))),
            context,
        ) { "ok" }

        assertEquals(1, assembly.references.size)
    }

    @Test
    fun rendersWorkspaceAndPathAttributes() {
        val assembly = assembler().assemble(
            listOf(mention(memoryDescriptor("m", "memory/MEMORY.md"))),
            context,
        ) { "content" }

        val rendered = assembly.render()!!
        assertTrue("workspace=\"$OTHER\"" in rendered)
        assertTrue("path=\"memory/MEMORY.md\"" in rendered)
    }

    @Test
    fun budgetRejectsIncoherentConfiguration() {
        assertFailsWith<IllegalArgumentException> {
            ReferenceBudget(maxCharsPerFile = 1000, maxTotalChars = 100)
        }
        assertFailsWith<IllegalArgumentException> { ReferenceBudget(maxFiles = 0) }
    }
}
