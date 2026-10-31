package com.cordiact.integration.harness

import com.cordiact.core.action.ActionRequest

/** Integration seam; provider/runtime details stay outside the Android UI and core policy. */
interface DeepSeekHarnessAdapter {
    suspend fun submit(request: ActionRequest): HarnessSubmission
}

data class HarnessSubmission(val accepted: Boolean, val externalId: String? = null, val message: String? = null)
