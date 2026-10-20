package com.elvi.tvremote.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class ProtoTest {
    @Test fun fragmentedMultibyteFrameAndCoalescedFollowingFrame() {
        val first = Proto.encode { bytes(90, ByteArray(180) { it.toByte() }) }
        val second = RemoteMessages.key(23)
        val output = ByteArrayOutputStream()
        Proto.writeFrame(output, first)
        Proto.writeFrame(output, second)
        val fragmented = object : InputStream() {
            private val input = ByteArrayInputStream(output.toByteArray())
            override fun read(): Int = input.read()
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                input.read(bytes, offset, minOf(length, 1))
        }
        assertArrayEquals(first, Proto.readFrame(fragmented))
        assertArrayEquals(second, Proto.readFrame(fragmented))
        assertEquals(-1, fragmented.read())
    }

    @Test fun navigationPacketMatchesWireSchema() {
        // RemoteMessage.key_inject (10), key_code (1)=DPAD_CENTER, direction (2)=SHORT.
        assertArrayEquals(byteArrayOf(0x52, 0x04, 0x08, 0x17, 0x10, 0x03), RemoteMessages.key(23))
    }

    @Test fun protobufUsesLastScalarAndSkipsUnknownFixedWidthFields() {
        val message = Proto.decode(byteArrayOf(
            0x08, 0x01, // Scalar field 1 = 1.
            0x15, 0x00, 0x00, 0x00, 0x00, // Unknown fixed32 field 2.
            0x19, 0, 0, 0, 0, 0, 0, 0, 0, // Unknown fixed64 field 3.
            0x08, 0x02, // Scalar field 1 = 2.
        ))
        assertEquals(2L, message.number(1))
    }

    @Test fun preservesNestedMessagesAndNegativeSignedVarint() {
        val encoded = Proto.encode {
            message(8) { number(1, -1) }
            message(8) { number(1, 42) }
        }
        assertEquals(-1L, Proto.decode(encoded).messages(8).first().number(1))
        assertEquals(42L, Proto.decode(encoded).message(8)!!.number(1))
    }

    @Test fun rejectsOversizeAndTruncatedFrames() {
        assertIoFailure { Proto.readFrame(ByteArrayInputStream(byteArrayOf(0xff.toByte(), 0xff.toByte(), 0x7f))) }
        assertIoFailure { Proto.readFrame(ByteArrayInputStream(byteArrayOf(0x05, 0x01))) }
        assertIoFailure { Proto.readFrame(ByteArrayInputStream(byteArrayOf(0x80.toByte()))) }
        assertIoFailure {
            Proto.readFrame(ByteArrayInputStream(ByteArray(5) { 0x80.toByte() }))
        }
    }

    @Test fun rejectsMalformedWireMessages() {
        assertIoFailure { Proto.decode(byteArrayOf(0)) } // Zero field number.
        assertIoFailure { Proto.decode(byteArrayOf(8, 0x80.toByte())) } // Truncated scalar.
        assertIoFailure { Proto.decode(byteArrayOf(0x0a, 5, 1)) } // Truncated bytes.
        assertIoFailure { Proto.decode(byteArrayOf(0x0b)) } // Unsupported start-group.
        assertIoFailure { Proto.decode(byteArrayOf(8) + ByteArray(10) { 0xff.toByte() }) }
    }

    @Test fun pairingEnvelopeUsesProtocolVersionTwoAndSuccessStatus() {
        val request = Proto.decode(RemoteMessages.pairingRequest())
        assertEquals(2L, request.number(1))
        assertEquals(200L, request.number(2))
        assertEquals("atvremote", request.message(10)!!.bytes(1)!!.toString(Charsets.UTF_8))
        val options = Proto.decode(RemoteMessages.pairingOptions()).message(20)!!
        assertEquals(1L, options.number(3))
        assertEquals(3L, options.message(1)!!.number(1))
        assertEquals(6L, options.message(1)!!.number(2))
    }

    @Test fun configureAdvertisesOnlyImplementedFeatures() {
        val configuration = Proto.decode(RemoteMessages.configure(RemoteMessages.FEATURES)).message(1)!!
        assertEquals(99L, configuration.number(1))
        assertEquals("com.elvi.tvremote", configuration.message(2)!!.bytes(5)!!.toString(Charsets.UTF_8))
    }

    private fun assertIoFailure(block: () -> Unit) {
        try {
            block()
            fail("Expected malformed input to be rejected")
        } catch (_: IOException) {
            // Expected.
        }
    }
}
