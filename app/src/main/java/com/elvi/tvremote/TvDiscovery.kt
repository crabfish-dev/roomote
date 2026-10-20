package com.elvi.tvremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

/** DNS-SD discovery is scoped to the local network and never probes arbitrary hosts. */
@Suppress("DEPRECATION")
class TvDiscovery(
    context: Context,
    private val onDevice: (TvDevice) -> Unit,
    private val onLost: (String) -> Unit,
    private val onScanning: (Boolean, String?) -> Unit,
) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var listener: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var generation = 0
    private var resolving = false
    private val pending = ArrayDeque<Pair<Int, NsdServiceInfo>>()
    private val present = mutableSetOf<String>()
    private var stopScan: Runnable? = null

    fun scan() {
        stop()
        val epoch = generation
        present.clear()
        try {
            multicast = wifi?.createMulticastLock("Roomote:discovery")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            val callback = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(type: String) = main.postUnit {
                    if (epoch == generation) onScanning(true, null)
                }
                override fun onServiceFound(info: NsdServiceInfo) = main.postUnit {
                    if (epoch == generation && present.add(info.serviceName)) {
                        pending.add(epoch to info)
                        resolveNext()
                    }
                }
                override fun onServiceLost(info: NsdServiceInfo) = main.postUnit {
                    if (epoch == generation) {
                        present.remove(info.serviceName)
                        onLost(info.serviceName)
                    }
                }
                override fun onDiscoveryStopped(type: String) = Unit
                override fun onStartDiscoveryFailed(type: String, error: Int) = main.postUnit {
                    if (epoch == generation) {
                        stop()
                        onScanning(false, "Discovery couldn't start ($error). Try again or enter the TV address.")
                    }
                }
                override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            }
            listener = callback
            onScanning(true, null)
            nsd.discoverServices("_androidtvremote2._tcp.", NsdManager.PROTOCOL_DNS_SD, callback)
            stopScan = Runnable { if (epoch == generation) stop() }.also {
                main.postDelayed(it, 20_000)
            }
        } catch (_: Exception) {
            stop()
            onScanning(false, "Discovery is unavailable. Connect to the same Wi-Fi or enter the TV address.")
        }
    }

    private fun resolveNext() {
        if (resolving) return
        while (pending.isNotEmpty()) {
            val (epoch, info) = pending.removeFirst()
            if (epoch != generation || info.serviceName !in present) continue
            resolving = true
            try {
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(service: NsdServiceInfo, code: Int) = main.postUnit {
                        resolving = false
                        resolveNext()
                    }
                    override fun onServiceResolved(service: NsdServiceInfo) = main.postUnit {
                        resolving = false
                        if (epoch == generation && info.serviceName in present) {
                            service.host?.hostAddress?.let { host ->
                                val name = service.attributes["fn"]?.toString(Charsets.UTF_8)
                                    ?.takeIf { it.isNotBlank() } ?: service.serviceName
                                if (service.port in 1..65535) {
                                    onDevice(TvDevice(service.serviceName, name, host, service.port))
                                }
                            }
                        }
                        resolveNext()
                    }
                })
            } catch (_: Exception) {
                resolving = false
                continue
            }
            return
        }
    }

    fun stop() {
        generation++
        stopScan?.let(main::removeCallbacks)
        stopScan = null
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        pending.clear()
        multicast?.let { runCatching { if (it.isHeld) it.release() } }
        multicast = null
        onScanning(false, null)
    }

    private fun Handler.postUnit(action: () -> Unit) { post(action) }
}
