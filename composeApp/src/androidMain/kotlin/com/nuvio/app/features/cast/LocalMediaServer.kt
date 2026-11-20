package com.nuvio.app.features.cast

import android.content.Context
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Tiny HTTP server that lets receivers play things they cannot fetch themselves:
 * - /u/{token}   range-capable proxy for streams needing headers, plus content:// and file:// media
 * - /hls/{token} HLS playlist rewriting (audio rendition selection, header-protected segments)
 * - /sub/{token} subtitles, optionally converted from SRT to WebVTT (Chromecast needs VTT + CORS)
 * - /ts/{token}  FFmpeg remux of one chosen audio track (+ video) to a live MPEG-TS stream
 */
internal class LocalMediaServer(
    private val context: Context,
    private val http: OkHttpClient,
) {
    private class Entry(val url: String, val headers: Map<String, String>, val mime: String?)
    private class SubEntry(val url: String, val headers: Map<String, String>, val toVtt: Boolean)
    private class HlsEntry(val url: String, val headers: Map<String, String>, val audioId: String?)
    private class TsEntry(
        val url: String,
        val headers: Map<String, String>,
        val mime: String?,
        val audioIndex: Int,
        val audioCodec: String,
        val startMs: Long,
    )
    private class Req(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private val subs = ConcurrentHashMap<String, SubEntry>()
    private val hlsEntries = ConcurrentHashMap<String, HlsEntry>()
    private val tsEntries = ConcurrentHashMap<String, TsEntry>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "cast-http").apply { isDaemon = true } }

    @Volatile
    private var server: ServerSocket? = null

    val port: Int get() = server?.localPort ?: 0

    @Synchronized
    fun start() {
        if (server != null) return
        val s = ServerSocket(0, 50)
        server = s
        Thread({
            while (!s.isClosed) {
                try {
                    val client = s.accept()
                    pool.execute { handle(client) }
                } catch (_: IOException) {
                    if (s.isClosed) break
                }
            }
        }, "cast-http-accept").apply { isDaemon = true }.start()
    }

    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        server = null
        entries.clear()
        subs.clear()
        hlsEntries.clear()
        tsEntries.clear()
    }

    // ---- registration -------------------------------------------------------------------------

    fun registerMedia(url: String, headers: Map<String, String>, mime: String?): String =
        token("u", url, headers, mime).also { entries[it] = Entry(url, headers, mime) }

    fun registerHls(url: String, headers: Map<String, String>, audioId: String?): String =
        token("hls", url, headers, audioId).also { hlsEntries[it] = HlsEntry(url, headers, audioId) }

    fun registerSubtitle(url: String, headers: Map<String, String>, toVtt: Boolean): String =
        token("sub", url, headers, toVtt.toString()).also { subs[it] = SubEntry(url, headers, toVtt) }

    /** A live MPEG-TS stream of [url] carrying only audio stream [audioIndex], starting at [startMs]. */
    fun registerRemux(
        url: String,
        headers: Map<String, String>,
        mime: String?,
        audioIndex: Int,
        audioCodec: String,
        startMs: Long,
    ): String =
        token("ts", url, headers, "$audioIndex|$audioCodec|$startMs").also {
            tsEntries[it] = TsEntry(url, headers, mime, audioIndex, audioCodec, startMs)
        }

    /** Plain loopback URL FFmpeg can read (no TLS, headers and content:// handled by this server). */
    fun loopbackUrlFor(url: String, headers: Map<String, String>, mime: String?): String =
        "http://127.0.0.1:$port/u/${registerMedia(url, headers, mime)}"

    fun urlFor(address: String, kind: String, token: String): String = "http://$address:$port/$kind/$token"

    private fun token(kind: String, url: String, headers: Map<String, String>, extra: String?): String =
        UUID.nameUUIDFromBytes("$kind|$url|${headers.toSortedMap()}|$extra".toByteArray())
            .toString().replace("-", "")

    // ---- request handling ---------------------------------------------------------------------

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                s.soTimeout = 30_000
                val input = BufferedInputStream(s.getInputStream())
                val output = BufferedOutputStream(s.getOutputStream(), 64 * 1024)
                val req = readRequest(input) ?: return
                route(req, output)
                output.flush()
            }
        } catch (_: Exception) {
            // Receivers routinely drop connections mid-stream (seeks); nothing to do.
        }
    }

    private fun readRequest(input: InputStream): Req? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        return Req(parts[0].uppercase(), parts[1].substringBefore('?'), headers)
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString()
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 16 * 1024) return null
        }
    }

    private fun route(req: Req, out: OutputStream) {
        if (req.method == "OPTIONS") {
            writeHead(out, 204, "No Content", emptyMap())
            return
        }
        if (req.method != "GET" && req.method != "HEAD") {
            writeHead(out, 405, "Method Not Allowed", emptyMap())
            return
        }
        val segments = req.path.trim('/').split('/')
        val token = segments.getOrNull(1)
        when (segments.firstOrNull()) {
            "u" -> entries[token]?.let { serveEntry(req, it, out) } ?: notFound(out)
            "hls" -> hlsEntries[token]?.let { serveHls(req, it, out) } ?: notFound(out)
            "sub" -> subs[token]?.let { serveSubtitle(req, it, out) } ?: notFound(out)
            "ts" -> tsEntries[token]?.let { serveRemux(req, it, out) } ?: notFound(out)
            else -> notFound(out)
        }
    }

    private fun notFound(out: OutputStream) = writeHead(out, 404, "Not Found", mapOf("Content-Length" to "0"))

    private fun writeHead(out: OutputStream, status: Int, reason: String, extra: Map<String, String>) {
        val sb = StringBuilder("HTTP/1.1 $status $reason\r\n")
        sb.append("Access-Control-Allow-Origin: *\r\n")
        sb.append("Access-Control-Allow-Headers: *\r\n")
        sb.append("Access-Control-Allow-Methods: GET, HEAD, OPTIONS\r\n")
        sb.append("Access-Control-Expose-Headers: Content-Length, Content-Range, Accept-Ranges\r\n")
        sb.append("Connection: close\r\n")
        extra.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    private fun dlnaHeaders(): Map<String, String> = mapOf(
        "transferMode.dlna.org" to "Streaming",
        "contentFeatures.dlna.org" to "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000",
        "Accept-Ranges" to "bytes",
    )

    // ---- /u : media proxy -----------------------------------------------------------------------

    private fun serveEntry(req: Req, entry: Entry, out: OutputStream) {
        when (Uri.parse(entry.url).scheme?.lowercase()) {
            "http", "https" -> proxyHttp(req, entry, out)
            "content", "file" -> serveLocal(req, entry, out)
            else -> notFound(out)
        }
    }

    private fun proxyHttp(req: Req, entry: Entry, out: OutputStream) {
        val builder = Request.Builder().url(entry.url)
        entry.headers.forEach { (k, v) -> builder.header(k, v) }
        req.headers["range"]?.let { builder.header("Range", it) }
        if (req.method == "HEAD") builder.head() else builder.get()

        http.newCall(builder.build()).execute().use { resp ->
            val headers = LinkedHashMap<String, String>()
            if (resp.code in 200..299) {
                val upstreamType = resp.header("Content-Type")
                headers["Content-Type"] = entry.mime
                    ?: upstreamType?.takeUnless { it.startsWith("application/octet-stream") }
                    ?: guessMimeType(entry.url)
                resp.header("Content-Length")?.let { headers["Content-Length"] = it }
                resp.header("Content-Range")?.let { headers["Content-Range"] = it }
                headers.putAll(dlnaHeaders())
            } else {
                headers["Content-Length"] = "0"
            }
            writeHead(out, resp.code, resp.message.ifBlank { "OK" }, headers)
            if (req.method != "HEAD" && resp.code in 200..299) {
                resp.body?.byteStream()?.use { it.copyTo(out, 64 * 1024) }
            }
        }
    }

    private fun serveLocal(req: Req, entry: Entry, out: OutputStream) {
        val uri = Uri.parse(entry.url)
        val total: Long
        val open: () -> InputStream
        if (uri.scheme.equals("file", ignoreCase = true)) {
            val file = File(uri.path ?: return notFound(out))
            if (!file.isFile) return notFound(out)
            total = file.length()
            open = { FileInputStream(file) }
        } else {
            total = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
            }.getOrNull() ?: -1L
            open = { requireNotNull(context.contentResolver.openInputStream(uri)) }
        }

        var start = 0L
        var end = if (total >= 0) total - 1 else Long.MAX_VALUE
        var partial = false
        val range = req.headers["range"]
        if (range != null && range.startsWith("bytes=") && total >= 0) {
            val spec = range.removePrefix("bytes=").substringBefore(',').trim()
            val from = spec.substringBefore('-').trim()
            val to = spec.substringAfter('-', "").trim()
            if (from.isEmpty()) {
                val suffix = to.toLongOrNull() ?: 0L
                start = (total - suffix).coerceAtLeast(0L)
            } else {
                start = from.toLongOrNull() ?: 0L
                if (to.isNotEmpty()) end = minOf(to.toLongOrNull() ?: end, total - 1)
            }
            if (start > end || start >= total) {
                writeHead(out, 416, "Range Not Satisfiable", mapOf("Content-Range" to "bytes */$total", "Content-Length" to "0"))
                return
            }
            partial = true
        }

        val headers = LinkedHashMap<String, String>()
        headers["Content-Type"] = entry.mime ?: guessMimeType(entry.url)
        if (total >= 0) {
            headers["Content-Length"] = (end - start + 1).toString()
            if (partial) headers["Content-Range"] = "bytes $start-$end/$total"
        }
        headers.putAll(dlnaHeaders())
        writeHead(out, if (partial) 206 else 200, if (partial) "Partial Content" else "OK", headers)
        if (req.method == "HEAD") return

        open().use { input ->
            var toSkip = start
            while (toSkip > 0) {
                val skipped = input.skip(toSkip)
                if (skipped <= 0) break
                toSkip -= skipped
            }
            var remaining = if (total >= 0) end - start + 1 else Long.MAX_VALUE
            val buffer = ByteArray(64 * 1024)
            while (remaining > 0) {
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n < 0) break
                out.write(buffer, 0, n)
                remaining -= n
            }
        }
    }

    // ---- /ts : FFmpeg audio-track remux ---------------------------------------------------------------

    private fun serveRemux(req: Req, entry: TsEntry, out: OutputStream) {
        // Live stream: no length, no byte ranges. The session restarts it at a new offset to seek.
        writeHead(
            out, 200, "OK",
            mapOf(
                "Content-Type" to "video/mp2t",
                "transferMode.dlna.org" to "Streaming",
                "contentFeatures.dlna.org" to "DLNA.ORG_OP=00;DLNA.ORG_CI=1;DLNA.ORG_FLAGS=01700000000000000000000000000000",
                "Cache-Control" to "no-cache",
            ),
        )
        if (req.method == "HEAD") return
        out.flush()
        val source = loopbackUrlFor(entry.url, entry.headers, entry.mime)
        AudioRemux.stream(context, source, entry.audioIndex, entry.audioCodec, entry.startMs, out)
    }

    // ---- /hls : playlist rewriting ----------------------------------------------------------------

    private fun serveHls(req: Req, entry: HlsEntry, out: OutputStream) {
        val builder = Request.Builder().url(entry.url)
        entry.headers.forEach { (k, v) -> builder.header(k, v) }
        http.newCall(builder.get().build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                writeHead(out, resp.code, resp.message.ifBlank { "Error" }, mapOf("Content-Length" to "0"))
                return
            }
            val text = resp.body?.string().orEmpty()
            val base = resp.request.url.toString()
            val host = req.headers["host"] ?: "${localAddressFor(null) ?: "127.0.0.1"}:$port"
            val mapper = { abs: String, isPlaylist: Boolean -> mapHlsUri(host, entry, abs, isPlaylist) }
            val rewritten = if (HlsRewriter.isMaster(text)) {
                HlsRewriter.rewriteMaster(text, base, entry.audioId, mapper)
            } else {
                HlsRewriter.rewriteMedia(text, base, mapper)
            }
            val bytes = rewritten.toByteArray(Charsets.UTF_8)
            writeHead(
                out, 200, "OK",
                mapOf(
                    "Content-Type" to "application/vnd.apple.mpegurl",
                    "Content-Length" to bytes.size.toString(),
                    "Cache-Control" to "no-cache",
                ),
            )
            if (req.method != "HEAD") out.write(bytes)
        }
    }

    private fun mapHlsUri(host: String, entry: HlsEntry, abs: String, isPlaylist: Boolean): String = when {
        isPlaylist -> "http://$host/hls/${registerHls(abs, entry.headers, null)}"
        entry.headers.isEmpty() -> abs
        else -> "http://$host/u/${registerMedia(abs, entry.headers, null)}"
    }

    // ---- /sub : subtitles ---------------------------------------------------------------------------

    private fun serveSubtitle(req: Req, entry: SubEntry, out: OutputStream) {
        val raw = fetchText(entry.url, entry.headers)
        if (raw == null) {
            notFound(out)
            return
        }
        val body = if (entry.toVtt) SubtitleConverter.srtToVtt(raw) else raw
        val bytes = body.toByteArray(Charsets.UTF_8)
        writeHead(
            out, 200, "OK",
            mapOf(
                "Content-Type" to if (entry.toVtt) "text/vtt; charset=utf-8" else "text/plain; charset=utf-8",
                "Content-Length" to bytes.size.toString(),
            ),
        )
        if (req.method != "HEAD") out.write(bytes)
    }

    private fun fetchText(url: String, headers: Map<String, String>): String? = runCatching {
        val uri = Uri.parse(url)
        when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                val builder = Request.Builder().url(url)
                headers.forEach { (k, v) -> builder.header(k, v) }
                http.newCall(builder.build()).execute().use { if (it.isSuccessful) it.body?.string() else null }
            }

            "content" -> context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            "file" -> File(uri.path ?: return@runCatching null).readText()
            else -> null
        }
    }.getOrNull()

    companion object {
        /** The phone's IPv4 address on the same subnet as [peerHost], falling back to any site-local address. */
        fun localAddressFor(peerHost: String?): String? {
            val peer = peerHost?.let { runCatching { InetAddress.getByName(it).address }.getOrNull() }
            var fallback: String? = null
            val interfaces = NetworkInterface.getNetworkInterfaces()?.let { Collections.list(it) } ?: return null
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val addr = ia.address as? Inet4Address ?: continue
                    if (addr.isLoopbackAddress) continue
                    val host = addr.hostAddress ?: continue
                    if (peer != null && peer.size == 4 && sameSubnet(addr.address, peer, ia.networkPrefixLength.toInt())) {
                        return host
                    }
                    if (fallback == null && addr.isSiteLocalAddress) fallback = host
                }
            }
            return fallback
        }

        private fun sameSubnet(a: ByteArray, b: ByteArray, prefix: Int): Boolean {
            var bits = prefix
            for (i in 0 until 4) {
                if (bits <= 0) return true
                val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
                if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
                bits -= 8
            }
            return true
        }
    }
}
