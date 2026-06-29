package com.pinna.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteLatencyAdvisorTest {
    @Test
    fun bluetoothRouteWarnsAboutLatency() {
        val warning = RouteLatencyAdvisor.warning(AudioRoute.BLUETOOTH)

        assertEquals(
            "Bluetooth output adds delay. Use wired or speaker output for tighter sync.",
            warning,
        )
    }

    @Test
    fun wiredRouteHasNoWarning() {
        assertNull(RouteLatencyAdvisor.warning(AudioRoute.WIRED))
    }

    @Test
    fun speakerRouteHasNoWarning() {
        assertNull(RouteLatencyAdvisor.warning(AudioRoute.SPEAKER))
    }

    @Test
    fun unknownRouteHasNoWarning() {
        assertNull(RouteLatencyAdvisor.warning(AudioRoute.UNKNOWN))
    }
}
