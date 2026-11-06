package com.cordiact.app.screen

import android.content.Context
import com.cordiact.core.automation.ConfirmationDecision
import com.cordiact.core.automation.ConfirmationGate
import com.cordiact.core.automation.ConfirmationRequest

/**
 * Default gate: denies by default.
 *
 * A real prompt needs a foreground Activity; tools can run while CordiAct is
 * backgrounded, and blocking a worker thread on UI that may never appear is
 * worse than refusing. The host can install a UI-backed gate later; until then
 * "confirm each step" apps are effectively read-only, which is the safe side.
 */
class ConfirmationGateImpl(private val context: Context) : ConfirmationGate {
    override fun confirm(request: ConfirmationRequest): ConfirmationDecision =
        ConfirmationDecision.DENY
}
