package com.nuvio.app.features.cast

import android.util.Xml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import kotlin.coroutines.coroutineContext

private const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
private const val RCS = "urn:schemas-upnp-org:service:RenderingControl:1"

// ==================================================================================================
// Discovery (SSDP)
// ==================================================================================================

internal class DlnaDiscovery(private val http: OkHttpClient) {

    suspend fun scan(timeoutMs: Long = 4_000L, onDevice: (CastDevice) -> Unit) = withContext(Dispatchers.IO) {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(0))
            soTimeout = 500
        }
        try {
            val group = InetAddress.getByName("239.255.255.250")
            val targets = listOf(
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                "urn:schemas-upnp-org:service:AVTransport:1",
            )

            fun sendSearch() {
                targets.forEach { st ->
                    val msg = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                        "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n"
                    val bytes = msg.toByteArray()
                    runCatching { socket.send(DatagramPacket(bytes, bytes.size, group, 1900)) }
                }
            }

            val start = System.currentTimeMillis()
            val deadline = start + timeoutMs
            var resent = false
            val seen = HashSet<String>()
            val buffer = ByteArray(4096)
            sendSearch()
            while (System.currentTimeMillis() < deadline) {
                coroutineContext.ensureActive()
                if (!resent && System.currentTimeMillis() - start > 1_000) {
                    sendSearch()
                    resent = true
                }
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length)
                    val location = headerValue(text, "LOCATION") ?: continue
                    if (seen.add(location)) {
                        describe(location)?.let(onDevice)
                    }
                } catch (_: SocketTimeoutException) {
                    // keep listening until the deadline
                }
            }
        } finally {
            socket.close()
        }
    }

    private fun headerValue(response: String, name: String): String? =
        response.lineSequence()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()

    private fun describe(location: String): CastDevice? = runCatching {
        val xml = http.newCall(Request.Builder().url(location).build()).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching null
            resp.body?.string()
        } ?: return@runCatching null

        val parser = Xml.newPullParser()
        parser.setInput(StringReader(xml))

        var friendly: String? = null
        var manufacturer: String? = null
        var model: String? = null
        var udn: String? = null
        var urlBase: String? = null
        val services = LinkedHashMap<String, String>()
        var tag = ""
        var inService = false
        var serviceType: String? = null
        var controlUrl: String? = null

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name
                    if (tag == "service") {
                        inService = true
                        serviceType = null
                        controlUrl = null
                    }
                }

                XmlPullParser.TEXT -> {
                    val text = parser.text.trim()
                    if (text.isNotEmpty()) {
                        when (tag) {
                            "friendlyName" -> if (friendly == null) friendly = text
                            "manufacturer" -> if (manufacturer == null) manufacturer = text
                            "modelName" -> if (model == null) model = text
                            "UDN" -> if (udn == null) udn = text
                            "URLBase" -> urlBase = text
                            "serviceType" -> if (inService) serviceType = text
                            "controlURL" -> if (inService) controlUrl = text
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    if (parser.name == "service") {
                        val type = serviceType
                        val control = controlUrl
                        if (type != null && control != null) {
                            services[type] = URI(urlBase ?: location).resolve(control).toString()
                        }
                        inService = false
                    }
                    tag = ""
                }
            }
            event = parser.next()
        }

        if (services.keys.none { it.contains("AVTransport") }) return@runCatching null
        val uri = URI(location)
        CastDevice(
            id = "dlna:" + (udn ?: location),
            name = friendly ?: uri.host,
            protocol = CastProtocol.DLNA,
            host = uri.host,
            port = if (uri.port > 0) uri.port else 80,
            manufacturer = manufacturer,
            model = model,
            controlUrls = services,
        )
    }.getOrNull()
}

// ==================================================================================================
// Session (UPnP AVTransport + RenderingControl over SOAP)
// ==================================================================================================

internal class DlnaSession(
    override val device: CastDevice,
    private val http: OkHttpClient,
    private val server: LocalMediaServer,
) : CastSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(CastSessionState(device = device, status = CastPlayerStatus.CONNECTING))
    override val state: StateFlow<CastSessionState> = _state.asStateFlow()

    private val avtUrl = device.controlUrls.entries.firstOrNull { it.key.contains("AVTransport") }?.value
    private val avtType = device.controlUrls.keys.firstOrNull { it.contains("AVTransport") } ?: AVT
    private val rcsUrl = device.controlUrls.entries.firstOrNull { it.key.contains("RenderingControl") }?.value
    private val rcsType = device.controlUrls.keys.firstOrNull { it.contains("RenderingControl") } ?: RCS

    private var pollJob: Job? = null
    private var media: CastMedia? = null
    private var pendingSeekMs = 0L
    private var loadingUntil = 0L

    override suspend fun load(media: CastMedia) {
        this.media = media
        val mime = guessMimeType(media.url, media.mimeType)

        var tracks = media.audioTracks
        var selected = media.selectedAudioTrackId
        if (isHlsUrl(media.url, mime)) {
            val fromMaster = fetchHlsAudioTracks(media)
            if (fromMaster.size > 1) {
                tracks = fromMaster
                selected = selected?.takeIf { id -> fromMaster.any { it.id == id } }
                    ?: fromMaster.firstOrNull { it.language != null && it.id == media.selectedAudioTrackId }?.id
                    ?: fromMaster.first().id
            }
        }
        val mode = when {
            isHlsUrl(media.url, mime) && tracks.size > 1 -> AudioSwitchMode.RELOAD
            tracks.size > 1 && tracks.any { it.sourceUrl != null } -> AudioSwitchMode.RELOAD
            else -> AudioSwitchMode.NONE
        }
        _state.update {
            it.copy(
                media = media,
                audioTracks = tracks,
                selectedAudioTrackId = selected,
                audioSwitchMode = mode,
                durationMs = media.durationMs,
                error = null,
            )
        }
        startPlayback(media, mime, selected, media.startPositionMs)
        refreshVolume()
    }

    private suspend fun startPlayback(media: CastMedia, mime: String, audioId: String?, startAtMs: Long) {
        _state.update { it.copy(status = CastPlayerStatus.LOADING, positionMs = startAtMs, error = null) }
        try {
            val prepared = prepare(media, mime, audioId)
            val subtitleUrl = media.subtitle?.let { sub ->
                val address = LocalMediaServer.localAddressFor(device.host)
                if (address != null && (sub.url.startsWith("content") || sub.url.startsWith("file"))) {
                    server.urlFor(address, "sub", server.registerSubtitle(sub.url, emptyMap(), false))
                } else {
                    sub.url
                }
            }
            runCatching { soapAvt("Stop") }
            soapAvt(
                "SetAVTransportURI",
                "CurrentURI" to prepared.first,
                "CurrentURIMetaData" to didl(media, prepared.first, prepared.second, subtitleUrl),
            )
            soapAvt("Play", "Speed" to "1")
            pendingSeekMs = startAtMs
            loadingUntil = System.currentTimeMillis() + 10_000
            startPolling()
        } catch (e: Exception) {
            fail(e.message ?: "Could not start playback on ${device.name}")
            throw e
        }
    }

    /** Returns the URL the receiver should fetch and its mime type. */
    private fun prepare(media: CastMedia, mime: String, audioId: String?): Pair<String, String> {
        val address = LocalMediaServer.localAddressFor(device.host)
        val source = media.audioTracks.firstOrNull { it.id == audioId }?.sourceUrl ?: media.url
        val scheme = source.substringBefore(':').lowercase()
        val isLocal = scheme == "content" || scheme == "file"
        val needsServer = isLocal || media.headers.isNotEmpty() || media.forceProxy ||
            (isHlsUrl(source, mime) && audioId != null && _state.value.audioSwitchMode == AudioSwitchMode.RELOAD)
        if (!needsServer) return source to mime

        requireNotNull(address) { "Could not find this phone's Wi-Fi address" }
        server.start()
        return if (isHlsUrl(source, mime)) {
            server.urlFor(address, "hls", server.registerHls(source, media.headers, audioId)) to
                "application/vnd.apple.mpegurl"
        } else {
            server.urlFor(address, "u", server.registerMedia(source, media.headers, media.mimeType)) to mime
        }
    }

    private suspend fun fetchHlsAudioTracks(media: CastMedia): List<CastAudioTrack> = withContext(Dispatchers.IO) {
        runCatching {
            val builder = Request.Builder().url(media.url)
            media.headers.forEach { (k, v) -> builder.header(k, v) }
            val text = http.newCall(builder.build()).execute().use { it.body?.string().orEmpty() }
            if (!HlsRewriter.isMaster(text)) return@runCatching emptyList()
            HlsRewriter.audioRenditions(text)
                .distinctBy { it.name }
                .map {
                    CastAudioTrack(
                        id = it.id,
                        label = it.name,
                        language = it.language,
                        channels = it.channels?.substringBefore('/')?.toIntOrNull(),
                    )
                }
        }.getOrDefault(emptyList())
    }

    override suspend fun play() {
        soapAvt("Play", "Speed" to "1")
    }

    override suspend fun pause() {
        soapAvt("Pause")
    }

    override suspend fun stop() {
        pollJob?.cancel()
        runCatching { soapAvt("Stop") }
        _state.update { it.copy(status = CastPlayerStatus.STOPPED) }
    }

    override suspend fun seekTo(positionMs: Long) {
        soapAvt("Seek", "Unit" to "REL_TIME", "Target" to formatTime(positionMs))
        _state.update { it.copy(positionMs = positionMs) }
    }

    override suspend fun setVolume(volume: Float) {
        val url = rcsUrl ?: return
        val level = (volume.coerceIn(0f, 1f) * 100).toInt()
        soap(url, rcsType, "SetVolume", "Channel" to "Master", "DesiredVolume" to level.toString())
        _state.update { it.copy(volume = volume.coerceIn(0f, 1f)) }
    }

    override suspend fun setMuted(muted: Boolean) {
        val url = rcsUrl ?: return
        soap(url, rcsType, "SetMute", "Channel" to "Master", "DesiredMute" to if (muted) "1" else "0")
        _state.update { it.copy(muted = muted) }
    }

    override suspend fun selectAudioTrack(trackId: String) {
        val current = media ?: return
        val snapshot = _state.value
        if (snapshot.audioSwitchMode != AudioSwitchMode.RELOAD) {
            throw UnsupportedOperationException(
                "${device.name} can't switch audio tracks for this stream over DLNA",
            )
        }
        if (snapshot.audioTracks.none { it.id == trackId }) return
        val resumeAt = snapshot.positionMs
        _state.update { it.copy(selectedAudioTrackId = trackId) }
        startPlayback(current, guessMimeType(current.url, current.mimeType), trackId, resumeAt)
    }

    override fun release() {
        pollJob?.cancel()
        val url = avtUrl
        if (url != null) {
            // Best effort; the scope is cancelled right after the Stop is on the wire.
            Thread {
                runCatching { soapBlocking(url, avtType, "Stop", listOf("InstanceID" to "0")) }
            }.start()
        }
        scope.cancel()
        _state.update { CastSessionState() }
    }

    // ---- polling --------------------------------------------------------------------------------

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var failures = 0
            while (isActive) {
                try {
                    val transport = soapAvt("GetTransportInfo")
                    val position = soapAvt("GetPositionInfo")
                    failures = 0

                    val rawState = tag(transport, "CurrentTransportState")
                    val rel = parseTime(tag(position, "RelTime"))
                    val dur = parseTime(tag(position, "TrackDuration"))
                    val now = System.currentTimeMillis()
                    val status = when (rawState) {
                        "PLAYING" -> CastPlayerStatus.PLAYING
                        "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> CastPlayerStatus.PAUSED
                        "TRANSITIONING" -> CastPlayerStatus.BUFFERING
                        "STOPPED", "NO_MEDIA_PRESENT" ->
                            if (now < loadingUntil) CastPlayerStatus.LOADING else CastPlayerStatus.STOPPED

                        else -> _state.value.status
                    }
                    if (status == CastPlayerStatus.PLAYING && pendingSeekMs > 1_000) {
                        val target = pendingSeekMs
                        pendingSeekMs = 0
                        runCatching {
                            soapAvt("Seek", "Unit" to "REL_TIME", "Target" to formatTime(target))
                        }
                    } else if (status == CastPlayerStatus.PLAYING) {
                        pendingSeekMs = 0
                    }
                    _state.update {
                        it.copy(
                            status = status,
                            positionMs = if (pendingSeekMs > 0) it.positionMs else rel,
                            durationMs = if (dur > 0) dur else it.durationMs,
                        )
                    }
                } catch (_: Exception) {
                    if (++failures >= 6) {
                        fail("Lost connection to ${device.name}")
                        break
                    }
                }
                delay(1_000)
            }
        }
    }

    private suspend fun refreshVolume() {
        val url = rcsUrl ?: return
        runCatching {
            val res = soap(url, rcsType, "GetVolume", "Channel" to "Master")
            val level = tag(res, "CurrentVolume")?.toIntOrNull() ?: return
            _state.update { it.copy(volume = level / 100f) }
        }
    }

    private fun fail(message: String) {
        _state.update { it.copy(status = CastPlayerStatus.ERROR, error = message) }
    }

    // ---- SOAP -------------------------------------------------------------------------------------

    private suspend fun soapAvt(action: String, vararg args: Pair<String, String>): String {
        val url = requireNotNull(avtUrl) { "${device.name} has no AVTransport service" }
        return soap(url, avtType, action, *args)
    }

    private suspend fun soap(url: String, type: String, action: String, vararg args: Pair<String, String>): String =
        withContext(Dispatchers.IO) {
            soapBlocking(url, type, action, listOf("InstanceID" to "0") + args.toList())
        }

    private fun soapBlocking(url: String, type: String, action: String, args: List<Pair<String, String>>): String {
        val body = buildString {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """)
            append("""s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""")
            append("<u:$action xmlns:u=\"$type\">")
            args.forEach { (k, v) -> append("<$k>").append(escapeXml(v)).append("</$k>") }
            append("</u:$action></s:Body></s:Envelope>")
        }
        val request = Request.Builder()
            .url(url)
            .header("SOAPACTION", "\"$type#$action\"")
            .post(body.toRequestBody("text/xml; charset=\"utf-8\"".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val reason = tag(text, "errorDescription") ?: "HTTP ${resp.code}"
                throw IOException("$action failed: $reason")
            }
            return text
        }
    }

    private fun tag(xml: String?, name: String): String? {
        if (xml == null) return null
        val m = Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL).find(xml) ?: return null
        return m.groupValues[1].trim()
    }

    private fun didl(media: CastMedia, url: String, mime: String, subtitleUrl: String?): String {
        val isHls = isHlsUrl(url, mime)
        val flags = if (isHls) "*" else "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"
        val kind = if (mime.startsWith("audio/")) "object.item.audioItem.musicTrack" else "object.item.videoItem"
        return buildString {
            append("""<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" """)
            append("""xmlns:dc="http://purl.org/dc/elements/1.1/" """)
            append("""xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" """)
            append("""xmlns:dlna="urn:schemas-dlna-org:metadata-1-0/" """)
            append("""xmlns:sec="http://www.sec.co.kr/">""")
            append("""<item id="0" parentID="-1" restricted="1">""")
            append("<dc:title>").append(escapeXml(media.title)).append("</dc:title>")
            append("<upnp:class>").append(kind).append("</upnp:class>")
            media.posterUrl?.let { append("<upnp:albumArtURI>").append(escapeXml(it)).append("</upnp:albumArtURI>") }
            append("""<res protocolInfo="http-get:*:""").append(escapeXml(mime)).append(':').append(flags).append("\">")
            append(escapeXml(url)).append("</res>")
            if (subtitleUrl != null) {
                append("""<res protocolInfo="http-get:*:text/srt:*">""").append(escapeXml(subtitleUrl)).append("</res>")
                append("""<sec:CaptionInfoEx sec:type="srt">""").append(escapeXml(subtitleUrl)).append("</sec:CaptionInfoEx>")
            }
            append("</item></DIDL-Lite>")
        }
    }

    private fun escapeXml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d:%02d".format(total / 3600, (total % 3600) / 60, total % 60)
    }

    private fun parseTime(value: String?): Long {
        if (value.isNullOrBlank() || !value.contains(':')) return 0L
        val parts = value.substringBefore('.').split(':')
        if (parts.size != 3) return 0L
        val h = parts[0].toLongOrNull() ?: return 0L
        val m = parts[1].toLongOrNull() ?: return 0L
        val s = parts[2].toLongOrNull() ?: return 0L
        return (h * 3600 + m * 60 + s) * 1000
    }
}
