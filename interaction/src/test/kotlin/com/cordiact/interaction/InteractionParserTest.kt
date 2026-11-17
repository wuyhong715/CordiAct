package com.cordiact.interaction

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InteractionParserTest {
    private val context = InteractionContext("session-1", "workspace-1")
    private val file = MentionDescriptor("file-1", MentionType.FILE, "设计案.md", "workspace-1", parentId = "workspace-1")
    private val workspace = MentionDescriptor("workspace-1", MentionType.WORKSPACE, "产品工作区", "workspace-1")
    private val directory = object : MentionDirectory {
        override fun children(context: InteractionContext, parentId: String?): List<MentionDescriptor> =
            if (context.workspaceId != "workspace-1") emptyList()
            else when (parentId) {
                null -> listOf(workspace)
                "workspace-1" -> listOf(file)
                else -> emptyList()
            }

        override fun find(context: InteractionContext, id: String): MentionDescriptor? =
            listOf(workspace, file).singleOrNull { it.id == id && it.workspaceId == context.workspaceId }
    }
    private val registry = InteractionCommandRegistry().apply {
        register(
            CommandDescriptor(
                id = "command.target",
                name = "目标",
                ownerPluginId = "plugin.test",
                aliases = setOf("goal"),
                allowedMentionTypes = setOf(MentionType.WORKSPACE, MentionType.FILE),
                requiredCapabilities = setOf("task.track"),
                executor = InteractionCommandExecutor { _, _, _, _ -> CommandExecutionResult(true) },
            ),
        )
        setOwnerEnabled("plugin.test", true)
    }
    private val fills = InMemoryFillValueStore(mapOf("111" to "123456789"))
    private val parser = InteractionParser(registry, directory, fills)

    @Test
    fun parsesCompleteCommandAndNestedChineseMention() {
        val result = parser.parse("/目标：依据@产品工作区：设计案.md执行", context)

        assertTrue(result.valid, result.diagnostics.toString())
        assertEquals("command.target", result.envelope?.command?.id)
        assertEquals(listOf("workspace-1", "file-1"), result.envelope?.mentions?.single()?.entities?.map { it.id })
        assertTrue("task.track" in result.envelope!!.requiredCapabilities)
    }

    @Test
    fun rejectsEmptyMessage() {
        val result = parser.parse("   ", context)

        assertFalse(result.valid)
        assertEquals("message_empty", result.diagnostics.single().code)
    }

    @Test
    fun rejectsDanglingSecondLevelRecipientSeparator() {
        val result = parser.parse("/目标：依据@产品工作区：", context)

        assertFalse(result.valid)
        assertEquals("mention_child_incomplete", result.diagnostics.single().code)
    }

    @Test
    fun rejectsUnknownCommandsAndMissingSeparator() {
        val unknown = parser.parse("/目标任务：执行", context)
        val missing = parser.parse("/目标执行", context)

        assertFalse(unknown.valid)
        assertEquals("unknown_command", unknown.diagnostics.single().code)
        assertFalse(missing.valid)
        assertEquals("unknown_command", missing.diagnostics.single().code)
    }

    @Test
    fun ordinaryTextDoesNotActivateCommandButCanReferenceExplicitEntity() {
        val result = parser.parse("请参考@产品工作区完成总结", context)

        assertTrue(result.valid, result.diagnostics.toString())
        assertNull(result.envelope?.command)
        assertEquals("workspace-1", result.envelope?.mentions?.single()?.leaf?.id)
    }

    @Test
    fun rejectsReferenceOutsideCurrentWorkspace() {
        val result = parser.parse("使用@{private-file}", context)

        assertFalse(result.valid)
        assertEquals("mention_unresolved", result.diagnostics.single().code)
    }

    @Test
    fun substitutesOnlyDefinedWholeFillKey() {
        val result = parser.parse("/目标 订单#111，文件@{file-1}", context)

        assertTrue(result.valid, result.diagnostics.toString())
        assertEquals("/目标 订单123456789，文件@{file-1}", result.envelope?.submittedText)
    }

    @Test
    fun undefinedExplicitFillBlocksSubmission() {
        val result = parser.parse("/目标 使用#{missing}", context)

        assertFalse(result.valid)
        assertEquals("fill_undefined", result.diagnostics.single().code)
    }

    @Test
    fun structuredMentionAcceptsStableIdsAndChecksParentChild() {
        val result = parser.parse("/目标：请处理@{workspace-1}：@{file-1}", context)
        val wrongParent = parser.parse("/目标：请处理@{file-1}：@{workspace-1}", context)

        assertTrue(result.valid, result.diagnostics.toString())
        assertEquals(listOf("workspace-1", "file-1"), result.envelope?.mentions?.single()?.entities?.map { it.id })
        assertFalse(wrongParent.valid)
    }

    @Test
    fun commandRequiresMentionWhenDescriptorSaysSo() {
        val localRegistry = InteractionCommandRegistry().apply {
            register(CommandDescriptor(
                id = "command.scoped",
                name = "范围",
                ownerPluginId = "plugin.scoped",
                requiresMention = true,
                executor = InteractionCommandExecutor { _, _, _, _ -> CommandExecutionResult(true) },
            ))
            setOwnerEnabled("plugin.scoped", true)
        }
        val result = InteractionParser(localRegistry, directory, fills).parse("/范围：执行", context)

        assertFalse(result.valid)
        assertEquals("mention_required", result.diagnostics.single().code)
    }

    @Test
    fun commandOwnerDisableImmediatelyMakesCommandInvalid() {
        registry.setOwnerEnabled("plugin.test", false)

        val result = parser.parse("/目标：执行", context)

        assertFalse(result.valid)
        assertEquals("unknown_command", result.diagnostics.single().code)
    }

    @Test
    fun fillKeyMatchesWholeTokenRatherThanPrefix() {
        val result = parser.parse("/目标 订单#1110", context)

        assertTrue(result.valid)
        assertTrue(result.envelope!!.submittedText.contains("#1110"))
    }
    @Test
    fun chineseVariablesExpandOnceAndExplicitUndefinedBlocks() {
        fills.set("项目", "原文 #111 @{file-1} /目标")
        val result = parser.parse("按#{项目}执行，#missing保留", context)
        assertTrue(result.valid, result.diagnostics.toString())
        assertEquals("按原文 #111 @{file-1} /目标执行，#missing保留", result.envelope!!.submittedText)
        assertTrue(result.envelope!!.mentions.isEmpty())
        assertFalse(parser.parse("#{不存在}", context).valid)
    }

    @Test
    fun shieldsCodeEscapesAndLinks() {
        val text = "`@missing #{missing}` https://example.com/#missing \\#111\n```kotlin\n@missing #{missing}\n```"
        val result = parser.parse(text, context)
        assertTrue(result.valid, result.diagnostics.toString())
        assertEquals(text, result.envelope!!.submittedText)
    }

    @Test
    fun completionTargetsFollowCursorAndSuppressLiteralRegions() {
        val text = "前文 #{项目} 后文"
        val target = InputSyntax.completionTarget(text, text.indexOf("项") + 1)!!
        assertEquals('#', target.kind)
        assertEquals("#{项目}", text.substring(target.start, target.endExclusive))
        assertNull(InputSyntax.completionTarget("`#项目`", 4))
        assertNull(InputSyntax.completionTarget("https://a/#项目", 12))
        assertNull(InputSyntax.completionTarget("正文 /压", 5))
        assertEquals("压", InputSyntax.completionTarget("/压", 2)!!.query)
    }

    @Test
    fun localCommandWithoutParametersAndCandidateAliases() {
        registry.register(CommandDescriptor("compact", "compact", "plugin.test", aliases = setOf("压缩"),
            allowsEmptyBody = true, executor = InteractionCommandExecutor { _, _, _, _ -> CommandExecutionResult(true) }))
        assertTrue(parser.parse("/压缩", context).valid)
        val candidates = completionCandidates(InputCompletionTarget('/', 0, 2, "压"), registry, directory, fills, context)
        assertEquals("/压缩 ", candidates.single().insertText)
    }

}
