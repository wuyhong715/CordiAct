package com.cordiact.app.experience.settings

import org.junit.Assert.*
import org.junit.Test

class SettingsAuditErrorTest {
    @Test fun failedAttemptAndUncertainCommitHaveDistinctMessages() {
        assertTrue(settingsError(com.cordiact.core.settings.SettingsAttemptAuditFailed()).contains("未执行"))
        val uncertain = settingsError(com.cordiact.core.settings.SettingsMutationUncertain())
        assertTrue(uncertain.contains("可能已有部分更改"))
        assertTrue(uncertain.contains("不要直接重复提交"))
    }
}
