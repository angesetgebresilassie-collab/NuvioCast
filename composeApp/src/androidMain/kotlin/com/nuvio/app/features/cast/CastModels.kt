package com.nuvio.app.features.cast

import kotlinx.coroutines.flow.StateFlow

enum class CastProtocol(val label: String) {
    DLNA("DLNA"),
    CHROMECAST("Chromecast"),
}

data class CastDevice(
    val id: String,
    val name: String,
    val protocol: CastProtocol,
    val host: String,
    val port: Int,
    val manufacturer: String? = null,
    val model: String? = null,
    /** DLNA only: service type -> absolute control URL. */
    val controlUrls: Map<String, String> = emptyMap(),
)

data class CastAudioTrack(
    val id: String,
    val label: String,
    val language: String? = null,
    val codec: String? = null,
    val channels: Int? = null,
    /**
     * Optional separate stream that already carries this audio track. When set, protocols that
     * cannot switch tracks natively can switch by reloading this URL at the current position.
     */
    val sourceUrl: String? = null,
)

data class CastSubtitle(
    val url: String,
    val label: String,
    val language: String? = null,
)

data class CastMedia(
    val url: String,
    val title: String,
    val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val posterUrl: String? = null,
    val subtitle: CastSubtitle? = null,
    val durationMs: Long = 0L,
    val startPositionMs: Long = 0L,
    val audioTracks: List<CastAudioTrack> = emptyList(),
    val selectedAudioTrackId: String? = null,
    /** Force the stream through the phone's local server even when the receiver could fetch it directly. */
    val forceProxy: Boolean = false,
)

enum class CastPlayerStatus { IDLE, CONNECTING, LOADING, BUFFERING, PLAYING, PAUSED, STOPPED, ERROR }

enum class AudioSwitchMode {
    /** The receiver cannot change audio tracks for this stream. */
    NONE,

    /** The receiver switches tracks itself (Chromecast on adaptive streams). */
    NATIVE,

    /** The engine switches by re-serving or reloading the stream at the current position. */
    RELOAD,
}

data class CastSessionState(
    val device: CastDevice? = null,
    val media: CastMedia? = null,
    val status: CastPlayerStatus = CastPlayerStatus.IDLE,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val audioTracks: List<CastAudioTrack> = emptyList(),
    val selectedAudioTrackId: String? = null,
    val audioSwitchMode: AudioSwitchMode = AudioSwitchMode.NONE,
    val error: String? = null,
) {
    val isActive: Boolean
        get() = device != null && status != CastPlayerStatus.IDLE && status != CastPlayerStatus.ERROR
}

interface CastSession {
    val device: CastDevice
    val state: StateFlow<CastSessionState>

    suspend fun load(media: CastMedia)
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    suspend fun seekTo(positionMs: Long)
    suspend fun setVolume(volume: Float)
    suspend fun setMuted(muted: Boolean)
    suspend fun selectAudioTrack(trackId: String)
    fun release()
}

internal fun guessMimeType(url: String, hint: String? = null): String {
    if (!hint.isNullOrBlank()) return hint
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
        path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
        path.endsWith(".mpd") -> "application/dash+xml"
        path.endsWith(".mkv") -> "video/x-matroska"
        path.endsWith(".webm") -> "video/webm"
        path.endsWith(".avi") -> "video/x-msvideo"
        path.endsWith(".mov") -> "video/quicktime"
        path.endsWith(".ts") || path.endsWith(".m2ts") || path.endsWith(".mts") -> "video/mp2t"
        path.endsWith(".mp3") -> "audio/mpeg"
        path.endsWith(".m4a") || path.endsWith(".aac") -> "audio/mp4"
        path.endsWith(".flac") -> "audio/flac"
        path.endsWith(".srt") -> "application/x-subrip"
        path.endsWith(".vtt") -> "text/vtt"
        else -> "video/mp4"
    }
}

internal fun isHlsUrl(url: String, mime: String?): Boolean =
    url.substringBefore('?').lowercase().endsWith(".m3u8") ||
        mime?.lowercase()?.contains("mpegurl") == true
