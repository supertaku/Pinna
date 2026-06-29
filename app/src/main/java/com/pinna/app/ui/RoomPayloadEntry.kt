package com.pinna.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun RoomPayloadEntry(
    error: String?,
    onBack: () -> Unit,
    onJoin: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var payload by rememberSaveable { mutableStateOf("") }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Paste a Pinna room payload", style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            value = payload,
            onValueChange = { payload = it },
            modifier = Modifier
                .fillMaxWidth()
                .testTag("manual-payload-input"),
            minLines = 4,
            maxLines = 8,
            label = { Text("Room payload") },
        )
        error?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("join-error"),
            )
        }
        Button(
            onClick = { onJoin(payload.trim()) },
            enabled = payload.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("join-room-button"),
        ) {
            Text("Join room")
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}
