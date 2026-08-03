package com.pinna.app.connectivity

import java.net.Inet4Address
import java.net.NetworkInterface

fun interface NetworkAddressProvider {
    fun selectedIpv4Address(): String
}

class DefaultNetworkAddressProvider(
    private val networkInterfaces: () -> Sequence<NetworkInterface> = {
        NetworkInterface.getNetworkInterfaces()?.asSequence() ?: emptySequence()
    },
) : NetworkAddressProvider {
    override fun selectedIpv4Address(): String =
        runCatching {
            networkInterfaces()
                .filter { it.isUp && !it.isLoopback && it.isLikelyLanInterface() }
                .flatMap { networkInterface -> networkInterface.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull {
                    !it.isLoopbackAddress && LocalAddressValidator.isAllowedLocalHost(it.hostAddress.orEmpty())
                }
                ?.hostAddress
        }.getOrNull().orEmpty()

    companion object {
        private fun NetworkInterface.isLikelyLanInterface(): Boolean {
            val normalized = name.orEmpty().lowercase()
            return normalized.startsWith("wlan") ||
                normalized.startsWith("wifi") ||
                normalized.startsWith("ap") ||
                normalized.startsWith("swlan") ||
                normalized.startsWith("eth") ||
                normalized.startsWith("en")
        }
    }
}
