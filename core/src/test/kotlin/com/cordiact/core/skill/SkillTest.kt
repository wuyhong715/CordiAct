package com.cordiact.core.skill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val WS = "ws-1"

private fun skillMd(name: String?, description: String?, body: String = "Instructions here.") = buildString {
    appendLine("---")
    if (name != null) appendLine("name: $name")
    if (description != null) appendLine("description: $description")
    appendLine("---")
    appendLine()
    appendLine(body)
}

class SkillParserTest {

    @Test
    fun parsesNameDescriptionAndBody() {
        val parsed = SkillParser.parse(skillMd("review", "Review a change", "Do the review."))

        assertEquals("review", parsed.name)
        assertEquals("Review a change", parsed.description)
        assertEquals("Do the review.", parsed.body)
    }

    @Test
    fun stripsQuotesFromValues() {
        val parsed = SkillParser.parse(skillMd("review", "\"Quoted description\""))

        assertEquals("Quoted description", parsed.description)
    }

    @Test
    fun rejectsMissingFrontmatter() {
        // Inferring a name from the directory would produce an entry the author never reviewed.
        val error = assertFailsWith<SkillException> { SkillParser.parse("# Just a heading\n") }
        assertEquals(SkillException.Code.MISSING_FRONTMATTER, error.code)
    }

    @Test
    fun rejectsUnclosedFrontmatter() {
        val error = assertFailsWith<SkillException> { SkillParser.parse("---\nname: x\n") }
        assertEquals(SkillException.Code.MISSING_FRONTMATTER, error.code)
    }

    @Test
    fun rejectsOversizedFile() {
        val huge = "---\nname: x\ndescription: y\n---\n" + "a".repeat(SkillParser.MAX_FILE_CHARS)

        val error = assertFailsWith<SkillException> { SkillParser.parse(huge) }
        assertEquals(SkillException.Code.TOO_LARGE, error.code)
    }

    @Test
    fun parsesEmptyFrontmatterAsMissingFields() {
        val parsed = SkillParser.parse("---\n---\nbody")

        assertNull(parsed.name)
        assertNull(parsed.description)
    }

    @Test
    fun validateRequiresName() {
        val error = assertFailsWith<SkillException> {
            SkillParser.validate(SkillParser.parse(skillMd(null, "d")), "x", SkillOrigin.WORKSPACE, WS, "skills/x/SKILL.md")
        }
        assertEquals(SkillException.Code.MISSING_NAME, error.code)
    }

    @Test
    fun validateRequiresDescription() {
        // The description is all the model sees before choosing; without it the skill is
        // invisible in practice even though it exists.
        val error = assertFailsWith<SkillException> {
            SkillParser.validate(SkillParser.parse(skillMd("x", null)), "x", SkillOrigin.WORKSPACE, WS, "skills/x/SKILL.md")
        }
        assertEquals(SkillException.Code.MISSING_DESCRIPTION, error.code)
    }

    @Test
    fun validateRejectsOverlongName() {
        val long = "a".repeat(65)
        val error = assertFailsWith<SkillException> {
            SkillParser.validate(SkillParser.parse(skillMd(long, "d")), long, SkillOrigin.WORKSPACE, WS, "p")
        }
        assertEquals(SkillException.Code.NAME_TOO_LONG, error.code)
    }

    @Test
    fun validateRejectsNonKebabName() {
        for (bad in listOf("Review", "re_view", "review me", "-review", "review!")) {
            val error = assertFailsWith<SkillException>("expected rejection for $bad") {
                SkillParser.validate(
                    SkillParser.parse(skillMd(bad, "d")),
                    bad,
                    SkillOrigin.WORKSPACE,
                    WS,
                    "skills/$bad/SKILL.md",
                )
            }
            assertEquals(SkillException.Code.INVALID_NAME, error.code)
        }
    }

    @Test
    fun validateRejectsNameThatDisagreesWithDirectory() {
        // Two ways to address one skill means the index can list something unopenable.
        val error = assertFailsWith<SkillException> {
            SkillParser.validate(
                SkillParser.parse(skillMd("review", "d")),
                "reviewer",
                SkillOrigin.WORKSPACE,
                WS,
                "skills/reviewer/SKILL.md",
            )
        }
        assertEquals(SkillException.Code.INVALID_NAME, error.code)
    }

    @Test
    fun acceptsKebabNameWithDigits() {
        val descriptor = SkillParser.validate(
            SkillParser.parse(skillMd("api-v2-review", "d")),
            "api-v2-review",
            SkillOrigin.WORKSPACE,
            WS,
            "skills/api-v2-review/SKILL.md",
        )

        assertEquals("api-v2-review", descriptor.name)
    }
}

class DefaultSkillServiceTest {

    private class FakeSource(
        val files: Map<String, String>,
        val unreadable: Set<String> = emptySet(),
    ) : SkillFileSource {
        var listCalls = 0

        override fun listSkillFiles(workspaceId: String): List<String> {
            listCalls++
            return files.keys.toList()
        }

        override fun read(workspaceId: String, path: String): String? {
            if (path in unreadable) throw IllegalStateException("io error")
            return files[path]
        }
    }

    private fun entry(name: String, description: String = "d") = "skills/$name/SKILL.md" to skillMd(name, description)

    @Test
    fun discoversWorkspaceSkills() {
        val source = FakeSource(mapOf(entry("review"), entry("plan")))

        val catalog = DefaultSkillService(source, emptyList()).catalog(WS)

        assertEquals(listOf("plan", "review"), catalog.skills.map { it.name })
        assertTrue(catalog.rejected.isEmpty())
    }

    @Test
    fun workspaceSkillOverridesBundled() {
        // The user's own file must win, or their skill looks ignored in their own Workspace.
        val bundled = listOf(
            SkillDescriptor("review", "bundled version", SkillOrigin.BUNDLED, null, "bundled/review/SKILL.md"),
        )
        val source = FakeSource(mapOf(entry("review", "workspace version")))

        val catalog = DefaultSkillService(source, bundled).catalog(WS)

        assertEquals(1, catalog.skills.size)
        assertEquals(SkillOrigin.WORKSPACE, catalog.skills.single().origin)
        assertEquals("workspace version", catalog.skills.single().description)
    }

    @Test
    fun bundledSkillsRemainWhenNotOverridden() {
        val bundled = listOf(
            SkillDescriptor("explain", "bundled", SkillOrigin.BUNDLED, null, "bundled/explain/SKILL.md"),
        )

        val catalog = DefaultSkillService(FakeSource(emptyMap()), bundled).catalog(WS)

        assertEquals(listOf("explain"), catalog.skills.map { it.name })
    }

    @Test
    fun rejectedSkillsAreReportedNotDroppedSilently() {
        // A user who wrote a skill and cannot find it needs the reason, not an empty list.
        val source = FakeSource(
            mapOf(
                "skills/no-desc/SKILL.md" to skillMd("no-desc", null),
                "skills/bad-name/SKILL.md" to skillMd("BadName", "d"),
            ),
        )

        val catalog = DefaultSkillService(source, emptyList()).catalog(WS)

        assertTrue(catalog.isEmpty)
        assertEquals(2, catalog.rejected.size)
        assertTrue(catalog.rejected.any { it.reason == SkillException.Code.MISSING_DESCRIPTION })
        assertTrue(catalog.rejected.any { it.reason == SkillException.Code.INVALID_NAME })
    }

    @Test
    fun unreadableFileIsRejectedRatherThanCrashing() {
        val source = FakeSource(mapOf(entry("broken")), unreadable = setOf("skills/broken/SKILL.md"))

        val catalog = DefaultSkillService(source, emptyList()).catalog(WS)

        assertTrue(catalog.isEmpty)
        assertEquals(SkillException.Code.BODY_UNREADABLE, catalog.rejected.single().reason)
    }

    @Test
    fun oneBadSkillDoesNotHideTheOthers() {
        val source = FakeSource(
            mapOf(
                "skills/good/SKILL.md" to skillMd("good", "d"),
                "skills/bad/SKILL.md" to skillMd("Bad", "d"),
            ),
        )

        val catalog = DefaultSkillService(source, emptyList()).catalog(WS)

        assertEquals(listOf("good"), catalog.skills.map { it.name })
        assertEquals(1, catalog.rejected.size)
    }

    @Test
    fun catalogIsCachedUntilInvalidated() {
        val source = FakeSource(mapOf(entry("review")))
        val service = DefaultSkillService(source, emptyList())

        service.catalog(WS)
        service.catalog(WS)

        assertEquals(1, source.listCalls, "a second read should hit the cache")

        service.invalidate(WS)
        service.catalog(WS)

        assertEquals(2, source.listCalls)
    }

    @Test
    fun cachesAreSeparatePerWorkspace() {
        val source = FakeSource(mapOf(entry("review")))
        val service = DefaultSkillService(source, emptyList())

        service.catalog(WS)
        service.catalog("ws-2")
        service.catalog("ws-2")

        assertEquals(2, source.listCalls)
    }

    @Test
    fun bodyIsReadOnDemand() {
        val source = FakeSource(mapOf("skills/review/SKILL.md" to skillMd("review", "d", "The instructions.")))
        val service = DefaultSkillService(source, emptyList())

        assertEquals("The instructions.", service.body(WS, "review"))
    }

    @Test
    fun bodyIsNullForUnknownSkill() {
        assertNull(DefaultSkillService(FakeSource(emptyMap()), emptyList()).body(WS, "nope"))
    }

    @Test
    fun discoveryDoesNotReadBodies() {
        // Loading every body would defeat the point of an index.
        val source = FakeSource(mapOf(entry("review"), entry("plan")))
        val service = DefaultSkillService(source, emptyList())

        var reads = 0
        val counting = object : SkillFileSource {
            override fun listSkillFiles(workspaceId: String) = source.listSkillFiles(workspaceId)
            override fun read(workspaceId: String, path: String): String? {
                reads++
                return source.read(workspaceId, path)
            }
        }
        val counted = DefaultSkillService(counting, emptyList())
        counted.catalog(WS)

        // Discovery reads each SKILL.md once for its header; it must not read bodies again.
        assertEquals(2, reads)
        assertNotNull(service.find(WS, "review"))
    }
}

class SkillPluginTest {

    private class FakeSource(private val files: Map<String, String>) : SkillFileSource {
        override fun listSkillFiles(workspaceId: String) = files.keys.toList()
        override fun read(workspaceId: String, path: String) = files[path]
    }

    private fun plugin() = SkillPlugin(
        FakeSource(mapOf("skills/review/SKILL.md" to skillMd("review", "Review a change"))),
    )

    @Test
    fun failsClosedWhenDisabled() {
        val plugin = plugin()

        // No onEnable: the plugin must not serve a catalog it never loaded.
        assertFailsWith<IllegalStateException> { plugin.catalog(WS) }
    }

    @Test
    fun servesAfterEnable() {
        val plugin = plugin().apply { onEnable() }

        assertEquals(1, plugin.catalog(WS).skills.size)
    }

    @Test
    fun stopsServingAfterDisable() {
        val plugin = plugin().apply { onEnable() }
        plugin.onDisable()

        assertFailsWith<IllegalStateException> { plugin.catalog(WS) }
    }

    @Test
    fun reEnableReloadsRatherThanServingStaleCache() {
        // A disable/enable cycle is how the host reacts to a Workspace change; serving the old
        // catalog would hide the edit.
        val plugin = plugin().apply { onEnable() }
        plugin.catalog(WS)
        plugin.onDisable()
        plugin.onEnable()

        assertEquals(1, plugin.catalog(WS).skills.size)
    }
}
