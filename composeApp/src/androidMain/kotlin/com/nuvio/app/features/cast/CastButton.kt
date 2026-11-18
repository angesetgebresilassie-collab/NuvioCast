package com.nuvio.app.features.cast

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Cast icon with a popup that extends from it: device list while idle, transport / volume /
 * audio-track controls while casting. [mediaProvider] is called when the user picks a device so
 * the current stream (url, headers, position, audio tracks) is captured at that moment.
 */
@Composable
fun CastButton(
    mediaProvider: () -> CastMedia?,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    val state by CastManager.state.collectAsState()
    val devices by CastManager.devices.collectAsState()
    val scanning by CastManager.scanning.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    IconButton(
        onClick = {
            expanded = true
            if (!CastManager.state.value.isActive) CastManager.startDiscovery()
        },
        modifier = modifier,
    ) {
        Icon(
            imageVector = if (state.isActive) Icons.Filled.CastConnected else Icons.Filled.Cast,
            contentDescription = "Cast",
            tint = tint,
        )
    }

    DropdownMenu(
        expanded = expanded,
        onDismissRequest = {
            expanded = false
            if (!CastManager.state.value.isActive) CastManager.stopDiscovery()
        },
        modifier = Modifier.widthIn(min = 280.dp, max = 340.dp),
    ) {
        if (state.isActive) {
            NowCasting(state = state, scope = scope)
        } else {
            DevicePicker(
                devices = devices,
                scanning = scanning,
                error = state.error,
                connecting = state.status == CastPlayerStatus.CONNECTING || state.status == CastPlayerStatus.LOADING,
                onPick = { device ->
                    val media = mediaProvider() ?: return@DevicePicker
                    scope.launch { CastManager.cast(device, media) }
                },
            )
        }
    }
}

@Composable
private fun DevicePicker(
    devices: List<CastDevice>,
    scanning: Boolean,
    error: String?,
    connecting: Boolean,
    onPick: (CastDevice) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Cast to", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            if (scanning || connecting) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(8.dp))
        if (devices.isEmpty()) {
            Text(
                if (scanning) "Looking for devices on your network…" else "No devices found",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        devices.forEach { device ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPick(device) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(device.protocol.label, device.model).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun NowCasting(state: CastSessionState, scope: kotlinx.coroutines.CoroutineScope) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = state.durationMs
    val progress = dragging ?: if (duration > 0) (state.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val playing = state.status == CastPlayerStatus.PLAYING || state.status == CastPlayerStatus.BUFFERING

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "Casting to ${state.device?.name.orEmpty()}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            state.media?.title.orEmpty(),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Slider(
            value = progress,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                val target = dragging
                dragging = null
                if (target != null && duration > 0) scope.launch { CastManager.seekTo((target * duration).toLong()) }
            },
            enabled = duration > 0,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatClock(((dragging ?: progress) * duration).toLong().takeIf { duration > 0 } ?: state.positionMs), style = MaterialTheme.typography.labelSmall)
            Text(if (duration > 0) formatClock(duration) else "--:--", style = MaterialTheme.typography.labelSmall)
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { scope.launch { if (playing) CastManager.pause() else CastManager.play() } }) {
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause" else "Play")
            }
            IconButton(onClick = { CastManager.disconnect() }) {
                Icon(Icons.Filled.Stop, contentDescription = "Stop casting")
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { scope.launch { CastManager.setMuted(!state.muted) } }) {
                Icon(if (state.muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp, contentDescription = "Mute")
            }
            Slider(
                value = state.volume,
                onValueChange = { scope.launch { CastManager.setVolume(it) } },
                modifier = Modifier.weight(1f),
            )
        }

        if (state.audioTracks.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Audiotrack, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("  Audio", style = MaterialTheme.typography.labelMedium)
            }
            val canSwitch = state.audioSwitchMode != AudioSwitchMode.NONE
            state.audioTracks.forEach { track ->
                val selected = track.id == state.selectedAudioTrackId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = canSwitch) { scope.launch { CastManager.selectAudioTrack(track.id) } }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        listOfNotNull(track.label, track.language?.takeIf { it != track.label }).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (selected) Icon(Icons.Filled.Check, contentDescription = "Selected", modifier = Modifier.size(18.dp))
                }
            }
            if (!canSwitch) {
                Text(
                    "This device can't switch audio for this stream.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatClock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
