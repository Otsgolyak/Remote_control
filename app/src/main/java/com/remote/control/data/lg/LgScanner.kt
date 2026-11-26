package com.remote.control.data.lg

import android.content.Context
import android.net.wifi.WifiManager
import com.remote.control.data.wifi.DiscoveredTv
import com.remote.control.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class LgScanner(context: Context? = null) {
    private val appContext = context?.applicationContext
    private val wifiManager = appContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(2000, TimeUnit.MILLISECONDS)
            .callTimeout(2500, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * Потоковый поиск телевизоров LG Smart TV (webOS) в локальной Wi-Fi сети:
     * 1. SSDP M-SEARCH поиск (urn:lge-com:service:webos-second-screen:1, MediaRenderer, ssdp:all).
     * 2. Проверка XML-дескриптора и порта 3001 (SSL WS) / 3000 (WS).
     */
    fun discoverLgTvs(): Flow<List<DiscoveredTv>> = flow {
        val discovered = ConcurrentHashMap<String, DiscoveredTv>()
        emit(emptyList())

        val multicastLock = wifiManager?.createMulticastLock("lg_ssdp_lock")?.apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }

        try {
            Logger.log("LgScanner: Запуск SSDP поиска LG Smart TV (webOS)...")

            // Шаг 1: SSDP M-SEARCH
            val ssdpCandidates = performSsdpSearch()
            if (ssdpCandidates.isNotEmpty()) {
                coroutineScope {
                    ssdpCandidates.map { (ip, locationUrl) ->
                        async(Dispatchers.IO) {
                            val tv = resolveLgTv(ip, locationUrl)
                            discovered[ip] = tv
                        }
                    }.awaitAll()
                }
                emit(discovered.values.sortedBy { it.ip })
            }

            // Шаг 2: Фоновая проверка локальной подсети на порт 3001/3000
            val subnetPrefix = getLocalSubnetPrefix()
            if (subnetPrefix != null) {
                val semaphore = Semaphore(32)
                coroutineScope {
                    val jobs = (1..254).map { host ->
                        val ip = "$subnetPrefix.$host"
                        async(Dispatchers.IO) {
                            if (discovered.containsKey(ip)) return@async null
                            semaphore.withPermit {
                                probeLgPort(ip)
                            }?.also { tv ->
                                discovered[ip] = tv
                            }
                        }
                    }
                    jobs.awaitAll()
                }
                emit(discovered.values.sortedBy { it.ip })
            }

            Logger.log("LgScanner: Поиск завершен. Найдено LG ТВ: ${discovered.size}")
        } catch (e: Exception) {
            Logger.log("LgScanner: Ошибка при поиске LG ТВ: ${e.message}")
        } finally {
            runCatching {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    suspend fun scan(): List<DiscoveredTv> = withContext(Dispatchers.IO) {
        val discovered = ConcurrentHashMap<String, DiscoveredTv>()
        val ssdpCandidates = performSsdpSearch()
        coroutineScope {
            ssdpCandidates.map { (ip, locationUrl) ->
                async {
                    discovered[ip] = resolveLgTv(ip, locationUrl)
                }
            }.awaitAll()
        }
        discovered.values.sortedBy { it.ip }
    }

    private fun performSsdpSearch(timeoutMs: Int = 2200): Map<String, String?> {
        val foundIps = mutableMapOf<String, String?>()
        val searchTargets = listOf(
            "urn:lge-com:service:webos-second-screen:1",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "ssdp:all"
        )

        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket().apply {
                soTimeout = 400
                reuseAddress = true
            }
            val multicastAddress = InetAddress.getByName(SSDP_IP)

            for (st in searchTargets) {
                val query = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: $SSDP_IP:$SSDP_PORT\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 2\r\n")
                    append("ST: $st\r\n")
                    append("USER-AGENT: Android/14 UPnP/1.1 XiaomiUniversalRemote/1.0\r\n")
                    append("\r\n")
                }
                val data = query.toByteArray(Charsets.UTF_8)
                val packet = DatagramPacket(data, data.size, multicastAddress, SSDP_PORT)
                runCatching { socket.send(packet) }
            }

            val buffer = ByteArray(4096)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                try {
                    val receivePacket = DatagramPacket(buffer, buffer.size)
                    socket.receive(receivePacket)
                    val senderIp = receivePacket.address?.hostAddress ?: continue
                    val payload = String(receivePacket.data, 0, receivePacket.length, Charsets.UTF_8)

                    if (isLgSsdpResponse(payload)) {
                        val location = extractHeader(payload, "LOCATION")
                        Logger.log("LgScanner: SSDP ответ от LG устройства ($senderIp, loc=$location)")
                        foundIps[senderIp] = location
                    }
                } catch (_: SocketTimeoutException) {
                }
            }
        } catch (e: Exception) {
            Logger.log("LgScanner: Ошибка SSDP M-SEARCH: ${e.message}")
        } finally {
            runCatching { socket?.close() }
        }

        return foundIps
    }

    private fun isLgSsdpResponse(response: String): Boolean {
        val lower = response.lowercase()
        return lower.contains("lge") ||
            lower.contains("webos") ||
            lower.contains("second-screen") ||
            (lower.contains("lg electronics") && lower.contains("tv"))
    }

    private fun extractHeader(response: String, headerName: String): String? {
        val lines = response.split("\r\n", "\n")
        val prefix = "${headerName.lowercase()}:"
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.lowercase().startsWith(prefix)) {
                return trimmed.substring(prefix.length).trim()
            }
        }
        return null
    }

    private fun resolveLgTv(ip: String, locationUrl: String?): DiscoveredTv {
        var friendlyName: String? = null
        if (!locationUrl.isNullOrBlank()) {
            friendlyName = fetchFriendlyNameFromXml(locationUrl)
        }

        val name = when {
            !friendlyName.isNullOrBlank() -> friendlyName
            else -> "LG webOS Smart TV ($ip)"
        }

        return DiscoveredTv(
            name = name,
            ip = ip,
            port = LG_WEBOS_PORT
        )
    }

    private fun fetchFriendlyNameFromXml(url: String): String? {
        return try {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                extractTag(body, "friendlyName") ?: extractTag(body, "modelName")
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractTag(xml: String, tag: String): String? {
        val open = "<$tag>"
        val close = "</$tag>"
        val start = xml.indexOf(open, ignoreCase = true)
        if (start == -1) return null
        val end = xml.indexOf(close, start + open.length, ignoreCase = true)
        if (end == -1) return null
        val value = xml.substring(start + open.length, end).trim()
        return value.ifBlank { null }
    }

    private fun probeLgPort(ip: String): DiscoveredTv? {
        // Проверяем открыт ли порт 3001 (SSL) или 3000 (ws)
        val is3001Open = isPortOpen(ip, LG_WEBOS_PORT, timeoutMs = 400)
        val is3000Open = if (!is3001Open) isPortOpen(ip, 3000, timeoutMs = 400) else false

        if (is3001Open || is3000Open) {
            Logger.log("LgScanner: Обнаружен открытый webOS порт на $ip (port=${if (is3001Open) 3001 else 3000})")
            return DiscoveredTv(
                name = "LG webOS Smart TV ($ip)",
                ip = ip,
                port = LG_WEBOS_PORT
            )
        }
        return null
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun getLocalSubnetPrefix(): String? {
        try {
            val dhcpIp = wifiManager?.dhcpInfo?.ipAddress ?: 0
            if (dhcpIp != 0) {
                val b1 = dhcpIp and 0xFF
                val b2 = (dhcpIp shr 8) and 0xFF
                val b3 = (dhcpIp shr 16) and 0xFF
                if (b1 != 0 && b1 != 127) {
                    return "$b1.$b2.$b3"
                }
            }
        } catch (_: Exception) {
        }

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
                for (addr in intf.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                        val hostAddr = addr.hostAddress ?: continue
                        val parts = hostAddr.split(".")
                        if (parts.size == 4) {
                            return "${parts[0]}.${parts[1]}.${parts[2]}"
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    companion object {
        private const val SSDP_IP = "239.255.255.250"
        private const val SSDP_PORT = 1900
        const val LG_WEBOS_PORT = 3001
    }
}
