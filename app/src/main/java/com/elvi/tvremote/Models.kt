package com.elvi.tvremote

data class TvDevice(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 6466,
    val pairingPort: Int = 6467,
    val saved: Boolean = false,
)

enum class ConnectionPhase { IDLE, CONNECTING, CONNECTED, PAIRING, AWAITING_CODE, ERROR }

data class RemoteUiState(
    val devices: List<TvDevice> = emptyList(),
    val selected: TvDevice? = null,
    val phase: ConnectionPhase = ConnectionPhase.IDLE,
    val scanning: Boolean = false,
    val automaticConnection: Boolean = false,
    val message: String = "Choose a TV to get started.",
    val volume: Int? = null,
    val maxVolume: Int? = null,
    val muted: Boolean = false,
    val powerOn: Boolean? = null,
)

/** Accept hostnames and numeric addresses, never URLs, paths, credentials, or shell text. */
object TvAddress {
    fun normalize(input: String): String {
        val host = input.trim().removeSurrounding("[", "]")
        require(host.isNotEmpty() && host.length <= 253) { "Enter your TV's IP address or hostname." }
        if (host.contains(':')) {
            val literal = host.substringBefore('%')
            val zone = host.substringAfter('%', "")
            require(host.count { it == ':' } >= 2 &&
                literal.matches(Regex("[0-9a-fA-F:.]+")) &&
                (!host.contains('%') || zone.isNotEmpty()) &&
                zone.all { it.isLetterOrDigit() || it == '_' || it == '-' } &&
                runCatching { java.net.InetAddress.getByName(literal) }.isSuccess) {
                "Enter an IPv6 address without a port."
            }
        } else {
            require(host.split('.').all {
                it.length in 1..63 && it.first().isLetterOrDigit() && it.last().isLetterOrDigit() &&
                    it.all { char -> char.isLetterOrDigit() && char.code < 128 || char == '-' }
            }) { "Enter an IP address or hostname without a port or https://." }
            if (host.all { it.isDigit() || it == '.' }) {
                require(host.split('.').size == 4 && host.split('.').all { it.toIntOrNull() in 0..255 }) {
                    "Enter a valid IPv4 address, such as 192.168.1.20."
                }
            }
        }
        return host.lowercase(java.util.Locale.ROOT)
    }
}
