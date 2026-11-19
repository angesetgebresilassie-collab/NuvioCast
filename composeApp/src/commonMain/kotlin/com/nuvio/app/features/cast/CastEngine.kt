package com.nuvio.app.features.cast

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A UPnP/DLNA MediaRenderer found on the local network. */
data class CastRenderer(
    val id: String,
    val name: String,
    val manufacturer: String? = null,
    val model: String? = null,
    val address: String? = null,
)

/** What to send to a renderer. [headers] are applied by the phone-side relay, not by the renderer. */
data class CastMedia(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val title: String,
    val startPositionMs: Long = 0L,
)

enum class CastStatus { Idle, Connecting, Playing, Paused, Buffering, Stopped, Error }

data class CastSessionState(
    val renderer: CastRenderer? = null,
    val status: CastStatus = CastStatus.Idle,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Int? = null,
    /** True when the renderer exposes an audio-track action in its AVTransport/RenderingControl service. */
    val audioSwitchSupported: Boolean = false,
    /** 0-based position of the audio track last selected on the renderer. */
    val audioTrackPosition: Int? = null,
    val notice: String? = null,
) {
    val isActive: Boolean
        get() = renderer != null && (
            status == CastStatus.Connecting ||
                status == CastStatus.Playing ||
                status == CastStatus.Paused ||
                status == CastStatus.Buffering
            )
}

interface CastEngine {
    val renderers: StateFlow<List<CastRenderer>>
    val session: StateFlow<CastSessionState>
    val isDiscovering: StateFlow<Boolean>

    fun startDiscovery()
    fun stopDiscovery()
    fun cast(renderer: CastRenderer, media: CastMedia)
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setVolume(percent: Int)
    fun selectAudioTrack(position: Int)
    fun stop()
}

@Composable
expect fun rememberCastEngine(): CastEngine

/** Used on platforms without a UPnP engine (iOS for now). */
internal object NoOpCastEngine : CastEngine {
    private val emptyRenderers = MutableStateFlow<List<CastRenderer>>(emptyList())
    private val idleSession = MutableStateFlow(CastSessionState())
    private val notDiscovering = MutableStateFlow(false)

    override val renderers: StateFlow<List<CastRenderer>> = emptyRenderers.asStateFlow()
    override val session: StateFlow<CastSessionState> = idleSession.asStateFlow()
    override val isDiscovering: StateFlow<Boolean> = notDiscovering.asStateFlow()

    override fun startDiscovery() = Unit
    override fun stopDiscovery() = Unit
    override fun cast(renderer: CastRenderer, media: CastMedia) = Unit
    override fun play() = Unit
    override fun pause() = Unit
    override fun seekTo(positionMs: Long) = Unit
    override fun setVolume(percent: Int) = Unit
    override fun selectAudioTrack(position: Int) = Unit
    override fun stop() = Unit
}
