package com.elvi.tvremote

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.elvi.tvremote.protocol.RemoteClient
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Owns one connection. All state and retry decisions run on the main thread. */
class RemoteController internal constructor(
    context: Context,
    private val client: TvConnection,
    discoveryFactory: DiscoveryFactory,
    private val reconnectTimeoutMs: Long,
    onState: (RemoteUiState) -> Unit,
) {
    constructor(context: Context, onState: (RemoteUiState) -> Unit) : this(
        context, AndroidTvConnection(context.applicationContext),
        discoveryFactory(context.applicationContext), 30_000, onState,
    )

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var stateListener: ((RemoteUiState) -> Unit)? = onState
    private val prefs = context.applicationContext.getSharedPreferences("roomote.devices", Context.MODE_PRIVATE)
    private val lastUsed = LastUsedTvStore(prefs)
    private val savedStore = SavedTvStore(prefs)
    private var storageError: String? = null
    private val saved = try {
        savedStore.load(client::migratePairing)
    } catch (_: Exception) {
        // Preserve the original records and pins if storage/migration is unavailable.
        storageError = "Couldn't load saved TVs. Close and reopen Roomote to retry. Your pairings have not been removed."
        linkedMapOf()
    }
    private val found = linkedMapOf<String, TvDevice>()
    private var state = RemoteUiState()
    @Volatile private var generation = 0
    @Volatile private var closed = false
    private var pairingTimeout: Runnable? = null
    private var reconnectTimeout: Runnable? = null
    private var connection: ConnectionAttempt? = null

    private class ConnectionAttempt(
        val epoch: Int, val target: TvDevice, val automatic: Boolean, val recoverAddress: Boolean,
    ) {
        val attempted = mutableSetOf<Pair<String, Int>>()
        var token = 0
        var active = false
        var connected = false
    }

    private val discovery = discoveryFactory(
        { device ->
            if (!closed) {
                found[device.id] = device
                devicesChanged()
                connection?.takeIf { it.recoverAddress && !it.active && !it.connected }?.let(::tryNextAddress)
            }
        },
        { id -> if (!closed) { found.remove(id); devicesChanged() } },
        { scanning, message ->
            update(state.copy(scanning = scanning,
                message = if (state.phase == ConnectionPhase.IDLE) message ?: state.message else state.message))
        },
    )

    fun start() {
        if (storageError != null) {
            update(state.copy(phase = ConnectionPhase.ERROR, message = storageError!!))
            return
        }
        devicesChanged()
        rescan()
    }

    fun attach(onState: (RemoteUiState) -> Unit) {
        if (closed) return
        stateListener = onState
        onState(state)
    }

    fun detach() { stateListener = null }

    /** One bounded recovery per foreground entry, without interrupting pairing or a live session. */
    fun onForeground() {
        if (closed || storageError != null || state.phase !in listOf(ConnectionPhase.IDLE, ConnectionPhase.ERROR)) return
        lastUsed.get(saved.values)?.let { connect(it, automatic = true) }
    }

    fun rescan() {
        if (closed) return
        found.clear()
        devicesChanged()
        discovery.scan()
    }

    fun select(tv: TvDevice) {
        if (storageError != null) return
        val known = saved[tv.id]
        connect(known ?: tv.copy(id = UUID.randomUUID().toString(), saved = false), automatic = false)
    }

    private fun connect(tv: TvDevice, automatic: Boolean, discover: Boolean = true) {
        if (closed || storageError != null) return
        val epoch = resetConnection()
        val known = saved.containsKey(tv.id)
        val attempt = ConnectionAttempt(epoch, tv, automatic, discover && known && client.hasPairing(tv.id))
        connection = attempt
        update(state.copy(selected = tv, phase = ConnectionPhase.CONNECTING,
            message = if (automatic) "Reconnecting to ${tv.name}…" else "Connecting to ${tv.name}…",
            automaticConnection = automatic, volume = null, maxVolume = null, powerOn = null))
        if (attempt.recoverAddress) {
            reconnectTimeout = Runnable {
                if (connection === attempt && !attempt.connected) {
                    resetConnection()
                    update(state.copy(phase = ConnectionPhase.ERROR,
                        message = "Couldn't find your paired TV. Check it is on and on the same network, then try again or update its address."))
                }
            }.also { main.postDelayed(it, reconnectTimeoutMs) }
        }
        // A service name is only a hint; every candidate must prove the saved TLS identity.
        val candidate = found.values.firstOrNull {
            discover &&
            tv.discoveryId != null && it.discoveryId == tv.discoveryId
        }?.let { atAddress(tv, it) } ?: tv
        tryAddress(attempt, candidate)
        if (attempt.recoverAddress && !state.scanning) rescan()
    }

    private fun atAddress(target: TvDevice, discovered: TvDevice) = target.copy(
        host = discovered.host, port = discovered.port, pairingPort = discovered.pairingPort,
        discoveryId = discovered.discoveryId,
    )

    private fun tryAddress(attempt: ConnectionAttempt, candidate: TvDevice) {
        if (connection !== attempt || closed || attempt.epoch != generation) return
        if (!attempt.attempted.add(candidate.host to candidate.port)) return
        attempt.active = true
        val token = ++attempt.token
        enqueue(attempt.epoch) {
            try {
                client.connect(candidate, listener(attempt, token, candidate))
                if (closed || attempt.epoch != generation) client.disconnect()
            } catch (error: Exception) {
                main.post { candidateFailed(attempt, token, error) }
            }
        }
    }

    private fun candidateFailed(attempt: ConnectionAttempt, token: Int, error: Exception) {
        if (!isCurrent(attempt, token) || !attempt.active) return
        attempt.active = false
        client.disconnect()
        if (attempt.recoverAddress) {
            update(state.copy(message = "Looking for ${attempt.target.name} on your network…"))
            tryNextAddress(attempt)
        } else if (!attempt.automatic && !saved.containsKey(attempt.target.id)) {
            connection = null
            enqueue(attempt.epoch) { beginPairing(attempt.target, attempt.epoch) }
        } else {
            connection = null
            fail(attempt.epoch, error, "Couldn't connect. Check the TV is on and on the same network. Pair again only if its authorization changed.")
        }
    }

    private fun tryNextAddress(attempt: ConnectionAttempt) {
        if (connection !== attempt || attempt.active || attempt.connected || closed) return
        if (attempt.attempted.size >= 16) return // The overall deadline still ends the search.
        val candidates = found.values.sortedByDescending {
            attempt.target.discoveryId != null && it.discoveryId == attempt.target.discoveryId
        }.map { atAddress(attempt.target, it) } + attempt.target
        val next = candidates.firstOrNull { (it.host to it.port) !in attempt.attempted }
        if (next != null) tryAddress(attempt, next)
        // No candidate yet: discovery can arrive after the old address has failed.
    }

    fun connectManual(host: String): Boolean {
        if (storageError != null) return false
        return try {
            val address = TvAddress.normalize(host)
            // An address may have been reassigned. Reuse only an unambiguous saved entry;
            // its stable certificate pin must still validate before it can connect.
            val known = saved.values.singleOrNull { it.host == address }
            select(known ?: TvDevice("", "Android TV", address))
            true
        } catch (error: IllegalArgumentException) {
            update(state.copy(message = error.message ?: "Enter a valid TV address."))
            false
        }
    }

    /** Explicit recovery for networks that block discovery; never replaces the TV's pin. */
    fun updateSelectedAddress(host: String): Boolean {
        val tv = state.selected?.let { saved[it.id] } ?: return false
        return try {
            connect(tv.copy(host = TvAddress.normalize(host)), automatic = false, discover = false)
            true
        } catch (error: IllegalArgumentException) {
            update(state.copy(message = error.message ?: "Enter a valid TV address."))
            false
        }
    }

    fun pair() {
        if (storageError != null) return
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
            client.startPairing(tv)
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
                main.post {
                    if (!closed && epoch == generation) {
                        val paired = tv.copy(saved = true)
                        saved[tv.id] = paired
                        writeSaved()
                        connect(paired, automatic = false)
                    }
                }
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
        client.forget(tv)
        lastUsed.forget(tv)
        saved.remove(tv.id)
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

    private fun listener(attempt: ConnectionAttempt, token: Int, tv: TvDevice) = object : RemoteClient.Listener {
        override fun onConnected() = main.postUnit {
            if (!isCurrent(attempt, token) || !attempt.active) return@postUnit
            attempt.connected = true
            reconnectTimeout?.let(main::removeCallbacks)
            reconnectTimeout = null
            val connected = tv.copy(saved = true)
            saved[tv.id] = connected
            writeSaved()
            lastUsed.remember(connected)
            update(state.copy(selected = connected, devices = deviceList(), phase = ConnectionPhase.CONNECTED,
                automaticConnection = false, message = "Connected. Make yourself comfortable."))
        }
        override fun onDisconnected(message: String) = main.postUnit {
            if (!isCurrent(attempt, token)) return@postUnit
            if (!attempt.connected) candidateFailed(attempt, token, IOException(message))
            else {
                connection = null
                update(state.copy(phase = ConnectionPhase.ERROR,
                    message = message.ifBlank { "Connection lost. Reconnect to your TV." }))
            }
        }
        override fun onVolume(level: Int, max: Int, muted: Boolean) = main.postUnit {
            if (isCurrent(attempt, token) && attempt.connected) update(state.copy(volume = level, maxVolume = max, muted = muted))
        }
        override fun onPower(isOn: Boolean) = main.postUnit {
            if (isCurrent(attempt, token) && attempt.connected) update(state.copy(powerOn = isOn))
        }
    }

    private fun isCurrent(attempt: ConnectionAttempt, token: Int) =
        !closed && attempt.epoch == generation && connection === attempt && attempt.token == token

    private fun resetConnection(): Int {
        generation++
        connection = null
        reconnectTimeout?.let(main::removeCallbacks)
        reconnectTimeout = null
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
        val result = saved.values.toMutableList()
        found.values.forEach { device ->
            // Discovery never changes a saved address or merges records by IP/name.
            if (saved.values.none { it.discoveryId != null && it.discoveryId == device.discoveryId }) result += device
        }
        return result.sortedWith(compareByDescending<TvDevice> { it.saved }.thenBy { it.name.lowercase() })
    }

    private fun devicesChanged() { update(state.copy(devices = deviceList())) }
    private fun update(newState: RemoteUiState) {
        if (!closed) {
            state = newState
            stateListener?.invoke(state)
        }
    }

    private fun writeSaved() { if (storageError == null) savedStore.save(saved.values) }
    private fun Handler.postUnit(action: () -> Unit) { post(action) }

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
