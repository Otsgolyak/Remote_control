package com.remote.control.data.samsung

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
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class SamsungScanner(context: Context? = null) {
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
     * Потоковый поиск телевизоров Samsung Smart TV (Tizen) в локальной Wi-Fi сети:
     * 1. Быстрый SSDP M-SEARCH запрос на UDP 239.255.255.250:1900 с проверкой HTTP API порта 8001.
     * 2. HTTP GET сканирование локальной подсети на http://<ip>:8001/api/v2/.
     */
    fun discoverSamsungTvs(): Flow<List<DiscoveredTv>> = flow {
        val discovered = ConcurrentHashMap<String, DiscoveredTv>()
        emit(emptyList())

        val multicastLock = wifiManager?.createMulticastLock("samsung_ssdp_lock")?.apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }

        try {
            Logger.log("SamsungScanner: Запуск SSDP M-SEARCH и HTTP 8001 поиска Samsung Smart TV...")

            // Шаг 1: SSDP M-SEARCH запрос
            val ssdpCandidates = performSsdpSearch()
            if (ssdpCandidates.isNotEmpty()) {
                coroutineScope {
                    ssdpCandidates.map { ip ->
                        async(Dispatchers.IO) {
                            val tv = probeSamsungApi(ip) ?: DiscoveredTv(
                                name = "Samsung Smart TV ($ip)",
                                ip = ip,
                                port = SAMSUNG_API_PORT
                            )
                            discovered[ip] = tv
                        }
                    }.awaitAll()
                }
                emit(discovered.values.sortedBy { it.ip })
            }

            // Шаг 2: HTTP GET сканирование подсети на порт 8001 (/api/v2/)
            val subnetPrefix = getLocalSubnetPrefix()
            if (subnetPrefix != null) {
                val semaphore = Semaphore(32)
                coroutineScope {
                    val jobs = (1..254).map { host ->
                        val ip = "$subnetPrefix.$host"
                        async(Dispatchers.IO) {
                            if (discovered.containsKey(ip)) return@async null
                            semaphore.withPermit {
                                probeSamsungApi(ip)
                            }?.also { tv ->
                                discovered[ip] = tv
                            }
                        }
                    }
                    jobs.awaitAll()
                }
                emit(discovered.values.sortedBy { it.ip })
            }

            Logger.log("SamsungScanner: Поиск завершен. Найдено Samsung ТВ: ${discovered.size}")
        } catch (e: Exception) {
            Logger.log("SamsungScanner: Ошибка при поиске Samsung ТВ: ${e.message}")
        } finally {
            runCatching {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Однократное полное сканирование сети (SSDP + HTTP /api/v2/ на порту 8001).
     */
    suspend fun scan(): List<DiscoveredTv> = withContext(Dispatchers.IO) {
        val discovered = ConcurrentHashMap<String, DiscoveredTv>()
        val multicastLock = wifiManager?.createMulticastLock("samsung_ssdp_scan_lock")?.apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }

        try {
            val ssdpIps = performSsdpSearch()
            coroutineScope {
                ssdpIps.map { ip ->
                    async {
                        val tv = probeSamsungApi(ip) ?: DiscoveredTv(
                            name = "Samsung Smart TV ($ip)",
                            ip = ip,
                            port = SAMSUNG_API_PORT
                        )
                        discovered[ip] = tv
                    }
                }.awaitAll()
            }

            val subnetPrefix = getLocalSubnetPrefix()
            if (subnetPrefix != null) {
                val semaphore = Semaphore(32)
                coroutineScope {
                    (1..254).map { host ->
                        val ip = "$subnetPrefix.$host"
                        async {
                            if (discovered.containsKey(ip)) return@async
                            semaphore.withPermit {
                                probeSamsungApi(ip)?.let { tv ->
                                    discovered[ip] = tv
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
        } finally {
            runCatching {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            }
        }

        discovered.values.sortedBy { it.ip }
    }

    /**
     * Метод 1: SSDP M-SEARCH запрос на UDP 239.255.255.250:1900.
     * Ищет urn:samsung.com:device:RemoteControlReceiver:1 и ssdp:all,
     * фильтрует ответы от Samsung / Tizen / порта 8001.
     */
    private fun performSsdpSearch(timeoutMs: Int = 2200): Set<String> {
        val foundIps = mutableSetOf<String>()
        val searchTargets = listOf(
            "urn:samsung.com:device:RemoteControlReceiver:1",
            "urn:samsung.com:service:MultiScreenService:1",
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

                    if (isSamsungSsdpResponse(payload)) {
                        Logger.log("SamsungScanner: SSDP ответ от Samsung устройства ($senderIp)")
                        foundIps.add(senderIp)
                    }
                } catch (_: SocketTimeoutException) {
                    // Продолжаем слушать до истечения общего времени ожидания
                }
            }
        } catch (e: Exception) {
            Logger.log("SamsungScanner: Ошибка SSDP M-SEARCH: ${e.message}")
        } finally {
            runCatching { socket?.close() }
        }

        return foundIps
    }

    private fun isSamsungSsdpResponse(response: String): Boolean {
        val lower = response.lowercase()
        return lower.contains("samsung") ||
            lower.contains("tizen") ||
            lower.contains("remotecontrolreceiver") ||
            lower.contains(":8001") ||
            lower.contains(":8002")
    }

    /**
     * Метод 2: HTTP GET запрос на http://<ip>:8001/api/v2/
     * Проверяет JSON с device.name, device.modelName и device.OS == "Tizen".
     */
    fun probeSamsungApi(ip: String): DiscoveredTv? {
        val url = "http://$ip:$SAMSUNG_API_PORT/api/v2/"
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string()?.takeIf { it.isNotBlank() } ?: return null
                val root = JSONObject(body)
                val deviceObj = root.optJSONObject("device")

                val os = deviceObj?.optString("OS").orEmpty()
                val rawName = deviceObj?.optString("name")
                    ?.takeIf { it.isNotBlank() }
                    ?: root.optString("name").takeIf { it.isNotBlank() }
                val modelName = deviceObj?.optString("modelName").orEmpty()

                val isTizenOrSamsung = os.equals("Tizen", ignoreCase = true) ||
                    body.contains("Tizen", ignoreCase = true) ||
                    body.contains("Samsung", ignoreCase = true) ||
                    modelName.isNotBlank()

                if (!isTizenOrSamsung) return null

                val displayName = when {
                    !rawName.isNullOrBlank() && modelName.isNotBlank() && !rawName.contains(modelName, ignoreCase = true) ->
                        "$rawName ($modelName)"
                    !rawName.isNullOrBlank() -> rawName
                    modelName.isNotBlank() -> "Samsung $modelName"
                    else -> "Samsung Smart TV ($ip)"
                }

                Logger.log("SamsungScanner: Обнаружен Samsung ТВ по HTTP API: $displayName ($ip:$SAMSUNG_API_PORT, OS=$os)")
                DiscoveredTv(
                    name = displayName,
                    ip = ip,
                    port = SAMSUNG_API_PORT
                )
            }
        } catch (_: Exception) {
            null
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
        const val SAMSUNG_API_PORT = 8001
    }
}
