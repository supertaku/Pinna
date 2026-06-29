package com.pinna.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.pinna.app.qr.CameraQrScanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScannerScreen(
    error: String?,
    onBack: () -> Unit,
    onPayloadScanned: (String) -> Unit,
    onManualFallback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var scannerStarted by rememberSaveable { mutableStateOf(false) }
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var scannerError by rememberSaveable { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        scannerStarted = granted
        permissionDenied = !granted
    }

    fun startScanner() {
        scannerError = null
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            permissionDenied = false
            scannerStarted = true
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Scaffold(topBar = { LargeTopAppBar(title = { Text("Join room") }) }) { padding ->
        Column(
            modifier = modifier
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Scan a Pinna room QR code", style = MaterialTheme.typography.titleLarge)

            val visibleError = scannerError ?: error
            visibleError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("scanner-error"),
                )
            }

            if (scannerStarted) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(3f / 4f)
                        .testTag("camera-qr-preview")
                        .semantics { contentDescription = "Camera viewfinder for scanning a room QR code" },
                ) {
                    CameraQrScanner(
                        onValidPayload = onPayloadScanned,
                        onScanError = { scannerError = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                Button(
                    onClick = ::startScanner,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("start-camera-scanner-button"),
                ) {
                    Text("Scan QR code")
                }
            }

            if (permissionDenied) {
                Text(
                    text = "Camera permission is required to scan.",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("camera-permission-error"),
                )
            }

            OutlinedButton(
                onClick = onManualFallback,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual-fallback-button"),
            ) {
                Text("Enter payload manually")
            }
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
    }
}
