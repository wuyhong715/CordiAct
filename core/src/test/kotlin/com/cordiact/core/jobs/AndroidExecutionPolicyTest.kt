package com.cordiact.core.jobs

import kotlin.test.*

class AndroidExecutionPolicyTest {
    @Test fun genericJobsAreNotDataSyncEvidence() {
        JobKind.entries.forEach { assertFalse(AndroidExecutionPolicy.allowsDataSync(it)) }
    }
    @Test fun missingReceiptExpiresWithoutAcceptingLateAck() {
        val gate = ForegroundReceiptGate()
        gate.request("one")
        assertFalse(gate.expire("old"))
        assertTrue(gate.expire("one"))
        assertEquals(ForegroundExecutionState.START_TIMEOUT, gate.state)
        assertFalse(gate.acknowledge("one", true))
        gate.request("two")
        gate.acknowledge("two", true)
        assertFalse(gate.expire("two"))
        assertEquals(ForegroundExecutionState.ACTIVE, gate.state)
    }
    @Test fun requestIsNotActiveUntilReceipt() {
        val gate = ForegroundReceiptGate()
        gate.request("one")
        assertEquals(ForegroundExecutionState.REQUESTED, gate.state)
        assertFalse(gate.acknowledge("obsolete", true))
        assertTrue(gate.acknowledge("one", true))
        assertEquals(ForegroundExecutionState.ACTIVE, gate.state)
    }
    @Test fun stoppedAndTimedOutRequestsCannotReturnActive() {
        val gate = ForegroundReceiptGate()
        gate.request("one")
        gate.stop(timedOut = true)
        assertFalse(gate.acknowledge("one", true))
        assertEquals(ForegroundExecutionState.TIMED_OUT, gate.state)
        gate.request("two")
        assertTrue(gate.acknowledge("two", false))
        assertFalse(gate.acknowledge("two", true))
        assertEquals(ForegroundExecutionState.REJECTED, gate.state)
    }
}
