package com.cordiact.harness.deepseek

import com.cordiact.core.action.ActionRequest

/** Adapter contract; provider/runtime details stay outside the Android UI and core policy. */
interface DeepSeekHarnessAdapter {
    suspend fun submit(request: ActionRequest): HarnessSubmission
}

data class HarnessSubmission(val accepted: Boolean, val externalId: String? = null, val message: String? = null)
