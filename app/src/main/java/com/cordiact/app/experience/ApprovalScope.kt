package com.cordiact.app.experience

import androidx.compose.runtime.compositionLocalOf
import com.cordiact.core.action.ApprovalOwner

/**
 * Sessions with a pending approval they own. Ambient rather than a parameter so the
 * sidebar keeps one stable composable signature for its callers and tests.
 */
internal val LocalApprovalOwners = compositionLocalOf { emptySet<ApprovalOwner>() }
