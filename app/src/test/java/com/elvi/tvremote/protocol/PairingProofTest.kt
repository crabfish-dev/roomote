package com.elvi.tvremote.protocol

import java.math.BigInteger
import java.security.interfaces.RSAPublicKey
import org.junit.Assert.*
import org.junit.Test

class PairingProofTest {
    private val client = TestRsaKey("80ff1122334455")
    private val server = TestRsaKey("aaff998877665544")

    @Test fun proofMatchesIndependentSha256VectorWithUnsignedRsaNumbers() {
        // SHA256(80ff1122334455 || 010001 || aaff998877665544 || 010001 || 1234).
        val proof = PairingProof.secret(client, server, "931234")
        assertEquals(
            "93a3ba8891ac139db95a31bef9ec8232d59d32072bfec51da23e24efc42d0139",
            proof.joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') },
        )
    }

    @Test fun stripsBigIntegerSignPrefixButPreservesExponentBytes() {
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0xff.toByte()), PairingProof.unsigned(BigInteger("80ff", 16)))
        assertArrayEquals(byteArrayOf(1, 0, 1), PairingProof.unsigned(BigInteger.valueOf(65537)))
    }

    @Test fun wrongCodeAndDifferentTvKeyCannotAuthenticate() {
        assertBadCode { PairingProof.secret(client, server, "001234") }
        assertBadCode { PairingProof.secret(client, TestRsaKey("bbcc998877665544"), "931234") }
    }

    @Test fun malformedCodesAreRejectedBeforeNetworkTransmission() {
        for (code in listOf("", "12345", "1234567", "93123Z", "93 234", "１２３４５６")) {
            assertBadCode { PairingProof.secret(client, server, code) }
        }
    }

    private fun assertBadCode(block: () -> Unit) {
        try {
            block()
            fail("Expected invalid pairing code to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private class TestRsaKey(private val modulusHex: String) : RSAPublicKey {
        override fun getModulus(): BigInteger = BigInteger(modulusHex, 16)
        override fun getPublicExponent(): BigInteger = BigInteger.valueOf(65537)
        override fun getAlgorithm(): String = "RSA"
        override fun getFormat(): String = "X.509"
        override fun getEncoded(): ByteArray = error("The challenge uses the RSA numbers, not DER")
    }
}
