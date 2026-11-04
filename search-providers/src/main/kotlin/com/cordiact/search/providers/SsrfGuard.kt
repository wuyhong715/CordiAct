package com.cordiact.search.providers

import com.cordiact.core.search.SearchException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * Decides whether a user-supplied URL may be fetched by this device.
 *
 * This guard exists only for URLs that came from outside (search results, model
 * output). Provider endpoints are operator-configured and are deliberately not
 * checked here: a self-hosted SearXNG on a LAN is a legitimate target, while
 * `http://192.168.1.1/admin` arriving from a search result is not.
 *
 * The check is resolve-then-classify: every address a hostname resolves to must
 * be public. Rejecting on any single blocked address keeps a host that resolves
 * to both a public and a private address from slipping through.
 *
 * Known residual risk: DNS rebinding. The name is resolved here and connected to
 * afterwards, so an attacker controlling DNS could answer differently the second
 * time. Closing that requires connecting to the validated address with an
 * explicit Host header, which HttpURLConnection does not expose.
 */
open class SsrfGuard(
    private val resolver: (String) -> Array<InetAddress> = { host -> InetAddress.getAllByName(host) },
) {
    /** Throws [SearchException] with `INVALID_URL` when [url] must not be fetched. */
    fun requireFetchable(url: String): URI {
        val uri = runCatching { URI(url) }.getOrNull()
            ?: throw reject("URL is invalid")
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") throw reject("Only HTTP(S) URLs may be fetched")
        if (uri.userInfo != null) throw reject("URLs with embedded credentials are not allowed")
        val host = uri.host?.takeIf { it.isNotBlank() } ?: throw reject("URL has no host")

        val literal = parseLiteral(host)
        if (literal != null) {
            if (!isPublic(literal)) throw reject("URL points to a private or reserved address")
            return uri
        }
        val addresses = try {
            resolver(host)
        } catch (_: Exception) {
            throw reject("URL host could not be resolved")
        }
        if (addresses.isEmpty()) throw reject("URL host could not be resolved")
        if (addresses.any { !isPublic(it) }) {
            throw reject("URL host resolves to a private or reserved address")
        }
        return uri
    }

    /**
     * True when [address] is a globally routable unicast address.
     *
     * Loopback, link-local, site-local, multicast, unspecified and the special
     * ranges (CGNAT, benchmarking, documentation, reserved) are all false.
     */
    open fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress) return false
        if (address.isLinkLocalAddress || address.isSiteLocalAddress) return false
        if (address.isMulticastAddress) return false
        return when (address) {
            is Inet4Address -> isPublicV4(address.address)
            is Inet6Address -> isPublicV6(address)
            else -> false
        }
    }

    private fun isPublicV4(bytes: ByteArray): Boolean {
        val a = bytes[0].toInt() and 0xFF
        val b = bytes[1].toInt() and 0xFF
        return when {
            a == 0 -> false                                  // 0.0.0.0/8 this network
            a == 10 -> false                                 // private
            a == 127 -> false                                // loopback
            a == 169 && b == 254 -> false                    // link-local, cloud metadata
            a == 172 && b in 16..31 -> false                 // private
            a == 192 && b == 168 -> false                    // private
            a == 192 && b == 0 -> false                      // 192.0.0/24 + 192.0.2/24
            a == 100 && b in 64..127 -> false                // CGNAT 100.64/10
            a == 198 && b in 18..19 -> false                 // benchmarking
            a == 198 && b == 51 -> false                     // 198.51.100/24 documentation
            a == 203 && b == 0 -> false                      // 203.0.113/24 documentation
            a >= 224 -> false                                // multicast + reserved
            else -> true
        }
    }

    private fun isPublicV6(address: Inet6Address): Boolean {
        val bytes = address.address
        // IPv4-mapped ::ffff:a.b.c.d must be judged by the IPv4 rules, or
        // ::ffff:127.0.0.1 would pass as a normal IPv6 address.
        val mapped = bytes.take(10).all { it == 0.toByte() } &&
            bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
        if (mapped) return isPublicV4(bytes.copyOfRange(12, 16))
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return when {
            first == 0 -> false                              // ::/8 unspecified + loopback
            first == 0xFE && second and 0xC0 == 0x80 -> false // fe80::/10 link-local
            first and 0xFE == 0xFC -> false                  // fc00::/7 unique local
            first == 0xFF -> false                           // ff00::/8 multicast
            first == 0x20 && second == 0x01 && bytes[2] == 0x0D.toByte() &&
                bytes[3] == 0xB8.toByte() -> false           // 2001:db8::/32 documentation
            else -> true
        }
    }

    private fun parseLiteral(host: String): InetAddress? {
        val cleaned = host.removePrefix("[").removeSuffix("]")
        val looksV4 = cleaned.matches(Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}"))
        val looksV6 = cleaned.contains(':')
        if (!looksV4 && !looksV6) return null
        return runCatching { InetAddress.getByName(cleaned) }.getOrNull()
    }

    private fun reject(message: String) = SearchException(SearchException.Code.INVALID_URL, message)
}
