package com.pinna.app.connectivity

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.StateFlow

class AndroidLocalHotspotCoordinator(context: Context) : LocalHotspotCoordinator {
    private val appContext = context.applicationContext
    private val apiLevel = Build.VERSION.SDK_INT
    private val targetSdk = appContext.applicationInfo.targetSdkVersion
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startCoordinator = LocalHotspotStartCoordinator(initialState())

    override val state: StateFlow<LocalHotspotState> = startCoordinator.state

    override suspend fun start(): Result<LocalHotspotSession> {
        val readiness = initialState()
        if (readiness != LocalHotspotState.Stopped) {
            startCoordinator.stop()
            return Result.failure(readiness.toStartFailure())
        }
        val manager = wifiManager
            ?: return Result.failure(IllegalStateException("Wi-Fi is unavailable on this device."))

        startCoordinator.activeSession()?.let { session ->
            return Result.success(session)
        }

        val pending = startCoordinator.beginStart()
        return try {
            manager.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(nextReservation: WifiManager.LocalOnlyHotspotReservation) {
                        startCoordinator.onStarted(
                            token = pending.token,
                            session = nextReservation.toSessionOrNull(),
                            handle = nextReservation.asReservationHandle(),
                        )
                    }

                    override fun onStopped() {
                        startCoordinator.onStopped(pending.token)
                    }

                    override fun onFailed(reason: Int) {
                        startCoordinator.onFailed(pending.token, failureMessage(reason))
                    }
                },
                mainHandler,
            )
            runCatching { pending.deferred.await() }.getOrElse { Result.failure(it) }
        } catch (exception: SecurityException) {
            startCoordinator.stop()
            Result.failure(exception)
        } catch (exception: RuntimeException) {
            startCoordinator.onFailed(pending.token, "The device could not start a local hotspot.")
            Result.failure(exception)
        }
    }

    override suspend fun stop() {
        startCoordinator.stop()
    }

    private fun initialState(): LocalHotspotState {
        if (apiLevel < Build.VERSION_CODES.O || wifiManager == null) {
            return LocalHotspotState.Unavailable
        }

        val grantedPermissions = LocalHotspotPermissions.requiredFor(apiLevel, targetSdk)
            .filter { permission ->
                ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
            }
            .toSet()

        return LocalHotspotPermissions.stateFor(
            apiLevel = apiLevel,
            targetSdk = targetSdk,
            grantedPermissions = grantedPermissions,
        )
    }

    @Suppress("DEPRECATION")
    private fun WifiManager.LocalOnlyHotspotReservation.toSessionOrNull(): LocalHotspotSession? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            softApConfiguration?.let { config ->
                return sessionOrNull(
                    ssid = config.ssid,
                    passphrase = config.passphrase,
                )
            }
        }
        val config = wifiConfiguration
        return sessionOrNull(
            ssid = config?.SSID?.trim('"'),
            passphrase = config?.preSharedKey?.trim('"'),
        )
    }

    private fun sessionOrNull(ssid: String?, passphrase: String?): LocalHotspotSession? {
        val nonBlankSsid = ssid?.takeIf(String::isNotBlank)
        val nonBlankPassphrase = passphrase?.takeIf(String::isNotBlank)
        if (nonBlankSsid == null || nonBlankPassphrase == null) {
            return null
        }

        return LocalHotspotSession(
            ssid = nonBlankSsid,
            passphrase = nonBlankPassphrase,
        )
    }

    private fun LocalHotspotState.toStartFailure(): Throwable = when (this) {
        is LocalHotspotState.PermissionRequired -> SecurityException(
            "Missing local hotspot permissions: ${missingPermissions.sorted().joinToString()}",
        )
        LocalHotspotState.Unavailable -> IllegalStateException("Wi-Fi is unavailable on this device.")
        else -> IllegalStateException("Local hotspot is not ready to start.")
    }

    private fun failureMessage(reason: Int): String = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> "No Wi-Fi channel is available for a local hotspot."
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> "The device could not start a local hotspot."
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> "The current Wi-Fi mode cannot start a local hotspot."
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> "Local hotspot is blocked by this device or carrier."
        else -> "The device could not start a local hotspot."
    }

    private fun WifiManager.LocalOnlyHotspotReservation.asReservationHandle(): LocalHotspotReservationHandle =
        object : LocalHotspotReservationHandle {
            override fun close() {
                this@asReservationHandle.close()
            }
        }
}
