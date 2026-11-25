package com.cordiact.harness.deepseek

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FollowPairingHandoffTest {
    private val nonce = "01".repeat(32)
    private val proof = "ab".repeat(32)
    private val prefix = "cordiact-follow-envelope/1 "

    @Test fun oneRequestThenEofAndBoundedCanonicalReply() {
        val sealed = ByteArray(864) { it.toByte() }
        var requestClosed = false
        val request = object : ByteArrayOutputStream() {
            override fun close() { requestClosed = true }
        }
        val data = ByteArrayInputStream((prefix + encode(sealed) + "\n").encodeToByteArray())
        val reply = object : InputStream() {
            override fun read(): Int {
                assertTrue(requestClosed)
                return data.read()
            }
        }
        var cancelled = 0
        val pipe = FollowPairingHandoff(reply, request) { cancelled++ }
        val channel = assertNotNull(pipe.take())
        assertContentEquals(sealed, exchangeFollowPairing(channel, nonce, proof) { true })
        assertEquals("cordiact-follow-review/1 $nonce $proof\n", request.toString(Charsets.US_ASCII))
        assertNull(pipe.take())
        pipe.close()
        assertEquals(0, cancelled)
    }

    @Test fun rejectsNoncanonicalFramesAndAnyTrailingBytes() {
        val exact = encode(ByteArray(38))
        val good = "$prefix$exact\n"
        for (bad in listOf("", good.dropLast(1), "$good\n", good + good, "$prefix${exact.dropLast(1)}\n",
            "$prefix${exact.dropLast(2)}B=\n", good.replace("\n", "\r\n"), good.replace("/1", "/2"),
            "$prefix${encode(ByteArray(37))}\n", "$prefix${encode(ByteArray(865))}\n")) {
            val channel = FollowPairingHandoff.Channel(ByteArrayInputStream(bad.encodeToByteArray()), ByteArrayOutputStream()) {}
            assertNull(exchangeFollowPairing(channel, nonce, proof) { true })
        }
    }

    @Test fun failedDispatchDoesNotFlushReadOrRepeat() {
        val request = object : java.io.OutputStream() {
            override fun write(b: Int) = error("Unexpected per-byte write")
            override fun write(b: ByteArray) { throw IOException("uncertain") }
            override fun flush() = error("Failed writes must not be flushed")
        }
        val reply = object : InputStream() { override fun read(): Int = error("No response expected") }
        val channel = FollowPairingHandoff.Channel(reply, request) {}
        assertFailsWith<IOException> { exchangeFollowPairing(channel, nonce, proof) { true } }
    }

    private fun encode(sealed: ByteArray) = Base64.getEncoder().encodeToString(sealed)
}
