# Small Kotlin client example

`RemoteClient` is the app's blocking protocol client. Run pairing, connecting, and key writes on a worker. Display the pairing prompt and update UI state on the main thread.

The fragment below shows the order of operations inside an Activity that already owns a `RemoteClient` instance. `showCodeDialog`, `showStatus`, and `showError` stand for your UI functions, not additional protocol APIs.

```kotlin
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import com.elvi.tvremote.protocol.RemoteClient
import java.util.concurrent.Executors

// The Activity owns and closes these resources.
val main = Handler(Looper.getMainLooper())
val worker = Executors.newSingleThreadExecutor()
val remote = RemoteClient(applicationContext)

val listener = object : RemoteClient.Listener {
    override fun onConnected() {
        main.post { showStatus("Connected") }
    }

    override fun onDisconnected(message: String) {
        main.post { showStatus(message) }
    }

    override fun onVolume(level: Int, max: Int, muted: Boolean) {
        main.post { showStatus("Volume $level / $max") }
    }

    override fun onPower(isOn: Boolean) {
        main.post { showStatus(if (isOn) "TV awake" else "TV in standby") }
    }
}

fun pair(host: String) {
    worker.execute {
        try {
            remote.startPairing(host, 6467)
            main.post {
                showCodeDialog(
                    onSubmit = { code ->
                        worker.execute {
                            try {
                                remote.completePairing(code)
                                remote.connect(host, 6466, listener)
                            } catch (error: Exception) {
                                remote.cancelPairing()
                                main.post { showError(error.message ?: "Pairing failed") }
                            }
                        }
                    },
                    onCancel = {
                        remote.cancelPairing()
                    }
                )
            }
        } catch (error: Exception) {
            main.post { showError(error.message ?: "TV is unavailable") }
        }
    }
}

// Call only after onConnected(). Use the same worker to preserve key order.
fun pressHome() {
    worker.execute {
        try {
            remote.sendKey(KeyEvent.KEYCODE_HOME)
        } catch (error: Exception) {
            main.post { showError(error.message ?: "Command failed") }
        }
    }
}

// For a TV already paired by this app:
fun reconnect(host: String) {
    worker.execute {
        try {
            remote.connect(host, 6466, listener)
        } catch (error: Exception) {
            main.post { showError(error.message ?: "Connection failed") }
        }
    }
}

// In the owner's cleanup path: prevent new UI actions first.
fun closeRemote() {
    remote.cancelPairing()
    remote.disconnect()
    worker.shutdownNow()
}
```

This is an API usage excerpt, not a second runnable app. A full integration must also avoid duplicate pairing submissions, prevent stale callbacks after Activity destruction, and stop discovery and sockets at the appropriate lifecycle boundary. The app's own controller handles these concerns.

`cancelPairing()` and `disconnect()` close sockets without waiting for network replies, so cleanup calls them directly. Queueing cancellation behind a blocked handshake would delay that cancellation.

Use the discovered remote-service port when available instead of assuming 6466 for every endpoint. A successful `connect` call is not a substitute for receiving the ready callback. Do not log the pairing code or copy the TLS and pairing internals into UI code.
