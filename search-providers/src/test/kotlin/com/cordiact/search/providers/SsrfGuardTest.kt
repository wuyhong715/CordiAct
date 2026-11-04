package com.cordiact.search.providers

import com.cordiact.core.search.SearchException
import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SsrfGuardTest {
    private val guard = SsrfGuard()

    private fun reject(url: String, host: String? = null) {
        val failure = assertFailsWith<SearchException>("expected $url to be rejected") {
            if (host == null) {
                guard.requireFetchable(url)
            } else {
                SsrfGuard { arrayOf(InetAddress.getByName(host)) }.requireFetchable(url)
            }
        }
        assertEquals(SearchException.Code.INVALID_URL, failure.code)
    }

    @Test
    fun `rejects loopback in every spelling`() {
        reject("http://127.0.0.1/")
        reject("http://127.0.0.1:8080/admin")
        reject("http://localhost/")
        reject("http://[::1]/")
        reject("http://2130706433/")            // 127.0.0.1 as a decimal integer
        reject("http://127.1/")                 // short form of 127.0.0.1
        // 0177.0.0.1 is NOT octal to the JVM: it resolves to 177.0.0.1, a public address.
        // Asserting it is rejected would encode a misunderstanding of the resolver.
    }

    @Test
    fun `rejects private ranges`() {
        reject("http://10.0.0.1/")
        reject("http://172.16.0.1/")
        reject("http://172.31.255.255/")
        reject("http://192.168.1.1/")
        reject("http://100.64.0.1/")            // CGNAT
    }

    @Test
    fun `rejects link local and cloud metadata`() {
        reject("http://169.254.169.254/latest/meta-data/")
        reject("http://[fe80::1]/")
        reject("http://[fd00::1]/")
    }

    @Test
    fun `rejects non http schemes and credentials`() {
        reject("file:///etc/passwd")
        reject("ftp://example.com/x")
        reject("gopher://example.com/")
        reject("https://user:pass@example.com/")
    }

    @Test
    fun `rejects a hostname that resolves to a private address`() {
        reject("https://internal.example.com/", host = "10.1.2.3")
        reject("https://metadata.example.com/", host = "169.254.169.254")
    }

    @Test
    fun `accepts a public hostname`() {
        val uri = SsrfGuard { arrayOf(InetAddress.getByName("93.184.216.34")) }
            .requireFetchable("https://example.com/page")
        assertEquals("example.com", uri.host)
    }

    @Test
    fun `accepts a public literal address`() {
        guard.requireFetchable("http://93.184.216.34/")
    }

    @Test
    fun `an ipv4-mapped ipv6 loopback is rejected`() {
        reject("http://[::ffff:127.0.0.1]/")
        reject("http://[::ffff:192.168.0.1]/")
    }

    @Test
    fun `classification matches the reserved ranges`() {
        assertTrue(guard.isPublic(InetAddress.getByName("93.184.216.34")))
        assertTrue(guard.isPublic(InetAddress.getByName("2606:4700::1111")))
        assertEquals(false, guard.isPublic(InetAddress.getByName("192.0.2.1")))   // documentation
        assertEquals(false, guard.isPublic(InetAddress.getByName("198.51.100.1")))
        assertEquals(false, guard.isPublic(InetAddress.getByName("203.0.113.1")))
        assertEquals(false, guard.isPublic(InetAddress.getByName("198.18.0.1")))   // benchmarking
        assertEquals(false, guard.isPublic(InetAddress.getByName("224.0.0.1")))    // multicast
        assertEquals(false, guard.isPublic(InetAddress.getByName("0.0.0.0")))
    }

    @Test
    fun `an unresolvable host is rejected rather than allowed`() {
        val failing = SsrfGuard { throw java.net.UnknownHostException("nope") }
        val failure = assertFailsWith<SearchException> { failing.requireFetchable("https://does-not-exist.invalid/") }
        assertEquals(SearchException.Code.INVALID_URL, failure.code)
    }

    @Test
    fun `a host with one private answer among public ones is rejected`() {
        val mixed = SsrfGuard {
            arrayOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.5"))
        }
        val failure = assertFailsWith<SearchException> { mixed.requireFetchable("https://mixed.example.com/") }
        assertEquals(SearchException.Code.INVALID_URL, failure.code)
    }
}
