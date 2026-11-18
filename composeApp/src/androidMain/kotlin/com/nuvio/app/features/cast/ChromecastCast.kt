package com.nuvio.app.features.cast

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

private const val NS_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
private const val NS_HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
private const val NS_RECEIVER = "urn:x-cast:com.google.cast.receiver"
private const val NS_MEDIA = "urn:x-cast:com.google.cast.media"
private const val DEFAULT_MEDIA_RECEIVER = "CC1AD845"

// ==================================================================================================
// Discovery (mDNS via NsdManager)
// ==================================================================================================

internal class ChromecastDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.DiscoveryListener? = null
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val found = LinkedHashMap<String, CastDevice>()
    private val lock = Any()

    fun start(onChanged: (List<CastDevice>) -> Unit) {
        if (listener != null) return
        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                synchronized(lock) { resolveQueue.addLast(serviceInfo) }
                resolveNext(onChanged)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val snapshot = synchronized(lock) {
                    found.entries.removeAll { it.value.name == serviceInfo.serviceName || it.key.endsWith(serviceInfo.serviceName) }
                    found.values.toList()
                }
                onChanged(snapshot)
            }
        }
        listener = discoveryListener
        runCatching { nsd.discoverServices("_googlecast._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        synchronized(lock) {
            resolveQueue.clear()
            resolving = false
            found.clear()
        }
    }

    @Suppress("DEPRECATION")
    private fun resolveNext(onChanged: (List<CastDevice>) -> Unit) {
        val next = synchronized(lock) {
            if (resolving) return
            val item = resolveQueue.removeFirstOrNull() ?: return
            resolving = true
            item
        }
        val finish = {
            synchronized(lock) { resolving = false }
            resolveNext(onChanged)
        }
        runCatching {
            nsd.resolveService(
                next,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        finish()
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val address = serviceInfo.host
                        val host = (address as? Inet4Address)?.hostAddress ?: address?.hostAddress?.substringBefore('%')
                        if (host != null) {
                            val attrs = serviceInfo.attributes
                            fun attr(key: String) = attrs[key]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
                            val id = attr("id") ?: serviceInfo.serviceName
                            val device = CastDevice(
                                id = "cast:$id",
                                name = attr("fn") ?: serviceInfo.serviceName,
                                protocol = CastProtocol.CHROMECAST,
                                host = host,
                                port = if (serviceInfo.port > 0) serviceInfo.port else 8009,
                                manufacturer = "Google",
                                model = attr("md"),
                            )
                            val snapshot = synchronized(lock) {
                                found[device.id] = device
                                found.values.toList()
                            }
                            onChanged(snapshot)
                        }
                        finish()
                    }
                },
            )
        }.onFailure { finish() }
    }
}

// ==================================================================================================
// CASTV2 wire protocol (TLS + length-prefixed protobuf CastMessage)
// ==================================================================================================

internal data class CastV2Message(
    val source: String,
    val destination: String,
    val namespace: String,
    val payload: String,
)

internal class CastV2Connection(private val host: String, private val port: Int) {
    private var socket: SSLSocket? = null
    private var output: DataOutputStream? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _messages = MutableSharedFlow<CastV2Message>(extraBufferCapacity = 128)
    val messages: SharedFlow<CastV2Message> = _messages.asSharedFlow()
    private val _closed = CompletableDeferred<Unit>()
    val closed: CompletableDeferred<Unit> get() = _closed
    private val writeLock = Any()

    suspend fun connect() = withContext(Dispatchers.IO) {
        // Chromecast presents a device-specific self-signed certificate, so chain validation is not
        // possible. Traffic never leaves the local network.
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val ctx = SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
        val s = ctx.socketFactory.createSocket() as SSLSocket
        s.connect(InetSocketAddress(host, port), 5_000)
        s.soTimeout = 0
        s.startHandshake()
        socket = s
        output = DataOutputStream(s.getOutputStream().buffered())

        scope.launch { readLoop(DataInputStream(s.getInputStream().buffered())) }
        scope.launch {
            while (isActive) {
                delay(5_000)
                runCatching { send(NS_HEARTBEAT, "receiver-0", JSONObject().put("type", "PING")) }
            }
        }
    }

    private suspend fun readLoop(input: DataInputStream) {
        try {
            while (scope.isActive) {
                val length = input.readInt()
                if (length <= 0 || length > 4 * 1024 * 1024) break
                val bytes = ByteArray(length)
                input.readFully(bytes)
                val msg = decode(bytes) ?: continue
                if (msg.namespace == NS_HEARTBEAT) {
                    if (JSONObject(msg.payload).optString("type") == "PING") {
                        runCatching { send(NS_HEARTBEAT, msg.source, JSONObject().put("type", "PONG")) }
                    }
                    continue
                }
                _messages.emit(msg)
            }
        } catch (_: Exception) {
            // fallthrough to close
        } finally {
            _closed.complete(Unit)
        }
    }

    fun send(namespace: String, destination: String, json: JSONObject, source: String = "sender-0") {
        val bytes = encode(source, destination, namespace, json.toString())
        synchronized(writeLock) {
            val out = output ?: error("Not connected")
            out.writeInt(bytes.size)
            out.write(bytes)
            out.flush()
        }
    }

    fun close() {
        scope.cancel()
        runCatching { socket?.close() }
        _closed.complete(Unit)
    }

    // Minimal protobuf for:
    //   message CastMessage { int32 protocol_version = 1; string source_id = 2; string destination_id = 3;
    //                         string namespace = 4; enum payload_type = 5; string payload_utf8 = 6; }
    private fun encode(source: String, destination: String, namespace: String, payload: String): ByteArray {
        val out = ByteArrayOutputStream()
        fun varint(value: Int) {
            var v = value
            while (v and 0x7F.inv() != 0) {
                out.write((v and 0x7F) or 0x80)
                v = v ushr 7
            }
            out.write(v)
        }

        fun string(field: Int, value: String) {
            val data = value.toByteArray(Charsets.UTF_8)
            varint((field shl 3) or 2)
            varint(data.size)
            out.write(data)
        }
        varint((1 shl 3) or 0); varint(0)
        string(2, source)
        string(3, destination)
        string(4, namespace)
        varint((5 shl 3) or 0); varint(0)
        string(6, payload)
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray): CastV2Message? {
        var pos = 0
        fun readVarint(): Int {
            var result = 0
            var shift = 0
            while (pos < bytes.size) {
                val b = bytes[pos++].toInt()
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return result
        }

        var source = ""
        var destination = ""
        var namespace = ""
        var payload = ""
        while (pos < bytes.size) {
            val tag = readVarint()
            val field = tag ushr 3
            when (tag and 7) {
                0 -> readVarint()
                2 -> {
                    val len = readVarint()
                    if (len < 0 || pos + len > bytes.size) return null
                    val text = String(bytes, pos, len, Charsets.UTF_8)
                    pos += len
                    when (field) {
                        2 -> source = text
                        3 -> destination = text
                        4 -> namespace = text
                        6 -> payload = text
                    }
                }

                else -> return null
            }
        }
        return CastV2Message(source, destination, namespace, payload)
    }
}

// ==================================================================================================
// Session (Default Media Receiver)
// ==================================================================================================

internal class ChromecastSession(
    override val device: CastDevice,
    private val server: LocalMediaServer,
) : CastSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connection = CastV2Connection(device.host, device.port)
    private val requestId = AtomicInteger(1)
    private val _state = MutableStateFlow(CastSessionState(device = device, status = CastPlayerStatus.CONNECTING))
    override val state: StateFlow<CastSessionState> = _state.asStateFlow()

    private var transportId: String? = null
    private var sessionId: String? = null
    private var mediaSessionId: Int? = null
    private var pollJob: Job? = null
    private var connected = false
    private var media: CastMedia? = null
    private var audioTrackIds: List<Int> = emptyList()
    private var textTrackIds: List<Int> = emptyList()

    private val launched = CompletableDeferred<Unit>()

    private suspend fun ensureConnected() {
        if (connected) return
        connection.connect()
        scope.launch { connection.messages.collect { handle(it) } }
        scope.launch {
            connection.closed.await()
            if (connected) {
                connected = false
                _state.update { it.copy(status = CastPlayerStatus.ERROR, error = "Lost connection to ${device.name}") }
            }
        }
        connected = true
        connection.send(NS_CONNECTION, "receiver-0", JSONObject().put("type", "CONNECT"))
        connection.send(
            NS_RECEIVER, "receiver-0",
            JSONObject().put("type", "LAUNCH").put("appId", DEFAULT_MEDIA_RECEIVER).put("requestId", requestId.getAndIncrement()),
        )
        withTimeout(15_000) { launched.await() }
    }

    override suspend fun load(media: CastMedia) {
        this.media = media
        try {
            ensureConnected()
            val address = LocalMediaServer.localAddressFor(device.host)
            val mime = guessMimeType(media.url, media.mimeType)
            val isHls = isHlsUrl(media.url, mime)
            val scheme = media.url.substringBefore(':').lowercase()
            val needsServer = scheme == "content" || scheme == "file" || media.headers.isNotEmpty() || media.forceProxy
            val contentUrl = if (needsServer) {
                requireNotNull(address) { "Could not find this phone's Wi-Fi address" }
                server.start()
                if (isHls) {
                    server.urlFor(address, "hls", server.registerHls(media.url, media.headers, null))
                } else {
                    server.urlFor(address, "u", server.registerMedia(media.url, media.headers, media.mimeType))
                }
            } else {
                media.url
            }

            // Chromecast needs WebVTT with CORS, so subtitles always go through the local server.
            val tracks = JSONArray()
            val activeTracks = JSONArray()
            media.subtitle?.let { sub ->
                requireNotNull(address) { "Could not find this phone's Wi-Fi address" }
                server.start()
                val subUrl = server.urlFor(address, "sub", server.registerSubtitle(sub.url, emptyMap(), true))
                tracks.put(
                    JSONObject()
                        .put("trackId", 1000)
                        .put("type", "TEXT")
                        .put("trackType", "TEXT")
                        .put("subtype", "SUBTITLES")
                        .put("trackContentId", subUrl)
                        .put("trackContentType", "text/vtt")
                        .put("name", sub.label)
                        .put("language", sub.language ?: "und"),
                )
                activeTracks.put(1000)
            }

            val metadata = JSONObject()
                .put("metadataType", 0)
                .put("title", media.title)
            media.posterUrl?.let { metadata.put("images", JSONArray().put(JSONObject().put("url", it))) }

            val mediaInfo = JSONObject()
                .put("contentId", contentUrl)
                .put("contentType", if (isHls) "application/x-mpegURL" else mime)
                .put("streamType", "BUFFERED")
                .put("metadata", metadata)
            if (tracks.length() > 0) mediaInfo.put("tracks", tracks)
            if (media.durationMs > 0) mediaInfo.put("duration", media.durationMs / 1000.0)

            val load = JSONObject()
                .put("type", "LOAD")
                .put("requestId", requestId.getAndIncrement())
                .put("sessionId", sessionId)
                .put("media", mediaInfo)
                .put("autoplay", true)
                .put("currentTime", media.startPositionMs / 1000.0)
            if (activeTracks.length() > 0) load.put("activeTrackIds", activeTracks)

            _state.update { it.copy(media = media, status = CastPlayerStatus.LOADING, positionMs = media.startPositionMs, error = null) }
            connection.send(NS_MEDIA, requireNotNull(transportId), load)
            startPolling()
        } catch (e: Exception) {
            _state.update { it.copy(status = CastPlayerStatus.ERROR, error = e.message ?: "Could not cast to ${device.name}") }
            throw e
        }
    }

    override suspend fun play() = mediaCommand("PLAY")
    override suspend fun pause() = mediaCommand("PAUSE")

    override suspend fun stop() {
        pollJob?.cancel()
        mediaCommand("STOP")
        _state.update { it.copy(status = CastPlayerStatus.STOPPED) }
    }

    override suspend fun seekTo(positionMs: Long) {
        mediaCommand("SEEK") { put("currentTime", positionMs / 1000.0) }
        _state.update { it.copy(positionMs = positionMs) }
    }

    override suspend fun setVolume(volume: Float) {
        val level = volume.coerceIn(0f, 1f)
        connection.send(
            NS_RECEIVER, "receiver-0",
            JSONObject().put("type", "SET_VOLUME").put("requestId", requestId.getAndIncrement())
                .put("volume", JSONObject().put("level", level.toDouble())),
        )
        _state.update { it.copy(volume = level) }
    }

    override suspend fun setMuted(muted: Boolean) {
        connection.send(
            NS_RECEIVER, "receiver-0",
            JSONObject().put("type", "SET_VOLUME").put("requestId", requestId.getAndIncrement())
                .put("volume", JSONObject().put("muted", muted)),
        )
        _state.update { it.copy(muted = muted) }
    }

    override suspend fun selectAudioTrack(trackId: String) {
        val id = trackId.toIntOrNull() ?: return
        if (id !in audioTrackIds) return
        val active = JSONArray().put(id)
        textTrackIds.forEach { active.put(it) }
        mediaCommand("EDIT_TRACKS_INFO") { put("activeTrackIds", active) }
        _state.update { it.copy(selectedAudioTrackId = trackId) }
    }

    override fun release() {
        pollJob?.cancel()
        runCatching {
            val sid = sessionId
            if (sid != null) {
                connection.send(
                    NS_RECEIVER, "receiver-0",
                    JSONObject().put("type", "STOP").put("sessionId", sid).put("requestId", requestId.getAndIncrement()),
                )
            }
        }
        connected = false
        connection.close()
        scope.cancel()
        _state.update { CastSessionState() }
    }

    // ---- protocol handling ----------------------------------------------------------------------

    private fun mediaCommand(type: String, extra: (JSONObject.() -> Unit)? = null) {
        val transport = transportId ?: return
        val msId = mediaSessionId ?: return
        val json = JSONObject().put("type", type).put("requestId", requestId.getAndIncrement()).put("mediaSessionId", msId)
        extra?.invoke(json)
        connection.send(NS_MEDIA, transport, json)
    }

    private fun handle(msg: CastV2Message) {
        val json = runCatching { JSONObject(msg.payload) }.getOrNull() ?: return
        when (msg.namespace) {
            NS_RECEIVER -> if (json.optString("type") == "RECEIVER_STATUS") handleReceiverStatus(json.optJSONObject("status"))
            NS_MEDIA -> when (json.optString("type")) {
                "MEDIA_STATUS" -> handleMediaStatus(json.optJSONArray("status"))
                "LOAD_FAILED", "INVALID_REQUEST", "LOAD_CANCELLED" ->
                    _state.update { it.copy(status = CastPlayerStatus.ERROR, error = "${device.name} could not play this stream") }
            }

            NS_CONNECTION -> if (json.optString("type") == "CLOSE") {
                _state.update { it.copy(status = CastPlayerStatus.STOPPED) }
            }
        }
    }

    private fun handleReceiverStatus(status: JSONObject?) {
        status ?: return
        status.optJSONObject("volume")?.let { vol ->
            _state.update {
                it.copy(
                    volume = if (vol.has("level")) vol.optDouble("level", it.volume.toDouble()).toFloat() else it.volume,
                    muted = vol.optBoolean("muted", it.muted),
                )
            }
        }
        val apps = status.optJSONArray("applications") ?: return
        for (i in 0 until apps.length()) {
            val app = apps.optJSONObject(i) ?: continue
            if (app.optString("appId") == DEFAULT_MEDIA_RECEIVER && transportId == null) {
                transportId = app.optString("transportId")
                sessionId = app.optString("sessionId")
                connection.send(NS_CONNECTION, requireNotNull(transportId), JSONObject().put("type", "CONNECT"))
                launched.complete(Unit)
            }
        }
    }

    private fun handleMediaStatus(statuses: JSONArray?) {
        val s = statuses?.optJSONObject(0) ?: return
        mediaSessionId = s.optInt("mediaSessionId", mediaSessionId ?: 0)

        val mediaObj = s.optJSONObject("media")
        if (mediaObj != null) {
            val tracks = mediaObj.optJSONArray("tracks")
            if (tracks != null) {
                val audio = ArrayList<CastAudioTrack>()
                val text = ArrayList<Int>()
                for (i in 0 until tracks.length()) {
                    val t = tracks.optJSONObject(i) ?: continue
                    val id = t.optInt("trackId")
                    when (t.optString("type")) {
                        "AUDIO" -> audio += CastAudioTrack(
                            id = id.toString(),
                            label = t.optString("name").ifBlank { t.optString("language").ifBlank { "Track ${audio.size + 1}" } },
                            language = t.optString("language").ifBlank { null },
                        )

                        "TEXT" -> text += id
                    }
                }
                audioTrackIds = audio.mapNotNull { it.id.toIntOrNull() }
                textTrackIds = text
                if (audio.isNotEmpty()) {
                    val active = s.optJSONArray("activeTrackIds")
                    var selected: String? = null
                    if (active != null) {
                        for (i in 0 until active.length()) {
                            if (active.optInt(i) in audioTrackIds) selected = active.optInt(i).toString()
                        }
                    }
                    _state.update {
                        it.copy(
                            audioTracks = audio,
                            selectedAudioTrackId = selected ?: it.selectedAudioTrackId ?: audio.first().id,
                            audioSwitchMode = if (audio.size > 1) AudioSwitchMode.NATIVE else AudioSwitchMode.NONE,
                        )
                    }
                }
            }
            val duration = mediaObj.optDouble("duration", 0.0)
            if (duration > 0) _state.update { it.copy(durationMs = (duration * 1000).toLong()) }
        }

        val status = when (s.optString("playerState")) {
            "PLAYING" -> CastPlayerStatus.PLAYING
            "PAUSED" -> CastPlayerStatus.PAUSED
            "BUFFERING" -> CastPlayerStatus.BUFFERING
            "IDLE" -> when (s.optString("idleReason")) {
                "FINISHED", "CANCELLED", "INTERRUPTED" -> CastPlayerStatus.STOPPED
                "ERROR" -> CastPlayerStatus.ERROR
                else -> _state.value.status
            }

            else -> _state.value.status
        }
        _state.update {
            it.copy(
                status = status,
                positionMs = (s.optDouble("currentTime", it.positionMs / 1000.0) * 1000).toLong(),
                error = if (status == CastPlayerStatus.ERROR) "${device.name} could not play this stream" else it.error,
            )
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(1_000)
                runCatching {
                    val transport = transportId ?: return@runCatching
                    val json = JSONObject().put("type", "GET_STATUS").put("requestId", requestId.getAndIncrement())
                    mediaSessionId?.let { json.put("mediaSessionId", it) }
                    connection.send(NS_MEDIA, transport, json)
                }
            }
        }
    }
}
