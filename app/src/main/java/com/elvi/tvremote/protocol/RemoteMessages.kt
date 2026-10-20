package com.elvi.tvremote.protocol

/** Field assignments interoperable with Android TV Remote Service v2, not a public Google SDK. */
internal object RemoteMessages {
    const val FEATURE_PING = 1
    const val FEATURE_KEY = 2
    const val FEATURE_POWER = 32
    const val FEATURE_VOLUME = 64
    const val FEATURES = FEATURE_PING or FEATURE_KEY or FEATURE_POWER or FEATURE_VOLUME

    fun configure(features: Int): ByteArray = Proto.encode {
        message(1) {
            number(1, features.toLong())
            message(2) {
                string(1, "Android phone")
                string(2, "Roomote")
                number(3, 1)
                string(4, "1")
                string(5, "com.elvi.tvremote")
                string(6, "1.0")
            }
        }
    }

    fun active(features: Int): ByteArray = Proto.encode {
        message(2) { number(1, features.toLong()) }
    }

    fun ping(value: Long): ByteArray = Proto.encode {
        message(9) { number(1, value) }
    }

    fun key(code: Int): ByteArray = Proto.encode {
        require(code in 0..65535) { "Invalid Android key code" }
        message(10) {
            number(1, code.toLong())
            number(2, 3) // SHORT: the service synthesizes down/up.
        }
    }

    fun pairing(field: Int, block: Proto.Writer.() -> Unit): ByteArray = Proto.encode {
        number(1, 2)
        number(2, 200)
        message(field, block)
    }

    fun pairingRequest(): ByteArray = pairing(10) {
        string(1, "atvremote")
        string(2, "Roomote")
    }

    fun pairingOptions(): ByteArray = pairing(20) {
        message(1) {
            number(1, 3) // Hexadecimal.
            number(2, 6)
        }
        number(3, 1) // Phone inputs the TV's code.
    }

    fun pairingConfiguration(): ByteArray = pairing(30) {
        message(1) {
            number(1, 3)
            number(2, 6)
        }
        number(2, 1)
    }
}
