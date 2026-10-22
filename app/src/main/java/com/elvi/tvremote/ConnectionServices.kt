package com.elvi.tvremote

import android.content.Context
import com.elvi.tvremote.protocol.RemoteClient

/** Small I/O boundary so reconnection races can be exercised without real network timing. */
internal interface TvConnection {
    fun connect(tv: TvDevice, listener: RemoteClient.Listener)
    fun startPairing(tv: TvDevice)
    fun completePairing(code: String)
    fun hasPairing(id: String): Boolean
    fun migratePairing(id: String, host: String)
    fun forget(tv: TvDevice)
    fun sendKey(code: Int)
    fun disconnect()
    fun cancelPairing()
}

internal class AndroidTvConnection(context: Context) : TvConnection {
    private val client = RemoteClient(context)
    override fun connect(tv: TvDevice, listener: RemoteClient.Listener) =
        client.connect(tv.host, tv.port, listener, tv.id)
    override fun startPairing(tv: TvDevice) = client.startPairing(tv.host, tv.pairingPort, tv.id)
    override fun completePairing(code: String) = client.completePairing(code)
    override fun hasPairing(id: String) = client.hasPairing(id)
    override fun migratePairing(id: String, host: String) { client.migratePairing(id, host) }
    override fun forget(tv: TvDevice) = client.forget(tv.host, tv.id)
    override fun sendKey(code: Int) = client.sendKey(code)
    override fun disconnect() = client.disconnect()
    override fun cancelPairing() = client.cancelPairing()
}

internal interface DiscoveryControl {
    fun scan()
    fun stop()
}

internal typealias DiscoveryFactory = (
    (TvDevice) -> Unit, (String) -> Unit, (Boolean, String?) -> Unit,
) -> DiscoveryControl

internal fun discoveryFactory(context: Context): DiscoveryFactory = { onDevice, onLost, onScanning ->
    val discovery = TvDiscovery(context, onDevice, onLost, onScanning)
    object : DiscoveryControl {
        override fun scan() = discovery.scan()
        override fun stop() = discovery.stop()
    }
}
