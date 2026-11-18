package app.pulse4.android.reticulum

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimální kanonický MessagePack kodér/dekodér pro Reticulum a LXMF wire formát.
 * S ochranou proti přetečení paměti a hluboké rekurzi (maxDepth, maxElements, len <= remaining).
 */
object MsgPack {
    const val MAX_DEPTH = 16
    const val MAX_ELEMENTS = 10_000

    fun encode(value: Any?): ByteArray {
        val out = ByteArrayOutputStream(128)
        writeValue(out, value)
        return out.toByteArray()
    }

    fun decode(
        bytes: ByteArray,
        offset: Int = 0,
        maxDepth: Int = MAX_DEPTH,
        maxElements: Int = MAX_ELEMENTS,
    ): Pair<Any?, Int> {
        require(offset in 0..bytes.size) { "Invalid MsgPack offset: $offset" }
        val buf = ByteBuffer.wrap(bytes, offset, bytes.size - offset).order(ByteOrder.BIG_ENDIAN)
        val state = DecodeState(maxDepth = maxDepth, maxElements = maxElements)
        val v = readValue(buf, state, depth = 0)
        return v to (buf.position() - offset)
    }

    private class DecodeState(val maxDepth: Int, val maxElements: Int) {
        var elements: Int = 0
        fun consumeElements(count: Int) {
            require(count >= 0) { "Negative MsgPack count: $count" }
            require(elements + count <= maxElements) { "MsgPack element limit exceeded: ${elements + count} > $maxElements" }
            elements += count
        }
    }

    private fun writeValue(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.write(0xC0)
            is Boolean -> out.write(if (value) 0xC3 else 0xC2)
            is Int -> writeLong(out, value.toLong())
            is Long -> writeLong(out, value)
            is Float -> writeDouble(out, value.toDouble())
            is Double -> writeDouble(out, value)
            is ByteArray -> writeBin(out, value)
            is String -> writeStr(out, value.toByteArray(Charsets.UTF_8))
            is List<*> -> {
                val n = value.size
                when {
                    n <= 15 -> out.write(0x90 or n)
                    n <= 0xFFFF -> {
                        out.write(0xDC)
                        out.write((n ushr 8) and 0xFF)
                        out.write(n and 0xFF)
                    }
                    else -> {
                        out.write(0xDD)
                        writeInt32(out, n)
                    }
                }
                for (item in value) writeValue(out, item)
            }
            is Map<*, *> -> {
                val n = value.size
                when {
                    n <= 15 -> out.write(0x80 or n)
                    n <= 0xFFFF -> {
                        out.write(0xDE)
                        out.write((n ushr 8) and 0xFF)
                        out.write(n and 0xFF)
                    }
                    else -> {
                        out.write(0xDF)
                        writeInt32(out, n)
                    }
                }
                for ((k, v) in value) {
                    writeValue(out, k)
                    writeValue(out, v)
                }
            }
            else -> throw IllegalArgumentException("Unsupported MsgPack type: ${value::class.java.name}")
        }
    }

    private fun writeLong(out: ByteArrayOutputStream, v: Long) {
        when {
            v in 0..127 -> out.write(v.toInt())
            v in -32..-1 -> out.write(v.toInt() and 0xFF)
            v in 128..0xFF -> {
                out.write(0xCC)
                out.write(v.toInt() and 0xFF)
            }
            v in 0x100..0xFFFF -> {
                out.write(0xCD)
                out.write((v ushr 8).toInt() and 0xFF)
                out.write(v.toInt() and 0xFF)
            }
            v in 0x10000L..0xFFFFFFFFL -> {
                out.write(0xCE)
                writeInt32(out, v.toInt())
            }
            v in -128..-33 -> {
                out.write(0xD0)
                out.write(v.toInt() and 0xFF)
            }
            v in Short.MIN_VALUE..Short.MAX_VALUE -> {
                out.write(0xD1)
                out.write((v ushr 8).toInt() and 0xFF)
                out.write(v.toInt() and 0xFF)
            }
            v in Int.MIN_VALUE..Int.MAX_VALUE -> {
                out.write(0xD2)
                writeInt32(out, v.toInt())
            }
            else -> {
                out.write(0xD3)
                writeInt64(out, v)
            }
        }
    }

    private fun writeDouble(out: ByteArrayOutputStream, v: Double) {
        out.write(0xCB)
        writeInt64(out, java.lang.Double.doubleToRawLongBits(v))
    }

    private fun writeBin(out: ByteArrayOutputStream, bytes: ByteArray) {
        val n = bytes.size
        when {
            n <= 0xFF -> {
                out.write(0xC4)
                out.write(n)
            }
            n <= 0xFFFF -> {
                out.write(0xC5)
                out.write((n ushr 8) and 0xFF)
                out.write(n and 0xFF)
            }
            else -> {
                out.write(0xC6)
                writeInt32(out, n)
            }
        }
        out.write(bytes)
    }

    private fun writeStr(out: ByteArrayOutputStream, utf8: ByteArray) {
        val n = utf8.size
        when {
            n <= 31 -> out.write(0xA0 or n)
            n <= 0xFF -> {
                out.write(0xD9)
                out.write(n)
            }
            n <= 0xFFFF -> {
                out.write(0xDA)
                out.write((n ushr 8) and 0xFF)
                out.write(n and 0xFF)
            }
            else -> {
                out.write(0xDB)
                writeInt32(out, n)
            }
        }
        out.write(utf8)
    }

    private fun writeInt32(out: ByteArrayOutputStream, v: Int) {
        out.write((v ushr 24) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeInt64(out: ByteArrayOutputStream, v: Long) {
        for (shift in 56 downTo 0 step 8) {
            out.write(((v ushr shift) and 0xFF).toInt())
        }
    }

    private fun readValue(buf: ByteBuffer, state: DecodeState, depth: Int): Any? {
        require(depth <= state.maxDepth) { "MsgPack depth limit exceeded: $depth > ${state.maxDepth}" }
        state.consumeElements(1)
        require(buf.hasRemaining()) { "Unexpected end of MsgPack buffer" }
        val b = buf.get().toInt() and 0xFF
        return when {
            b <= 0x7F -> b.toLong()
            b in 0x80..0x8F -> readMap(buf, b and 0x0F, state, depth)
            b in 0x90..0x9F -> readArray(buf, b and 0x0F, state, depth)
            b in 0xA0..0xBF -> readStr(buf, b and 0x1F)
            b >= 0xE0 -> (b - 256).toLong()
            else -> when (b) {
                0xC0 -> null
                0xC2 -> false
                0xC3 -> true
                0xC4 -> readBin(buf, readUInt8(buf))
                0xC5 -> readBin(buf, readUInt16(buf))
                0xC6 -> readBin(buf, readInt32Len(buf))
                0xCA -> { require(buf.remaining() >= 4); buf.float.toDouble() }
                0xCB -> { require(buf.remaining() >= 8); buf.double }
                0xCC -> { require(buf.remaining() >= 1); (buf.get().toInt() and 0xFF).toLong() }
                0xCD -> readUInt16(buf).toLong()
                0xCE -> { require(buf.remaining() >= 4); buf.int.toLong() and 0xFFFFFFFFL }
                0xCF -> { require(buf.remaining() >= 8); buf.long }
                0xD0 -> { require(buf.remaining() >= 1); buf.get().toLong() }
                0xD1 -> { require(buf.remaining() >= 2); buf.short.toLong() }
                0xD2 -> { require(buf.remaining() >= 4); buf.int.toLong() }
                0xD3 -> { require(buf.remaining() >= 8); buf.long }
                0xD9 -> readStr(buf, readUInt8(buf))
                0xDA -> readStr(buf, readUInt16(buf))
                0xDB -> readStr(buf, readInt32Len(buf))
                0xDC -> readArray(buf, readUInt16(buf), state, depth)
                0xDD -> readArray(buf, readInt32Len(buf), state, depth)
                0xDE -> readMap(buf, readUInt16(buf), state, depth)
                0xDF -> readMap(buf, readInt32Len(buf), state, depth)
                else -> throw IllegalArgumentException("Unsupported MsgPack marker: 0x${b.toString(16)}")
            }
        }
    }

    private fun readUInt8(buf: ByteBuffer): Int {
        require(buf.remaining() >= 1) { "Truncated MsgPack uint8" }
        return buf.get().toInt() and 0xFF
    }

    private fun readUInt16(buf: ByteBuffer): Int {
        require(buf.remaining() >= 2) { "Truncated MsgPack uint16" }
        return buf.short.toInt() and 0xFFFF
    }

    private fun readInt32Len(buf: ByteBuffer): Int {
        require(buf.remaining() >= 4) { "Truncated MsgPack int32" }
        val len = buf.int
        require(len >= 0) { "Negative MsgPack length: $len" }
        return len
    }

    private fun readBin(buf: ByteBuffer, len: Int): ByteArray {
        require(len >= 0 && len <= buf.remaining()) { "MsgPack bin length $len exceeds remaining ${buf.remaining()}" }
        val out = ByteArray(len)
        buf.get(out)
        return out
    }

    private fun readStr(buf: ByteBuffer, len: Int): String =
        String(readBin(buf, len), Charsets.UTF_8)

    private fun readArray(buf: ByteBuffer, len: Int, state: DecodeState, depth: Int): List<Any?> {
        require(depth + 1 <= state.maxDepth) { "MsgPack depth limit exceeded: ${depth + 1} > ${state.maxDepth}" }
        require(len >= 0 && len <= buf.remaining()) { "MsgPack array length $len exceeds remaining ${buf.remaining()}" }
        require(state.elements + len <= state.maxElements) { "MsgPack element limit exceeded" }
        val out = ArrayList<Any?>(len)
        repeat(len) { out.add(readValue(buf, state, depth + 1)) }
        return out
    }

    private fun readMap(buf: ByteBuffer, len: Int, state: DecodeState, depth: Int): Map<Any?, Any?> {
        require(depth + 1 <= state.maxDepth) { "MsgPack depth limit exceeded: ${depth + 1} > ${state.maxDepth}" }
        require(len >= 0 && len * 2L <= buf.remaining().toLong()) { "MsgPack map length $len exceeds remaining ${buf.remaining()}" }
        require(state.elements + len * 2L <= state.maxElements.toLong()) { "MsgPack element limit exceeded" }
        val map = LinkedHashMap<Any?, Any?>(len)
        repeat(len) {
            val k = readValue(buf, state, depth + 1)
            val v = readValue(buf, state, depth + 1)
            map[k] = v
        }
        return map
    }
}
