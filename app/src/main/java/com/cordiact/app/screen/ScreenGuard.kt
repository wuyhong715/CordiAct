package com.cordiact.app.screen

import com.cordiact.core.automation.AppPolicy
import com.cordiact.core.automation.AppPolicyTable
import com.cordiact.core.automation.ConfirmationDecision
import com.cordiact.core.automation.ConfirmationGate
import com.cordiact.core.automation.ConfirmationRequest
import com.cordiact.core.automation.ProtectedPages
import com.cordiact.core.automation.ScreenError
import com.cordiact.core.automation.ScreenErrorCodes
import com.cordiact.core.automation.ScreenSnapshot

/**
 * Single place where every screen action passes before the driver sees it.
 *
 * The checks are intentionally conservative: a page we cannot classify is
 * treated as protected, and an app with `CONFIRM_EACH_STEP` gets a prompt for
 * every action rather than once per session.
 */
class ScreenGuard(
    private val confirmationGate: ConfirmationGate,
    private val policyOverrides: () -> Map<String, AppPolicy> = { emptyMap() },
) {

    fun checkAction(snapshot: ScreenSnapshot, action: String, targetLabel: String?): ScreenError? {
        val packageName = snapshot.packageName
        if (packageName.isBlank()) return null

        if (ProtectedPages.isBlockedPackage(packageName)) {
            return ScreenError(
                code = ScreenErrorCodes.APP_BLOCKED,
                message = "$packageName is on the blocked list",
                recovery = "This app cannot be automated. Use it manually.",
            )
        }

        val titles = snapshot.elements
            .asSequence()
            .filter { it.bounds.top < snapshot.height * 0.2f || it.bounds.bottom > snapshot.height * 0.85f }
            .map { it.label }

        if (ProtectedPages.isProtected(packageName, titles)) {
            return ScreenError(
                code = ScreenErrorCodes.PROTECTED_PAGE,
                message = "This page changes system or account security settings",
                recovery = "Complete it manually; the agent may read but not act on protected pages.",
            )
        }

        return when (AppPolicyTable.policyFor(packageName, policyOverrides())) {
            AppPolicy.ALLOW -> null
            AppPolicy.READ_ONLY -> ScreenError(
                code = ScreenErrorCodes.APP_BLOCKED,
                message = "$packageName is read-only for the agent",
                recovery = "Ask the user to perform this step.",
            )
            AppPolicy.BLOCK -> ScreenError(
                code = ScreenErrorCodes.APP_BLOCKED,
                message = "$packageName is blocked for the agent",
                recovery = "Ask the user to perform this step.",
            )
            AppPolicy.CONFIRM_EACH_STEP -> {
                val decision = confirmationGate.confirm(
                    ConfirmationRequest(
                        packageName = packageName,
                        action = action,
                        targetLabel = targetLabel,
                        reason = "This app requires confirmation for every action",
                    ),
                )
                when (decision) {
                    ConfirmationDecision.ALLOW -> null
                    ConfirmationDecision.DENY -> ScreenError(
                        code = ScreenErrorCodes.CONFIRMATION_REQUIRED,
                        message = "The user declined this action",
                        recovery = "Do not retry the same action; ask what to do instead.",
                    )
                    ConfirmationDecision.TIMEOUT -> ScreenError(
                        code = ScreenErrorCodes.CONFIRMATION_REQUIRED,
                        message = "No confirmation arrived in time",
                        recovery = "Treat as declined; ask the user before retrying.",
                    )
                }
            }
        }
    }
}
