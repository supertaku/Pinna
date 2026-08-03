package com.pinna.app.connectivity

import org.junit.Assert.assertEquals
import org.junit.Test

class NetworkAddressProviderTest {
    @Test
    fun defaultProviderReturnsNoAddressWhenNoLanIpv4AddressIsAvailable() {
        val provider = DefaultNetworkAddressProvider(networkInterfaces = { emptySequence() })

        assertEquals("", provider.selectedIpv4Address())
    }
}
