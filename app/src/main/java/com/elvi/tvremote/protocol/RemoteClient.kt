package com.elvi.tvremote.protocol

import android.content.Context
import android.os.Looper
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Locale
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * Local-network Android TV Remote Service v2 client.
 *
 * Call connect, startPairing, completePairing and sendKey on a background executor.
 * Listener callbacks run on the reader thread; marshal UI changes to the main thread.
 * connect returns after TLS setup. Only onConnected means keys can be sent.
 * disconnect/cancelPairing invalidate sessions immediately and intentionally do not notify.
 * Calls from the main thread close sockets in the background because TLS close sends data.
 *
 * The remote protocol is reverse engineered. References for interoperability:
 * https://github.com/tronikos/androidtvremote2
 * https://github.com/louis49/androidtv-remote
 */
class RemoteClient(context: Context) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(message: String)
        fun onVolume(level: Int, max: Int, muted: Boolean)
        fun onPower(isOn: Boolean)
    }

    class PairingRequiredException(message: String) : IOException(message)

    private val preferences = context.applicationContext
        .getSharedPreferences("android_tv_peer_pins", Context.MODE_PRIVATE)
    private val stateLock = Any()
    private val identity by lazy { ClientIdentity.load() }
    private var remote: RemoteSession? = null
    private var pairing: PairSession? = null

    private class RemoteSession(val host: String, val listener: Listener) {
        @Volatile var socket: SSLSocket? = null
        @Volatile var ready = false
        val writeLock = Any()
    }

    private class PairSession(val host: String) {
        @Volatile var socket: SSLSocket? = null
        @Volatile var ready = false
        @Volatile var peer: X509Certificate? = null
        val operationLock = Any()
    }

    /** No silent trust-on-first-use: a saved code-authenticated certificate is mandatory. */
    fun connect(host: String, port: Int = 6466, listener: Listener) {
        val target = normalizeHost(host)
        checkPort(port)
        val session = RemoteSession(target, listener)
        val previous = synchronized(stateLock) {
            remote.also { remote = session; it?.ready = false }
        }
        closeQuietly(previous?.socket)
        try {
            val expected = preferences.getString(pinKey(target), null)
                ?: throw PairingRequiredException("Pair with this TV using the code on its screen first.")
            val socket = openSocket(target, port, expected) { opened ->
                synchronized(stateLock) {
                    if (remote !== session) throw IOException("Connection cancelled")
                    session.socket = opened
                }
            }
            if (!isCurrent(session)) {
                closeQuietly(socket)
                throw IOException("Connection cancelled")
            }
            thread(name = "android-tv-remote", isDaemon = true) { readRemote(session, socket) }
        } catch (error: Exception) {
            synchronized(stateLock) { if (remote === session) remote = null }
            closeQuietly(session.socket)
            throw connectionError(error)
        }
    }

    /** Starts the TV code prompt. The socket remains open while the human enters the code. */
    fun startPairing(host: String, port: Int = 6467) {
        val target = normalizeHost(host)
        checkPort(port)
        val session = PairSession(target)
        val previous = synchronized(stateLock) {
            val sockets = listOf(remote?.socket, pairing?.socket)
            remote?.ready = false
            pairing?.ready = false
            remote = null
            pairing = session
            sockets
        }
        previous.forEach(::closeQuietly)
        try {
            synchronized(session.operationLock) {
                val socket = openSocket(target, port, expectedPin = null) { opened ->
                    synchronized(stateLock) {
                        if (pairing !== session) throw IOException("Pairing cancelled")
                        session.socket = opened
                    }
                }
                session.peer = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                    ?: throw IOException("TV did not provide a certificate")
                writePair(socket, RemoteMessages.pairingRequest())
                readPair(socket, 11)
                writePair(socket, RemoteMessages.pairingOptions())
                val options = readPair(socket, 20).message(20)!!
                val outputEncodings = options.messages(2)
                // Some older services omit their options; when present, honor the negotiation.
                if (outputEncodings.isNotEmpty() && outputEncodings.none {
                        it.number(1) == 3L && it.number(2) == 6L
                    }) {
                    throw IOException("This TV does not offer a six-character pairing code.")
                }
                writePair(socket, RemoteMessages.pairingConfiguration())
                readPair(socket, 31)
                synchronized(stateLock) {
                    if (pairing !== session) throw IOException("Pairing cancelled")
                    session.ready = true
                }
            }
        } catch (error: Exception) {
            finishPairSession(session)
            throw connectionError(error)
        }
    }

    /** Verifies the code against both TLS public keys, waits for success, then pins the TV. */
    fun completePairing(code: String) {
        val session = synchronized(stateLock) { pairing }
            ?: throw IOException("Start pairing again to request a new TV code.")
        synchronized(session.operationLock) {
            if (!session.ready) throw IOException("The TV is not ready for a code yet.")
            val socket = session.socket ?: throw IOException("Pairing connection closed")
            val peer = session.peer ?: throw IOException("TV certificate is missing")
            val clientKey = identity.certificate.publicKey as? RSAPublicKey
                ?: throw IOException("The phone's pairing key is unsupported")
            val serverKey = peer.publicKey as? RSAPublicKey
                ?: throw IOException("The TV's pairing key is unsupported")
            // Invalid local codes leave the session alive so the user can correct a typo.
            val secret = PairingProof.secret(clientKey, serverKey, code)
            try {
                writePair(socket, RemoteMessages.pairing(40) { bytes(1, secret) })
                readPair(socket, 41)
                synchronized(stateLock) {
                    if (pairing !== session) throw IOException("Pairing cancelled")
                    preferences.edit().putString(pinKey(session.host), fingerprint(peer)).apply()
                }
            } catch (error: Exception) {
                throw connectionError(error)
            } finally {
                finishPairSession(session)
            }
        }
    }

    fun sendKey(keyCode: Int) {
        val session = synchronized(stateLock) { remote }
            ?: throw IOException("Connect to a TV before using the remote.")
        if (!session.ready) throw IOException("The TV is still connecting.")
        try {
            writeRemote(session, RemoteMessages.key(keyCode))
        } catch (error: IOException) {
            failRemote(session, error.message ?: "Could not send the key")
            throw error
        }
    }

    fun disconnect() {
        val previous = synchronized(stateLock) {
            remote.also { remote = null; it?.ready = false }
        }
        closeQuietly(previous?.socket)
    }

    fun cancelPairing() {
        val previous = synchronized(stateLock) {
            pairing.also { pairing = null; it?.ready = false }
        }
        closeQuietly(previous?.socket)
    }

    /** Forgets this address locally. Removing the phone from TV settings is a separate action. */
    fun forget(host: String) {
        val target = normalizeHost(host)
        val sockets = synchronized(stateLock) {
            val closing = mutableListOf<SSLSocket?>()
            remote?.takeIf { it.host == target }?.let {
                it.ready = false
                closing += it.socket
                remote = null
            }
            pairing?.takeIf { it.host == target }?.let {
                it.ready = false
                closing += it.socket
                pairing = null
            }
            preferences.edit().remove(pinKey(target)).apply()
            closing
        }
        sockets.forEach(::closeQuietly)
    }

    private fun readRemote(session: RemoteSession, socket: SSLSocket) {
        var features = RemoteMessages.FEATURES
        var configured = false
        var active = false
        val deadline = System.nanoTime() + HANDSHAKE_TIMEOUT_MS * 1_000_000L
        try {
            while (isCurrent(session)) {
                if (!session.ready && System.nanoTime() > deadline) {
                    throw IOException("TV did not finish connecting. Try pairing again.")
                }
                val message = Proto.decode(Proto.readFrame(socket.inputStream))
                message.message(1)?.let { configuration ->
                    features = RemoteMessages.FEATURES and configuration.number(1).toInt()
                    if (features and RemoteMessages.FEATURE_KEY == 0) {
                        throw IOException("This TV's Remote Service does not support navigation keys.")
                    }
                    writeRemote(session, RemoteMessages.configure(features))
                    configured = true
                }
                if (message.has(2)) {
                    if (!configured) throw IOException("TV sent an unexpected connection handshake")
                    writeRemote(session, RemoteMessages.active(features))
                    active = true
                }
                message.message(8)?.let { ping ->
                    writeRemote(session, RemoteMessages.ping(ping.number(1)))
                }
                message.message(40)?.let { start ->
                    if (!configured || !active) throw IOException("TV started before the handshake finished")
                    if (!session.ready) {
                        session.ready = true
                        socket.soTimeout = IDLE_TIMEOUT_MS
                        notifyCurrent(session) { onConnected() }
                    }
                    notifyCurrent(session) { onPower(start.number(1) != 0L) }
                }
                message.message(50)?.let { volume ->
                    val maximum = volume.number(6).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                    val level = volume.number(7).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
                    notifyCurrent(session) { onVolume(level, maximum, volume.number(8) != 0L) }
                }
                if (message.has(3)) throw IOException("The TV's Remote Service rejected a command.")
            }
        } catch (error: Exception) {
            failRemote(session, connectionError(error).message ?: "Connection to the TV closed")
        } finally {
            closeQuietly(socket)
        }
    }

    private fun writeRemote(session: RemoteSession, bytes: ByteArray) {
        synchronized(session.writeLock) {
            if (!isCurrent(session)) throw IOException("Connection closed")
            val socket = session.socket ?: throw IOException("Connection closed")
            Proto.writeFrame(socket.outputStream, bytes)
        }
    }

    private fun writePair(socket: SSLSocket, bytes: ByteArray) = Proto.writeFrame(socket.outputStream, bytes)

    private fun readPair(socket: SSLSocket, expectedField: Int): Proto.Message {
        val response = Proto.decode(Proto.readFrame(socket.inputStream))
        when (response.number(2)) {
            200L -> Unit
            402L -> throw IOException("The TV rejected the code. Request a new code and try again.")
            401L -> throw IOException("The TV does not support this pairing configuration.")
            else -> throw IOException("The TV rejected pairing. Start pairing again.")
        }
        if (response.number(1) != 2L || response.message(expectedField) == null) {
            throw IOException("Unexpected response from the TV during pairing.")
        }
        return response
    }

    private fun openSocket(
        host: String,
        port: Int,
        expectedPin: String?,
        register: (SSLSocket) -> Unit,
    ): SSLSocket {
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(arrayOf(identity), arrayOf(PeerTrust(expectedPin)), null)
        val socket = ssl.socketFactory.createSocket() as SSLSocket
        try {
            register(socket)
            socket.enabledProtocols = socket.supportedProtocols.filter {
                it == "TLSv1.2" || it == "TLSv1.3"
            }.toTypedArray()
            socket.useClientMode = true
            socket.tcpNoDelay = true
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.startHandshake()
            return socket
        } catch (error: Exception) {
            closeQuietly(socket)
            throw error
        }
    }

    private fun finishPairSession(session: PairSession) {
        synchronized(stateLock) {
            if (pairing === session) pairing = null
            session.ready = false
        }
        closeQuietly(session.socket)
    }

    private fun isCurrent(session: RemoteSession): Boolean = synchronized(stateLock) { remote === session }

    private fun notifyCurrent(session: RemoteSession, block: Listener.() -> Unit) {
        synchronized(stateLock) {
            if (remote === session) session.listener.block()
        }
    }

    private fun failRemote(session: RemoteSession, message: String) {
        val notify = synchronized(stateLock) {
            if (remote !== session) false else {
                remote = null
                session.ready = false
                true
            }
        }
        closeQuietly(session.socket)
        if (notify) session.listener.onDisconnected(message)
    }

    /**
     * During explicit pairing, the code authenticates a provisional RSA certificate.
     * Outside pairing, exact certificate pinning replaces CA/hostname validation,
     * because TVs use self-signed certificates and private IP addresses.
     */
    private class PeerTrust(private val expectedPin: String?) : X509TrustManager {
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("Server authentication only")
        }
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val certificate = chain?.firstOrNull() ?: throw CertificateException("Missing TV certificate")
            val publicKey = certificate.publicKey as? RSAPublicKey
                ?: throw CertificateException("TV must use an RSA pairing certificate")
            if (publicKey.modulus.bitLength() < 2048) {
                throw CertificateException("TV certificate uses an unsupported RSA key size")
            }
            if (expectedPin != null && !MessageDigest.isEqual(
                    expectedPin.toByteArray(Charsets.US_ASCII),
                    fingerprint(certificate).toByteArray(Charsets.US_ASCII),
                )) {
                throw CertificateException("The TV certificate changed. Pair with this TV again.")
            }
        }
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val HANDSHAKE_TIMEOUT_MS = 15_000
        private const val IDLE_TIMEOUT_MS = 30_000
        // Keep closure independent of the connection worker, which may be blocked reading
        // the very socket being cancelled. Capture only the old socket, never a new session.
        private val socketCloser = Executors.newCachedThreadPool { action ->
            Thread(action, "android-tv-close").apply { isDaemon = true }
        }
        private fun normalizeHost(host: String): String =
            host.trim().removeSurrounding("[", "]").lowercase(Locale.ROOT).also {
                require(it.isNotEmpty() && it.length <= 253 && it.none(Char::isWhitespace)) {
                    "Enter a TV IP address or hostname."
                }
            }
        private fun checkPort(port: Int) {
            require(port in 1..65535) { "Invalid TV port" }
        }
        private fun pinKey(host: String): String = "peer." + hexDigest(host.toByteArray(Charsets.UTF_8))
        private fun fingerprint(certificate: X509Certificate): String = hexDigest(certificate.encoded)
        private fun hexDigest(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                (it.toInt() and 255).toString(16).padStart(2, '0')
            }
        private fun closeQuietly(closeable: Closeable?) {
            if (closeable == null) return
            if (Looper.myLooper() == Looper.getMainLooper()) {
                socketCloser.execute { closeNow(closeable) }
                return
            }
            closeNow(closeable)
        }
        private fun closeNow(closeable: Closeable) {
            try { closeable.close() } catch (_: IOException) { /* Already disconnected. */ }
        }
        private fun connectionError(error: Exception): IOException = when (error) {
            is java.net.SocketTimeoutException ->
                IOException("The TV did not respond. Check that it is awake and on the same Wi-Fi.", error)
            is SSLException -> PairingRequiredException(
                "The TV could not verify this connection. Pair again using the code on the TV.",
            ).also { it.initCause(error) }
            is IOException -> error
            else -> IOException(error.message ?: "Could not connect to the TV", error)
        }
    }
}
