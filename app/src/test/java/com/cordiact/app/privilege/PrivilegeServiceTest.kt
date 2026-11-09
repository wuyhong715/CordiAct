package com.cordiact.app.privilege

import com.cordiact.core.automation.PrivilegeLevel
import com.cordiact.core.automation.PrivilegeStatus
import com.cordiact.core.automation.PrivilegedChannel
import com.cordiact.core.automation.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivilegeServiceTest {

    @Test
    fun defaultSettingsNeverReachRootChannelForEitherAdvancedValue() {
        val fakeRoot = FakeChannel(PrivilegeLevel.ROOT)
        val service = PrivilegeService(fakeRoot, fakeRoot)
        val first = service.exec("id", 10_000, advanced = false)
        val second = service.exec("id", 10_000, advanced = true)
        assertEquals(-1, first.exitCode)
        assertEquals(-1, second.exitCode)
        assertEquals(0, fakeRoot.executed.size)
    }

    @Test
    fun defaultSettingsNeverUseRootForOrdinaryExec() {
        val shell = FakeChannel(PrivilegeLevel.SHELL)
        val root = FakeChannel(PrivilegeLevel.ROOT)
        val service = PrivilegeService(shell, root)

        val result = service.exec("id", 10_000, advanced = false)

        assertEquals(0, result.exitCode)
        assertEquals(1, shell.executed.size)
        assertEquals(0, root.executed.size)
        assertFalse(service.hasRoot())
    }

    @Test
    fun rootRequestIsRefusedWhenRootSwitchIsOff() {
        val shell = FakeChannel(PrivilegeLevel.SHELL)
        val root = FakeChannel(PrivilegeLevel.ROOT)
        val service = PrivilegeService(shell, root)

        val result = service.exec("id", 10_000, advanced = false, requireRoot = true)

        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("root is disabled"))
        assertEquals(0, shell.executed.size)
        assertEquals(0, root.executed.size)
    }

    @Test
    fun advancedFlagCannotBypassDisabledAdvancedSwitchEvenWithRoot() {
        val shell = FakeChannel(PrivilegeLevel.SHELL)
        val root = FakeChannel(PrivilegeLevel.ROOT)
        val service = PrivilegeService(
            shell, root,
            PrivilegeService.PrivilegeSettings(advancedCommandsEnabled = false, rootEnabled = true),
        )

        val result = service.exec("id", 10_000, advanced = true, requireRoot = true)

        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("advanced commands are disabled"))
        assertEquals(0, shell.executed.size)
        assertEquals(0, root.executed.size)
    }

    @Test
    fun explicitRootRunsOnRootChannelWhenBothSwitchesAreOn() {
        val shell = FakeChannel(PrivilegeLevel.SHELL)
        val root = FakeChannel(PrivilegeLevel.ROOT)
        val service = PrivilegeService(
            shell, root,
            PrivilegeService.PrivilegeSettings(advancedCommandsEnabled = true, rootEnabled = true),
        )

        val result = service.exec("id", 10_000, advanced = true, requireRoot = true)

        assertEquals(0, result.exitCode)
        assertEquals(0, shell.executed.size)
        assertEquals(1, root.executed.size)
        assertTrue(service.hasRoot())
    }

    @Test
    fun shellChannelThatIsActuallyRootIsNotUsedImplicitly() {
        val shell = FakeChannel(PrivilegeLevel.ROOT)
        val root = FakeChannel(PrivilegeLevel.ROOT)
        val service = PrivilegeService(shell, root)

        val result = service.exec("id", 10_000, advanced = false)

        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("explicit priv_root"))
        assertEquals(0, shell.executed.size)
        assertEquals(0, root.executed.size)
    }

    @Test
    fun rootRequestIsRefusedWhenRootChannelOnlyHasShell() {
        val shell = FakeChannel(PrivilegeLevel.SHELL)
        val root = FakeChannel(PrivilegeLevel.SHELL)
        val service = PrivilegeService(
            shell, root,
            PrivilegeService.PrivilegeSettings(rootEnabled = true),
        )

        val result = service.exec("id", 10_000, advanced = false, requireRoot = true)

        assertEquals(-1, result.exitCode)
        assertTrue(result.stderr.contains("uid 0"))
        assertEquals(0, shell.executed.size)
        assertEquals(0, root.executed.size)
    }

    @Test
    fun ordinaryExecIsRefusedWhenOnlyRootChannelExists() {
        val shell = FakeChannel(PrivilegeLevel.NONE)
        val root = FakeChannel(PrivilegeLevel.ROOT)
        val service = PrivilegeService(
            shell, root,
            PrivilegeService.PrivilegeSettings(rootEnabled = true),
        )

        val result = service.exec("id", 10_000, advanced = false)

        assertEquals(-1, result.exitCode)
        assertEquals(0, root.executed.size)
    }

    private class FakeChannel(private val level: PrivilegeLevel) : PrivilegedChannel {
        val executed = mutableListOf<String>()
        override fun status(): PrivilegeStatus = PrivilegeStatus(level, reason = "fake")
        override fun exec(command: String, timeoutMs: Long): ShellResult {
            executed += command
            return ShellResult(0, "ok", "")
        }
    }
}
