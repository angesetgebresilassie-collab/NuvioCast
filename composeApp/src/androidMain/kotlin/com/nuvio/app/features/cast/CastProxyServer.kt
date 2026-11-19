package com.nuvio.app.features.cast

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val DLNA_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

internal fun guessMimeFromUrl(url: String): String? {
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    return when {
        path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
        path.endsWith(".mkv") -> "video/x-matroska"
        path.endsWith(".webm") -> "video/webm"
        path.endsWith(".avi") -> "video/x-msvideo"
        path.endsWith(".ts") || path.endsWith(".m2ts") || path.endsWith(".mts") -> "video/mp2t"
        path.endsWith(".mov") -> "video/quicktime"
        path.endsWith(".flv") -> "video/x-flv"
        path.endsWith(".wmv") -> "video/x-ms-wmv"
        path.endsWith(".m3u8") -> "application/vnd.apple.mpegurl"
        path.endsWith(".mp3") -> "audio/mpeg"
        path.endsWith(".aac") -> "audio/aac"
        path.endsWith(".flac") -> "audio/flac"
        else -> null
    }
}

private fun extensionFor(mime: String): String = when (mime) {
    "video/mp4" -> ".mp4"
    "video/x-matroska" -> ".mkv"
    "video/webm" -> ".webm"
    "video/x-msvideo" -> ".avi"
    "video/mp2t" -> ".ts"
    "video/quicktime" -> ".mov"
    "application/vnd.apple.mpegurl" -> ".m3u8"
    else -> ""
}

private fun fileNameFor(url: String, mime: String): String {
    val raw = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
    val clean = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
    if (clean.isBlank()) return "video" + extensionFor(mime)
    return if (clean.contains('.')) clean else clean + extensionFor(mime)
}

/**
 * Small HTTP relay that runs on the phone. DLNA renderers can't send custom headers, can't follow
 * every redirect, often can't do HTTPS and can't reach 127.0.0.1 (torrent/local streams), so the
 * renderer is pointed at this relay instead. It supports Range requests (seeking) and rewrites
 * HLS playlists so every segment also goes through the relay.
 */
internal class CastProxyServer {
    private class Source(val url: String, val headers: Map<String, String>, val mime: String)

    private val sources = ConcurrentHashMap<String, Source>()
    private val executor = Executors.newCachedThreadPool()

    @Volatile
    private var serverSocket: ServerSocket? = null

    fun register(url: String, headers: Map<String, String>, host: String, mime: String): String {
        val port = ensureStarted()
        val token = newToken()
        sources[token] = Source(url, headers, mime)
        return "http://$host:$port/s/$token/${fileNameFor(url, mime)}"
    }

    fun clear() {
        sources.clear()
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    @Synchronized
    private fun ensureStarted(): Int {
        val existing = serverSocket
        if (existing != null && !existing.isClosed) return existing.localPort
        val created = ServerSocket(0, 50)
        serverSocket = created
        executor.execute { acceptLoop(created) }
        return created.localPort
    }

    private fun newToken(): String = UUID.randomUUID().toString().replace("-", "").take(16)

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            executor.execute { handle(client, server.localPort) }
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 8192) return null
        }
        return sb.toString()
    }

    private fun handle(socket: Socket, port: Int) {
        socket.use { client ->
            try {
                client.soTimeout = 30_000
                val input = BufferedInputStream(client.getInputStream())
                val out = BufferedOutputStream(client.getOutputStream())
                val requestLine = readLine(input) ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val path = parts[1]
                val headers = HashMap<String, String>()
                while (true) {
                    val line = readLine(input) ?: break
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
                }
                val token = Regex("^/s/([0-9a-f]+)").find(path)?.groupValues?.get(1)
                val source = token?.let { sources[it] }
                if (source == null || (method != "GET" && method != "HEAD")) {
                    writeStatusOnly(out, 404, "Not Found")
                    return
                }
                val hostHeader = headers["host"] ?: "127.0.0.1:$port"
                serve(out, method, source, headers["range"], hostHeader)
            } catch (_: IOException) {
                // Renderer closed the connection (seek, stop, buffering done): nothing to do.
            } catch (_: RuntimeException) {
            }
        }
    }

    private fun writeStatusOnly(out: OutputStream, code: Int, reason: String) {
        out.write("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        out.flush()
    }

    private fun reasonFor(code: Int) = when (code) {
        200 -> "OK"
        206 -> "Partial Content"
        416 -> "Requested Range Not Satisfiable"
        403 -> "Forbidden"
        404 -> "Not Found"
        else -> "Error"
    }

    private fun serve(out: OutputStream, method: String, source: Source, range: String?, hostHeader: String) {
        var currentUrl = source.url
        var connection: HttpURLConnection? = null
        var redirects = 0
        while (connection == null) {
            val c = URL(currentUrl).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.requestMethod = "GET"
            source.headers.forEach { (name, value) -> c.setRequestProperty(name, value) }
            if (range != null) c.setRequestProperty("Range", range)
            if (c.getRequestProperty("User-Agent") == null) {
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) NuvioCast")
            }
            val code = c.responseCode
            val location = c.getHeaderField("Location")
            if (code in 300..399 && location != null && redirects < 6) {
                currentUrl = URL(URL(currentUrl), location).toString()
                c.disconnect()
                redirects++
            } else {
                connection = c
            }
        }
        val c = connection
        try {
            val code = c.responseCode
            val body = if (code in 200..299) c.inputStream else c.errorStream
            val upstreamType = c.contentType?.substringBefore(';')?.trim()?.lowercase()
            val isPlaylist = code in 200..299 && (
                upstreamType?.contains("mpegurl") == true ||
                    currentUrl.substringBefore('?').lowercase().endsWith(".m3u8")
                )

            if (isPlaylist && body != null) {
                val text = body.bufferedReader().use { it.readText() }
                val rewritten = rewritePlaylist(text, currentUrl, hostHeader, source).toByteArray()
                val head = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n" +
                    "Content-Length: ${rewritten.size}\r\ncontentFeatures.dlna.org: $DLNA_FEATURES\r\nConnection: close\r\n\r\n"
                out.write(head.toByteArray())
                if (method == "GET") out.write(rewritten)
                out.flush()
                return
            }

            val generic = upstreamType == null || upstreamType == "application/octet-stream" ||
                upstreamType == "binary/octet-stream" || upstreamType.startsWith("text/")
            val type = if (generic) source.mime else upstreamType
            val length = c.getHeaderField("Content-Length")?.toLongOrNull()
            val contentRange = c.getHeaderField("Content-Range")

            val head = StringBuilder()
            head.append("HTTP/1.1 $code ${reasonFor(code)}\r\n")
            head.append("Content-Type: $type\r\n")
            head.append("Accept-Ranges: bytes\r\n")
            if (length != null) head.append("Content-Length: $length\r\n")
            if (contentRange != null) head.append("Content-Range: $contentRange\r\n")
            head.append("transferMode.dlna.org: Streaming\r\n")
            head.append("contentFeatures.dlna.org: $DLNA_FEATURES\r\n")
            head.append("Connection: close\r\n\r\n")
            out.write(head.toString().toByteArray())

            if (method == "GET" && body != null) {
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = body.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
            }
            out.flush()
        } finally {
            runCatching { c.disconnect() }
        }
    }

    private fun rewritePlaylist(text: String, baseUrl: String, hostHeader: String, source: Source): String {
        val uriAttribute = Regex("URI=\"([^\"]+)\"")
        return text.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> line
                trimmed.startsWith("#") -> uriAttribute.replace(line) { match ->
                    "URI=\"" + proxify(match.groupValues[1], baseUrl, hostHeader, source) + "\""
                }
                else -> proxify(trimmed, baseUrl, hostHeader, source)
            }
        }
    }

    private fun proxify(reference: String, baseUrl: String, hostHeader: String, source: Source): String {
        val absolute = runCatching { URL(URL(baseUrl), reference).toString() }.getOrNull() ?: return reference
        val mime = guessMimeFromUrl(absolute) ?: "video/mp2t"
        val token = newToken()
        sources[token] = Source(absolute, source.headers, mime)
        return "http://$hostHeader/s/$token/${fileNameFor(absolute, mime)}"
    }
}
