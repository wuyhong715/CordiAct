package com.cordiact.harness.deepseek

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FollowPipeReceiverTest {
    private val target = FollowTarget("host-a", "local-a", "session-a")
    private val baseline = frame(0, -1, active = false, running = false)

    @Test fun everySplitAndMultipleFramesUseWireOrdinalAndCompleteReplacements() {
        val bytes = (baseline + frame(1, -1, true, true) + frame(2, 10, true, false)).encodeToByteArray()
        for (split in 0..bytes.size) {
            val decoder = FollowPipeReceiver()
            val handle = decoder.begin(target)
            assertEquals(FollowStatus.AwaitingBaseline, decoder.status(handle))
            assertEquals(FollowPipeReceiver.Receive.ACCEPTED, decoder.receive(handle, bytes, 0, split))
            if (split < baseline.length) assertEquals(FollowStatus.AwaitingBaseline, decoder.status(handle))
            assertEquals(FollowPipeReceiver.Receive.ACCEPTED, decoder.receive(handle, bytes, split))
            assertEquals(FollowStatus.Current(FollowSessionState(10, true, false)), decoder.status(handle))
        }
    }

    @Test fun malformedFramesRetireAndEraseThePreviousProjection() {
        val normal = frame(1, 0, true, false)
        val invalid = listOf(
            "\n", "{}\n", " $normal", normal.replace("\n", "\r\n"),
            normal.replace("\"cursor\":0", "\"cursor\":0,\"cursor\":1"),
            normal.replace("\"cursor\":0", "\"other\":0"),
            normal.replace("\"delivery\":1,\"cursor\":0", "\"cursor\":0,\"delivery\":1"),
            normal.replace("\"cursor\":0", "\"cursor\":-0"),
            normal.replace("\"cursor\":0", "\"cursor\":1e1"),
            normal.replace("\"cursor\":0", "\"cursor\":01"),
            normal.replace("\"cursor\":0", "\"cursor\":9007199254740992"),
            normal.replace("\"cursor\":0", "\"cursor\":-2"),
            normal.replace("\"running\":false", "\"running\":0"),
            normal.replace("true", "\"true\""),
            normal.replace("\"active\":true", "\"active\":true,\"token\":\"sensitive\""),
            frame(1, 0, false, true),
            "非 ASCII\n", "\u0000\n", "x".repeat(128),
        ).map(String::encodeToByteArray) + listOf(byteArrayOf(0x80.toByte(), 10))
        for (payload in invalid) {
            val decoder = FollowPipeReceiver()
            val handle = decoder.begin(target)
            decoder.receive(handle, baseline.encodeToByteArray())
            assertEquals(FollowPipeReceiver.Receive.RETIRED, decoder.receive(handle, payload))
            assertEquals(FollowStatus.Ended(FollowEnd.INVALID_SOURCE), decoder.status(handle))
            assertEquals(FollowPipeReceiver.Receive.STALE, decoder.receive(handle, normal.encodeToByteArray()))
        }
    }

    @Test fun repeatedMissingOrBackwardWireFramesAreNotRetriedOrRenumbered() {
        for ((first, next) in listOf(baseline to baseline, baseline to frame(2, 4, true, true),
            frame(0, 10, true, false) to frame(1, 9, true, false))) {
            val decoder = FollowPipeReceiver()
            val handle = decoder.begin(target)
            assertEquals(FollowPipeReceiver.Receive.RETIRED, decoder.receive(handle, (first + next).encodeToByteArray()))
            assertEquals(FollowStatus.Ended(FollowEnd.INVALID_SOURCE), decoder.status(handle))
        }
        val decoder = FollowPipeReceiver()
        val handle = decoder.begin(target)
        assertEquals(FollowPipeReceiver.Receive.RETIRED, decoder.receive(handle, frame(1, 0, true, true).encodeToByteArray()))
    }

    @Test fun terminalSignalsDistinguishTruncationAndLocallyKnownWithdrawal() {
        val decoder = FollowPipeReceiver()
        val open = decoder.begin(target)
        decoder.receive(open, baseline.encodeToByteArray())
        assertTrue(decoder.eof(open))
        assertEquals(FollowStatus.Ended(FollowEnd.DISCONNECTED), decoder.status(open))
        assertFalse(decoder.end(open, FollowEnd.ACCESS_LOST))

        val partial = decoder.begin(target)
        decoder.receive(partial, baseline.dropLast(1).encodeToByteArray())
        assertTrue(decoder.eof(partial))
        assertEquals(FollowStatus.Ended(FollowEnd.INVALID_SOURCE), decoder.status(partial))

        val revoked = decoder.begin(target)
        decoder.receive(revoked, baseline.encodeToByteArray())
        assertTrue(decoder.end(revoked, FollowEnd.ACCESS_LOST))
        assertEquals(FollowStatus.Ended(FollowEnd.ACCESS_LOST), decoder.status(revoked))
        assertFalse(decoder.eof(revoked))
        assertEquals(FollowPipeReceiver.Receive.STALE, decoder.receive(revoked, baseline.encodeToByteArray()))
    }

    @Test fun aToBToARejectsOldFragmentsCallbacksAndAlienHandles() {
        val decoder = FollowPipeReceiver()
        val a1 = decoder.begin(target)
        decoder.receive(a1, baseline.dropLast(1).encodeToByteArray())
        val b = decoder.begin(target.copy(localWorkspaceId = "local-b"))
        decoder.receive(b, baseline.encodeToByteArray())
        val a2 = decoder.begin(target)
        val alien = FollowPipeReceiver().begin(target)
        for (old in listOf(a1, b, alien)) {
            assertNull(decoder.status(old))
            assertEquals(FollowPipeReceiver.Receive.STALE, decoder.receive(old, "\n".encodeToByteArray()))
            assertFalse(decoder.eof(old))
            assertFalse(decoder.end(old, FollowEnd.ACCESS_LOST))
            assertFalse(decoder.clear(old))
        }
        assertEquals(FollowStatus.AwaitingBaseline, decoder.status(a2))
        decoder.receive(a2, baseline.encodeToByteArray())
        assertEquals(FollowStatus.Current(FollowSessionState(-1, false, false)), decoder.status(a2))
        assertTrue(decoder.clear(a2))
        assertNull(decoder.status(a2))
    }

    @Test fun exactNumericBoundAndReadLimitsDoNotDependOnBufferCapacity() {
        val decoder = FollowPipeReceiver()
        val handle = decoder.begin(target)
        val padded = ("!" + frame(0, 9_007_199_254_740_991L, true, true) + "!").encodeToByteArray()
        assertEquals(FollowPipeReceiver.Receive.ACCEPTED, decoder.receive(handle, padded, 1, padded.size - 2))
        assertEquals(FollowStatus.Current(FollowSessionState(9_007_199_254_740_991L, true, true)), decoder.status(handle))
        assertEquals(FollowPipeReceiver.Receive.RETIRED, decoder.receive(handle, ByteArray(4097)))
        assertEquals(FollowStatus.Ended(FollowEnd.INVALID_SOURCE), decoder.status(handle))
    }

    private fun frame(delivery: Long, cursor: Long, active: Boolean, running: Boolean): String =
        """{"delivery":$delivery,"cursor":$cursor,"active":$active,"running":$running}""" + "\n"
}
