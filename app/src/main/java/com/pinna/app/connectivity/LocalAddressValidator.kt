package com.pinna.app.connectivity

/**
 * Guards against pointing a listener at an arbitrary host. A scanned/pasted room payload may only
 * resolve to a loopback, RFC1918 private, or link-local IPv4 address. Public IPs and hostnames are
 * rejected before any socket connect, satisfying the local-only requirement in
 * docs/final-mvp-transport.md (no public IPs, non-local hostnames, or external relay).
 *
 * Pure and dependency-free so it can be unit tested.
 */
object LocalAddressValidator {
    fun isAllowedLocalHost(host: String): Boolean {
        val octets = parseIpv4(host) ?: return false
        val a = octets[0]
        val b = octets[1]
        return when {
            a == 0 -> false // 0.0.0.0/8 "this network"
            a == 127 -> true // loopback 127.0.0.0/8
            a == 10 -> true // 10.0.0.0/8
            a == 172 && b in 16..31 -> true // 172.16.0.0/12
            a == 192 && b == 168 -> true // 192.168.0.0/16
            a == 169 && b == 254 -> true // link-local 169.254.0.0/16
            else -> false
        }
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split(".")
        if (parts.size != 4) return null
        val octets = IntArray(4)
        parts.forEachIndexed { index, part ->
            if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            octets[index] = value
        }
        return octets
    }
}
