package com.nuvio.app.features.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContent
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.CastConnected
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.cast.CastMedia
import com.nuvio.app.features.cast.CastPopup
import com.nuvio.app.features.cast.CastRenderer
import com.nuvio.app.features.cast.CastStatus
import com.nuvio.app.features.cast.rememberCastEngine

/**
 * Cast button + popup drawn on top of the player. The button sits in the top-right under the toolbar
 * buttons, follows the controls' visibility and tints when a renderer is playing.
 */
@Composable
internal fun PlayerScreenRuntime.RenderCastOverlay() {
    val engine = rememberCastEngine()
    val renderers by engine.renderers.collectAsStateWithLifecycle()
    val discovering by engine.isDiscovering.collectAsStateWithLifecycle()
    val session by engine.session.collectAsStateWithLifecycle()
    val isInPip = rememberIsInPictureInPicture()
    var open by remember { mutableStateOf(false) }

    val sourceUrl: String? = if (activeTorrentInfoHash != null) p2pResolvedSourceUrl else activeSourceUrl
    val tracks = serverAudioTracks.ifEmpty { audioTracks }
    val localSelectedIndex = serverAudioTracks.firstOrNull { it.isSelected }?.index ?: selectedAudioIndex
    val selectedPosition = session.audioTrackPosition
        ?: tracks.indexOfFirst { it.index == localSelectedIndex }.coerceAtLeast(0)

    val showButton = sourceUrl != null && (controlsVisible || open) && !playerControlsLocked && !isInPip

    LaunchedEffect(open) {
        if (open) engine.startDiscovery()
    }
    LaunchedEffect(showButton) {
        if (!showButton) open = false
    }

    if (!showButton) return

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val buttonSize = metrics.headerIconSize + 16.dp
        val topInset = WindowInsets.safeContent.only(WindowInsetsSides.Top).asPaddingValues().calculateTopPadding()
        val endPadding = horizontalSafePadding + metrics.horizontalPadding
        val buttonTop = topInset + metrics.verticalPadding / 4 + buttonSize + 10.dp
        val popupTop = buttonTop + buttonSize + 8.dp

        if (open) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { open = false },
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = endPadding, top = buttonTop),
        ) {
            PlayerHeaderIconButton(
                icon = if (session.isActive) Icons.Rounded.CastConnected else Icons.Rounded.Cast,
                contentDescription = "Cast",
                buttonSize = buttonSize,
                iconSize = metrics.headerIconSize,
                onClick = {
                    open = !open
                    controlsActivityTick += 1
                },
                tint = if (session.isActive) MaterialTheme.colorScheme.primary else Color.White,
            )
        }

        AnimatedVisibility(
            visible = open,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = endPadding, top = popupTop),
            enter = fadeIn(tween(160)) + scaleIn(
                animationSpec = tween(200),
                initialScale = 0.9f,
                transformOrigin = TransformOrigin(1f, 0f),
            ),
            exit = fadeOut(tween(120)) + scaleOut(
                animationSpec = tween(140),
                targetScale = 0.9f,
                transformOrigin = TransformOrigin(1f, 0f),
            ),
        ) {
            CastPopup(
                renderers = renderers,
                isDiscovering = discovering,
                session = session,
                audioTracks = tracks,
                selectedAudioPosition = selectedPosition,
                onRescan = { engine.startDiscovery() },
                onRendererClick = { renderer: CastRenderer ->
                    val alreadyCurrent = session.renderer?.id == renderer.id && session.isActive
                    val url = sourceUrl
                    if (!alreadyCurrent && url != null) {
                        val startMs = if (session.isActive) session.positionMs else playbackSnapshot.positionMs
                        engine.cast(
                            renderer,
                            CastMedia(
                                url = url,
                                headers = activeSourceHeaders.orEmpty(),
                                title = castTitle(),
                                startPositionMs = startMs,
                            ),
                        )
                        shouldPlay = false
                    }
                },
                onTogglePlayback = {
                    if (session.status == CastStatus.Playing) engine.pause() else engine.play()
                },
                onSeekBy = { deltaMs ->
                    val duration = session.durationMs
                    val target = (session.positionMs + deltaMs).coerceAtLeast(0L)
                    engine.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
                },
                onVolumeChange = { percent -> engine.setVolume(percent) },
                onAudioTrackSelected = { position -> engine.selectAudioTrack(position) },
                onStop = {
                    val resumeMs = session.positionMs
                    engine.stop()
                    if (resumeMs > 0L) playerController?.seekTo(resumeMs)
                    shouldPlay = true
                    open = false
                },
                modifier = Modifier
                    .width(minOf(340.dp, maxWidth - endPadding - 16.dp))
                    .heightIn(max = (maxHeight - popupTop - 16.dp).coerceAtLeast(160.dp)),
            )
        }
    }
}

private fun PlayerScreenRuntime.castTitle(): String {
    val season = activeSeasonNumber
    val episode = activeEpisodeNumber
    return if (season != null && episode != null) {
        "$title S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
    } else {
        title
    }
}
