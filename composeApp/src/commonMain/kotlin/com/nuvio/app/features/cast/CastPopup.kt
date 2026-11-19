package com.nuvio.app.features.cast

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioLoadingIndicator
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.player.AudioTrack
import kotlin.math.roundToInt

private val CastDanger = Color(0xFFFF8A80)
private val CastDangerSurface = Color(0xFF5D1F1F)

/**
 * The popup that grows out of the player's cast button: pick a renderer, control playback on it and
 * change the audio track. Styled with the same accent/row language as the player's track pickers.
 */
@Composable
internal fun CastPopup(
    renderers: List<CastRenderer>,
    isDiscovering: Boolean,
    session: CastSessionState,
    audioTracks: List<AudioTrack>,
    selectedAudioPosition: Int,
    onRescan: () -> Unit,
    onRendererClick: (CastRenderer) -> Unit,
    onTogglePlayback: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onVolumeChange: (Int) -> Unit,
    onAudioTrackSelected: (Int) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = session.renderer != null && session.status != CastStatus.Idle

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF121212).copy(alpha = 0.96f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CastHeader(isDiscovering = isDiscovering, onRescan = onRescan)

            if (renderers.isEmpty()) {
                Text(
                    text = if (isDiscovering) {
                        "Looking for DLNA / UPnP renderers on your Wi-Fi…"
                    } else {
                        "No renderers found. Make sure the receiver is on and on the same Wi-Fi, then tap refresh."
                    },
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    renderers.forEach { renderer ->
                        val isCurrent = connected && session.renderer?.id == renderer.id
                        RendererRow(
                            renderer = renderer,
                            isCurrent = isCurrent,
                            status = if (isCurrent) session.status else null,
                            onClick = { onRendererClick(renderer) },
                        )
                    }
                }
            }

            if (connected) {
                CastDivider()
                TransportSection(
                    session = session,
                    onTogglePlayback = onTogglePlayback,
                    onSeekBy = onSeekBy,
                    onVolumeChange = onVolumeChange,
                )
                if (audioTracks.isNotEmpty()) {
                    CastDivider()
                    AudioSection(
                        tracks = audioTracks,
                        selectedPosition = selectedAudioPosition,
                        supported = session.audioSwitchSupported,
                        onSelected = onAudioTrackSelected,
                    )
                }
                session.notice?.let { notice ->
                    Text(
                        text = notice,
                        color = CastDanger,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                StopButton(onClick = onStop)
            }
        }
    }
}

@Composable
private fun CastHeader(isDiscovering: Boolean, onRescan: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Cast,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = "Cast to",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .clickable(enabled = !isDiscovering, onClick = onRescan),
            contentAlignment = Alignment.Center,
        ) {
            if (isDiscovering) {
                NuvioLoadingIndicator(modifier = Modifier.size(20.dp))
            } else {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = "Refresh",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun CastDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.08f)),
    )
}

@Composable
private fun RendererRow(
    renderer: CastRenderer,
    isCurrent: Boolean,
    status: CastStatus?,
    onClick: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val primary = if (isCurrent) tokens.colors.onAccent else Color.White
    val secondary = if (isCurrent) tokens.colors.onAccent.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.7f)
    val subtitle = when (status) {
        CastStatus.Connecting -> "Connecting…"
        CastStatus.Buffering -> "Buffering…"
        CastStatus.Playing -> "Casting"
        CastStatus.Paused -> "Paused"
        CastStatus.Stopped -> "Stopped"
        CastStatus.Error -> "Couldn't connect"
        else -> listOfNotNull(renderer.manufacturer, renderer.model).distinct().joinToString(" · ")
            .ifBlank { renderer.address.orEmpty() }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isCurrent) tokens.colors.accent else Color.White.copy(alpha = 0.06f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Tv,
            contentDescription = null,
            tint = primary,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = renderer.name,
                color = primary,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isCurrent && status == CastStatus.Playing) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = null,
                tint = primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun TransportSection(
    session: CastSessionState,
    onTogglePlayback: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onVolumeChange: (Int) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val fraction = if (session.durationMs > 0L) {
        (session.positionMs.toFloat() / session.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.15f)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(tokens.colors.accent),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = formatCastTime(session.positionMs),
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = formatCastTime(session.durationMs),
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CastIconButton(Icons.Rounded.Replay10, "Back 10 seconds", 44.dp) { onSeekBy(-10_000L) }
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(tokens.colors.accent)
                    .clickable(onClick = onTogglePlayback),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (session.status == CastStatus.Playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (session.status == CastStatus.Playing) "Pause" else "Play",
                    tint = tokens.colors.onAccent,
                    modifier = Modifier.size(30.dp),
                )
            }
            CastIconButton(Icons.Rounded.Forward10, "Forward 10 seconds", 44.dp) { onSeekBy(10_000L) }
        }
        session.volume?.let { volume ->
            var draft by remember(volume) { mutableStateOf(volume / 100f) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = "Volume",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(20.dp),
                )
                Slider(
                    value = draft,
                    onValueChange = { draft = it },
                    onValueChangeFinished = { onVolumeChange((draft * 100f).roundToInt()) },
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = tokens.colors.accent,
                        activeTrackColor = tokens.colors.accent,
                        inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                    ),
                )
            }
        }
    }
}

@Composable
private fun CastIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun AudioSection(
    tracks: List<AudioTrack>,
    selectedPosition: Int,
    supported: Boolean,
    onSelected: (Int) -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Audio track",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
        if (!supported) {
            Text(
                text = "This renderer doesn't expose audio track switching over UPnP, so it plays its default track.",
                color = Color.White.copy(alpha = 0.6f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        tracks.forEachIndexed { position, track ->
            val selected = supported && position == selectedPosition
            val primary = if (selected) tokens.colors.onAccent else Color.White
            val secondary = if (selected) tokens.colors.onAccent.copy(alpha = 0.82f) else Color.White.copy(alpha = 0.72f)
            val rawLabel: String? = track.label
            val rawLanguage: String? = track.language
            val language = rawLanguage?.takeIf { it.isNotBlank() && it != "und" }
            val name = rawLabel?.takeIf { it.isNotBlank() } ?: language ?: "Track ${position + 1}"

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) tokens.colors.accent else Color.Transparent)
                    .clickable(enabled = supported) { onSelected(position) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = name,
                        color = if (supported) primary else Color.White.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (language != null && language != name) {
                        Text(
                            text = language,
                            color = if (supported) secondary else Color.White.copy(alpha = 0.4f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (selected) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = tokens.colors.onAccent,
                        modifier = Modifier
                            .padding(start = 8.dp)
                            .size(20.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CastDangerSurface.copy(alpha = 0.7f))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Stop,
            contentDescription = null,
            tint = CastDanger,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = "Stop casting",
            color = CastDanger,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

private fun formatCastTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    val mm = if (hours > 0) minutes.toString().padStart(2, '0') else minutes.toString()
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:$mm:$ss" else "$mm:$ss"
}
