package com.pinna.app.connectivity

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkAddressProviderTest {
    @Test
    fun defaultProviderFallsBackToLoopbackWhenNoIpv4AddressIsAvailable() {
        val provider = DefaultNetworkAddressProvider(networkInterfaces = { emptySequence() })

        assertEquals("127.0.0.1", provider.selectedIpv4Address())
    }
}
