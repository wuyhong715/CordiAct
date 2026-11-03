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
        assertEquals("command_separator_missing", unknown.diagnostics.single().code)
        assertFalse(missing.valid)
        assertEquals("command_separator_missing", missing.diagnostics.single().code)
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
    fun undefinedFillBlocksSubmission() {
        val result = parser.parse("/目标 使用#missing", context)

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

        assertFalse(result.valid)
        assertTrue(result.diagnostics.any { it.code == "fill_undefined" })
    }
}
