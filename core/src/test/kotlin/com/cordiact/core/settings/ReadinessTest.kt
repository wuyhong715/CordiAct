package com.cordiact.core.settings

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReadinessTest {
    @Test fun configurationDoesNotClaimNetworkSuccess() {
        val ready = ConfigurationReadinessProbe("p", "P", { true }, route = "settings://models/providers").probe()
        assertEquals(ReadinessState.READY, ready.state)
        assertEquals("CONFIGURED_NETWORK_UNTESTED", ready.reasonCode)
        assertTrue(ready.summary.contains("未检测"))
        assertEquals(ReadinessState.NEEDS_ACTION, ConfigurationReadinessProbe("p", "P", { false }, route = "x").probe().state)
        assertEquals(ReadinessState.DISABLED, ConfigurationReadinessProbe("p", "P", { false }, { false }, "x").probe().state)
    }
    @Test fun oneFailedProbeDoesNotHideOtherProblemsOrLeakExceptionText() {
        val failed = object : ReadinessProbe {
            override val id = "bad"
            override fun probe(): Readiness = error("secret-token")
        }
        val results = ReadinessRegistry(listOf(failed, ConfigurationReadinessProbe("ok", "OK", { false }, route = "x"))).all()
        assertEquals(2, results.size)
        assertEquals("PROBE_FAILED", results.first().reasonCode)
        assertFalse(results.toString().contains("secret-token"))
        assertEquals(ReadinessState.NEEDS_ACTION, results.last().state)
    }
    @Test fun duplicateIdentityIsRejected() {
        val probe = ConfigurationReadinessProbe("p", "P", { true }, route = "x")
        assertThrows(IllegalArgumentException::class.java) { ReadinessRegistry(listOf(probe, probe)) }
    }
}
