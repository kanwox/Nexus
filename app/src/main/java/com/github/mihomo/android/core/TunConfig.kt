package com.github.mihomo.android.core

import android.net.ConnectivityManager
import android.os.Build
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * The tunnel's fixed address plan.
 *
 * These values are referenced from three places that must agree:
 *  - `MihomoVpnService` assigns them on the `VpnService.Builder` (interface addresses);
 *  - `ClashCore.startTun` hands them to the native core (gateway / portal / DNS);
 *  - `ConfigManager` builds the plaintext-DNS reject rule, which has to exempt the
 *    tunnel's own portal address or the core's own DNS lookups get rejected.
 *
 * Keeping them here (instead of repeating the literals) removes the chance that the
 * rule and the interface drift apart and silently break DNS leak protection.
 */
object TunConfig {
    /** IPv4 address assigned to the local (device) side of the TUN interface. */
    const val IPV4_ADDRESS = "172.19.0.1"

    /** IPv4 prefix length of the TUN interface. */
    const val IPV4_PREFIX = 30

    /** IPv4 address of the remote (portal) side, used by the core as the DNS server. */
    const val IPV4_PORTAL = "172.19.0.2"

    /** IPv6 ULA assigned to the local side of the TUN interface. */
    const val IPV6_ADDRESS = "fdfe:dcba:9876::1"

    /** IPv6 prefix length of the TUN interface. */
    const val IPV6_PREFIX = 126

    /** IPv6 address of the remote (portal) side, used by the core as the DNS server. */
    const val IPV6_PORTAL = "fdfe:dcba:9876::2"

    /** Gateway list handed to the native core (both families). */
    const val GATEWAY = "$IPV4_ADDRESS/$IPV4_PREFIX,$IPV6_ADDRESS/$IPV6_PREFIX"

    /** Portal list handed to the native core (both families). */
    const val PORTAL = "$IPV4_PORTAL,$IPV6_PORTAL"

    /** DNS list handed to the native core (both families). */
    const val DNS = "$IPV4_PORTAL,$IPV6_PORTAL"

    /** IPv4-only variants, used when the device rejects the IPv6 ULA assignment. */
    const val GATEWAY_IPV4 = "$IPV4_ADDRESS/$IPV4_PREFIX"
    const val PORTAL_IPV4 = IPV4_PORTAL
    const val DNS_IPV4 = IPV4_PORTAL
}

/**
 * Shared connection-owner UID lookup used by both [ClashCore] and `MihomoVpnService`.
 *
 * Requires API 29+ (`ConnectivityManager.getConnectionOwnerUid`) and strictly literal IP
 * endpoints so that UID attribution can never trigger blocking DNS resolution.
 */
internal fun resolveConnectionOwnerUid(
    connectivityManager: ConnectivityManager?,
    protocol: Int,
    source: String,
    target: String
): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || connectivityManager == null) return 0
    val srcAddr = parseHostPort(source) ?: return 0
    val dstAddr = parseHostPort(target) ?: return 0
    return try {
        connectivityManager.getConnectionOwnerUid(protocol, srcAddr, dstAddr)
    } catch (_: Throwable) {
        0
    }
}

/**
 * Parses a literal `ip:port` or `[ipv6]:port` endpoint into a resolved [InetSocketAddress]
 * without ever performing DNS resolution.
 *
 * Rules enforced here:
 *  - a bracketed host must be a valid IPv6 literal followed by `:<port>`;
 *  - an unbracketed host must be a canonical dotted-quad IPv4 literal followed by `:<port>`;
 *  - domain names and non-literal hosts are rejected immediately (`null`) so callers on
 *    hot paths (`RequestRepository`, JNI `querySocketUid`) never block on DNS lookups;
 *  - the port must be a plain decimal number in `1..65535`.
 */
internal fun parseHostPort(endpoint: String): InetSocketAddress? {
    val trimmed = endpoint.trim()
    if (trimmed.isEmpty()) return null
    return if (trimmed.startsWith("[")) {
        parseBracketedIpv6Endpoint(trimmed)
    } else {
        parseUnbracketedIpv4Endpoint(trimmed)
    }
}

private fun parseBracketedIpv6Endpoint(trimmed: String): InetSocketAddress? {
    val close = trimmed.indexOf(']')
    if (close <= 1) return null
    val host = trimmed.substring(1, close)
    val rest = trimmed.substring(close + 1)
    if (!rest.startsWith(":")) return null
    val port = parsePort(rest.substring(1)) ?: return null
    val bytes = parseIpv6LiteralBytes(host) ?: return null
    return buildSocketAddress(bytes, port)
}

private fun parseUnbracketedIpv4Endpoint(trimmed: String): InetSocketAddress? {
    val colon = trimmed.indexOf(':')
    if (colon <= 0) return null
    // More than one colon means a bare IPv6 literal, which is ambiguous without brackets.
    if (trimmed.indexOf(':', colon + 1) >= 0) return null
    val host = trimmed.substring(0, colon)
    val port = parsePort(trimmed.substring(colon + 1)) ?: return null
    val bytes = parseIpv4LiteralBytes(host) ?: return null
    return buildSocketAddress(bytes, port)
}

/** Parses a decimal port and rejects everything outside the usable TCP/UDP range. */
private fun parsePort(raw: String): Int? {
    if (raw.isEmpty() || !raw.all { it in '0'..'9' }) return null
    val port = raw.toIntOrNull() ?: return null
    return if (port in 1..65535) port else null
}

/** Constructs an [InetSocketAddress] from raw address bytes so no DNS lookup can occur. */
private fun buildSocketAddress(addressBytes: ByteArray, port: Int): InetSocketAddress? =
    runCatching { InetSocketAddress(InetAddress.getByAddress(addressBytes), port) }.getOrNull()

internal fun parseIpv4LiteralBytes(host: String): ByteArray? {
    val parts = host.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for (i in 0 until 4) {
        val part = parts[i]
        if (!isCanonicalDecimalOctet(part)) return null
        val value = part.toIntOrNull() ?: return null
        if (value !in 0..255) return null
        bytes[i] = value.toByte()
    }
    return bytes
}

private fun isCanonicalDecimalOctet(part: String): Boolean {
    if (part.isEmpty() || part.length > 3) return false
    if (part.length > 1 && part[0] == '0') return false
    return part.all { it in '0'..'9' }
}

internal fun parseIpv6LiteralBytes(rawHost: String): ByteArray? {
    val percent = rawHost.indexOf('%')
    if (percent == 0 || percent == rawHost.length - 1) return null
    val host = if (percent > 0) rawHost.substring(0, percent) else rawHost
    if (host.isEmpty()) return null

    val doubleColon = host.indexOf("::")
    if (doubleColon >= 0 && host.indexOf("::", doubleColon + 1) >= 0) return null

    val groups = if (doubleColon < 0) {
        parseFullIpv6Groups(host) ?: return null
    } else {
        parseCompressedIpv6Groups(host, doubleColon) ?: return null
    }

    val bytes = ByteArray(16)
    for (i in 0 until 8) {
        val word = groups[i]
        bytes[i * 2] = ((word ushr 8) and 0xFF).toByte()
        bytes[i * 2 + 1] = (word and 0xFF).toByte()
    }
    return bytes
}

private fun parseFullIpv6Groups(host: String): IntArray? {
    if (host.startsWith(':') || host.endsWith(':')) return null
    val groups = parseIpv6SegmentList(host, allowTrailingIpv4 = true) ?: return null
    return if (groups.size == 8) groups else null
}

private fun parseCompressedIpv6Groups(host: String, doubleColon: Int): IntArray? {
    val leftStr = host.substring(0, doubleColon)
    val rightStr = host.substring(doubleColon + 2)
    val leftGroups = if (leftStr.isEmpty()) {
        IntArray(0)
    } else {
        if (leftStr.startsWith(':') || leftStr.endsWith(':')) return null
        parseIpv6SegmentList(leftStr, allowTrailingIpv4 = false) ?: return null
    }
    val rightGroups = if (rightStr.isEmpty()) {
        IntArray(0)
    } else {
        if (rightStr.startsWith(':') || rightStr.endsWith(':')) return null
        parseIpv6SegmentList(rightStr, allowTrailingIpv4 = true) ?: return null
    }
    val explicitCount = leftGroups.size + rightGroups.size
    if (explicitCount > 7) return null

    val result = IntArray(8)
    leftGroups.copyInto(result, destinationOffset = 0)
    rightGroups.copyInto(result, destinationOffset = 8 - rightGroups.size)
    return result
}

private fun parseIpv6SegmentList(segmentStr: String, allowTrailingIpv4: Boolean): IntArray? {
    val parts = segmentStr.split(':')
    val out = IntArray(parts.size + 1)
    var count = 0
    for (i in parts.indices) {
        val part = parts[i]
        val isLast = i == parts.lastIndex
        if (isLast && allowTrailingIpv4 && part.contains('.')) {
            val v4 = parseIpv4LiteralBytes(part) ?: return null
            out[count++] = ((v4[0].toInt() and 0xFF) shl 8) or (v4[1].toInt() and 0xFF)
            out[count++] = ((v4[2].toInt() and 0xFF) shl 8) or (v4[3].toInt() and 0xFF)
        } else {
            val word = parseHextet(part) ?: return null
            out[count++] = word
        }
    }
    return out.copyOf(count)
}

private fun parseHextet(part: String): Int? {
    if (part.isEmpty() || part.length > 4) return null
    var value = 0
    for (ch in part) {
        val digit = when (ch) {
            in '0'..'9' -> ch - '0'
            in 'a'..'f' -> ch - 'a' + 10
            in 'A'..'F' -> ch - 'A' + 10
            else -> return null
        }
        value = (value shl 4) or digit
    }
    return value
}
