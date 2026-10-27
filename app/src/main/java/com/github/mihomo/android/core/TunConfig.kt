package com.github.mihomo.android.core

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
 * Parses a `host:port` endpoint into a socket address.
 *
 * The core reports connection endpoints as `host:port`, and IPv6 literals arrive in their
 * bracketed form (`[2001:db8::1]:443`). Two previous copies of this parser split on the
 * LAST colon, which silently mangled bare IPv6 literals (`2001:db8::1` was read as the
 * host `2001:db8:` with port `1`) and accepted endpoints without a port at all.
 *
 * Rules enforced here:
 *  - a bracketed host must be followed by `:<port>`;
 *  - an unbracketed host may contain at most one colon (so a bare IPv6 literal is rejected
 *    rather than misinterpreted);
 *  - the port must be a plain decimal number in the valid TCP/UDP range.
 *
 * Returns null when the endpoint cannot be parsed — callers use that to fall back to
 * "unknown owner" instead of guessing a wrong address.
 */
internal fun parseHostPort(endpoint: String): InetSocketAddress? {
    val trimmed = endpoint.trim()
    if (trimmed.isEmpty()) return null

    if (trimmed.startsWith("[")) {
        val close = trimmed.indexOf(']')
        if (close < 0) return null
        val host = trimmed.substring(1, close)
        if (host.isEmpty()) return null
        // A bracketed literal must be followed by ":port"; "[::1]" alone is not an endpoint.
        val rest = trimmed.substring(close + 1)
        if (!rest.startsWith(":")) return null
        val port = parsePort(rest.substring(1)) ?: return null
        return buildSocketAddress(host, port)
    }

    val colon = trimmed.indexOf(':')
    if (colon < 0) return null
    // More than one colon means a bare IPv6 literal, which is ambiguous without a port.
    if (trimmed.indexOf(':', colon + 1) >= 0) return null
    val host = trimmed.substring(0, colon)
    if (host.isEmpty()) return null
    val port = parsePort(trimmed.substring(colon + 1)) ?: return null
    return buildSocketAddress(host, port)
}

/** Parses a decimal port and rejects everything outside the usable TCP/UDP range. */
private fun parsePort(raw: String): Int? {
    if (raw.isEmpty()) return null
    // toIntOrNull() would accept a leading '+'/'-'; the port must be plain digits.
    if (!raw.all { it in '0'..'9' }) return null
    val port = raw.toIntOrNull() ?: return null
    return if (port in 1..65535) port else null
}

/**
 * Resolves the host part of an endpoint.
 *
 * [InetSocketAddress] resolves literal IPv4/IPv6 addresses itself and falls back to an
 * *unresolved* address when a hostname cannot be resolved — which is exactly the behaviour
 * we want here: an unresolvable host still yields a well-formed endpoint, so the
 * platform's connection-owner lookup can be attempted instead of dropping the attribution.
 */
private fun buildSocketAddress(host: String, port: Int): InetSocketAddress? =
    runCatching { InetSocketAddress(host, port) }.getOrNull()
