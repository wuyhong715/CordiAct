package com.cordiact.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenSafetyTest {

    @Test
    fun blocksInstallerAndPermissionControllers() {
        assertTrue(ProtectedPages.isProtected("com.android.packageinstaller", emptySequence()))
        assertTrue(ProtectedPages.isProtected("com.android.permissioncontroller", emptySequence()))
        assertTrue(ProtectedPages.isProtected("com.android.shell", emptySequence()))
    }

    @Test
    fun protectsSensitiveSettingsPages() {
        assertTrue(ProtectedPages.isProtected("com.android.settings", sequenceOf("无障碍")))
        assertTrue(ProtectedPages.isProtected("com.android.settings", sequenceOf("Developer options")))
        assertTrue(ProtectedPages.isProtected("com.android.settings", sequenceOf("开发者选项")))
    }

    @Test
    fun allowsOrdinarySettingsPages() {
        assertFalse(ProtectedPages.isProtected("com.android.settings", sequenceOf("网络与互联网")))
        assertFalse(ProtectedPages.isProtected("com.android.settings", sequenceOf("显示")))
    }

    @Test
    fun failsClosedWhenSettingsPageIsUnknown() {
        assertTrue(ProtectedPages.isProtected("com.android.settings", emptySequence()))
    }

    @Test
    fun ignoresUnrelatedPackages() {
        assertFalse(ProtectedPages.isProtected("com.example.notes", sequenceOf("无障碍")))
    }

    @Test
    fun defaultPoliciesMatchDesign() {
        assertEquals(AppPolicy.BLOCK, AppPolicyTable.policyFor("com.eg.android.AlipayGphone"))
        assertEquals(AppPolicy.CONFIRM_EACH_STEP, AppPolicyTable.policyFor("com.tencent.mm"))
        assertEquals(AppPolicy.ALLOW, AppPolicyTable.policyFor("com.example.notes"))
    }

    @Test
    fun overridesReplaceDefaults() {
        val overrides = mapOf("com.tencent.mm" to AppPolicy.READ_ONLY)
        assertEquals(AppPolicy.READ_ONLY, AppPolicyTable.policyFor("com.tencent.mm", overrides))
    }

    @Test
    fun screenHandleParsing() {
        assertTrue(ScreenHandle.parse("s0")!!.isMain)
        assertFalse(ScreenHandle.parse("b1")!!.isMain)
        assertEquals(null, ScreenHandle.parse("display:6"))
        assertEquals(null, ScreenHandle.parse("../etc"))
    }
}
