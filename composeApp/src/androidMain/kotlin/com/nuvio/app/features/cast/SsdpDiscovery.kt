package com.nuvio.app.features.cast

import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

private const val TAG = "CastDiscovery"

/**
 * UPnP/DLNA discovery over SSDP, with a running commentary ([log]) so a failed search says why.
 *
 * - One socket per usable IPv4 interface (Android's default route is often not the Wi-Fi one).
 * - Several search targets, because small receivers answer only some of them.
 * - Device descriptions are fetched in parallel so one slow device cannot stall the scan.
 *
 * [log] is called with (key, text); a later call with the same key replaces the earlier line.
 */
internal class SsdpDiscovery(
    private val http: OkHttpClient,
    private val log: (String, String) -> Unit,
) {
    private val searchTargets = listOf(
        "urn:schemas-upnp-org:device:MediaRenderer:1",
        "urn:schemas-upnp-org:service:AVTransport:1",
        "upnp:rootdevice",
        "ssdp:all",
    )

    suspend fun scan(timeoutMs: Long = 5_000L, onDevice: (CastDevice) -> Unit) = withContext(Dispatchers.IO) {
        val sockets = openSockets()
        if (sockets.isEmpty()) {
            log("scan", "No Wi-Fi/LAN network with multicast found. Is Wi-Fi on?")
            return@withContext
        }
        val group = InetAddress.getByName("239.255.255.250")
        val pool = Executors.newFixedThreadPool(6)
        val pending = Collections.synchronizedList(ArrayList<Future<*>>())
        val seen = ConcurrentHashMap.newKeySet<String>()
        val replies = AtomicInteger(0)
        val found = AtomicInteger(0)
        val buffer = ByteArray(8192)
        try {
            val start = System.currentTimeMillis()
            val deadline = start + timeoutMs
            var rounds = 0
            while (System.currentTimeMillis() < deadline) {
                coroutineContext.ensureActive()
                // Three bursts, a second apart: UDP is lossy and receivers can be slow to wake up.
                if (rounds < 3 && System.currentTimeMillis() - start >= rounds * 1_000L) {
                    for (socket in sockets) {
                        for (target in searchTargets) {
                            val bytes = searchMessage(target).toByteArray()
                            runCatching { socket.send(DatagramPacket(bytes, bytes.size, group, 1900)) }
                                .onFailure { log("send", "Could not send search: ${it.message}") }
                        }
                    }
                    rounds++
                }
                for (socket in sockets) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        replies.incrementAndGet()
                        val text = String(packet.data, 0, packet.length)
                        val location = headerValue(text, "LOCATION") ?: continue
                        if (seen.add(location)) {
                            pending += pool.submit {
                                describe(location)?.let {
                                    found.incrementAndGet()
                                    Log.d(TAG, "Found ${it.name} at ${it.host}")
                                    log("found:${it.id}", "Found ${it.name} (${it.host})")
                                    onDevice(it)
                                }
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // nothing on this interface right now
                    }
                }
            }
            synchronized(pending) { pending.toList() }.forEach {
                runCatching { it.get(4, TimeUnit.SECONDS) }
            }
            log(
                "scan",
                "Last search: ${replies.get()} reply(ies), ${seen.size} device(s) answered, " +
                    "${found.get()} can play media",
            )
        } finally {
            sockets.forEach { runCatching { it.close() } }
            pool.shutdownNow()
        }
    }

    private fun searchMessage(target: String): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: $target\r\n" +
            "USER-AGENT: Android/1.0 UPnP/1.1 NuvioCast/1.0\r\n\r\n"

    private fun openSockets(): List<MulticastSocket> {
        val interfaces = runCatching { Collections.list(NetworkInterface.getNetworkInterfaces()) }
            .getOrDefault(emptyList())
        val opened = ArrayList<MulticastSocket>()
        val names = ArrayList<String>()
        for (ni in interfaces) {
            runCatching {
                if (!ni.isUp || ni.isLoopback || !ni.supportsMulticast()) return@runCatching
                val address = Collections.list(ni.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress } ?: return@runCatching
                val socket = MulticastSocket(InetSocketAddress(address, 0)).apply {
                    timeToLive = 4
                    soTimeout = 150
                    networkInterface = ni
                }
                opened += socket
                names += "${ni.name} ${address.hostAddress}"
            }
        }
        log("net", if (names.isEmpty()) "No usable network found" else "Searching on: ${names.joinToString(", ")}")
        return opened
    }

    private fun headerValue(response: String, name: String): String? =
        response.lineSequence()
            .firstOrNull { it.startsWith("$name:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.trim()

    private fun describe(location: String): CastDevice? = try {
        val xml = http.newCall(Request.Builder().url(location).build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                log("desc:$location", "Device at $location answered HTTP ${resp.code}")
                return@use null
            }
            resp.body?.string()
        }
        if (xml == null) null else parseDescription(location, xml)
    } catch (e: Exception) {
        log("desc:$location", "Could not read $location: ${e.message}")
        null
    }

    private fun parseDescription(location: String, xml: String): CastDevice? {
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

        if (services.keys.none { it.contains("AVTransport") }) {
            // Routers, printers, NAS boxes... answer SSDP too; only renderers can play media.
            log("skip:$location", "Skipped ${friendly ?: location}: not a media renderer")
            return null
        }
        val uri = URI(location)
        return CastDevice(
            id = "dlna:" + (udn ?: location),
            name = friendly ?: uri.host,
            protocol = CastProtocol.DLNA,
            host = uri.host,
            port = if (uri.port > 0) uri.port else 80,
            manufacturer = manufacturer,
            model = model,
            controlUrls = services,
        )
    }
}
