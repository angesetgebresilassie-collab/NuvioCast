package com.nuvio.app.features.cast

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Single entry point for casting. Call [init] once (e.g. from Application.onCreate), then:
 *
 *  - [startDiscovery]/[stopDiscovery] while a device picker is visible
 *  - [cast] to connect to a device and load media
 *  - the transport/volume/audio functions to control the active session
 *  - [state] to render it
 *
 * Protocols are pluggable: each one provides a discovery source and a [CastSession]. DLNA and
 * Chromecast are implemented; adding another (AirPlay, Roku ECP, ...) means adding a
 * [CastProtocol] entry, a discovery feeding [publish], and a branch in [cast].
 */
object CastManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var appContext: Context? = null

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private var server: LocalMediaServer? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var dlnaDiscovery: DlnaDiscovery? = null
    private var chromecastDiscovery: ChromecastDiscovery? = null
    private var scanJob: Job? = null

    private val dlnaFound = ConcurrentHashMap<String, CastDevice>()
    @Volatile
    private var chromecastFound: List<CastDevice> = emptyList()

    private val _devices = MutableStateFlow<List<CastDevice>>(emptyList())
    val devices: StateFlow<List<CastDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _state = MutableStateFlow(CastSessionState())
    val state: StateFlow<CastSessionState> = _state.asStateFlow()

    private var session: CastSession? = null
    private var sessionJob: Job? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ---- discovery ---------------------------------------------------------------------------------

    @Synchronized
    fun startDiscovery() {
        val ctx = appContext ?: return
        if (scanJob?.isActive == true) return

        runCatching {
            val wifi = ctx.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("nuviocast-discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        val chromecast = ChromecastDiscovery(ctx).also { chromecastDiscovery = it }
        chromecast.start { list ->
            chromecastFound = list
            publish()
        }

        val dlna = DlnaDiscovery(http).also { dlnaDiscovery = it }
        _scanning.value = true
        scanJob = scope.launch {
            try {
                while (isActive) {
                    runCatching {
                        dlna.scan { device ->
                            dlnaFound[device.id] = device
                            publish()
                        }
                    }
                    delay(2_000)
                }
            } finally {
                _scanning.value = false
            }
        }
    }

    @Synchronized
    fun stopDiscovery() {
        scanJob?.cancel()
        scanJob = null
        chromecastDiscovery?.stop()
        chromecastDiscovery = null
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
        dlnaFound.clear()
        chromecastFound = emptyList()
        publish()
    }

    private fun publish() {
        _devices.value = (dlnaFound.values + chromecastFound).sortedBy { it.name.lowercase() }
    }

    // ---- session ---------------------------------------------------------------------------------------

    suspend fun cast(device: CastDevice, media: CastMedia): Result<Unit> {
        val ctx = appContext ?: return Result.failure(IllegalStateException("CastManager.init(context) was not called"))
        disconnect()
        return runCatching {
            val srv = server ?: LocalMediaServer(ctx, http).also { server = it }
            val created: CastSession = when (device.protocol) {
                CastProtocol.DLNA -> DlnaSession(device, http, srv)
                CastProtocol.CHROMECAST -> ChromecastSession(device, srv)
            }
            session = created
            sessionJob = scope.launch { created.state.collect { _state.value = it } }
            created.load(media)
        }.onFailure { error ->
            disconnect()
            _state.value = CastSessionState(
                device = device,
                media = media,
                status = CastPlayerStatus.ERROR,
                error = error.message ?: "Could not cast to ${device.name}",
            )
        }
    }

    fun disconnect() {
        sessionJob?.cancel()
        sessionJob = null
        session?.release()
        session = null
        server?.stop()
        _state.value = CastSessionState()
    }

    suspend fun play() = control { it.play() }
    suspend fun pause() = control { it.pause() }
    suspend fun stop() = control { it.stop() }
    suspend fun seekTo(positionMs: Long) = control { it.seekTo(positionMs) }
    suspend fun setVolume(volume: Float) = control { it.setVolume(volume) }
    suspend fun setMuted(muted: Boolean) = control { it.setMuted(muted) }
    suspend fun selectAudioTrack(trackId: String) = control { it.selectAudioTrack(trackId) }

    private suspend fun control(block: suspend (CastSession) -> Unit): Result<Unit> {
        val active = session ?: return Result.failure(IllegalStateException("Not casting"))
        return runCatching { block(active) }
    }
}
