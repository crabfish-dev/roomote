package com.elvi.tvremote

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.elvi.tvremote.protocol.RemoteClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Owns one connection. The screen receives immutable state only on the main thread. */
class RemoteController(context: Context, onState: (RemoteUiState) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var stateListener: ((RemoteUiState) -> Unit)? = onState
    private val prefs = context.applicationContext.getSharedPreferences("roomote.devices", Context.MODE_PRIVATE)
    private val lastUsed = LastUsedTvStore(prefs)
    private val client = RemoteClient(context.applicationContext)
    private val saved = readSaved()
    private val found = linkedMapOf<String, TvDevice>()
    private var state = RemoteUiState()
    @Volatile private var generation = 0
    @Volatile private var closed = false
    private var pairingTimeout: Runnable? = null
    private val discovery = TvDiscovery(
        context.applicationContext,
        onDevice = { device ->
            found[device.id] = device
            // Refresh saved addresses after DHCP changes; new addresses must be paired again.
            saved[device.id]?.let {
                saved[device.id] = device.copy(saved = true)
                writeSaved()
            }
            devicesChanged()
        },
        onLost = { id -> found.remove(id); devicesChanged() },
        onScanning = { scanning, message ->
            update(state.copy(scanning = scanning, message = message ?: state.message))
        },
    )

    fun start() {
        devicesChanged()
        rescan()
    }

    /** Main-thread screen handoff: the session outlives configuration changes. */
    fun attach(onState: (RemoteUiState) -> Unit) {
        if (closed) return
        stateListener = onState
        onState(state)
    }

    fun detach() {
        stateListener = null
    }

    /** One attempt per foreground entry; never interrupts an active connection or pairing. */
    fun onForeground() {
        if (closed || state.phase !in listOf(ConnectionPhase.IDLE, ConnectionPhase.ERROR)) return
        val tv = lastUsed.get(saved.values) ?: return
        connect(tv, automatic = true)
    }

    fun rescan() {
        if (closed) return
        found.clear()
        devicesChanged()
        discovery.scan()
    }

    fun select(tv: TvDevice) = connect(tv, automatic = false)

    private fun connect(tv: TvDevice, automatic: Boolean) {
        if (closed) return
        val epoch = resetConnection()
        val isKnown = saved.values.any { it.host == tv.host }
        update(state.copy(
            selected = tv, phase = ConnectionPhase.CONNECTING,
            message = if (automatic) "Reconnecting to ${tv.name}…" else "Connecting to ${tv.name}…",
            automaticConnection = automatic, volume = null, maxVolume = null, powerOn = null,
        ))
        enqueue(epoch) {
            try {
                client.connect(tv.host, tv.port, listener(epoch, tv))
                if (closed || epoch != generation) client.disconnect()
            } catch (error: Exception) {
                // A first connection needs the user's on-TV pairing code. Never silently
                // replace a stored certificate after a security/connection failure.
                if (!automatic && !isKnown && epoch == generation && !closed) {
                    beginPairing(tv, epoch)
                } else {
                    fail(epoch, error, "Couldn't connect. Check the TV is on and on the same network. Pair again if its authorization changed.")
                }
            }
        }
    }

    fun connectManual(host: String): Boolean {
        return try {
            val address = TvAddress.normalize(host)
            select(saved.values.firstOrNull { it.host == address }
                ?: TvDevice("manual:$address", "Android TV", address))
            true
        } catch (error: IllegalArgumentException) {
            update(state.copy(message = error.message ?: "Enter a valid TV address."))
            false
        }
    }

    fun pair() {
        val tv = state.selected ?: return
        val epoch = resetConnection()
        update(state.copy(phase = ConnectionPhase.PAIRING, automaticConnection = false,
            message = "Requesting a pairing code from ${tv.name}…"))
        enqueue(epoch) { beginPairing(tv, epoch) }
    }

    private fun beginPairing(tv: TvDevice, epoch: Int) {
        if (closed || epoch != generation) return
        publish(epoch) { it.copy(phase = ConnectionPhase.PAIRING, message = "Look for a pairing code on your TV.") }
        try {
            client.startPairing(tv.host, tv.pairingPort)
            if (closed || epoch != generation) {
                client.cancelPairing()
                return
            }
            publish(epoch) {
                pairingTimeout?.let(main::removeCallbacks)
                pairingTimeout = Runnable {
                    if (epoch == generation && state.phase == ConnectionPhase.AWAITING_CODE) {
                        cancelPairing()
                        update(state.copy(message = "Pairing expired. Select the TV to request a new code."))
                    }
                }.also { timeout -> main.postDelayed(timeout, 120_000) }
                it.copy(phase = ConnectionPhase.AWAITING_CODE, message = "Enter the six-character code shown on ${tv.name}.")
            }
        } catch (error: Exception) {
            fail(epoch, error, "Couldn't request a code. Check the TV is on and Android TV Remote Service is up to date.")
        }
    }

    fun submitPairingCode(code: String) {
        if (state.phase != ConnectionPhase.AWAITING_CODE) return
        val normalized = code.trim().uppercase(java.util.Locale.ROOT)
        if (!normalized.matches(Regex("[0-9A-F]{6}"))) {
            update(state.copy(message = "Enter all six characters (0–9 and A–F) shown on the TV."))
            return
        }
        val tv = state.selected ?: return
        val epoch = generation
        pairingTimeout?.let(main::removeCallbacks)
        update(state.copy(phase = ConnectionPhase.PAIRING, message = "Confirming your pairing code…"))
        enqueue(epoch) {
            try {
                client.completePairing(normalized)
                if (closed || epoch != generation) return@enqueue
                publish(epoch) {
                    saved[tv.id] = tv.copy(saved = true)
                    writeSaved()
                    it.copy(devices = deviceList(), selected = tv.copy(saved = true), phase = ConnectionPhase.CONNECTING,
                        message = "Paired. Connecting to ${tv.name}…")
                }
                client.connect(tv.host, tv.port, listener(epoch, tv))
                if (closed || epoch != generation) client.disconnect()
            } catch (error: Exception) {
                client.cancelPairing()
                fail(epoch, error, "Pairing failed. Request a new code and try again.")
            }
        }
    }

    fun cancelPairing() {
        resetConnection()
        update(state.copy(phase = ConnectionPhase.IDLE, automaticConnection = false,
            message = "Pairing cancelled. Choose a TV when you're ready."))
    }

    fun disconnect() {
        resetConnection()
        update(state.copy(phase = ConnectionPhase.IDLE, automaticConnection = false,
            message = "Disconnected.", volume = null, maxVolume = null, powerOn = null))
    }

    fun forgetSelected() {
        val tv = state.selected ?: return
        resetConnection()
        // Forget is a user mutation, not a cancellable connection attempt.
        client.forget(tv.host)
        lastUsed.forget(tv)
        saved.entries.removeAll { it.value.host == tv.host || it.key == tv.id }
        writeSaved()
        update(state.copy(selected = null, phase = ConnectionPhase.IDLE, automaticConnection = false,
            devices = deviceList(), message = "TV forgotten. Pair again to reconnect."))
    }

    fun sendKey(code: Int) {
        if (state.phase != ConnectionPhase.CONNECTED || closed) return
        val epoch = generation
        enqueue(epoch) {
            try { client.sendKey(code) }
            catch (error: Exception) { fail(epoch, error, "Connection lost. Turn on the TV and reconnect.") }
        }
    }

    private fun listener(epoch: Int, tv: TvDevice) = object : RemoteClient.Listener {
        override fun onConnected() = publish(epoch) {
            val connected = tv.copy(saved = true)
            saved.entries.removeAll { entry -> entry.value.host == tv.host && entry.key != tv.id }
            saved[tv.id] = connected
            writeSaved()
            lastUsed.remember(connected)
            it.copy(selected = connected, devices = deviceList(), phase = ConnectionPhase.CONNECTED,
                automaticConnection = false, message = "Connected. Make yourself comfortable.")
        }
        override fun onDisconnected(message: String) = publish(epoch) {
            it.copy(phase = ConnectionPhase.ERROR, message = message.ifBlank { "Connection lost. Reconnect to your TV." })
        }
        override fun onVolume(level: Int, max: Int, muted: Boolean) = publish(epoch) {
            it.copy(volume = level, maxVolume = max, muted = muted)
        }
        override fun onPower(isOn: Boolean) = publish(epoch) { it.copy(powerOn = isOn) }
    }

    private fun resetConnection(): Int {
        generation++
        pairingTimeout?.let(main::removeCallbacks)
        pairingTimeout = null
        client.cancelPairing()
        client.disconnect()
        return generation
    }

    private fun enqueue(epoch: Int, action: () -> Unit) {
        if (closed) return
        try { worker.execute { if (!closed && epoch == generation) action() } }
        catch (_: RejectedExecutionException) { /* Activity has been destroyed. */ }
    }

    private fun fail(epoch: Int, error: Exception, fallback: String) {
        val reason = error.message?.take(180)?.takeIf { it.isNotBlank() }
        publish(epoch) {
            it.copy(phase = ConnectionPhase.ERROR, message = if (reason == null) fallback else "$fallback\n$reason")
        }
    }

    private fun publish(epoch: Int, transform: (RemoteUiState) -> RemoteUiState) {
        main.post { if (!closed && epoch == generation) update(transform(state)) }
    }

    private fun deviceList(): List<TvDevice> {
        val combined = saved.toMutableMap()
        found.forEach { (id, device) ->
            val known = saved.values.firstOrNull { it.id == id || it.host == device.host }
            if (known != null && known.id != id) combined.remove(known.id)
            combined[id] = device.copy(saved = known != null)
        }
        return combined.values.sortedWith(compareByDescending<TvDevice> { it.saved }.thenBy { it.name.lowercase() })
    }

    private fun devicesChanged() { update(state.copy(devices = deviceList())) }
    private fun update(newState: RemoteUiState) {
        if (!closed) {
            state = newState
            stateListener?.invoke(state)
        }
    }

    private fun readSaved(): MutableMap<String, TvDevice> {
        val result = linkedMapOf<String, TvDevice>()
        runCatching {
            val array = JSONArray(prefs.getString("devices", "[]"))
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val tv = TvDevice(item.getString("id"), item.getString("name"), item.getString("host"),
                    item.optInt("port", 6466), item.optInt("pairingPort", 6467), true)
                result[tv.id] = tv
            }
        }
        return result
    }

    private fun writeSaved() {
        val array = JSONArray()
        saved.values.forEach { tv ->
            array.put(JSONObject().put("id", tv.id).put("name", tv.name).put("host", tv.host)
                .put("port", tv.port).put("pairingPort", tv.pairingPort))
        }
        prefs.edit().putString("devices", array.toString()).apply()
    }

    fun close() {
        if (closed) return
        detach()
        closed = true
        resetConnection()
        discovery.stop()
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
    }
}
