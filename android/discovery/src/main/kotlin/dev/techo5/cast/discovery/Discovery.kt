package dev.techo5.cast.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/** A Show seen on the network. */
data class Found(val name: String, val host: String, val port: Int)

private const val SERVICE_TYPE = "_techo5cast._tcp"

/**
 * Shows currently advertising `_techo5cast._tcp`, as a running list. Collecting the flow starts the
 * browse and holds a multicast lock; cancelling it stops both.
 */
fun discover(context: Context): Flow<List<Found>> = callbackFlow {
    val app = context.applicationContext
    val nsd = app.getSystemService(Context.NSD_SERVICE) as NsdManager
    val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
    val lock = wifi.createMulticastLock("techo5cast-discovery").apply { setReferenceCounted(false); acquire() }

    val found = LinkedHashMap<String, Found>()
    // resolveService allows one resolve at a time; queue the names and take them in turn.
    val toResolve = Channel<NsdServiceInfo>(Channel.UNLIMITED)

    val listener = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) { toResolve.trySend(info) }
        override fun onServiceLost(info: NsdServiceInfo) {
            val removed = synchronized(found) { found.remove(info.serviceName) }
            if (removed != null) trySend(synchronized(found) { found.values.toList() })
        }
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { close() }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    val resolver = launch {
        for (info in toResolve) {
            val done = Channel<Unit>(1)
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { done.trySend(Unit) }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val host = serviceInfo.host?.hostAddress
                    if (host != null) {
                        val list = synchronized(found) {
                            found[serviceInfo.serviceName] = Found(serviceInfo.serviceName, host, serviceInfo.port)
                            found.values.toList()
                        }
                        trySend(list)
                    }
                    done.trySend(Unit)
                }
            })
            done.receive()
        }
    }

    nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    awaitClose {
        resolver.cancel()
        toResolve.close()
        try { nsd.stopServiceDiscovery(listener) } catch (_: IllegalArgumentException) {}
        if (lock.isHeld) lock.release()
    }
}
