package com.pinna.app.connectivity

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test

class HotspotPermissionStateTest {
    @Test
    fun api26_requiresChangeWifiStateAndFineLocation() {
        val permissions = LocalHotspotPermissions.requiredFor(
            apiLevel = 26,
            targetSdk = 36,
        )

        assertEquals(
            setOf(
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            permissions,
        )
    }

    @Test
    fun api29_requiresChangeWifiStateAndFineLocation() {
        val permissions = LocalHotspotPermissions.requiredFor(
            apiLevel = 29,
            targetSdk = 36,
        )

        assertEquals(
            setOf(
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            permissions,
        )
    }

    @Test
    fun api31_requiresChangeWifiStateAndFineLocation() {
        val permissions = LocalHotspotPermissions.requiredFor(
            apiLevel = 31,
            targetSdk = 36,
        )

        assertEquals(
            setOf(
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            permissions,
        )
    }

    @Test
    fun api33RequiresChangeWifiStateAndNearbyWifiDevicesForModernTargets() {
        val permissions = LocalHotspotPermissions.requiredFor(
            apiLevel = 33,
            targetSdk = 36,
        )

        assertEquals(
            setOf(
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ),
            permissions,
        )
    }

    @Test
    fun api34RequiresChangeWifiStateAndNearbyWifiDevicesForModernTargets() {
        val permissions = LocalHotspotPermissions.requiredFor(
            apiLevel = 34,
            targetSdk = 36,
        )

        assertEquals(
            setOf(
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ),
            permissions,
        )
    }

    @Test
    fun missingRequiredPermissionsMapsToPermissionRequired() {
        val state = LocalHotspotPermissions.stateFor(
            apiLevel = 33,
            targetSdk = 36,
            grantedPermissions = setOf(Manifest.permission.CHANGE_WIFI_STATE),
        )

        assertEquals(
            LocalHotspotState.PermissionRequired(
                missingPermissions = setOf(Manifest.permission.NEARBY_WIFI_DEVICES),
            ),
            state,
        )
    }

    @Test
    fun grantedRequiredPermissionsMapsToStopped() {
        val state = LocalHotspotPermissions.stateFor(
            apiLevel = 31,
            targetSdk = 36,
            grantedPermissions = setOf(
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
        )

        assertEquals(LocalHotspotState.Stopped, state)
    }
}
