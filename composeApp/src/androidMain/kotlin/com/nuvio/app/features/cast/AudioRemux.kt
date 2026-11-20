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
    val defaultTrackId: String,
    val durationMs: Long,
)

/**
 * FFmpeg-backed audio track support for receivers that cannot switch tracks themselves.
 * Video and the chosen audio track are stream-copied (no re-encode) into a Matroska stream
 * that carries the original file's duration, and sent to the receiver through a FIFO.
 */
internal object AudioRemux {

    // FFmpeg's Matroska muxer reserves an 11-byte EBML Void in the Segment Info for the Duration and
    // only fills it in when it can seek back, which a pipe cannot. We fill it in ourselves.
    private val INFO_ID = byteArrayOf(0x15, 0x49, 0xA9.toByte(), 0x66)
    private val DURATION_VOID = byteArrayOf(0xEC.toByte(), 1, 0, 0, 0, 0, 0, 0, 2, 0, 0)
    private const val HEADER_WINDOW = 32 * 1024

    /** [url] must be readable by FFmpeg without TLS or custom headers (use the local server). */
    suspend fun probe(url: String): ProbedAudio? = withContext(Dispatchers.IO) {
        try {
            val info = FFprobeKit.getMediaInformation(url).mediaInformation ?: return@withContext null
            val audio = info.streams.orEmpty().filter { it.type == "audio" }
            if (audio.isEmpty()) return@withContext null

            val tracks = ArrayList<CastAudioTrack>()
            val indexById = LinkedHashMap<String, Int>()
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
                val isDefault = runCatching {
                    s.allProperties?.optJSONObject("disposition")?.optInt("default") == 1
                }.getOrDefault(false)
                if (defaultId == null && isDefault) defaultId = id
            }

            val durationMs = info.duration?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L
            ProbedAudio(
                tracks = tracks,
                streamIndexById = indexById,
                defaultTrackId = defaultId ?: tracks.first().id,
                durationMs = durationMs,
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Blocks, writing Matroska to [out] until the stream ends or the receiver disconnects.
     * [sourceUrl] must be a plain http URL FFmpeg can seek in (the phone's local server).
     * [durationMs] is the length of this stream (the original duration minus [startMs]); 0 = unknown.
     */
    fun stream(
        context: Context,
        sourceUrl: String,
        audioIndex: Int,
        startMs: Long,
        durationMs: Long,
        out: OutputStream,
    ) {
        val pipe = FFmpegKitConfig.registerNewFFmpegPipe(context)
            ?: throw IOException("Could not create FFmpeg pipe")
        val seekArgs = if (startMs > 0) "-ss %.3f ".format(Locale.US, startMs / 1000.0) else ""
        val command = "-hide_banner -loglevel error -nostdin -fflags +genpts " +
            seekArgs +
            "-i $sourceUrl -map 0:v:0? -map 0:$audioIndex -c copy -sn -dn " +
            "-f matroska -flush_packets 1 -y $pipe"

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
            FileInputStream(pipe).use { input ->
                val head = ByteArray(HEADER_WINDOW)
                var filled = 0
                if (durationMs > 0) {
                    // Hold back just the header until the Duration slot is found and filled in.
                    while (filled < head.size) {
                        val n = input.read(head, filled, head.size - filled)
                        if (n < 0) break
                        filled += n
                        if (patchDuration(head, filled, durationMs)) break
                    }
                } else {
                    val n = input.read(head)
                    if (n > 0) filled = n
                }
                if (filled > 0) out.write(head, 0, filled)
                input.copyTo(out, 64 * 1024)
            }
        } finally {
            runCatching { FFmpegKit.cancel(session.sessionId) }
            runCatching { FFmpegKitConfig.closeFFmpegPipe(pipe) }
        }
    }

    /** Overwrites the reserved Void in the Segment Info with a Duration element (float64, in ms). */
    private fun patchDuration(buf: ByteArray, len: Int, durationMs: Long): Boolean {
        val info = indexOf(buf, len, INFO_ID, 0)
        if (info < 0) return false
        val slot = indexOf(buf, len, DURATION_VOID, info)
        if (slot < 0) return false
        val bits = java.lang.Double.doubleToRawLongBits(durationMs.toDouble())
        buf[slot] = 0x44
        buf[slot + 1] = 0x89.toByte()
        buf[slot + 2] = 0x88.toByte()
        for (i in 0 until 8) buf[slot + 3 + i] = (bits ushr (56 - 8 * i)).toByte()
        return true
    }

    private fun indexOf(buf: ByteArray, len: Int, pattern: ByteArray, from: Int): Int {
        var i = from
        while (i <= len - pattern.size) {
            var match = true
            for (j in pattern.indices) {
                if (buf[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
            i++
        }
        return -1
    }
}
