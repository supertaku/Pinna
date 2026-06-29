package com.pinna.app.connectivity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalAddressValidatorTest {
    @Test
    fun allowsLoopbackPrivateAndLinkLocalIpv4() {
        listOf(
            "127.0.0.1",
            "10.0.0.5",
            "172.16.5.4",
            "172.31.255.255",
            "192.168.1.10",
            "192.168.43.1", // Android hotspot
            "172.20.10.1", // iOS hotspot range
            "169.254.10.20", // link-local
        ).forEach { host ->
            assertTrue("expected $host to be allowed", LocalAddressValidator.isAllowedLocalHost(host))
        }
    }

    @Test
    fun rejectsPublicAddressesAndHostnames() {
        listOf(
            "8.8.8.8",
            "1.1.1.1",
            "172.15.0.1", // just below 172.16/12
            "172.32.0.1", // just above 172.16/12
            "11.0.0.1",
            "example.com",
            "host.local",
            "",
            "256.1.1.1",
            "1.2.3",
            "1.2.3.4.5",
            "192.168.1", // incomplete
            "0.0.0.0",
        ).forEach { host ->
            assertFalse("expected $host to be rejected", LocalAddressValidator.isAllowedLocalHost(host))
        }
    }
}
