package com.nuvio.app.features.cast

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Streams a source video as Matroska (MKV) containing the video track plus ONE chosen audio track.
 *
 * Nothing is decoded or re-encoded: compressed samples are read with [MediaExtractor] and re-wrapped
 * into a live-style MKV (unknown-size segment, ~1.5 s clusters) that is written straight to [stream].
 * That lets a renderer that can't switch audio tracks play the track the user picked.
 *
 * Usage: [open] (throws if the source can't be read), then [stream], then [close].
 */
internal class MkvAudioRemuxer(
    private val url: String,
    private val headers: Map<String, String>,
    private val audioIndex: Int,
    private val startMs: Long,
) {
    private var extractor: MediaExtractor? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var headerBytes: ByteArray = ByteArray(0)

    fun open() {
        val ex = MediaExtractor()
        extractor = ex
        if (headers.isEmpty()) ex.setDataSource(url) else ex.setDataSource(url, headers)

        val audioTracks = ArrayList<Int>()
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            when {
                mime.startsWith("video/") && videoTrack < 0 -> videoTrack = i
                mime.startsWith("audio/") -> audioTracks += i
            }
        }
        if (videoTrack < 0) throw IOException("The stream has no video track to remux.")
        audioTrack = audioTracks.getOrNull(audioIndex)
            ?: throw IOException("Audio track ${audioIndex + 1} wasn't found in the stream.")

        val videoFormat = ex.getTrackFormat(videoTrack)
        val audioFormat = ex.getTrackFormat(audioTrack)
        val durationMs = if (videoFormat.containsKey(MediaFormat.KEY_DURATION)) {
            (videoFormat.getLong(MediaFormat.KEY_DURATION) / 1000L - startMs).coerceAtLeast(0L)
        } else {
            0L
        }

        val info = ByteArrayOutputStream().apply {
            write(uintElement(ID_TIMESTAMP_SCALE, 1_000_000L))
            write(stringElement(ID_MUXING_APP, "NuvioCast"))
            write(stringElement(ID_WRITING_APP, "NuvioCast"))
            if (durationMs > 0L) write(floatElement(ID_DURATION, durationMs.toDouble()))
        }.toByteArray()
        val tracks = ByteArrayOutputStream().apply {
            write(element(ID_TRACK_ENTRY, videoEntry(videoFormat)))
            write(element(ID_TRACK_ENTRY, audioEntry(audioFormat)))
        }.toByteArray()

        headerBytes = ByteArrayOutputStream().apply {
            write(element(ID_EBML, ebmlHeader()))
            write(idBytes(ID_SEGMENT))
            write(UNKNOWN_SIZE)
            write(element(ID_INFO, info))
            write(element(ID_TRACKS, tracks))
        }.toByteArray()

        ex.selectTrack(videoTrack)
        ex.selectTrack(audioTrack)
        if (startMs > 0L) ex.seekTo(startMs * 1000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    }

    /** Writes the MKV header and then clusters until the source ends or the reader disconnects. */
    fun stream(out: OutputStream) {
        val ex = extractor ?: throw IOException("Remuxer isn't open.")
        out.write(headerBytes)
        out.flush()

        var buffer = ByteBuffer.allocate(2 * 1024 * 1024)
        var baseUs = -1L
        var annexB: Boolean? = null
        var cluster: ByteArrayOutputStream? = null
        var clusterStartMs = 0L

        fun flushCluster() {
            val current = cluster ?: return
            out.write(element(ID_CLUSTER, current.toByteArray()))
            out.flush()
            cluster = null
        }

        while (true) {
            val trackIndex = ex.sampleTrackIndex
            if (trackIndex < 0) break
            if (Build.VERSION.SDK_INT >= 28) {
                val size = ex.sampleSize
                if (size > buffer.capacity()) buffer = ByteBuffer.allocate((size + size / 2).toInt())
            }
            buffer.clear()
            val read = ex.readSampleData(buffer, 0)
            if (read < 0) break
            val timeUs = ex.sampleTime
            val sampleFlags = ex.sampleFlags
            val isVideo = trackIndex == videoTrack

            if (baseUs < 0L) {
                if (!isVideo) {
                    ex.advance()
                    continue
                }
                baseUs = timeUs
            }
            val tsMs = (timeUs - baseUs) / 1000L
            if (!isVideo && tsMs < 0L) {
                ex.advance()
                continue
            }

            var data = ByteArray(buffer.remaining())
            buffer.get(data)
            if (isVideo) {
                if (annexB == null) annexB = looksLikeAnnexB(data)
                if (annexB == true) data = annexBToLengthPrefixed(data)
            }

            val keyframe = !isVideo || (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
            val startNew = cluster == null ||
                (isVideo && keyframe && tsMs - clusterStartMs >= CLUSTER_TARGET_MS) ||
                tsMs - clusterStartMs >= CLUSTER_MAX_MS
            if (startNew) {
                flushCluster()
                clusterStartMs = tsMs.coerceAtLeast(0L)
                cluster = ByteArrayOutputStream().also { it.write(uintElement(ID_CLUSTER_TIMESTAMP, clusterStartMs)) }
            }

            val relative = (tsMs - clusterStartMs).coerceIn(-32768L, 32767L).toInt()
            val block = ByteArrayOutputStream(data.size + 4).apply {
                write(0x80 or (if (isVideo) VIDEO_TRACK_NUMBER else AUDIO_TRACK_NUMBER))
                write((relative shr 8) and 0xFF)
                write(relative and 0xFF)
                write(if (keyframe) 0x80 else 0x00)
                write(data)
            }.toByteArray()
            cluster?.write(element(ID_SIMPLE_BLOCK, block))
            ex.advance()
        }
        flushCluster()
        out.flush()
    }

    fun close() {
        runCatching { extractor?.release() }
        extractor = null
    }

    // ---------------------------------------------------------------- track entries

    private fun videoEntry(format: MediaFormat): ByteArray {
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        val csd0 = csd(format, "csd-0")
        val csd1 = csd(format, "csd-1")
        val codecId: String
        val privateData: ByteArray?
        when (mime) {
            "video/avc" -> {
                codecId = "V_MPEG4/ISO/AVC"
                privateData = buildAvcC((csd0 ?: ByteArray(0)) + (csd1 ?: ByteArray(0)))
            }
            "video/hevc" -> {
                codecId = "V_MPEGH/ISO/HEVC"
                privateData = buildHvcC((csd0 ?: ByteArray(0)) + (csd1 ?: ByteArray(0)))
            }
            "video/x-vnd.on2.vp9" -> {
                codecId = "V_VP9"
                privateData = null
            }
            "video/x-vnd.on2.vp8" -> {
                codecId = "V_VP8"
                privateData = null
            }
            else -> throw IOException("Can't remux video codec $mime into MKV.")
        }
        return ByteArrayOutputStream().apply {
            write(uintElement(ID_TRACK_NUMBER, VIDEO_TRACK_NUMBER.toLong()))
            write(uintElement(ID_TRACK_UID, VIDEO_TRACK_NUMBER.toLong()))
            write(uintElement(ID_TRACK_TYPE, 1L))
            write(uintElement(ID_FLAG_LACING, 0L))
            write(stringElement(ID_CODEC_ID, codecId))
            if (privateData != null) write(element(ID_CODEC_PRIVATE, privateData))
            val dimensions = ByteArrayOutputStream().apply {
                if (format.containsKey(MediaFormat.KEY_WIDTH)) {
                    write(uintElement(ID_PIXEL_WIDTH, format.getInteger(MediaFormat.KEY_WIDTH).toLong()))
                }
                if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
                    write(uintElement(ID_PIXEL_HEIGHT, format.getInteger(MediaFormat.KEY_HEIGHT).toLong()))
                }
            }.toByteArray()
            write(element(ID_VIDEO, dimensions))
        }.toByteArray()
    }

    private fun audioEntry(format: MediaFormat): ByteArray {
        val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
        val codecId: String
        var privateData: ByteArray? = null
        when (mime) {
            "audio/mp4a-latm" -> {
                codecId = "A_AAC"
                privateData = csd(format, "csd-0")
            }
            "audio/ac3" -> codecId = "A_AC3"
            "audio/eac3" -> codecId = "A_EAC3"
            "audio/opus" -> {
                codecId = "A_OPUS"
                privateData = csd(format, "csd-0")
            }
            "audio/mpeg" -> codecId = "A_MPEG/L3"
            "audio/vnd.dts", "audio/vnd.dts.hd" -> codecId = "A_DTS"
            "audio/true-hd" -> codecId = "A_TRUEHD"
            else -> throw IOException("Can't remux audio codec $mime into MKV.")
        }
        val language = if (format.containsKey(MediaFormat.KEY_LANGUAGE)) {
            format.getString(MediaFormat.KEY_LANGUAGE)?.takeIf { it.isNotBlank() && it != "und" }
        } else {
            null
        }
        return ByteArrayOutputStream().apply {
            write(uintElement(ID_TRACK_NUMBER, AUDIO_TRACK_NUMBER.toLong()))
            write(uintElement(ID_TRACK_UID, AUDIO_TRACK_NUMBER.toLong()))
            write(uintElement(ID_TRACK_TYPE, 2L))
            write(uintElement(ID_FLAG_DEFAULT, 1L))
            write(uintElement(ID_FLAG_LACING, 0L))
            write(stringElement(ID_CODEC_ID, codecId))
            if (privateData != null) write(element(ID_CODEC_PRIVATE, privateData))
            if (language != null) write(stringElement(ID_LANGUAGE, language))
            if (mime == "audio/opus") {
                longCsd(format, "csd-1")?.let { write(uintElement(ID_CODEC_DELAY, it)) }
                longCsd(format, "csd-2")?.let { write(uintElement(ID_SEEK_PRE_ROLL, it)) }
            }
            val audio = ByteArrayOutputStream().apply {
                val rate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                    format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                } else {
                    48_000
                }
                val channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else {
                    2
                }
                write(floatElement(ID_SAMPLING_FREQUENCY, rate.toDouble()))
                write(uintElement(ID_CHANNELS, channels.toLong()))
            }.toByteArray()
            write(element(ID_AUDIO, audio))
        }.toByteArray()
    }

    private fun csd(format: MediaFormat, key: String): ByteArray? {
        if (!format.containsKey(key)) return null
        val source = format.getByteBuffer(key) ?: return null
        val copy = source.duplicate()
        copy.rewind()
        return ByteArray(copy.remaining()).also { copy.get(it) }
    }

    private fun longCsd(format: MediaFormat, key: String): Long? {
        val bytes = csd(format, key) ?: return null
        if (bytes.size < 8) return null
        return ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).long.takeIf { it >= 0L }
    }

    // ---------------------------------------------------------------- codec private data

    private fun buildAvcC(annexB: ByteArray): ByteArray {
        val nals = splitNals(annexB)
        val sps = nals.filter { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 7 }
        val pps = nals.filter { it.isNotEmpty() && (it[0].toInt() and 0x1F) == 8 }
        if (sps.isEmpty() || pps.isEmpty() || sps[0].size < 4) throw IOException("H.264 stream is missing its SPS/PPS.")
        return ByteArrayOutputStream().apply {
            write(1)
            write(sps[0][1].toInt() and 0xFF)
            write(sps[0][2].toInt() and 0xFF)
            write(sps[0][3].toInt() and 0xFF)
            write(0xFF)
            write(0xE0 or sps.size)
            sps.forEach {
                write(it.size shr 8)
                write(it.size and 0xFF)
                write(it)
            }
            write(pps.size)
            pps.forEach {
                write(it.size shr 8)
                write(it.size and 0xFF)
                write(it)
            }
        }.toByteArray()
    }

    private fun buildHvcC(annexB: ByteArray): ByteArray {
        val nals = splitNals(annexB)
        fun ofType(type: Int) = nals.filter { it.size > 2 && ((it[0].toInt() shr 1) and 0x3F) == type }
        val vps = ofType(32)
        val sps = ofType(33)
        val pps = ofType(34)
        if (sps.isEmpty()) throw IOException("H.265 stream is missing its SPS.")

        val rbsp = unescape(sps[0].copyOfRange(2, sps[0].size))
        if (rbsp.size < 13) throw IOException("H.265 SPS is too short.")
        val maxSubLayersMinus1 = (rbsp[0].toInt() shr 1) and 0x07
        var chroma = 1
        var lumaDepth = 8
        var chromaDepth = 8
        if (maxSubLayersMinus1 == 0) {
            runCatching {
                val bits = BitReader(rbsp, 13 * 8)
                bits.ue()
                chroma = bits.ue()
                if (chroma == 3) bits.bit()
                bits.ue()
                bits.ue()
                if (bits.bit() == 1) repeat(4) { bits.ue() }
                lumaDepth = bits.ue() + 8
                chromaDepth = bits.ue() + 8
            }
        }

        val arrays = listOf(32 to vps, 33 to sps, 34 to pps).filter { it.second.isNotEmpty() }
        return ByteArrayOutputStream().apply {
            write(1)
            write(rbsp[1].toInt() and 0xFF)
            write(rbsp, 2, 4)
            write(rbsp, 6, 6)
            write(rbsp[12].toInt() and 0xFF)
            write(0xF0)
            write(0x00)
            write(0xFC)
            write(0xFC or (chroma and 0x03))
            write(0xF8 or ((lumaDepth - 8) and 0x07))
            write(0xF8 or ((chromaDepth - 8) and 0x07))
            write(0x00)
            write(0x00)
            write(0x0F)
            write(arrays.size)
            arrays.forEach { (type, list) ->
                write(0x80 or type)
                write(list.size shr 8)
                write(list.size and 0xFF)
                list.forEach {
                    write(it.size shr 8)
                    write(it.size and 0xFF)
                    write(it)
                }
            }
        }.toByteArray()
    }

    private class BitReader(private val data: ByteArray, private var position: Int) {
        fun bit(): Int {
            val value = (data[position shr 3].toInt() shr (7 - (position and 7))) and 1
            position++
            return value
        }

        fun bits(count: Int): Int {
            var value = 0
            repeat(count) { value = (value shl 1) or bit() }
            return value
        }

        fun ue(): Int {
            var zeros = 0
            while (bit() == 0) {
                zeros++
                if (zeros > 31) throw IOException("Bad Exp-Golomb value")
            }
            return if (zeros == 0) 0 else (1 shl zeros) - 1 + bits(zeros)
        }
    }

    private fun unescape(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        var zeros = 0
        for (b in data) {
            if (zeros >= 2 && b.toInt() == 0x03) {
                zeros = 0
                continue
            }
            out.write(b.toInt() and 0xFF)
            zeros = if (b.toInt() == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- NAL helpers

    private fun looksLikeAnnexB(data: ByteArray): Boolean =
        data.size >= 4 && data[0].toInt() == 0 && data[1].toInt() == 0 &&
            (data[2].toInt() == 1 || (data[2].toInt() == 0 && data[3].toInt() == 1))

    private fun splitNals(data: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 2 < data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) {
                starts += i + 3
                i += 3
            } else {
                i++
            }
        }
        return starts.mapIndexed { index, start ->
            var end = if (index + 1 < starts.size) starts[index + 1] - 3 else data.size
            while (end > start && data[end - 1].toInt() == 0) end--
            data.copyOfRange(start, end)
        }
    }

    private fun annexBToLengthPrefixed(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        for (nal in splitNals(data)) {
            out.write((nal.size ushr 24) and 0xFF)
            out.write((nal.size ushr 16) and 0xFF)
            out.write((nal.size ushr 8) and 0xFF)
            out.write(nal.size and 0xFF)
            out.write(nal)
        }
        return out.toByteArray()
    }

    // ---------------------------------------------------------------- EBML writing

    private fun ebmlHeader(): ByteArray = ByteArrayOutputStream().apply {
        write(uintElement(0x4286L, 1L))
        write(uintElement(0x42F7L, 1L))
        write(uintElement(0x42F2L, 4L))
        write(uintElement(0x42F3L, 8L))
        write(stringElement(0x4282L, "matroska"))
        write(uintElement(0x4287L, 4L))
        write(uintElement(0x4285L, 2L))
    }.toByteArray()

    private fun idBytes(id: Long): ByteArray {
        var count = 1
        while (count < 4 && (id ushr (8 * count)) != 0L) count++
        return ByteArray(count) { index -> (id ushr (8 * (count - 1 - index))).toByte() }
    }

    private fun sizeBytes(size: Long): ByteArray {
        var count = 1
        while (count < 8 && size >= (1L shl (7 * count)) - 1) count++
        val value = (1L shl (7 * count)) or size
        return ByteArray(count) { index -> (value ushr (8 * (count - 1 - index))).toByte() }
    }

    private fun element(id: Long, payload: ByteArray): ByteArray {
        val idPart = idBytes(id)
        val sizePart = sizeBytes(payload.size.toLong())
        val result = ByteArray(idPart.size + sizePart.size + payload.size)
        System.arraycopy(idPart, 0, result, 0, idPart.size)
        System.arraycopy(sizePart, 0, result, idPart.size, sizePart.size)
        System.arraycopy(payload, 0, result, idPart.size + sizePart.size, payload.size)
        return result
    }

    private fun uintElement(id: Long, value: Long): ByteArray {
        var count = 1
        while (count < 8 && (value ushr (8 * count)) != 0L) count++
        val payload = ByteArray(count) { index -> (value ushr (8 * (count - 1 - index))).toByte() }
        return element(id, payload)
    }

    private fun stringElement(id: Long, value: String): ByteArray = element(id, value.toByteArray(Charsets.UTF_8))

    private fun floatElement(id: Long, value: Double): ByteArray =
        element(id, ByteBuffer.allocate(8).putDouble(value).array())

    private companion object {
        const val VIDEO_TRACK_NUMBER = 1
        const val AUDIO_TRACK_NUMBER = 2
        const val CLUSTER_TARGET_MS = 1_500L
        const val CLUSTER_MAX_MS = 8_000L

        val UNKNOWN_SIZE = byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1)

        const val ID_EBML = 0x1A45DFA3L
        const val ID_SEGMENT = 0x18538067L
        const val ID_INFO = 0x1549A966L
        const val ID_TRACKS = 0x1654AE6BL
        const val ID_CLUSTER = 0x1F43B675L
        const val ID_TIMESTAMP_SCALE = 0x2AD7B1L
        const val ID_MUXING_APP = 0x4D80L
        const val ID_WRITING_APP = 0x5741L
        const val ID_DURATION = 0x4489L
        const val ID_TRACK_ENTRY = 0xAEL
        const val ID_TRACK_NUMBER = 0xD7L
        const val ID_TRACK_UID = 0x73C5L
        const val ID_TRACK_TYPE = 0x83L
        const val ID_FLAG_DEFAULT = 0x88L
        const val ID_FLAG_LACING = 0x9CL
        const val ID_CODEC_ID = 0x86L
        const val ID_CODEC_PRIVATE = 0x63A2L
        const val ID_LANGUAGE = 0x22B59CL
        const val ID_CODEC_DELAY = 0x56AAL
        const val ID_SEEK_PRE_ROLL = 0x56BBL
        const val ID_VIDEO = 0xE0L
        const val ID_PIXEL_WIDTH = 0xB0L
        const val ID_PIXEL_HEIGHT = 0xBAL
        const val ID_AUDIO = 0xE1L
        const val ID_SAMPLING_FREQUENCY = 0xB5L
        const val ID_CHANNELS = 0x9FL
        const val ID_CLUSTER_TIMESTAMP = 0xE7L
        const val ID_SIMPLE_BLOCK = 0xA3L
    }
}
