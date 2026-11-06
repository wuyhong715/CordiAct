package com.cordiact.core.automation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShellCommandPolicyTest {

    @Test
    fun classifiesReadOnlyCommands() {
        assertEquals(ShellCommandClass.READ_ONLY, ShellCommandPolicy.classify("pm list packages -3"))
        assertEquals(ShellCommandClass.READ_ONLY, ShellCommandPolicy.classify("dumpsys window"))
        assertEquals(ShellCommandClass.READ_ONLY, ShellCommandPolicy.classify("id"))
        assertEquals(ShellCommandClass.READ_ONLY, ShellCommandPolicy.classify("getprop ro.build.version.sdk"))
    }

    @Test
    fun classifiesMutatingCommands() {
        assertEquals(ShellCommandClass.MUTATING, ShellCommandPolicy.classify("am start -n com.android.settings/.Settings"))
        assertEquals(ShellCommandClass.MUTATING, ShellCommandPolicy.classify("input -d 6 tap 100 200"))
        assertEquals(ShellCommandClass.MUTATING, ShellCommandPolicy.classify("settings put global foo 1"))
    }

    @Test
    fun classifiesDestructiveCommands() {
        assertEquals(ShellCommandClass.DESTRUCTIVE, ShellCommandPolicy.classify("rm -rf /sdcard/DCIM"))
        assertEquals(ShellCommandClass.DESTRUCTIVE, ShellCommandPolicy.classify("reboot"))
        assertEquals(ShellCommandClass.DESTRUCTIVE, ShellCommandPolicy.classify("pm uninstall com.tencent.mm"))
        assertEquals(ShellCommandClass.DESTRUCTIVE, ShellCommandPolicy.classify("svc power shutdown"))
        assertEquals(
            ShellCommandClass.DESTRUCTIVE,
            ShellCommandPolicy.classify("settings put secure enabled_accessibility_services com.foo/.Svc"),
        )
    }

    @Test
    fun rejectsEmptyAndOversizedCommands() {
        assertEquals(ShellCommandClass.INVALID, ShellCommandPolicy.classify("   "))
        assertEquals(ShellCommandClass.INVALID, ShellCommandPolicy.classify("a".repeat(ShellLimits.MAX_COMMAND_CHARS + 1)))
    }

    @Test
    fun gatesOnPrivilegeLevelAndAdvancedSwitch() {
        assertFalse(ShellCommandPolicy.isAllowed("pm list packages", PrivilegeLevel.NONE, advancedEnabled = true))
        assertTrue(ShellCommandPolicy.isAllowed("pm list packages", PrivilegeLevel.SHELL, advancedEnabled = false))
        assertFalse(ShellCommandPolicy.isAllowed("rm -rf /sdcard", PrivilegeLevel.SHELL, advancedEnabled = false))
        assertTrue(ShellCommandPolicy.isAllowed("rm -rf /sdcard", PrivilegeLevel.ROOT, advancedEnabled = true))
    }

    @Test
    fun doesNotTreatNestedRmAsSafe() {
        assertEquals(
            ShellCommandClass.DESTRUCTIVE,
            ShellCommandPolicy.classify("sh -c 'rm -rf /data/local/tmp/x'"),
        )
    }
}
