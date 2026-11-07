package com.cordiact.core.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecutionContractsTest {

    @Test
    fun `test execution result properties`() {
        val success = ExecutionResult(exitCode = 0, stdout = "hello")
        assertTrue(success.isSuccess)
        assertFalse(success.timedOut)
        assertFalse(success.truncated)

        val failure = ExecutionResult.failure("something broke", exitCode = 127)
        assertFalse(failure.isSuccess)
        assertEquals(127, failure.exitCode)
        assertEquals("something broke", failure.stderr)

        val timedOut = ExecutionResult.timedOut("timeout reached")
        assertFalse(timedOut.isSuccess)
        assertTrue(timedOut.timedOut)
        assertEquals(-1, timedOut.exitCode)
    }

    @Test
    fun `test truncation logic`() {
        val shortText = "abc"
        val (s1, trunc1) = ExecutionResult.truncateOutput(shortText, limit = 10)
        assertEquals("abc", s1)
        assertFalse(trunc1)

        val longText = "a".repeat(100)
        val (s2, trunc2) = ExecutionResult.truncateOutput(longText, limit = 50)
        assertTrue(trunc2)
        assertTrue(s2.startsWith("a".repeat(50)))
        assertTrue(s2.contains("truncated 50 chars"))
    }
}
