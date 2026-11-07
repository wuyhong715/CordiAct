package com.cordiact.core.context

import com.cordiact.core.conversation.MessageRole
import com.cordiact.core.conversation.ModelMessage
import java.util.UUID

object SummaryCompactor {

    const val COMPACTION_PROMPT = """
你在为另一个 LLM 写上下文交接单，它将继续完成用户的任务。不要写寒暄，不要重复原文。
必须包含以下七节，每节用二级标题：
## 1. 任务与目标      原始用户请求（可改写成一句话，但不得改变意图）
## 2. 已完成与关键决策  做了什么、为什么这样选、放弃了什么
## 3. 当前状态        工作区、打开的文件/页面、会话 id、未完成的命令
## 4. 重要事实与约束   用户偏好、环境限制、权限边界；密钥只写“已配置”，不写明文
## 5. 工具产出索引     spill 文件路径 + 一行内容摘要 + 何时需要重读
## 6. 下一步          可执行的、有顺序的步骤
## 7. 必须保留的原文   短引用（每条 ≤ 200 字符）
"""

    private val REQUIRED_SECTIONS = listOf(
        "## 1. 任务与目标",
        "## 2. 已完成与关键决策",
        "## 3. 当前状态",
        "## 4. 重要事实与约束",
        "## 5. 工具产出索引",
        "## 6. 下一步",
        "## 7. 必须保留的原文",
    )

    private val SECRET_PATTERNS = listOf(
        Regex("sk-[a-zA-Z0-9]{20,}"),
        Regex("ghp_[a-zA-Z0-9]{30,}"),
        Regex("github_pat_[a-zA-Z0-9_]{30,}"),
        Regex("-----BEGIN (?:RSA|EC|OPENSSH) PRIVATE KEY-----"),
    )

    /**
     * Synthesizes a structured compaction summary.
     *
     * @param llmSummarizer optional lambda to call an LLM for L0 summary.
     */
    fun compact(
        plan: CompactionPlan,
        estimator: TokenEstimator,
        llmSummarizer: ((prompt: String, bodyMessages: List<com.cordiact.core.conversation.ConversationMessage>) -> String?)? = null,
    ): CompactionResult {
        val recordId = "cmp-${UUID.randomUUID().toString().take(8)}"
        val tokensBefore = plan.bodyTokens

        // Try L0: LLM summary
        if (llmSummarizer != null) {
            val rawSummary = runCatching { llmSummarizer(COMPACTION_PROMPT, plan.body) }.getOrNull()
            if (!rawSummary.isNullOrBlank()) {
                val sanitized = redactSecrets(rawSummary.trim())
                val summaryTokens = estimator.estimate(sanitized)
                val hasAllSections = REQUIRED_SECTIONS.all { sanitized.contains(it) }

                if (hasAllSections && (tokensBefore <= 100 || summaryTokens <= tokensBefore * 0.75)) {
                    return CompactionResult(
                        recordId = recordId,
                        startMessageId = plan.startMessageId,
                        endMessageId = plan.endMessageId,
                        tokensBefore = tokensBefore,
                        tokensAfter = summaryTokens,
                        fallbackLevel = 0,
                        summary = sanitized,
                    )
                }
            }
        }

        // L1: Deterministic rule-based summary
        val ruleBasedSummary = buildRuleBasedSummary(plan)
        val l1Tokens = estimator.estimate(ruleBasedSummary)

        if (tokensBefore <= 100 || l1Tokens < tokensBefore) {
            return CompactionResult(
                recordId = recordId,
                startMessageId = plan.startMessageId,
                endMessageId = plan.endMessageId,
                tokensBefore = tokensBefore,
                tokensAfter = l1Tokens,
                fallbackLevel = 1,
                summary = ruleBasedSummary,
            )
        }

        // L2: Hard truncate fallback
        val l2Summary = buildHardTruncateSummary(plan)
        val l2Tokens = estimator.estimate(l2Summary)

        return CompactionResult(
            recordId = recordId,
            startMessageId = plan.startMessageId,
            endMessageId = plan.endMessageId,
            tokensBefore = tokensBefore,
            tokensAfter = l2Tokens,
            fallbackLevel = 2,
            summary = l2Summary,
        )
    }

    fun redactSecrets(text: String): String {
        var result = text
        for (pattern in SECRET_PATTERNS) {
            result = result.replace(pattern, "[REDACTED_SECRET]")
        }
        return result
    }

    internal fun buildRuleBasedSummary(plan: CompactionPlan): String {
        val userMessages = plan.body.filter { it.role == MessageRole.USER }
        val assistantMessages = plan.body.filter { it.role == MessageRole.ASSISTANT }
        val toolMessages = plan.body.filter { it.role == MessageRole.TOOL }

        val firstUserText = userMessages.firstOrNull()?.content?.take(300)?.replace("\n", " ")
            ?: plan.head.firstOrNull { it.role == MessageRole.USER }?.content?.take(300)?.replace("\n", " ")
            ?: "执行上下文历史任务"

        val keyActions = assistantMessages.takeLast(4).mapNotNull { msg ->
            val firstLine = msg.content.lines().firstOrNull { it.isNotBlank() }?.take(120)
            firstLine?.let { "- $it" }
        }.joinToString("\n").ifBlank { "- 已推进 ${plan.body.size} 条历史轮次交互" }

        val spills = toolMessages.mapNotNull { msg ->
            if (msg.content.contains("spill://")) {
                val match = Regex("spill://[^\\s,\\]\"']+").find(msg.content)?.value
                match?.let { "- $it [工具执行产生的大输出缓存]" }
            } else null
        }.distinct().joinToString("\n").ifBlank { "无独立 spill 输出缓存" }

        return """
## 1. 任务与目标
$firstUserText

## 2. 已完成与关键决策
$keyActions

## 3. 当前状态
中间历史 ${plan.body.size} 条消息已完成折叠并持久化归档。

## 4. 重要事实与约束
已继承前序交互所有约束边界与用户配置偏好。

## 5. 工具产出索引
$spills

## 6. 下一步
继续执行后续指令，推进当前未完成目标。

## 7. 必须保留的原文
- 用户近期关键交互与工具调用完整保留在 Tail 区域。
""".trimIndent()
    }

    private fun buildHardTruncateSummary(plan: CompactionPlan): String {
        return """
## 1. 任务与目标
历史对话已超出当前模型上下文预算。

## 2. 已完成与关键决策
已省略中间 ${plan.body.size} 条交互消息（L2 紧凑硬折叠）。

## 3. 当前状态
上下文已压至安全水位。

## 4. 重要事实与约束
注意防范重复执行前期已完成动作。

## 5. 工具产出索引
无

## 6. 下一步
直接继续当前工作。

## 7. 必须保留的原文
无
""".trimIndent()
    }
}
