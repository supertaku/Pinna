package com.pinna.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pinna.app.connectivity.LocalHotspotState
import com.pinna.app.core.model.PlaybackState
import com.pinna.app.core.model.Track
import com.pinna.app.library.ImportedTrackCandidate
import com.pinna.app.network.ControlStreamState
import com.pinna.app.qr.QrBitmapGenerator
import com.pinna.app.room.RoomState
import com.pinna.app.runtime.ListenerSyncStatus
import com.pinna.app.runtime.PinnaAppState
import com.pinna.app.runtime.PinnaScreen
import com.pinna.app.runtime.PinnaSessionController
import com.pinna.app.sync.SyncQuality
import kotlinx.coroutines.launch

@Composable
fun PinnaApp(controller: PinnaSessionController) {
    val state by controller.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        controller.importTracks(uris.map { uri -> ImportedTrackCandidate(sourceUri = uri.toString()) })
    }

    MaterialTheme(colorScheme = lightColorScheme()) {
        when (state.screen) {
            PinnaScreen.Home -> HomeScreen(
                onHost = { controller.show(PinnaScreen.HostSetup) },
                onJoin = { controller.show(PinnaScreen.Scanner) },
                onDiagnostics = { controller.show(PinnaScreen.Diagnostics) },
            )
            PinnaScreen.HostSetup -> HostSetupScreen(
                state = state,
                onBack = { controller.show(PinnaScreen.Home) },
                onImportTracks = { importLauncher.launch(arrayOf("audio/*")) },
                onImportFromUrl = { url -> controller.importFromUrl(url) },
                onCreateWifiRoom = { scope.launch { controller.createRoom() } },
                onCreateHotspotRoom = { scope.launch { controller.createRoom(useHotspot = true) } },
            )
            PinnaScreen.HostRoom -> HostRoomScreen(
                state = state,
                localDeviceId = controller.localDeviceId,
                onPlayPause = { scope.launch { controller.playPause() } },
                onEndRoom = { scope.launch { controller.endRoom() } },
                onDiagnostics = { controller.show(PinnaScreen.Diagnostics) },
                onStartTalk = { controller.startTalking() },
                onStopTalk = { controller.stopTalking() },
            )
            PinnaScreen.Scanner -> ScannerScreen(
                error = state.errorMessage,
                onBack = { controller.show(PinnaScreen.Home) },
                onPayloadScanned = { rawPayload -> scope.launch { controller.joinRoom(rawPayload) } },
                onManualFallback = { controller.show(PinnaScreen.ManualJoin) },
            )
            PinnaScreen.ManualJoin -> PayloadEntryScreen(
                error = state.errorMessage,
                onBack = { controller.show(PinnaScreen.Scanner) },
                onJoin = { rawPayload -> scope.launch { controller.joinRoom(rawPayload) } },
            )
            PinnaScreen.ListenerRoom -> ListenerRoomScreen(
                state = state,
                localDeviceId = controller.localDeviceId,
                onLeave = { scope.launch { controller.leaveRoom() } },
                onDiagnostics = { controller.show(PinnaScreen.Diagnostics) },
                onManualOffsetChange = { controller.setManualOffsetMs(it) },
                onResetOffset = { controller.resetManualOffset() },
                onStartTalk = { controller.startTalking() },
                onStopTalk = { controller.stopTalking() },
            )
            PinnaScreen.Diagnostics -> DiagnosticsScreen(
                state = state,
                onBack = { controller.show(PinnaScreen.Home) },
            )
        }

        state.errorMessage?.let { message ->
            ErrorDialog(message = message, onDismiss = { controller.show(state.screen) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(onHost: () -> Unit, onJoin: () -> Unit, onDiagnostics: () -> Unit) {
    Scaffold(topBar = { LargeTopAppBar(title = { Text("Pinna") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Listen together on the same Wi-Fi. No accounts.", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onHost, modifier = Modifier.fillMaxWidth()) { Text("Host a room") }
            OutlinedButton(onClick = onJoin, modifier = Modifier.fillMaxWidth()) { Text("Join a room") }
            OutlinedButton(onClick = onDiagnostics, modifier = Modifier.fillMaxWidth()) { Text("Network check") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostSetupScreen(
    state: PinnaAppState,
    onBack: () -> Unit,
    onImportTracks: () -> Unit,
    onImportFromUrl: (String) -> Unit,
    onCreateWifiRoom: () -> Unit,
    onCreateHotspotRoom: () -> Unit,
) {
    var linkText by rememberSaveable { mutableStateOf("") }
    Scaffold(topBar = { LargeTopAppBar(title = { Text("Host setup") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Add music to start a local listening room.")
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onImportTracks,
                    enabled = !state.isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import-tracks-button"),
                ) {
                    Text(if (state.isBusy) "Importing..." else "Import tracks")
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = linkText,
                    onValueChange = { linkText = it },
                    label = { Text("Paste a YouTube link") },
                    singleLine = true,
                    enabled = !state.isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import-link-input"),
                )
                OutlinedButton(
                    onClick = {
                        onImportFromUrl(linkText.trim())
                        linkText = ""
                    },
                    enabled = !state.isBusy && linkText.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("import-link-button"),
                ) {
                    Text("Add from link")
                }
            }
            items(state.importedTracks) { track ->
                TrackCard(track)
            }
            item {
                Button(
                    onClick = onCreateWifiRoom,
                    enabled = state.canCreateRoom,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("create-room-button"),
                ) {
                    Text("Create room on Wi-Fi")
                }
                OutlinedButton(
                    onClick = onCreateHotspotRoom,
                    enabled = state.canCreateRoom && state.isHotspotAvailable,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("hotspot-room-button"),
                ) {
                    Text("Use phone hotspot")
                }
                HotspotStatusText(state.hotspotState)
                OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostRoomScreen(
    state: PinnaAppState,
    localDeviceId: String,
    onPlayPause: () -> Unit,
    onEndRoom: () -> Unit,
    onDiagnostics: () -> Unit,
    onStartTalk: () -> Unit,
    onStopTalk: () -> Unit,
) {
    var showPayload by rememberSaveable { mutableStateOf(false) }
    val room = state.hostRoomState
    val currentTrack = room?.currentTrack()
    val isPlaying = room?.playback == PlaybackState.PLAYING
    val clipboard = LocalClipboardManager.current

    Scaffold(topBar = { LargeTopAppBar(title = { Text("Room live") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AssistChip(onClick = onDiagnostics, label = { Text(state.diagnostic.title) })
            Text("Room ${room?.roomId ?: "starting"}", style = MaterialTheme.typography.titleLarge)
            state.hostEndpoint?.let { endpoint ->
                Text("${endpoint.host}:${endpoint.port}", style = MaterialTheme.typography.bodyMedium)
            }
            state.hotspotSession?.let { session ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Phone hotspot", fontWeight = FontWeight.SemiBold)
                        Text("SSID: ${session.ssid}", modifier = Modifier.testTag("hotspot-ssid"))
                        Text("Password: ${session.passphrase}", modifier = Modifier.testTag("hotspot-passphrase"))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(session.ssid)) }) {
                                Text("Copy SSID")
                            }
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(session.passphrase)) }) {
                                Text("Copy password")
                            }
                        }
                    }
                }
            }
            currentTrack?.let { track -> TrackCard(track) }
            Text("${room?.listeners?.size ?: 0} listeners joined", modifier = Modifier.testTag("listener-count"))
            room?.listeners?.forEach { listener ->
                val status = if (listener.isReady) "ready · buffered ${listener.bufferedUntilMs / 1000}s" else "buffering…"
                Text(
                    "${listener.displayName} — $status",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onPlayPause, enabled = currentTrack != null) {
                    Text(if (isPlaying) "Pause" else "Play")
                }
                OutlinedButton(onClick = { showPayload = true }, enabled = state.hostPayload != null) {
                    Text("Show QR")
                }
            }
            PushToTalkButton(
                talkerDeviceId = state.talkerDeviceId,
                localDeviceId = localDeviceId,
                onStartTalk = onStartTalk,
                onStopTalk = onStopTalk,
            )
            OutlinedButton(onClick = onEndRoom, modifier = Modifier.fillMaxWidth()) { Text("End room") }
        }
    }

    val payload = state.hostPayload
    if (showPayload && payload != null) {
        val qrBitmap = remember(payload) { QrBitmapGenerator.generate(payload, 512) }
        AlertDialog(
            onDismissRequest = { showPayload = false },
            confirmButton = {
                Button(onClick = { showPayload = false }) {
                    Text("Close")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { clipboard.setText(AnnotatedString(payload)) }) {
                    Text("Copy payload")
                }
            },
            title = { Text("Share this room") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Same Wi-Fi required")
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Room QR code",
                        modifier = Modifier
                            .height(240.dp)
                            .fillMaxWidth()
                            .testTag("room-qr-code"),
                    )
                    Text(payload, style = MaterialTheme.typography.bodySmall)
                }
            },
        )
    }
}

@Composable
private fun HotspotStatusText(state: LocalHotspotState) {
    val text = when (state) {
        LocalHotspotState.Stopped -> "Hotspot fallback available on supported devices."
        LocalHotspotState.Starting -> "Starting hotspot..."
        LocalHotspotState.Stopping -> "Stopping hotspot..."
        LocalHotspotState.Unavailable -> "Hotspot fallback is unavailable on this device."
        is LocalHotspotState.PermissionRequired -> "Hotspot permission required."
        is LocalHotspotState.Active -> "Hotspot is active."
        is LocalHotspotState.Failed -> state.message
    }
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("hotspot-status"))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PayloadEntryScreen(error: String?, onBack: () -> Unit, onJoin: (String) -> Unit) {
    Scaffold(topBar = { LargeTopAppBar(title = { Text("Join room") }) }) { padding ->
        RoomPayloadEntry(
            error = error,
            onBack = onBack,
            onJoin = onJoin,
            modifier = Modifier
                .padding(padding)
                .padding(24.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListenerRoomScreen(
    state: PinnaAppState,
    localDeviceId: String,
    onLeave: () -> Unit,
    onDiagnostics: () -> Unit,
    onManualOffsetChange: (Long) -> Unit,
    onResetOffset: () -> Unit,
    onStartTalk: () -> Unit,
    onStopTalk: () -> Unit,
) {
    val room = state.listenerRoomState
    val sync = state.listenerSync
    Scaffold(topBar = { LargeTopAppBar(title = { Text("Listening with ${room?.hostDeviceId ?: "host"}") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = onDiagnostics,
                    label = { Text(connectionLabel(state.controlStreamState)) },
                    modifier = Modifier.testTag("connection-chip"),
                )
                AssistChip(
                    onClick = onDiagnostics,
                    label = { Text(syncQualityLabel(sync.quality)) },
                    modifier = Modifier.testTag("sync-quality-chip"),
                )
            }
            room?.currentTrack()?.let { track -> TrackCard(track) }
            Text("Host controls playback. Your volume stays local.")
            sync.routeWarning?.let { warning ->
                Text(
                    warning,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("route-warning"),
                )
            }
            SyncCalibrationCard(sync = sync, onManualOffsetChange = onManualOffsetChange, onResetOffset = onResetOffset)
            PushToTalkButton(
                talkerDeviceId = state.talkerDeviceId,
                localDeviceId = localDeviceId,
                onStartTalk = onStartTalk,
                onStopTalk = onStopTalk,
            )
            OutlinedButton(onClick = onLeave, modifier = Modifier.fillMaxWidth()) { Text("Leave room") }
        }
    }
}

@Composable
private fun PushToTalkButton(
    talkerDeviceId: String?,
    localDeviceId: String,
    onStartTalk: () -> Unit,
    onStopTalk: () -> Unit,
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    val isTalking = talkerDeviceId == localDeviceId
    val someoneElseTalking = talkerDeviceId != null && talkerDeviceId != localDeviceId
    val label = when {
        isTalking -> "Talking…"
        someoneElseTalking -> "Someone is talking"
        else -> "Hold to talk"
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (isTalking) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("push-to-talk-button")
            .pointerInput(someoneElseTalking, hasPermission) {
                if (someoneElseTalking) return@pointerInput
                detectTapGestures(
                    onPress = {
                        if (!hasPermission) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@detectTapGestures
                        }
                        onStartTalk()
                        tryAwaitRelease()
                        onStopTalk()
                    },
                )
            },
    ) {
        Text(
            label,
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SyncCalibrationCard(
    sync: ListenerSyncStatus,
    onManualOffsetChange: (Long) -> Unit,
    onResetOffset: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Sync calibration", fontWeight = FontWeight.SemiBold)
            Text(
                "Manual offset: ${sync.manualOffsetMs} ms",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("manual-offset-value"),
            )
            Slider(
                value = sync.manualOffsetMs.toFloat(),
                onValueChange = { onManualOffsetChange(it.toLong()) },
                valueRange = ListenerSyncStatus.MIN_MANUAL_OFFSET_MS.toFloat()..ListenerSyncStatus.MAX_MANUAL_OFFSET_MS.toFloat(),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("manual-offset-slider"),
            )
            TextButton(onClick = onResetOffset, modifier = Modifier.testTag("reset-offset-button")) {
                Text("Reset offset")
            }
            Text(
                "Buffered: ${sync.bufferedPositionMs / 1000}s · Corrections: ${sync.correctionCount}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DiagnosticsScreen(state: PinnaAppState, onBack: () -> Unit) {
    val diagnostic = state.diagnostic
    val sync = state.listenerSync
    Scaffold(topBar = { LargeTopAppBar(title = { Text("Network check") }) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DiagnosticsRow("Same network", diagnostic.title, isHealthy = diagnostic.severity != com.pinna.app.connectivity.DiagnosticSeverity.ERROR)
            DiagnosticsRow("Host reachable", if (state.hostEndpoint != null) "${state.hostEndpoint.host}:${state.hostEndpoint.port}" else "Join a room first", isHealthy = state.hostEndpoint != null)
            DiagnosticsRow("Control stream", connectionLabel(state.controlStreamState), isHealthy = state.controlStreamState is ControlStreamState.Connected)
            DiagnosticsRow("Sync quality", syncQualityLabel(sync.quality), isHealthy = sync.quality != SyncQuality.UNSTABLE)
            DiagnosticsRow("Round trip", sync.roundTripNanos?.let { "${it / 1_000_000} ms" } ?: "Measuring…", isHealthy = sync.roundTripNanos != null)
            DiagnosticsRow("Estimated drift", sync.driftMs?.let { "$it ms" } ?: "—", isHealthy = true)
            DiagnosticsRow("Hotspot", hotspotLabel(state.hotspotState), isHealthy = state.hotspotState !is LocalHotspotState.Failed)
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
        }
    }
}

private fun connectionLabel(state: ControlStreamState): String = when (state) {
    ControlStreamState.Connected -> "Connected"
    ControlStreamState.Connecting -> "Connecting…"
    ControlStreamState.Disconnected -> "Disconnected"
    is ControlStreamState.Reconnecting -> "Reconnecting…"
    is ControlStreamState.Failed -> "Connection lost"
}

private fun syncQualityLabel(quality: SyncQuality): String = when (quality) {
    SyncQuality.EXCELLENT -> "Sync: excellent"
    SyncQuality.GOOD -> "Sync: good"
    SyncQuality.UNSTABLE -> "Sync: unstable"
    SyncQuality.REBUFFERING -> "Sync: rebuffering"
}

private fun hotspotLabel(state: LocalHotspotState): String = when (state) {
    LocalHotspotState.Stopped -> "Off"
    LocalHotspotState.Starting -> "Starting…"
    LocalHotspotState.Stopping -> "Stopping…"
    LocalHotspotState.Unavailable -> "Unavailable"
    is LocalHotspotState.PermissionRequired -> "Permission required"
    is LocalHotspotState.Active -> "Active: ${state.session.ssid}"
    is LocalHotspotState.Failed -> state.message
}

@Composable
private fun ErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        },
        title = { Text("Pinna") },
        text = {
            Text(message, modifier = Modifier.testTag("error-message"))
        },
    )
}

@Composable
private fun TrackCard(track: Track) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(track.title, fontWeight = FontWeight.SemiBold)
            Text(track.artist ?: "Unknown artist")
            Text("${track.durationMs / 1000}s")
        }
    }
}

@Composable
private fun DiagnosticsRow(label: String, value: String, isHealthy: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text(if (isHealthy) value else "Check Wi-Fi")
    }
}

private fun RoomState.currentTrack(): Track? {
    return currentTrackId?.let { id -> queue.firstOrNull { it.id == id } } ?: queue.firstOrNull()
}
