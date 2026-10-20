package com.elvi.tvremote.protocol

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.os.Build
import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * Real AndroidKeyStore/mutual-TLS tests against a loopback TV simulator.
 * The simulator uses its own wire encoder, parser, identity, and pairing proof.
 * These tests do not establish compatibility with a physical television.
 */
@RunWith(AndroidJUnit4::class)
class RemoteClientIntegrationTest {
    private lateinit var client: RemoteClient
    private val identities = mutableListOf<TestIdentity>()
    private val servers = mutableListOf<TestServer>()
    private val pool = Executors.newCachedThreadPool()

    @Before fun setUp() {
        client = RemoteClient(InstrumentationRegistry.getInstrumentation().targetContext)
        client.forget(HOST)
    }

    @After fun tearDown() {
        client.cancelPairing()
        client.disconnect()
        client.forget(HOST)
        servers.forEach { it.close() }
        pool.shutdownNow()
        identities.forEach { it.close() }
    }

    @Test fun pairsAndControlsUsingMutualTls() {
        val identity = newIdentity()
        pair(identity)
        // A fresh instance must reload both the persisted pin and keystore identity.
        client = RemoteClient(InstrumentationRegistry.getInstrumentation().targetContext)
        val listener = RecordingListener()
        val server = newServer(identity)
        val keepOpen = CountDownLatch(1)
        val transcript = pool.submit {
            server.accept().use { socket ->
                negotiate(socket)
                // Deliberately combine several frames into one TLS write.
                val stream = socket.outputStream
                stream.write(frame(message(40, scalar(1, 1))) +
                    frame(message(50, scalar(6, 100) + scalar(7, 17) + scalar(8, 1))) +
                    frame(message(8, scalar(1, 1234))))
                stream.flush()
                var receivedKey = false
                var receivedPing = false
                repeat(2) {
                    val reply = readMessage(socket.inputStream)
                    reply[10]?.bytes?.let {
                        val key = decode(it)
                        assertEquals(3L, key.getValue(1).number) // KEYCODE_HOME
                        assertEquals(3L, key.getValue(2).number) // SHORT
                        receivedKey = true
                    }
                    reply[9]?.bytes?.let {
                        assertEquals(1234L, decode(it).getValue(1).number)
                        receivedPing = true
                    }
                }
                assertTrue("No Home command arrived", receivedKey)
                assertTrue("No ping response arrived", receivedPing)
                assertTrue(keepOpen.await(10, TimeUnit.SECONDS))
            }
        }
        try {
            client.connect(HOST, server.port, listener)
            assertTrue("Remote never became ready", listener.connected.await(10, TimeUnit.SECONDS))
            client.sendKey(3)
            assertTrue("No volume callback", listener.volume.await(10, TimeUnit.SECONDS))
            assertTrue("No power callback", listener.power.await(10, TimeUnit.SECONDS))
            assertEquals(Triple(17, 100, true), listener.lastVolume)
            assertEquals(true, listener.lastPower)
        } finally {
            keepOpen.countDown()
        }
        transcript.get(10, TimeUnit.SECONDS)
    }

    @Test fun rejectsDifferentServerIdentityAfterPairing() {
        pair(newIdentity())
        val replacement = newServer(newIdentity())
        val listener = RecordingListener()
        val accepted = pool.submit {
            try {
                replacement.accept().use { socket ->
                    // A permissive client would proceed and become ready.
                    negotiate(socket)
                    send(socket, message(40, scalar(1, 1)))
                }
            } catch (_: Exception) {
                // Expected when the client aborts the TLS/protocol connection.
            }
        }
        val failure = runCatching {
            client.connect(HOST, replacement.port, listener)
        }.exceptionOrNull()
        assertNotNull("Changed TV identity was accepted", failure)
        assertEquals("Unexpected ready callback", 1L, listener.connected.count)
        accepted.get(10, TimeUnit.SECONDS)
    }

    @Test fun disconnectOnMainClosesActiveTlsWithoutNetworkViolation() {
        assertMainThreadClose(forget = false)
    }

    @Test fun forgetOnMainClosesActiveTlsAndRemovesSavedPin() {
        assertMainThreadClose(forget = true)
    }

    @Test fun cancellationOnMainInterruptsPairingRead() {
        val server = newServer(newIdentity())
        val waitingForReply = CountDownLatch(1)
        val accepted = pool.submit {
            server.accept().use { socket ->
                assertTrue(readMessage(socket.inputStream).containsKey(10))
                waitingForReply.countDown()
                // Do not acknowledge pairing; wait for cancellation to close the socket.
                assertEquals(-1, socket.inputStream.read())
            }
        }
        val pending = pool.submit<Throwable?> {
            runCatching { client.startPairing(HOST, server.port) }.exceptionOrNull()
        }
        if (!waitingForReply.await(10, TimeUnit.SECONDS)) {
            surfaceServerFailure(accepted)
            fail("Pairing request never reached server")
        }
        onMainWithStrictNetworkPolicy { client.cancelPairing() }
        assertNotNull("Cancelled pairing completed successfully", pending.get(3, TimeUnit.SECONDS))
        accepted.get(3, TimeUnit.SECONDS)
    }

    private fun assertMainThreadClose(forget: Boolean) {
        val identity = newIdentity()
        pair(identity)
        val server = newServer(identity)
        val listener = RecordingListener()
        val detached = CountDownLatch(1)
        val transcript = pool.submit {
            server.accept().use { socket ->
                negotiate(socket)
                send(socket, message(40, scalar(1, 1)))
                assertTrue("Main-thread close never completed", detached.await(10, TimeUnit.SECONDS))
                // Race late TV state against the queued physical socket close. Either
                // the write fails or the detached client must suppress its callbacks.
                try {
                    socket.outputStream.write(
                        frame(message(50, scalar(6, 100) + scalar(7, 31))) +
                            frame(message(40, scalar(1, 0))),
                    )
                    socket.outputStream.flush()
                } catch (_: IOException) {
                    // The client may have completed its physical close already.
                }
                try {
                    assertEquals("Detached client left its TLS socket open",
                        -1, socket.inputStream.read())
                } catch (error: SocketTimeoutException) {
                    throw AssertionError("Detached client did not close its TLS socket", error)
                } catch (_: IOException) {
                    // TLS providers may report a closed/reset peer instead of EOF.
                }
            }
        }
        try {
            client.connect(HOST, server.port, listener)
            assertTrue("Remote never became ready", listener.connected.await(10, TimeUnit.SECONDS))
            assertTrue("Initial power callback missing", listener.power.await(10, TimeUnit.SECONDS))
            listener.rejectFurtherCallbacks.set(true)
            onMainWithStrictNetworkPolicy {
                if (forget) client.forget(HOST) else client.disconnect()
            }
            // Logical detachment must be synchronous even if TLS close is queued.
            assertNotNull("Detached client still accepted a key",
                runCatching { client.sendKey(3) }.exceptionOrNull())
            detached.countDown()
            if (!forget) {
                // Start another session before waiting for the old server's EOF.
                // Deferred cleanup must close the captured old socket only.
                assertReconnectCanSendKey(identity)
            }
        } finally {
            detached.countDown()
        }
        transcript.get(5, TimeUnit.SECONDS)
        assertFalse("A detached session delivered a stale callback",
            listener.unexpectedCallback.await(300, TimeUnit.MILLISECONDS))
        if (forget) {
            val port = server.port
            server.close()
            client = RemoteClient(InstrumentationRegistry.getInstrumentation().targetContext)
            val failure = runCatching {
                client.connect(HOST, port, RecordingListener())
            }.exceptionOrNull()
            assertTrue("Forgetting must remove the persisted pin and require new pairing",
                failure is RemoteClient.PairingRequiredException)
        }
    }

    private fun assertReconnectCanSendKey(identity: TestIdentity) {
        val replacement = newServer(identity)
        val listener = RecordingListener()
        val transcript = pool.submit {
            replacement.accept().use { socket ->
                negotiate(socket)
                send(socket, message(40, scalar(1, 1)))
                val key = decode(readMessage(socket.inputStream).getValue(10).bytes!!)
                assertEquals("Reconnected session did not receive Home", 3L, key.getValue(1).number)
            }
        }
        client.connect(HOST, replacement.port, listener)
        assertTrue("New session was interrupted by old socket cleanup",
            listener.connected.await(10, TimeUnit.SECONDS))
        client.sendKey(3)
        transcript.get(5, TimeUnit.SECONDS)
    }

    /** Reproduce Activity actions under the platform's strict main-thread network policy. */
    private fun onMainWithStrictNetworkPolicy(action: () -> Unit) {
        val failure = AtomicReference<Throwable?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val previous = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder()
                .detectNetwork()
                .penaltyDeathOnNetwork()
                .build())
            try {
                action()
            } catch (error: Throwable) {
                // Report as an assertion after restoring the main thread's policy.
                failure.set(error)
            } finally {
                StrictMode.setThreadPolicy(previous)
            }
        }
        failure.get()?.let { throw AssertionError("Main-thread TLS close failed", it) }
    }

    private fun pair(identity: TestIdentity) {
        val server = newServer(identity)
        val code = CompletableFuture<String>()
        val transcript = pool.submit {
            server.accept().use { socket ->
                val clientCertificate = socket.session.peerCertificates.first() as X509Certificate
                val proof = expectedProof(
                    clientCertificate.publicKey as RSAPublicKey,
                    identity.certificate.publicKey as RSAPublicKey,
                )
                code.complete("%02X1234".format(proof[0].toInt() and 0xff))
                val request = readMessage(socket.inputStream)
                assertEquals(2L, request.getValue(1).number)
                assertEquals(200L, request.getValue(2).number)
                assertTrue(request.containsKey(10))
                send(socket, pairing(11))
                val options = readMessage(socket.inputStream).getValue(20).bytes!!
                assertEquals(1L, decode(options).getValue(3).number)
                send(socket, pairing(20, message(2, scalar(1, 3) + scalar(2, 6)) + scalar(3, 2)))
                assertTrue(readMessage(socket.inputStream).containsKey(30))
                send(socket, pairing(31))
                val secret = decode(readMessage(socket.inputStream).getValue(40).bytes!!).getValue(1).bytes!!
                assertArrayEquals("Client pairing proof does not match TV proof", proof, secret)
                send(socket, pairing(41))
            }
        }
        try {
            client.startPairing(HOST, server.port)
            client.completePairing(code.get(10, TimeUnit.SECONDS))
        } catch (error: Exception) {
            surfaceServerFailure(transcript)
            throw error
        }
        transcript.get(10, TimeUnit.SECONDS)
    }

    private fun negotiate(socket: SSLSocket) {
        // Large unknown field forces a multibyte frame length. Split that prefix
        // across separate TLS writes so readFrame must tolerate partial input.
        val configuration = frame(message(1, scalar(1, 99) + message(2, byteArrayOf())) +
            message(90, ByteArray(150) { 0x31 }))
        socket.outputStream.write(configuration, 0, 1)
        socket.outputStream.flush()
        socket.outputStream.write(configuration, 1, configuration.size - 1)
        socket.outputStream.flush()
        val response = decode(readMessage(socket.inputStream).getValue(1).bytes!!)
        assertEquals(99L, response.getValue(1).number)
        send(socket, message(2, scalar(1, 99)))
        val active = decode(readMessage(socket.inputStream).getValue(2).bytes!!)
        assertEquals(99L, active.getValue(1).number)
    }

    private fun newIdentity(): TestIdentity = TestIdentity().also(identities::add)
    private fun newServer(identity: TestIdentity): TestServer = TestServer(identity).also(servers::add)

    private class RecordingListener : RemoteClient.Listener {
        val connected = CountDownLatch(1)
        val volume = CountDownLatch(1)
        val power = CountDownLatch(1)
        val rejectFurtherCallbacks = AtomicBoolean(false)
        val unexpectedCallback = CountDownLatch(1)
        @Volatile var lastVolume: Triple<Int, Int, Boolean>? = null
        @Volatile var lastPower: Boolean? = null
        private fun recordCallback() {
            if (rejectFurtherCallbacks.get()) unexpectedCallback.countDown()
        }
        override fun onConnected() {
            recordCallback()
            connected.countDown()
        }
        override fun onDisconnected(message: String) { recordCallback() }
        override fun onVolume(level: Int, max: Int, muted: Boolean) {
            recordCallback()
            lastVolume = Triple(level, max, muted)
            volume.countDown()
        }
        override fun onPower(isOn: Boolean) {
            recordCallback()
            lastPower = isOn
            power.countDown()
        }
    }

    private class TestIdentity : AutoCloseable {
        private val alias = "roomote.integration.server.${UUID.randomUUID()}"
        private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        init {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setKeySize(2048)
                    // Conscrypt can prehash/prepad before its private-key upcall.
                    .setDigests(KeyProperties.DIGEST_NONE,
                        KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384,
                        KeyProperties.DIGEST_SHA512)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                        KeyProperties.SIGNATURE_PADDING_RSA_PSS)
                    .setCertificateSubject(X500Principal("CN=Roomote loopback test"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(Date(System.currentTimeMillis() - 60_000))
                    .setCertificateNotAfter(Date(System.currentTimeMillis() + 86_400_000))
                    .apply {
                        if (Build.VERSION.SDK_INT >= 35) {
                            setMgf1Digests(KeyProperties.DIGEST_SHA256,
                                KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                        }
                    }
                    .build())
            }.generateKeyPair()
        }
        val certificate: X509Certificate get() = keyStore.getCertificate(alias) as X509Certificate
        val keyManager = object : X509KeyManager {
            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(alias)
            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = alias
            override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> =
                if (keyType?.startsWith("RSA") == true) arrayOf(alias) else emptyArray()
            override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
                if (keyType?.startsWith("RSA") == true) alias else null
            override fun getCertificateChain(requestedAlias: String?): Array<X509Certificate> =
                arrayOf(certificate)
            override fun getPrivateKey(requestedAlias: String?): PrivateKey =
                keyStore.getKey(alias, null) as PrivateKey
        }
        override fun close() { keyStore.deleteEntry(alias) }
    }

    private class TestServer(identity: TestIdentity) : AutoCloseable {
        // This permissive trust manager exists only in the local test server.
        // Production client validation is exercised by the replacement test.
        private val context = SSLContext.getInstance("TLS").apply {
            init(arrayOf(identity.keyManager), arrayOf(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) {
                    require(chain.isNotEmpty())
                }
                override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) =
                    error("Test server does not initiate TLS connections")
            }), SecureRandom())
        }
        private val server = (context.serverSocketFactory.createServerSocket(
            0, 1, InetAddress.getByName(HOST)) as SSLServerSocket).apply {
            enabledProtocols = arrayOf("TLSv1.2")
            needClientAuth = true
            soTimeout = 10_000
        }
        val port: Int get() = server.localPort
        fun accept(): SSLSocket = (server.accept() as SSLSocket).apply {
            soTimeout = 10_000
            startHandshake()
        }
        override fun close() { server.close() }
    }

    companion object {
        private const val HOST = "127.0.0.1"

        private fun surfaceServerFailure(future: java.util.concurrent.Future<*>) {
            try {
                future.get(2, TimeUnit.SECONDS)
            } catch (error: ExecutionException) {
                throw AssertionError("Loopback TV failed: ${error.cause}", error.cause)
            } catch (_: java.util.concurrent.TimeoutException) {
                // The client-side failure is more useful if the server is still waiting.
            }
        }

        private fun expectedProof(client: RSAPublicKey, server: RSAPublicKey): ByteArray {
            // Independent construction: parse unsigned RSA integers through hex.
            fun unsigned(number: BigInteger): ByteArray {
                val text = number.toString(16).let { if (it.length % 2 == 0) it else "0$it" }
                return ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            }
            val material = unsigned(client.modulus) + unsigned(client.publicExponent) +
                unsigned(server.modulus) + unsigned(server.publicExponent) + byteArrayOf(0x12, 0x34)
            return MessageDigest.getInstance("SHA-256").digest(material)
        }

        private data class Field(val number: Long? = null, val bytes: ByteArray? = null)

        private fun varint(number: Long): ByteArray {
            val out = ByteArrayOutputStream()
            var value = number
            do {
                val last = value < 128
                out.write(((value and 127).toInt()) or if (last) 0 else 128)
                value = value ushr 7
            } while (!last)
            return out.toByteArray()
        }

        private fun scalar(field: Int, value: Long) = varint(field.toLong() * 8) + varint(value)
        private fun message(field: Int, body: ByteArray) =
            varint(field.toLong() * 8 + 2) + varint(body.size.toLong()) + body
        private fun pairing(field: Int, body: ByteArray = byteArrayOf()) =
            scalar(1, 2) + scalar(2, 200) + message(field, body)
        private fun frame(body: ByteArray) = varint(body.size.toLong()) + body
        private fun send(socket: SSLSocket, body: ByteArray) {
            socket.outputStream.write(frame(body))
            socket.outputStream.flush()
        }
        private fun readNumber(input: InputStream): Long {
            var result = 0L
            for (offset in 0..63 step 7) {
                val octet = input.read()
                check(octet >= 0) { "Unexpected end of test transcript" }
                result += (octet and 127).toLong() shl offset
                if (octet < 128) return result
            }
            error("Invalid varint in test transcript")
        }
        private fun readMessage(input: InputStream): Map<Int, Field> {
            val size = readNumber(input).toInt()
            require(size in 0..262144)
            val body = ByteArray(size)
            DataInputStream(input).readFully(body)
            return decode(body)
        }
        private fun decode(body: ByteArray): Map<Int, Field> {
            val input = ByteArrayInputStream(body)
            val fields = linkedMapOf<Int, Field>()
            while (input.available() > 0) {
                val tag = readNumber(input).toInt()
                fields[tag ushr 3] = when (tag and 7) {
                    0 -> Field(number = readNumber(input))
                    2 -> {
                        val length = readNumber(input).toInt()
                        require(length in 0..input.available())
                        Field(bytes = ByteArray(length).also { DataInputStream(input).readFully(it) })
                    }
                    else -> error("Unexpected wire type in test transcript")
                }
            }
            return fields
        }
    }
}
