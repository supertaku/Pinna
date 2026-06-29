package com.pinna.app.connectivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectivityDiagnosticsClassifierTest {
    @Test
    fun noActiveNetwork_reportsNoNetwork() {
        val result = ConnectivityDiagnosticsClassifier.classify(
            ConnectivitySignals(activeTransport = NetworkTransport.NONE),
        )

        assertEquals(DiagnosticCode.NO_NETWORK, result.code)
        assertTrue(result.nextStep.contains("Connect to Wi-Fi"))
    }

    @Test
    fun cellularOnly_reportsWifiRequired() {
        val result = ConnectivityDiagnosticsClassifier.classify(
            ConnectivitySignals(activeTransport = NetworkTransport.CELLULAR),
        )

        assertEquals(DiagnosticCode.WIFI_REQUIRED, result.code)
    }

    @Test
    fun sameWifiButTcpRefused_reportsRoomServiceUnavailable() {
        val result = ConnectivityDiagnosticsClassifier.classify(
            ConnectivitySignals(
                activeTransport = NetworkTransport.WIFI,
                sameSubnet = true,
                hostReachable = true,
                portOpen = false,
            ),
        )

        assertEquals(DiagnosticCode.ROOM_SERVICE_UNAVAILABLE, result.code)
    }

    @Test
    fun allSignalsHealthy_reportsReady() {
        val result = ConnectivityDiagnosticsClassifier.classify(
            ConnectivitySignals(
                activeTransport = NetworkTransport.WIFI,
                sameSubnet = true,
                hostReachable = true,
                portOpen = true,
                permissionGranted = true,
            ),
        )

        assertEquals(DiagnosticCode.READY, result.code)
    }
}
