package com.cordiact.core.automation

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

class AutomationExecutionLeaseTest {
    @Test fun exclusiveOwnerAndExplicitRelease() {
        val dir = Files.createTempDirectory("execution-lease").toFile()
        try {
            val file = dir.resolve("runtime.lease")
            AutomationExecutionLease.acquire(file).use {
                assertFails { AutomationExecutionLease.acquire(file) }
                assertTrue(file.exists())
            }
            AutomationExecutionLease.acquire(file).use { assertTrue(file.exists()) }
        } finally { dir.deleteRecursively() }
    }
}
