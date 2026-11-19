package com.remote.control.data.wifi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.remote.control.utils.Logger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class DiscoveredTv(val name: String, val ip: String, val port: Int)

class NsdScanner(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    fun discover(): Flow<List<DiscoveredTv>> = callbackFlow {
        val discoveredDevices = mutableMapOf<String, DiscoveredTv>()
        val multicastLock = wifiManager?.createMulticastLock("atv_nsd_lock")?.apply {
            setReferenceCounted(true)
            runCatching { acquire() }
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Logger.log("NSD поиск ТВ запущен ($regType)")
            }

            override fun onDiscoveryStopped(regType: String) {
                Logger.log("NSD поиск ТВ остановлен")
            }

            override fun onStartDiscoveryFailed(regType: String, errorCode: Int) {
                Logger.log("Ошибка запуска NSD поиска: code=$errorCode")
                close()
            }

            override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Logger.log("NSD обнаружил устройство: ${serviceInfo.serviceName}")
                runCatching {
                    @Suppress("DEPRECATION")
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onServiceResolved(info: NsdServiceInfo) {
                            @Suppress("DEPRECATION")
                            val ip = info.host?.hostAddress ?: return
                            val device = DiscoveredTv(
                                name = info.serviceName,
                                ip = ip,
                                port = info.port
                            )
                            Logger.log("ТВ определен в сети: ${device.name} ($ip:${device.port})")
                            synchronized(discoveredDevices) {
                                discoveredDevices[ip] = device
                                trySend(discoveredDevices.values.toList())
                            }
                        }

                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                            Logger.log("Не удалось разрешить IP для ${serviceInfo.serviceName}: code=$errorCode")
                        }
                    })
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(discoveredDevices) {
                    val toRemove = discoveredDevices.values.filter { it.name == serviceInfo.serviceName }.map { it.ip }
                    toRemove.forEach { discoveredDevices.remove(it) }
                    trySend(discoveredDevices.values.toList())
                }
            }
        }

        try {
            nsdManager.discoverServices("_androidtvremote2._tcp.", NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Logger.log("Исключение при запуске NSD: ${e.message}")
        }

        awaitClose {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
            runCatching {
                if (multicastLock?.isHeld == true) {
                    multicastLock.release()
                }
            }
        }
    }
}
