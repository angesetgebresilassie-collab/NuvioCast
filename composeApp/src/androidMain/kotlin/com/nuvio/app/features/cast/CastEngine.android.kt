package com.nuvio.app.features.cast

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val SSDP_ADDRESS = "239.255.255.250"
private const val SSDP_PORT = 1900
private const val DISCOVERY_WINDOW_MS = 12_000L
private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:"
private const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:"
private val INSTANCE = listOf("InstanceID" to "0")
private val AUDIO_ACTION = Regex("(?i)audio[_]?(track|stream)|^(set|select)audio$")

private class UpnpService(val type: String, val controlUrl: String, val scpdUrl: String?)

private class AudioAction(
    val service: UpnpService,
    val action: String,
    val valueArgument: String,
    val hasInstanceId: Boolean,
    val base: Int,
)

private class UpnpDevice(
    val renderer: CastRenderer,
    val host: String,
    val avTransport: UpnpService,
    val rendering: UpnpService?,
) {
    @Volatile
    var audioAction: AudioAction? = null

    @Volatile
    var capabilitiesLoaded = false
}

@Composable
actual fun rememberCastEngine(): CastEngine {
    val context = LocalContext.current
    return remember(context) { UpnpCastEngine.get(context) }
}

/**
 * UPnP/DLNA sender: SSDP discovery, AVTransport/RenderingControl SOAP control, and a phone-side relay
 * ([CastProxyServer]) for streams a renderer can't fetch on its own.
 */
internal class UpnpCastEngine private constructor(private val appContext: Context) : CastEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val devices = ConcurrentHashMap<String, UpnpDevice>()
    private val proxy = CastProxyServer()

    private val rendererState = MutableStateFlow<List<CastRenderer>>(emptyList())
    private val sessionState = MutableStateFlow(CastSessionState())
    private val discoveringState = MutableStateFlow(false)

    override val renderers: StateFlow<List<CastRenderer>> = rendererState.asStateFlow()
    override val session: StateFlow<CastSessionState> = sessionState.asStateFlow()
    override val isDiscovering: StateFlow<Boolean> = discoveringState.asStateFlow()

    private var discoveryJob: Job? = null
    private var castJob: Job? = null
    private var pollJob: Job? = null

    @Volatile
    private var activeDevice: UpnpDevice? = null

    // ---------------------------------------------------------------- discovery

    override fun startDiscovery() {
        if (discoveryJob?.isActive == true) return
        discoveringState.value = true
        discoveryJob = scope.launch {
            val lock = runCatching {
                (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                    ?.createMulticastLock("nuvio-cast")
                    ?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }.getOrNull()
            val seenLocations = HashSet<String>()
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    soTimeout = 1_000
                    bind(InetSocketAddress(0))
                }
                val end = System.currentTimeMillis() + DISCOVERY_WINDOW_MS
                var lastSend = 0L
                val buffer = ByteArray(4096)
                while (isActive && System.currentTimeMillis() < end) {
                    val now = System.currentTimeMillis()
                    if (now - lastSend >= 3_000L) {
                        sendSearch(socket)
                        lastSend = now
                    }
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val location = headerValue(text, "LOCATION")
                        if (location != null && seenLocations.add(location)) {
                            launch { runCatching { loadDevice(location) } }
                        }
                    } catch (e: SocketTimeoutException) {
                        // keep listening until the window closes
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // discovery is best effort; the list simply stays as it is
            } finally {
                runCatching { socket?.close() }
                runCatching { lock?.release() }
                discoveringState.value = false
            }
        }
    }

    override fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        discoveringState.value = false
    }

    private fun sendSearch(socket: DatagramSocket) {
        val group = InetAddress.getByName(SSDP_ADDRESS)
        listOf(
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:service:AVTransport:1",
            "upnp:rootdevice",
        ).forEach { target ->
            val message = "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: $SSDP_ADDRESS:$SSDP_PORT\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 2\r\n" +
                "ST: $target\r\n" +
                "USER-AGENT: Android UPnP/1.1 NuvioCast/1.0\r\n\r\n"
            val data = message.toByteArray()
            runCatching { socket.send(DatagramPacket(data, data.size, group, SSDP_PORT)) }
        }
    }

    private fun headerValue(response: String, name: String): String? =
        response.lineSequence()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun loadDevice(location: String) {
        val xml = httpGet(location, 4_000) ?: return
        val udn = tag(xml, "UDN") ?: location
        if (devices.containsKey(udn)) return
        val base = tag(xml, "URLBase") ?: location
        val services = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .mapNotNull { match ->
                val block = match.groupValues[1]
                val type = tag(block, "serviceType") ?: return@mapNotNull null
                val control = tag(block, "controlURL") ?: return@mapNotNull null
                UpnpService(type, resolve(base, control), tag(block, "SCPDURL")?.let { resolve(base, it) })
            }
            .toList()
        val avTransport = services.firstOrNull { it.type.startsWith(AV_TRANSPORT) } ?: return
        val rendering = services.firstOrNull { it.type.startsWith(RENDERING_CONTROL) }
        val host = runCatching { URL(location).host }.getOrNull() ?: return
        val renderer = CastRenderer(
            id = udn,
            name = tag(xml, "friendlyName") ?: host,
            manufacturer = tag(xml, "manufacturer"),
            model = tag(xml, "modelName"),
            address = host,
        )
        devices[udn] = UpnpDevice(renderer, host, avTransport, rendering)
        rendererState.value = devices.values.map { it.renderer }.sortedBy { it.name.lowercase() }
    }

    // ---------------------------------------------------------------- casting

    override fun cast(renderer: CastRenderer, media: CastMedia) {
        val device = devices[renderer.id] ?: return
        castJob?.cancel()
        pollJob?.cancel()
        castJob = scope.launch {
            val previous = activeDevice
            if (previous != null && previous !== device) {
                runCatching { control(previous.avTransport, "Stop", INSTANCE) }
            }
            activeDevice = device
            sessionState.value = CastSessionState(renderer = device.renderer, status = CastStatus.Connecting)
            try {
                val (playUrl, mime) = prepare(media, device)
                runCatching { control(device.avTransport, "Stop", INSTANCE) }
                control(
                    device.avTransport,
                    "SetAVTransportURI",
                    listOf(
                        "InstanceID" to "0",
                        "CurrentURI" to playUrl,
                        "CurrentURIMetaData" to buildDidl(media.title, playUrl, mime),
                    ),
                )
                control(device.avTransport, "Play", listOf("InstanceID" to "0", "Speed" to "1"))
                loadCapabilities(device)
                sessionState.update { it.copy(audioSwitchSupported = device.audioAction != null) }
                startPolling(device, media.startPositionMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sessionState.value = CastSessionState(
                    renderer = device.renderer,
                    status = CastStatus.Error,
                    notice = e.message ?: "Couldn't start casting",
                )
            }
        }
    }

    private fun prepare(media: CastMedia, device: UpnpDevice): Pair<String, String> {
        val uri = runCatching { URI(media.url) }.getOrNull()
        val host = uri?.host.orEmpty()
        val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
        val needsRelay = media.headers.isNotEmpty() || uri?.scheme?.lowercase() != "http" || isLoopback
        val mime = guessMimeFromUrl(media.url) ?: probeMime(media) ?: "video/mp4"
        if (!needsRelay) return media.url to mime
        val ip = localAddressFor(device.host)
            ?: throw IOException("Couldn't find this phone's Wi-Fi address. Are you on the same network?")
        return proxy.register(media.url, media.headers, ip, mime) to mime
    }

    private fun probeMime(media: CastMedia): String? = runCatching {
        val c = URL(media.url).openConnection() as HttpURLConnection
        c.connectTimeout = 4_000
        c.readTimeout = 4_000
        c.requestMethod = "HEAD"
        media.headers.forEach { (name, value) -> c.setRequestProperty(name, value) }
        val type = c.contentType?.substringBefore(';')?.trim()?.lowercase()
        c.disconnect()
        type?.takeIf { it.startsWith("video/") || it.startsWith("audio/") }
    }.getOrNull()

    private fun buildDidl(title: String, url: String, mime: String): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>${xmlEscape(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:DLNA.ORG_OP=01;DLNA.ORG_CI=0;" +
            "DLNA.ORG_FLAGS=01700000000000000000000000000000\">${xmlEscape(url)}</res>" +
            "</item></DIDL-Lite>"

    private fun localAddressFor(remoteHost: String): String? {
        val remote = remoteHost.split(".")
        var fallback: String? = null
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (network in Collections.list(interfaces)) {
            if (!network.isUp || network.isLoopback) continue
            for (address in Collections.list(network.inetAddresses)) {
                if (address !is Inet4Address || address.isLoopbackAddress) continue
                val ip = address.hostAddress ?: continue
                if (fallback == null) fallback = ip
                val local = ip.split(".")
                if (local.size == 4 && remote.size == 4 && local.take(3) == remote.take(3)) return ip
            }
        }
        return fallback
    }

    // ---------------------------------------------------------------- transport controls

    override fun play() = act { device ->
        control(device.avTransport, "Play", listOf("InstanceID" to "0", "Speed" to "1"))
    }

    override fun pause() = act { device ->
        control(device.avTransport, "Pause", INSTANCE)
    }

    override fun seekTo(positionMs: Long) = act { device ->
        seekInternal(device, positionMs)
        sessionState.update { it.copy(positionMs = positionMs) }
    }

    override fun setVolume(percent: Int) = act { device ->
        val rendering = device.rendering ?: return@act
        val value = percent.coerceIn(0, 100)
        control(
            rendering,
            "SetVolume",
            listOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to value.toString()),
        )
        sessionState.update { it.copy(volume = value) }
    }

    override fun selectAudioTrack(position: Int) {
        val device = activeDevice ?: return
        val action = device.audioAction
        if (action == null) {
            sessionState.update { it.copy(notice = "This renderer can't switch audio tracks remotely.") }
            return
        }
        scope.launch {
            try {
                val args = buildList {
                    if (action.hasInstanceId) add("InstanceID" to "0")
                    add(action.valueArgument to (position + action.base).toString())
                }
                control(action.service, action.action, args)
                sessionState.update { it.copy(audioTrackPosition = position, notice = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sessionState.update { it.copy(notice = "The renderer rejected the audio track change.") }
            }
        }
    }

    override fun stop() {
        val device = activeDevice
        castJob?.cancel()
        pollJob?.cancel()
        activeDevice = null
        proxy.clear()
        sessionState.value = CastSessionState()
        if (device != null) {
            scope.launch { runCatching { control(device.avTransport, "Stop", INSTANCE) } }
        }
    }

    private fun act(block: (UpnpDevice) -> Unit) {
        val device = activeDevice ?: return
        scope.launch {
            try {
                block(device)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sessionState.update { it.copy(notice = e.message) }
            }
        }
    }

    private fun seekInternal(device: UpnpDevice, positionMs: Long) {
        control(
            device.avTransport,
            "Seek",
            listOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to formatTime(positionMs)),
        )
    }

    private fun startPolling(device: UpnpDevice, startPositionMs: Long) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var failures = 0
            var pendingSeek: Long? = startPositionMs.takeIf { it > 3_000L }
            val volume = readVolume(device)
            if (volume != null) sessionState.update { it.copy(volume = volume) }
            while (isActive) {
                try {
                    val transport = control(device.avTransport, "GetTransportInfo", INSTANCE)
                    val position = control(device.avTransport, "GetPositionInfo", INSTANCE)
                    failures = 0
                    val status = when (tag(transport, "CurrentTransportState")?.uppercase()) {
                        "PLAYING" -> CastStatus.Playing
                        "PAUSED_PLAYBACK", "PAUSED_RECORDING" -> CastStatus.Paused
                        "STOPPED", "NO_MEDIA_PRESENT" -> CastStatus.Stopped
                        else -> CastStatus.Buffering
                    }
                    val target = pendingSeek
                    if (target != null && status == CastStatus.Playing) {
                        runCatching { seekInternal(device, target) }
                        pendingSeek = null
                    }
                    val positionMs = parseTime(tag(position, "RelTime"))
                    val durationMs = parseTime(tag(position, "TrackDuration"))
                    sessionState.update { current ->
                        if (current.renderer?.id == device.renderer.id) {
                            current.copy(status = status, positionMs = positionMs, durationMs = durationMs)
                        } else {
                            current
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failures++
                    if (failures >= 5) {
                        sessionState.update {
                            it.copy(status = CastStatus.Error, notice = "Lost connection to the renderer.")
                        }
                        break
                    }
                }
                delay(1_000L)
            }
        }
    }

    private fun readVolume(device: UpnpDevice): Int? {
        val rendering = device.rendering ?: return null
        return runCatching {
            tag(
                control(rendering, "GetVolume", listOf("InstanceID" to "0", "Channel" to "Master")),
                "CurrentVolume",
            )?.toIntOrNull()
        }.getOrNull()
    }

    // ---------------------------------------------------------------- audio track capability

    /**
     * UPnP has no standard audio-track action, so look through the renderer's service descriptions for a
     * vendor/optional action (e.g. SetAudioTrack) and remember how to call it.
     */
    private fun loadCapabilities(device: UpnpDevice) {
        if (device.capabilitiesLoaded) return
        device.capabilitiesLoaded = true
        for (service in listOfNotNull(device.avTransport, device.rendering)) {
            val scpd = service.scpdUrl?.let { httpGet(it, 4_000) } ?: continue
            for (match in Regex("<action>(.*?)</action>", RegexOption.DOT_MATCHES_ALL).findAll(scpd)) {
                val block = match.groupValues[1]
                val name = tag(block, "name") ?: continue
                if (name.startsWith("Get", ignoreCase = true) || name.startsWith("List", ignoreCase = true)) continue
                if (!AUDIO_ACTION.containsMatchIn(name)) continue
                val inArguments = Regex("<argument>(.*?)</argument>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(block)
                    .map { it.groupValues[1] }
                    .filter { tag(it, "direction")?.lowercase() == "in" }
                    .toList()
                val valueArgument = inArguments.firstOrNull { tag(it, "name") != "InstanceID" } ?: continue
                val valueName = tag(valueArgument, "name") ?: continue
                val related = tag(valueArgument, "relatedStateVariable")
                val base = related?.let { stateVariableMinimum(scpd, it) } ?: 0
                device.audioAction = AudioAction(
                    service = service,
                    action = name,
                    valueArgument = valueName,
                    hasInstanceId = inArguments.any { tag(it, "name") == "InstanceID" },
                    base = base,
                )
                return
            }
        }
    }

    private fun stateVariableMinimum(scpd: String, name: String): Int? =
        Regex("<stateVariable[^>]*>(.*?)</stateVariable>", RegexOption.DOT_MATCHES_ALL)
            .findAll(scpd)
            .map { it.groupValues[1] }
            .firstOrNull { tag(it, "name") == name }
            ?.let { tag(it, "minimum")?.toIntOrNull() }

    // ---------------------------------------------------------------- SOAP / XML helpers

    private fun control(service: UpnpService, action: String, args: List<Pair<String, String>>): String {
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
            append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
            append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
            append("<u:$action xmlns:u=\"${service.type}\">")
            args.forEach { (name, value) -> append("<$name>${xmlEscape(value)}</$name>") }
            append("</u:$action></s:Body></s:Envelope>")
        }
        val connection = URL(service.controlUrl).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 5_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            connection.setRequestProperty("SOAPACTION", "\"${service.type}#$action\"")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val reason = tag(text, "errorDescription") ?: text.take(120)
                throw IOException("Renderer refused $action ($code): $reason")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }

    private fun httpGet(url: String, timeoutMs: Int): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            c.disconnect()
        }
    }.getOrNull()

    private fun resolve(base: String, relative: String): String =
        runCatching { URL(URL(base), relative).toString() }.getOrDefault(relative)

    private fun tag(xml: String, name: String): String? =
        Regex("<(?:\\w+:)?$name(?:\\s[^>]*)?>(.*?)</(?:\\w+:)?$name>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.let { xmlUnescape(it) }
            ?.takeIf { it.isNotEmpty() }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun xmlUnescape(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun parseTime(value: String?): Long {
        if (value.isNullOrBlank() || value == "NOT_IMPLEMENTED") return 0L
        val parts = value.trim().split(":")
        if (parts.size < 3) return 0L
        val hours = parts[0].toLongOrNull() ?: return 0L
        val minutes = parts[1].toLongOrNull() ?: return 0L
        val seconds = parts[2].toDoubleOrNull() ?: return 0L
        return (hours * 3_600_000L) + (minutes * 60_000L) + (seconds * 1000).toLong()
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0L)
        return String.format(Locale.US, "%d:%02d:%02d", total / 3600, (total % 3600) / 60, total % 60)
    }

    companion object {
        @Volatile
        private var instance: UpnpCastEngine? = null

        fun get(context: Context): UpnpCastEngine =
            instance ?: synchronized(this) {
                instance ?: UpnpCastEngine(context.applicationContext).also { instance = it }
            }
    }
}
