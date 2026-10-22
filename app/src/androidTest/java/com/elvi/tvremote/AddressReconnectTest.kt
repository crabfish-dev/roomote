package com.elvi.tvremote

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.elvi.tvremote.protocol.RemoteClient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Scripted transport events exercise discovery/reconnect races without opening sockets. */
@RunWith(AndroidJUnit4::class)
class AddressReconnectTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: IsolatedContext
    private lateinit var prefs: SharedPreferences
    private lateinit var controller: RemoteController
    private lateinit var discovery: FakeDiscovery
    private val client = FakeConnection()
    private val states = LinkedBlockingQueue<RemoteUiState>()
    private val latest = AtomicReference(RemoteUiState())
    private val tvA = TvDevice(UUID.randomUUID().toString(), "Living room", "192.0.2.10",
        saved = true, discoveryId = "service-a")
    private val tvB = TvDevice(UUID.randomUUID().toString(), "Bedroom", "192.0.2.20",
        saved = true, discoveryId = "service-b")

    @Before fun setUp() {
        context = IsolatedContext(instrumentation.targetContext)
        prefs = context.getSharedPreferences("roomote.devices", Context.MODE_PRIVATE)
    }

    @After fun tearDown() {
        if (::controller.isInitialized) onMain { controller.close() }
        instrumentation.waitForIdleSync()
        context.clear()
    }

    @Test fun delayedRediscoveryRecoversChangedAddressWithoutPairing() {
        client.throwAtHosts += tvA.host
        createController()
        onMain { controller.onForeground() }
        assertEquals(tvA.host, nextAttempt().tv.host)
        awaitState { it.message.startsWith("Looking for") }
        assertEquals(tvA.host, stored(tvA.id)?.host)

        discover("192.0.2.30")
        val recovered = nextAttempt()
        assertEquals(tvA.id, recovered.tv.id)
        assertEquals("192.0.2.30", recovered.tv.host)
        ready(recovered)

        assertEquals(ConnectionPhase.CONNECTED, latest.get().phase)
        assertEquals("192.0.2.30", stored(tvA.id)?.host)
        assertEquals(tvA.id, LastUsedTvStore(prefs).get(saved())?.id)
        assertTrue(client.pairingRequests.isEmpty())
    }

    @Test fun reusedOldAddressCannotReplaceTheOriginalIdentity() {
        createController()
        onMain { controller.onForeground() }
        val oldAddress = nextAttempt()
        reject(oldAddress, "Certificate does not match saved TV")
        discover("192.0.2.30", "renamed-service")
        val actualTv = nextAttempt()
        assertEquals(tvA.id, actualTv.tv.id)
        assertEquals(tvA.host, stored(tvA.id)?.host)

        ready(actualTv)

        assertEquals("192.0.2.30", stored(tvA.id)?.host)
        assertEquals("renamed-service", stored(tvA.id)?.discoveryId)
        assertEquals(setOf(tvA.id), client.pins.toSet())
        assertTrue(client.pairingRequests.isEmpty())
    }

    @Test fun identicalServiceNameIsOnlyAHintAndFailedCandidateDoesNotPersist() {
        createController()
        onMain { controller.onForeground() }
        reject(nextAttempt(), "Old address unavailable")
        discover("192.0.2.40", tvA.discoveryId!!)
        val imposter = nextAttempt()
        reject(imposter, "Certificate does not match saved TV")

        assertEquals(tvA.host, stored(tvA.id)?.host)
        assertEquals(ConnectionPhase.CONNECTING, latest.get().phase)
        assertTrue(client.pairingRequests.isEmpty())
        discover("192.0.2.30", "different-service")
        ready(nextAttempt())
        assertEquals("192.0.2.30", stored(tvA.id)?.host)
    }

    @Test fun asynchronousProtocolFailureAdvancesToTheNextDiscoveredAddress() {
        createController()
        onMain { controller.onForeground() }
        val stalled = nextAttempt()
        discover("192.0.2.30")
        assertTrue(client.attempts.isEmpty()) // Do not overlap a live protocol handshake.

        reject(stalled, "Remote protocol closed before ready")
        val next = nextAttempt()
        assertEquals("192.0.2.30", next.tv.host)
        ready(next)
        assertEquals(ConnectionPhase.CONNECTED, latest.get().phase)
    }

    @Test fun failedDiscoveryHintFallsBackToTheSavedAddress() {
        createController()
        discover("192.0.2.40")
        onMain { controller.onForeground() }
        val hinted = nextAttempt()
        assertEquals("192.0.2.40", hinted.tv.host)

        reject(hinted, "Certificate does not match saved TV")
        val savedAddress = nextAttempt()

        assertEquals(tvA.host, savedAddress.tv.host)
        assertEquals(tvA.id, savedAddress.tv.id)
        ready(savedAddress)
        assertEquals(tvA.host, stored(tvA.id)?.host)
        assertTrue(client.pairingRequests.isEmpty())
    }

    @Test fun repeatedDiscoveryDoesNotRetryAnAlreadyRejectedEndpoint() {
        createController()
        onMain { controller.onForeground() }
        reject(nextAttempt(), "Old address unavailable")
        discover("192.0.2.40")
        reject(nextAttempt(), "Certificate does not match saved TV")

        repeat(3) { discover("192.0.2.40", "another-name-$it") }

        noMoreAttempts()
        assertEquals(tvA.host, stored(tvA.id)?.host)
        discover("192.0.2.30")
        ready(nextAttempt())
        assertEquals("192.0.2.30", stored(tvA.id)?.host)
    }

    @Test fun staleCallbacksCannotConnectOrOverwriteTheReplacementAttempt() {
        createController()
        onMain { controller.onForeground() }
        val stale = nextAttempt()
        reject(stale, "Timed out")
        discover("192.0.2.30")
        val current = nextAttempt()

        callbacks(stale) {
            onConnected()
            onVolume(88, 100, true)
            onPower(true)
            onDisconnected("Stale callback")
        }
        assertEquals(ConnectionPhase.CONNECTING, latest.get().phase)
        assertEquals(tvA.host, stored(tvA.id)?.host)
        assertNull(latest.get().volume)
        ready(current)
        callbacks(stale) { onConnected(); onDisconnected("Still stale") }
        assertEquals(ConnectionPhase.CONNECTED, latest.get().phase)
        assertEquals("192.0.2.30", stored(tvA.id)?.host)
    }

    @Test fun disconnectCancelsDiscoveryRecoveryAndStaleCallbacks() {
        createController()
        onMain { controller.onForeground() }
        val stale = nextAttempt()
        reject(stale, "Old address unavailable")

        onMain { controller.disconnect() }
        discover("192.0.2.30")
        callbacks(stale) { onConnected(); onDisconnected("late") }

        assertEquals(ConnectionPhase.IDLE, latest.get().phase)
        assertEquals("Disconnected.", latest.get().message)
        assertEquals(tvA.host, stored(tvA.id)?.host)
        assertEquals(setOf(tvA.id), client.pins.toSet())
        noMoreAttempts()
    }

    @Test fun forgettingCancelsRecoveryAndRemovesOnlyThatIdentity() {
        createController(tvA, tvB)
        onMain { controller.onForeground() }
        val stale = nextAttempt()
        reject(stale, "Old address unavailable")

        onMain { controller.forgetSelected() }
        discover("192.0.2.30")
        callbacks(stale) { onConnected(); onDisconnected("late") }
        onMain { controller.onForeground() }

        assertNull(latest.get().selected)
        assertEquals(ConnectionPhase.IDLE, latest.get().phase)
        assertEquals(listOf(tvB.id), saved().map { it.id })
        assertEquals(setOf(tvB.id), client.pins.toSet())
        assertEquals(listOf(tvA.id), client.forgotten)
        assertNull(LastUsedTvStore(prefs).get(saved()))
        noMoreAttempts()
    }

    @Test fun selectingAnotherTvCancelsThePreviousRecovery() {
        createController(tvA, tvB)
        onMain { controller.onForeground() }
        val stale = nextAttempt()
        reject(stale, "Old address unavailable")

        onMain { controller.select(tvB) }
        val selected = nextAttempt()
        assertEquals(tvB.id, selected.tv.id)
        discover("192.0.2.30")
        callbacks(stale) { onConnected(); onDisconnected("late") }
        ready(selected)

        assertEquals(tvB.id, latest.get().selected?.id)
        assertEquals(tvA.host, stored(tvA.id)?.host)
        assertEquals(tvB.id, LastUsedTvStore(prefs).get(saved())?.id)
        noMoreAttempts()
    }

    @Test fun recoveryDeadlinePreservesSavedTvAndPinAndRejectsLateDiscovery() {
        createController(timeoutMs = 1_000)
        onMain { controller.onForeground() }
        val stale = nextAttempt()
        reject(stale, "Old address unavailable")
        awaitState { it.phase == ConnectionPhase.ERROR }

        discover("192.0.2.30")
        callbacks(stale) { onConnected() }

        assertEquals(ConnectionPhase.ERROR, latest.get().phase)
        assertEquals(tvA, stored(tvA.id))
        assertEquals(setOf(tvA.id), client.pins.toSet())
        assertTrue(client.pairingRequests.isEmpty())
        noMoreAttempts()
    }

    @Test fun manualAddressUpdateKeepsIdentityAndPersistsOnlyAfterReady() {
        createController()
        onMain { controller.onForeground() }
        val original = nextAttempt()
        reject(original, "Old address unavailable")
        onMain { assertFalse(controller.updateSelectedAddress("https://wrong.example")) }
        noMoreAttempts()
        onMain { assertTrue(controller.updateSelectedAddress("192.0.2.40")) }
        val wrong = nextAttempt()
        assertEquals(tvA.id, wrong.tv.id)
        assertEquals(tvA.host, stored(tvA.id)?.host)
        reject(wrong, "Certificate does not match saved TV")
        assertEquals(ConnectionPhase.ERROR, latest.get().phase)
        assertEquals(tvA.host, stored(tvA.id)?.host)
        // A stale service advertisement must not override the explicitly supplied address.
        discover("192.0.2.40")
        onMain { assertTrue(controller.updateSelectedAddress("192.0.2.30")) }
        val right = nextAttempt()
        assertEquals(tvA.id, right.tv.id)
        assertEquals("192.0.2.30", right.tv.host)
        assertEquals(tvA.host, stored(tvA.id)?.host)

        ready(right)

        assertEquals("192.0.2.30", stored(tvA.id)?.host)
        assertEquals(setOf(tvA.id), client.pins.toSet())
        assertTrue(client.pairingRequests.isEmpty())
    }

    @Test fun manualAddressUpdateRequiresASavedSelection() {
        createController()
        onMain { assertFalse(controller.updateSelectedAddress("192.0.2.30")) }
        assertEquals(tvA, stored(tvA.id))
        noMoreAttempts()
    }

    @Test fun tvsSharingAnOldAddressRemainSeparateAfterReconnectAndForget() {
        val sharedAddressTv = tvB.copy(host = tvA.host, name = tvA.name)
        createController(tvA, sharedAddressTv)
        onMain { controller.onForeground() }
        reject(nextAttempt(), "Address reassigned")
        discover("192.0.2.30")
        ready(nextAttempt())

        assertEquals(2, saved().size)
        assertEquals("192.0.2.30", stored(tvA.id)?.host)
        assertEquals(tvA.host, stored(tvB.id)?.host)
        onMain { controller.select(sharedAddressTv) }
        val other = nextAttempt()
        onMain { controller.forgetSelected() }
        callbacks(other) { onConnected() }

        assertEquals(listOf(tvA.id), saved().map { it.id })
        assertEquals(tvA.id, LastUsedTvStore(prefs).get(saved())?.id)
        assertEquals(setOf(tvA.id), client.pins.toSet())
        noMoreAttempts()
    }

    private fun createController(vararg initial: TvDevice, timeoutMs: Long = 5_000) {
        val devices = initial.toList().ifEmpty { listOf(tvA) }
        SavedTvStore(prefs).save(devices)
        LastUsedTvStore(prefs).remember(tvA)
        client.pins += devices.map { it.id }
        onMain {
            controller = RemoteController(context, client, { onDevice, onLost, onScanning ->
                FakeDiscovery(onDevice, onLost, onScanning).also { discovery = it }
            }, timeoutMs) { state -> latest.set(state); states.offer(state) }
        }
    }

    private fun discover(host: String, service: String = tvA.discoveryId!!) = onMain {
        discovery.onDevice(TvDevice("discovery:$service:$host", "Discovered TV", host,
            discoveryId = service))
    }

    private fun nextAttempt(): Attempt = client.attempts.poll(3, TimeUnit.SECONDS)
        ?: throw AssertionError("No connection attempt; latest state: ${latest.get()}")

    private fun noMoreAttempts() {
        instrumentation.waitForIdleSync()
        assertNull("Cancelled recovery started another attempt", client.attempts.poll(200, TimeUnit.MILLISECONDS))
    }

    private fun reject(attempt: Attempt, message: String) = callbacks(attempt) { onDisconnected(message) }
    private fun ready(attempt: Attempt) = callbacks(attempt) { onConnected() }
    private fun callbacks(attempt: Attempt, action: RemoteClient.Listener.() -> Unit) {
        onMain { action(attempt.listener) }
        instrumentation.waitForIdleSync()
    }

    private fun awaitState(predicate: (RemoteUiState) -> Boolean): RemoteUiState {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (true) {
            latest.get().takeIf(predicate)?.let { return it }
            val remaining = end - System.nanoTime()
            if (remaining <= 0) throw AssertionError("Expected state did not arrive: ${latest.get()}")
            states.poll(remaining, TimeUnit.NANOSECONDS)
        }
    }

    private fun saved() = SavedTvStore(prefs).load().values.toList()
    private fun stored(id: String) = saved().singleOrNull { it.id == id }
    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private data class Attempt(val tv: TvDevice, val listener: RemoteClient.Listener)

    private class FakeConnection : TvConnection {
        val attempts = LinkedBlockingQueue<Attempt>()
        val pins = ConcurrentHashMap.newKeySet<String>()
        val throwAtHosts = ConcurrentHashMap.newKeySet<String>()
        val pairingRequests = CopyOnWriteArrayList<TvDevice>()
        val forgotten = CopyOnWriteArrayList<String>()
        override fun connect(tv: TvDevice, listener: RemoteClient.Listener) {
            attempts.offer(Attempt(tv, listener))
            if (tv.host in throwAtHosts) throw IOException("Address is unavailable")
        }
        override fun startPairing(tv: TvDevice) { pairingRequests += tv }
        override fun completePairing(code: String) = Unit
        override fun hasPairing(id: String) = id in pins
        override fun migratePairing(id: String, host: String) = Unit
        override fun forget(tv: TvDevice) { forgotten += tv.id; pins.remove(tv.id) }
        override fun sendKey(code: Int) = Unit
        override fun disconnect() = Unit
        override fun cancelPairing() = Unit
    }

    private class FakeDiscovery(
        val onDevice: (TvDevice) -> Unit,
        @Suppress("unused") val onLost: (String) -> Unit,
        private val onScanning: (Boolean, String?) -> Unit,
    ) : DiscoveryControl {
        override fun scan() { onScanning(true, null) }
        override fun stop() = Unit
    }

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "roomote.address_reconnect_test.${UUID.randomUUID()}."
        private val names = ConcurrentHashMap.newKeySet<String>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = prefix + name
            names.add(isolatedName)
            return baseContext.getSharedPreferences(isolatedName, mode)
        }
        fun clear() = names.forEach { baseContext.deleteSharedPreferences(it) }
    }
}
