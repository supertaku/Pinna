package com.pinna.app.connectivity

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import kotlinx.coroutines.flow.StateFlow

data class LocalHotspotSession(
    val ssid: String,
    val passphrase: String,
) {
    override fun toString(): String = "LocalHotspotSession(ssid=<redacted>, passphrase=<redacted>)"
}

sealed interface LocalHotspotState {
    data object Stopped : LocalHotspotState
    data object Starting : LocalHotspotState
    data object Stopping : LocalHotspotState
    data object Unavailable : LocalHotspotState
    data class PermissionRequired(val missingPermissions: Set<String>) : LocalHotspotState
    data class Active(val session: LocalHotspotSession) : LocalHotspotState {
        override fun toString(): String = "Active(session=<redacted>)"
    }
    data class Failed(val message: String) : LocalHotspotState
}

object LocalHotspotPermissions {
    @SuppressLint("InlinedApi")
    fun requiredFor(apiLevel: Int, targetSdk: Int): Set<String> {
        return buildSet {
            add(Manifest.permission.CHANGE_WIFI_STATE)
            if (apiLevel >= Build.VERSION_CODES.TIRAMISU && targetSdk >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    fun stateFor(apiLevel: Int, targetSdk: Int, grantedPermissions: Set<String>): LocalHotspotState {
        if (apiLevel < Build.VERSION_CODES.O) {
            return LocalHotspotState.Unavailable
        }

        val missing = requiredFor(apiLevel, targetSdk) - grantedPermissions
        return if (missing.isEmpty()) LocalHotspotState.Stopped else LocalHotspotState.PermissionRequired(missing)
    }
}

interface LocalHotspotCoordinator {
    val state: StateFlow<LocalHotspotState>
    suspend fun start(): Result<LocalHotspotSession>
    suspend fun stop()
}
