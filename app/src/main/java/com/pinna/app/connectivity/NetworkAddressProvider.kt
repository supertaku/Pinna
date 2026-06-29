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
                .filter { it.isUp && !it.isLoopback }
                .flatMap { networkInterface -> networkInterface.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
                ?.hostAddress
        }.getOrNull() ?: LOOPBACK_IPV4

    companion object {
        const val LOOPBACK_IPV4 = "127.0.0.1"
    }
}
