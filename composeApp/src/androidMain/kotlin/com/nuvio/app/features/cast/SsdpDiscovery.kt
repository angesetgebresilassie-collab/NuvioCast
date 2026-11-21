package com.nuvio.app.features.cast

import android.content.Context
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
import kotlin.coroutines.coroutineContext

private const val TAG = "CastDiscovery"

/**
 * UPnP/DLNA discovery over SSDP.
 *
 * Why this is more than one socket: a plain wildcard socket sends the multicast search out of
 * whatever interface Android picks, which is often not the Wi-Fi one (mobile data, VPN, hotspot).
 * This opens one socket per usable IPv4 interface, sends the search on each, and listens on each.
 * Device descriptions are fetched in parallel so one slow device cannot stall discovery, and
 * several search targets are used because small receivers answer only some of them.
 */
internal class SsdpDiscovery(
    @Suppress("unused") private val context: Context,
    private val http: OkHttpClient,
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
            Log.w(TAG, "No multicast-capable IPv4 interface found")
            return@withContext
        }
        val group = InetAddress.getByName("239.255.255.250")
        val pool = Executors.newFixedThreadPool(6)
        val pending = Collections.synchronizedList(ArrayList<Future<*>>())
        val seen = ConcurrentHashMap.newKeySet<String>()
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
                        }
                    }
                    rounds++
                }
                for (socket in sockets) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val text = String(packet.data, 0, packet.length)
                        val location = headerValue(text, "LOCATION") ?: continue
                        if (seen.add(location)) {
                            pending += pool.submit {
                                describe(location)?.let {
                                    Log.d(TAG, "Found ${it.name} at ${it.host}")
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
            Log.d(TAG, "Scan done: ${sockets.size} interface(s), ${seen.size} location(s) answered")
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
        return interfaces.mapNotNull { ni ->
            runCatching {
                if (!ni.isUp || ni.isLoopback || !ni.supportsMulticast()) return@runCatching null
                val address = Collections.list(ni.inetAddresses)
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull { !it.isLoopbackAddress } ?: return@runCatching null
                MulticastSocket(InetSocketAddress(address, 0)).apply {
                    timeToLive = 4
                    soTimeout = 150
                    networkInterface = ni
                    Log.d(TAG, "Searching on ${ni.name} (${address.hostAddress})")
                }
            }.getOrNull()
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
