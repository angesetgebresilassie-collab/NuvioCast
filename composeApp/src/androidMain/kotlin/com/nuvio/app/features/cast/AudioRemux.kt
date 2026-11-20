package com.nuvio.app.features.cast

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Locale

/** Audio streams found in a file: what the selector shows, plus what FFmpeg needs to pick one. */
internal class ProbedAudio(
    val tracks: List<CastAudioTrack>,
    /** CastAudioTrack.id -> absolute stream index in the container. */
    val streamIndexById: Map<String, Int>,
    val codecById: Map<String, String>,
    val defaultTrackId: String,
    val durationMs: Long,
)

/**
 * FFmpeg-backed audio track support for receivers that cannot switch tracks themselves.
 * Video is stream-copied; the chosen audio track is copied (or turned into AAC when MPEG-TS
 * cannot carry it) and the result is streamed to the receiver as MPEG-TS through a FIFO.
 */
internal object AudioRemux {
    // Codecs MPEG-TS carries and receivers decode. Anything else is re-encoded to AAC.
    private val COPYABLE = setOf("aac", "ac3", "eac3", "mp3", "mp2")

    /** [url] must be readable by FFmpeg without TLS or custom headers (use the local server). */
    suspend fun probe(url: String): ProbedAudio? = withContext(Dispatchers.IO) {
        try {
            val info = FFprobeKit.getMediaInformation(url).mediaInformation ?: return@withContext null
            val audio = info.streams.orEmpty().filter { it.type == "audio" }
            if (audio.isEmpty()) return@withContext null

            val tracks = ArrayList<CastAudioTrack>()
            val indexById = LinkedHashMap<String, Int>()
            val codecById = HashMap<String, String>()
            var defaultId: String? = null

            audio.forEachIndexed { n, s ->
                val index = (s.index ?: n.toLong()).toInt()
                val id = index.toString()
                val tags = s.tags
                val lang = tags?.optString("language")?.takeIf { it.isNotBlank() && it != "und" }
                val title = tags?.optString("title")?.takeIf { it.isNotBlank() }
                val langName = lang?.let { code ->
                    Locale.forLanguageTag(code).displayLanguage
                        .takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) } ?: code
                }
                val codec = s.codec?.lowercase().orEmpty()
                val channels = runCatching { s.getNumberProperty("channels")?.toInt() }.getOrNull()
                val channelLabel = when (channels) {
                    null -> null
                    1 -> "Mono"
                    2 -> "Stereo"
                    6 -> "5.1"
                    8 -> "7.1"
                    else -> "${channels}ch"
                }
                val details = listOfNotNull(codec.takeIf { it.isNotEmpty() }?.uppercase(), channelLabel)
                    .joinToString(" ")
                val name = title ?: langName ?: "Track ${n + 1}"
                tracks += CastAudioTrack(
                    id = id,
                    label = if (details.isNotEmpty()) "$name ($details)" else name,
                    language = if (title != null) langName else null,
                    codec = codec.ifEmpty { null },
                    channels = channels,
                )
                indexById[id] = index
                codecById[id] = codec
                val isDefault = runCatching {
                    s.allProperties?.optJSONObject("disposition")?.optInt("default") == 1
                }.getOrDefault(false)
                if (defaultId == null && isDefault) defaultId = id
            }

            val durationMs = info.duration?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L
            ProbedAudio(
                tracks = tracks,
                streamIndexById = indexById,
                codecById = codecById,
                defaultTrackId = defaultId ?: tracks.first().id,
                durationMs = durationMs,
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Blocks, writing MPEG-TS to [out] until the stream ends or the receiver disconnects.
     * [sourceUrl] must be a plain http URL FFmpeg can seek in (the phone's local server).
     */
    fun stream(
        context: Context,
        sourceUrl: String,
        audioIndex: Int,
        audioCodec: String,
        startMs: Long,
        out: OutputStream,
    ) {
        val pipe = FFmpegKitConfig.registerNewFFmpegPipe(context)
            ?: throw IOException("Could not create FFmpeg pipe")
        val audioArgs = if (audioCodec in COPYABLE) "-c:a copy" else "-c:a aac -b:a 384k"
        val seekArgs = if (startMs > 0) "-ss %.3f ".format(Locale.US, startMs / 1000.0) else ""
        val command = "-hide_banner -loglevel error -nostdin -fflags +genpts " +
            seekArgs +
            "-i $sourceUrl -map 0:v:0? -map 0:$audioIndex -c:v copy $audioArgs " +
            "-sn -dn -f mpegts -muxdelay 0 -muxpreload 0 -flush_packets 1 -y $pipe"

        val session = FFmpegKit.executeAsync(command) { finished ->
            val code = finished.returnCode
            if (!ReturnCode.isSuccess(code) && !ReturnCode.isCancel(code)) {
                // FFmpeg died before opening the pipe: open and close the write end so the
                // reader below gets EOF instead of blocking forever.
                Thread {
                    runCatching { FileOutputStream(pipe).close() }
                }.apply { isDaemon = true }.start()
            }
        }
        try {
            FileInputStream(pipe).use { it.copyTo(out, 64 * 1024) }
        } finally {
            runCatching { FFmpegKit.cancel(session.sessionId) }
            runCatching { FFmpegKitConfig.closeFFmpegPipe(pipe) }
        }
    }
}
