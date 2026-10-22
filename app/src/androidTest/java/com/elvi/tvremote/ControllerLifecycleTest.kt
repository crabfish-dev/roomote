package com.elvi.tvremote

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Screen handoff checks with private test preferences and no discovery or TV traffic. */
@RunWith(AndroidJUnit4::class)
class ControllerLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: IsolatedContext
    private lateinit var controller: RemoteController
    private val firstScreen = CopyOnWriteArrayList<RemoteUiState>()
    private val secondScreen = LinkedBlockingQueue<RemoteUiState>()
    private val tv = TvDevice("lifecycle-tv", "Test TV", "127.0.0.51", saved = true)

    @Before fun setUp() {
        context = IsolatedContext(instrumentation.targetContext)
        val prefs = context.getSharedPreferences("roomote.devices", Context.MODE_PRIVATE)
        SavedTvStore(prefs).save(listOf(tv))
        LastUsedTvStore(prefs).remember(tv)
        onMain { controller = RemoteController(context) { firstScreen.add(it) } }
        // Do not call start(): no NSD discovery. Empty, isolated pin storage makes
        // connection checks fail before opening sockets or loading a keystore key.
    }

    @After fun tearDown() {
        if (::controller.isInitialized) onMain { controller.close() }
        instrumentation.waitForIdleSync()
        context.clear()
    }

    @Test fun newScreenReceivesTheLatestStateWithoutNotifyingTheDetachedScreen() {
        onMain {
            controller.disconnect()
            controller.detach()
            controller.connectManual("not a valid address")
            controller.attach { secondScreen.offer(it) }
        }

        assertEquals(1, firstScreen.size)
        assertEquals("Disconnected.", firstScreen.single().message)
        val restored = secondScreen.poll(2, TimeUnit.SECONDS)
        assertEquals(ConnectionPhase.IDLE, restored?.phase)
        assertFalse(restored?.message == "Disconnected.")
        assertTrue(secondScreen.isEmpty())
    }

    @Test fun inFlightConnectionContinuesAcrossScreenHandoff() {
        onMain {
            controller.onForeground()
            controller.detach()
            controller.attach { secondScreen.offer(it) }
        }

        assertEquals(ConnectionPhase.CONNECTING, secondScreen.poll(2, TimeUnit.SECONDS)?.phase)
        val failure = secondScreen.poll(5, TimeUnit.SECONDS)
        assertEquals(ConnectionPhase.ERROR, failure?.phase)
        assertEquals(tv.id, failure?.selected?.id)
        assertTrue(failure?.automaticConnection == true)
        assertEquals(listOf(ConnectionPhase.CONNECTING), firstScreen.map { it.phase })
        assertTrue("Screen handoff must not restart the connection", secondScreen.isEmpty())
    }

    @Test fun reattachmentKeepsAnExplicitDisconnectWithoutAutomaticRetry() {
        onMain {
            controller.disconnect()
            controller.detach()
            controller.attach { secondScreen.offer(it) }
        }

        val restored = secondScreen.poll(2, TimeUnit.SECONDS)
        assertEquals(ConnectionPhase.IDLE, restored?.phase)
        assertEquals("Disconnected.", restored?.message)
        assertFalse(restored?.automaticConnection == true)
        instrumentation.waitForIdleSync()
        assertTrue(secondScreen.isEmpty())
        assertEquals(listOf(ConnectionPhase.IDLE), firstScreen.map { it.phase })
    }

    private fun onMain(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val prefix = "roomote.lifecycle_test.${UUID.randomUUID()}."
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
