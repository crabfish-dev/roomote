package com.elvi.tvremote.protocol

import java.math.BigInteger
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

/** Implements the Polo RSA challenge used by the TV's six hexadecimal symbols. */
internal object PairingProof {
    fun secret(client: RSAPublicKey, server: RSAPublicKey, code: String): ByteArray {
        val normalized = code.trim()
        require(normalized.matches(Regex("[0-9a-fA-F]{6}"))) {
            "Enter all 6 letters and numbers shown on the TV (0–9, A–F)."
        }
        val codeBytes = ByteArray(3) { normalized.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(unsigned(client.modulus))
        digest.update(unsigned(client.publicExponent))
        digest.update(unsigned(server.modulus))
        digest.update(unsigned(server.publicExponent))
        digest.update(codeBytes, 1, 2)
        val proof = digest.digest()
        require(proof[0] == codeBytes[0]) {
            "That code does not match this TV. Check the code and try again."
        }
        return proof
    }

    /** BigInteger emits an ASN.1 sign byte; the wire challenge uses unsigned RSA numbers. */
    internal fun unsigned(value: BigInteger): ByteArray {
        require(value.signum() > 0) { "RSA parameters must be positive" }
        val bytes = value.toByteArray()
        return if (bytes.size > 1 && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
    }
}
