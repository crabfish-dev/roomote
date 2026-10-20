package com.elvi.tvremote.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Small protobuf wire codec for the documented fields used by Remote Service v2.
 * Unknown scalar fields are skipped, allowing a newer service to add fields.
 * This is independent code; field numbers come from the upstream wire schemas:
 * https://github.com/tronikos/androidtvremote2/tree/main/src/androidtvremote2
 */
internal object Proto {
    const val MAX_FRAME_SIZE = 256 * 1024
    private const val MAX_FIELDS = 4096

    data class Field(val number: Int, val wire: Int, val scalar: Long = 0, val bytes: ByteArray? = null)

    class Message internal constructor(private val fields: List<Field>) {
        fun has(number: Int): Boolean = fields.any { it.number == number }
        fun number(number: Int, default: Long = 0): Long =
            fields.lastOrNull { it.number == number && it.wire == 0 }?.scalar ?: default
        fun bytes(number: Int): ByteArray? =
            fields.lastOrNull { it.number == number && it.wire == 2 }?.bytes
        fun message(number: Int): Message? = bytes(number)?.let(::decode)
        fun messages(number: Int): List<Message> =
            fields.filter { it.number == number && it.wire == 2 }.map { decode(it.bytes!!) }
    }

    class Writer {
        private val output = ByteArrayOutputStream()
        fun number(field: Int, value: Long): Writer = apply {
            require(field in 1..0x1fffffff)
            writeVarint(output, field.toLong() shl 3)
            writeVarint(output, value)
        }
        fun bytes(field: Int, value: ByteArray): Writer = apply {
            require(field in 1..0x1fffffff)
            require(value.size <= MAX_FRAME_SIZE)
            writeVarint(output, (field.toLong() shl 3) or 2)
            writeVarint(output, value.size.toLong())
            output.write(value)
        }
        fun string(field: Int, value: String): Writer = bytes(field, value.toByteArray(Charsets.UTF_8))
        fun message(field: Int, block: Writer.() -> Unit): Writer = bytes(field, encode(block))
        internal fun result(): ByteArray = output.toByteArray().also {
            require(it.size <= MAX_FRAME_SIZE) { "Message exceeds frame limit" }
        }
    }

    fun encode(block: Writer.() -> Unit): ByteArray = Writer().apply(block).result()

    fun decode(bytes: ByteArray): Message {
        if (bytes.size > MAX_FRAME_SIZE) throw IOException("Message exceeds frame limit")
        val input = ByteArrayInputStream(bytes)
        val fields = ArrayList<Field>()
        var count = 0
        while (input.available() > 0) {
            if (++count > MAX_FIELDS) throw IOException("Too many protobuf fields")
            val tag = readVarint(input)
            if (tag <= 0 || tag > 0xffffffffL || tag ushr 3 == 0L) {
                throw IOException("Invalid protobuf tag")
            }
            val number = (tag ushr 3).toInt()
            when (val wire = (tag and 7).toInt()) {
                0 -> fields += Field(number, wire, scalar = readVarint(input))
                1 -> readExactly(input, 8) // Unused fixed64 field.
                2 -> {
                    val length = readVarint(input)
                    if (length < 0 || length > input.available()) {
                        throw IOException("Invalid protobuf field length")
                    }
                    fields += Field(number, wire, bytes = readExactly(input, length.toInt()))
                }
                5 -> readExactly(input, 4) // Unused fixed32 field.
                else -> throw IOException("Unsupported protobuf wire type $wire")
            }
        }
        return Message(fields)
    }

    fun readFrame(input: InputStream): ByteArray {
        // Lengths are uint32 varints. Reject excessive prefixes before allocating.
        var length = 0L
        for (index in 0..4) {
            val byte = input.read()
            if (byte < 0) throw EOFException("TV closed the connection")
            length = length or ((byte and 0x7f).toLong() shl (index * 7))
            if (length > MAX_FRAME_SIZE) throw IOException("TV sent an oversized frame")
            if (byte and 0x80 == 0) return readExactly(input, length.toInt())
        }
        throw IOException("Invalid frame length")
    }

    fun writeFrame(output: OutputStream, bytes: ByteArray) {
        require(bytes.size <= MAX_FRAME_SIZE) { "Message exceeds frame limit" }
        writeVarint(output, bytes.size.toLong())
        output.write(bytes)
        output.flush()
    }

    private fun readExactly(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val count = input.read(bytes, offset, length - offset)
            if (count < 0) throw EOFException("TV closed an incomplete frame")
            if (count == 0) {
                val byte = input.read()
                if (byte < 0) throw EOFException("TV closed an incomplete frame")
                bytes[offset++] = byte.toByte()
            } else {
                offset += count
            }
        }
        return bytes
    }

    private fun readVarint(input: InputStream): Long {
        var result = 0L
        for (index in 0..9) {
            val byte = input.read()
            if (byte < 0) throw EOFException("Incomplete protobuf varint")
            if (index == 9 && byte and 0xfe != 0) throw IOException("Protobuf varint overflow")
            result = result or ((byte and 0x7f).toLong() shl (index * 7))
            if (byte and 0x80 == 0) return result
        }
        throw IOException("Invalid protobuf varint")
    }

    private fun writeVarint(output: OutputStream, value: Long) {
        var remaining = value
        while (remaining and -128L != 0L) {
            output.write((remaining.toInt() and 0x7f) or 0x80)
            remaining = remaining ushr 7
        }
        output.write(remaining.toInt())
    }
}
