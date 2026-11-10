package com.cordiact.app.security

import com.cordiact.core.action.ApprovalGateway
import com.cordiact.core.action.ApprovalRequest
import com.cordiact.core.security.CredentialOperation
import com.cordiact.core.security.HumanApprovalChannel
import com.cordiact.core.security.PendingSecurityApproval
import com.cordiact.core.security.SecurityApprovalDecision
import com.cordiact.core.security.SecurityApprovalResult

/**
 * Bridges the security gate to the existing trusted approval UI. The UI keeps polling
 * [ApprovalGateway.pendingRequests] and calling [ApprovalGateway.respond]; this adapter never
 * exposes a tool-callable approve method.
 */
class ApprovalGatewayHumanChannel(
    private val gateway: ApprovalGateway,
    private val timeoutMillis: Long = 120_000L,
) : HumanApprovalChannel {
    override fun request(ticket: PendingSecurityApproval): SecurityApprovalResult {
        val intent = ticket.intent
        val request = ApprovalRequest(
            id = ticket.requestId,
            toolName = "credential_vault",
            summary = summary(intent.operation),
            details = buildMap {
                put("操作", intent.operation.name)
                put("Workspace", intent.workspaceId)
                put("凭证", intent.credentialId ?: "(列表/新建)")
                intent.expectedRevision?.let { put("版本", it.toString()) }
                put("Agent 声称用途", intent.declaredPurpose.ifBlank { "(未提供)" })
                intent.canonicalTarget?.let { put("目标", it.canonical()) }
                intent.effectiveReceiver?.let { put("接收方", it.sanitized()) }
                put("说明", "批准仅覆盖本次操作；不会生成可复用授权。")
            },
            riskLevel = when (intent.operation) {
                CredentialOperation.READ_PLAINTEXT, CredentialOperation.USE -> ApprovalRequest.RiskLevel.CRITICAL
                CredentialOperation.WRITE, CredentialOperation.DELETE -> ApprovalRequest.RiskLevel.HIGH
                CredentialOperation.LIST, CredentialOperation.READ_METADATA -> ApprovalRequest.RiskLevel.MEDIUM
                CredentialOperation.TOOL_EXECUTION -> ApprovalRequest.RiskLevel.HIGH
            },
        )
        val decision = gateway.requestApproval(request, timeoutMillis)
        return if (decision.approved) {
            SecurityApprovalResult(SecurityApprovalDecision.ALLOW, "HUMAN_ALLOWED")
        } else {
            SecurityApprovalResult(
                SecurityApprovalDecision.DENY,
                "HUMAN_REJECTED",
                decision.reason?.take(280) ?: "",
            )
        }
    }

    private fun summary(operation: CredentialOperation): String = when (operation) {
        CredentialOperation.LIST -> "读取凭证列表（元数据）"
        CredentialOperation.READ_METADATA -> "读取凭证元数据"
        CredentialOperation.READ_PLAINTEXT -> "读取凭证明文"
        CredentialOperation.WRITE -> "新增或修改凭证"
        CredentialOperation.DELETE -> "删除凭证"
        CredentialOperation.USE -> "使用凭证发起 HTTPS 请求"
        CredentialOperation.TOOL_EXECUTION -> "执行工具调用"
    }
}
