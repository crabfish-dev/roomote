package com.elvi.tvremote

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Controller lifecycle checks with isolated preferences and no discovery or TV traffic. */
@RunWith(AndroidJUnit4::class)
class AutoReconnectTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: IsolatedPreferencesContext
    private lateinit var controller: RemoteController
    private val states = LinkedBlockingQueue<RemoteUiState>()
    private val history = CopyOnWriteArrayList<RemoteUiState>()
    private val tvA = TvDevice("test-a", "Test TV A", "127.0.0.41", saved = true)
    private val tvB = TvDevice("test-b", "Test TV B", "127.0.0.42", saved = true)

    @Before fun setUp() {
        context = IsolatedPreferencesContext(instrumentation.targetContext)
    }

    @After fun tearDown() {
        if (::controller.isInitialized) {
            onMain { controller.close() }
            instrumentation.waitForIdleSync()
        }
        context.deleteTestPreferences()
    }

    @Test fun automaticFailureDoesNotStartPairing() {
        createController(tvA)
        onMain { controller.onForeground() }

        val connecting = awaitPhase(ConnectionPhase.CONNECTING)
        assertEquals(tvA.id, connecting.selected?.id)
        assertTrue(connecting.automaticConnection)
        val failed = awaitPhase(ConnectionPhase.ERROR)
        assertEquals(tvA.id, failed.selected?.id)
        assertTrue(failed.automaticConnection)
        assertNoPairing()
    }

    @Test fun repeatedForegroundWhileConnectingStartsOneAttempt() {
        createController(tvA)
        // All callbacks run in one main-loop turn. A worker failure cannot publish
        // ERROR between these calls and accidentally turn this into separate visits.
        onMain { repeat(8) { controller.onForeground() } }

        awaitPhase(ConnectionPhase.ERROR)
        assertEquals(1, history.count { it.phase == ConnectionPhase.CONNECTING })
        assertTrue(history.single { it.phase == ConnectionPhase.CONNECTING }.automaticConnection)
        assertNoPairing()
    }

    @Test fun failedExplicitSelectionPreservesPreviousSuccessfulDefault() {
        createController(tvA, tvB)
        onMain { controller.select(tvB) }
        val failedB = awaitPhase(ConnectionPhase.ERROR)
        assertEquals(tvB.id, failedB.selected?.id)
        assertFalse(failedB.automaticConnection)

        onMain { controller.onForeground() }
        val reconnecting = awaitPhase(ConnectionPhase.CONNECTING)
        assertEquals("A failed selection must not replace the last successful TV",
            tvA.id, reconnecting.selected?.id)
        assertTrue(reconnecting.automaticConnection)
        assertEquals(tvA.id, awaitPhase(ConnectionPhase.ERROR).selected?.id)
        assertNoPairing()
    }

    @Test fun forgettingDefaultDoesNotFallBackToAnotherSavedTv() {
        createController(tvA, tvB)
        onMain { controller.onForeground() }
        assertEquals(tvA.id, awaitPhase(ConnectionPhase.ERROR).selected?.id)

        onMain { controller.forgetSelected() }
        val forgotten = awaitPhase(ConnectionPhase.IDLE)
        assertNull(forgotten.selected)
        assertEquals(listOf(tvB.id), forgotten.devices.map { it.id })
        val attemptsBefore = history.count { it.phase == ConnectionPhase.CONNECTING }

        onMain { controller.onForeground() }
        instrumentation.waitForIdleSync()
        assertEquals("Forgetting A must not silently select B",
            attemptsBefore, history.count { it.phase == ConnectionPhase.CONNECTING })
        assertTrue("Foreground with no default must stay idle", states.isEmpty())
        assertNoPairing()
    }

    private fun createController(vararg saved: TvDevice) {
        val preferences = context.getSharedPreferences("roomote.devices", Context.MODE_PRIVATE)
        SavedTvStore(preferences).save(saved.toList())
        LastUsedTvStore(preferences).remember(tvA)
        // The namespaced peer-pin preferences are empty. connect() therefore fails
        // before loading a keystore identity, resolving a host, or opening a socket.
        onMain {
            controller = RemoteController(context) { state ->
                history.add(state)
                states.offer(state)
            }
        }
        // Deliberately do not call start(): these tests never initiate NSD discovery.
    }

    private fun awaitPhase(expected: ConnectionPhase): RemoteUiState {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) {
                throw AssertionError("No $expected state; observed ${history.map { it.phase }}")
            }
            val state = states.poll(remaining, TimeUnit.NANOSECONDS)
                ?: throw AssertionError("No $expected state; observed ${history.map { it.phase }}")
            if (state.phase == expected) return state
        }
    }

    private fun assertNoPairing() {
        assertFalse("An automatic connection must not start a TV pairing prompt",
            history.any { it.phase == ConnectionPhase.PAIRING || it.phase == ConnectionPhase.AWAITING_CODE })
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private class IsolatedPreferencesContext(base: Context) : ContextWrapper(base) {
        private val prefix = "roomote.auto_reconnect_test.${UUID.randomUUID()}."
        private val names = ConcurrentHashMap.newKeySet<String>()
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val isolatedName = prefix + name
            names.add(isolatedName)
            return baseContext.getSharedPreferences(isolatedName, mode)
        }
        fun deleteTestPreferences() {
            names.forEach { baseContext.deleteSharedPreferences(it) }
        }
    }
}
