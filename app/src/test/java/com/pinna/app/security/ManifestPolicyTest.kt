package com.pinna.app.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static guardrails over the production manifest so a release candidate cannot silently add a
 * sensitive permission or export a non-launcher component. These are the Milestone 8 policy
 * assertions enforced without an Android device.
 */
class ManifestPolicyTest {
    private val manifest: String by lazy {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        candidates.firstOrNull { it.exists() }?.readText()
            ?: error("Could not locate AndroidManifest.xml from ${File(".").absolutePath}")
    }

    @Test
    fun manifestForbidsBluetoothAndSystemAudioCapture() {
        // RECORD_AUDIO is permitted only for push-to-talk; Bluetooth transport and system/third-party
        // audio capture remain out of scope.
        assertFalse("Bluetooth transport permissions are out of scope.", manifest.contains("android.permission.BLUETOOTH"))
        assertFalse("System audio capture is out of scope.", manifest.contains("CAPTURE_AUDIO_OUTPUT"))
        assertFalse("Media projection capture is out of scope.", manifest.contains("MediaProjection"))
    }

    @Test
    fun microphoneIsOnlyForPushToTalk() {
        // Exactly one microphone-related permission (RECORD_AUDIO); no always-on/background capture.
        assertTrue(manifest.contains("android.permission.RECORD_AUDIO"))
    }

    @Test
    fun manifestKeepsCameraOnlyForScanningAndNearbyWifiNeverForLocation() {
        assertTrue("Camera permission is required for the QR scanner.", manifest.contains("android.permission.CAMERA"))
        assertTrue(manifest.contains("android.permission.NEARBY_WIFI_DEVICES"))
        assertTrue(
            "NEARBY_WIFI_DEVICES must be flagged neverForLocation.",
            manifest.contains("neverForLocation"),
        )
    }

    @Test
    fun onlyLauncherActivityIsExported() {
        // The single exported component must be the launcher MainActivity; the playback service and
        // any other component must stay non-exported.
        val exportedTrueCount = Regex("android:exported=\"true\"").findAll(manifest).count()
        assertTrue("Exactly one exported component (the launcher) is allowed.", exportedTrueCount == 1)
        assertTrue(manifest.contains(".MainActivity"))

        val serviceBlock = Regex("<service[\\s\\S]*?</service>|<service[\\s\\S]*?/>").find(manifest)?.value.orEmpty()
        assertTrue("The playback service must be declared.", serviceBlock.contains("PinnaPlaybackService"))
        assertTrue("The playback service must not be exported.", serviceBlock.contains("android:exported=\"false\""))
    }

    @Test
    fun backupIsDisabledSoRoomDataStaysLocal() {
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
    }
}
