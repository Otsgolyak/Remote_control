package com.remote.control.data.wifi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class DiscoveredTv(val name: String, val ip: String, val port: Int)

class NsdScanner(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

    fun discover(): Flow<List<DiscoveredTv>> = callbackFlow {
        val discoveredDevices = mutableSetOf<DiscoveredTv>()
        
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}
            override fun onDiscoveryStopped(regType: String) {}
            override fun onStartDiscoveryFailed(regType: String, errorCode: Int) { close() }
            override fun onStopDiscoveryFailed(regType: String, errorCode: Int) {}
            
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val device = DiscoveredTv(
                            name = info.serviceName,
                            ip = info.host.hostAddress ?: "",
                            port = info.port
                        )
                        discoveredDevices.add(device)
                        trySend(discoveredDevices.toList())
                    }
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                })
            }
            
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                discoveredDevices.removeAll { it.name == serviceInfo.serviceName }
                trySend(discoveredDevices.toList())
            }
        }

        nsdManager.discoverServices("_androidtvremote2._tcp", NsdManager.PROTOCOL_DNS_SD, listener)

        awaitClose {
            try {
                nsdManager.stopServiceDiscovery(listener)
            } catch (e: Exception) {
                // Handle potential exceptions during stop
            }
        }
    }
}
