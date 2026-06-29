package com.pinna.app.connectivity

enum class NetworkTransport {
    NONE,
    WIFI,
    CELLULAR,
    OTHER,
}

data class ConnectivitySignals(
    val activeTransport: NetworkTransport,
    val sameSubnet: Boolean = false,
    val hostReachable: Boolean = false,
    val portOpen: Boolean = false,
    val permissionGranted: Boolean = true,
)

enum class DiagnosticSeverity {
    READY,
    WARNING,
    ERROR,
}

enum class DiagnosticCode {
    READY,
    NO_NETWORK,
    WIFI_REQUIRED,
    LOCAL_NETWORK_PERMISSION,
    NETWORK_MISMATCH,
    HOST_UNREACHABLE,
    ROOM_SERVICE_UNAVAILABLE,
}

data class ConnectivityDiagnostic(
    val code: DiagnosticCode,
    val severity: DiagnosticSeverity,
    val title: String,
    val message: String,
    val nextStep: String,
)

object ConnectivityDiagnosticsClassifier {
    fun classify(signals: ConnectivitySignals): ConnectivityDiagnostic = when {
        !signals.permissionGranted -> ConnectivityDiagnostic(
            code = DiagnosticCode.LOCAL_NETWORK_PERMISSION,
            severity = DiagnosticSeverity.ERROR,
            title = "Permission needed",
            message = "Pinna needs local network access to reach nearby rooms.",
            nextStep = "Allow local network access and try again.",
        )

        signals.activeTransport == NetworkTransport.NONE -> ConnectivityDiagnostic(
            code = DiagnosticCode.NO_NETWORK,
            severity = DiagnosticSeverity.ERROR,
            title = "No network",
            message = "This device is not connected to a reachable network.",
            nextStep = "Connect to Wi-Fi and scan the room QR again.",
        )

        signals.activeTransport == NetworkTransport.CELLULAR -> ConnectivityDiagnostic(
            code = DiagnosticCode.WIFI_REQUIRED,
            severity = DiagnosticSeverity.ERROR,
            title = "Wi-Fi required",
            message = "Pinna rooms are local and do not use mobile data.",
            nextStep = "Join the same Wi-Fi as the host.",
        )

        !signals.sameSubnet -> ConnectivityDiagnostic(
            code = DiagnosticCode.NETWORK_MISMATCH,
            severity = DiagnosticSeverity.ERROR,
            title = "Different Wi-Fi network",
            message = "The room QR points to a host outside this local subnet.",
            nextStep = "Check that both devices are on the same Wi-Fi.",
        )

        !signals.hostReachable -> ConnectivityDiagnostic(
            code = DiagnosticCode.HOST_UNREACHABLE,
            severity = DiagnosticSeverity.ERROR,
            title = "Could not reach host",
            message = "The host may have ended the room or the router is blocking local devices.",
            nextStep = "Ask the host to show a fresh QR code or try another Wi-Fi network.",
        )

        !signals.portOpen -> ConnectivityDiagnostic(
            code = DiagnosticCode.ROOM_SERVICE_UNAVAILABLE,
            severity = DiagnosticSeverity.WARNING,
            title = "Room service unavailable",
            message = "The host device is reachable, but the Pinna room server is not responding.",
            nextStep = "Ask the host to recreate the room.",
        )

        else -> ConnectivityDiagnostic(
            code = DiagnosticCode.READY,
            severity = DiagnosticSeverity.READY,
            title = "Ready",
            message = "The host is reachable on this Wi-Fi.",
            nextStep = "Join room.",
        )
    }
}
