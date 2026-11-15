package app.pulse4.android.reticulum

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimální kanonický MessagePack kodér/dekodér pro Reticulum a LXMF wire formát.
 */
object MsgPack {

    fun encode(value: Any?): ByteArray {
        val out = ByteArrayOutputStream(128)
        writeValue(out, value)
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray, offset: Int = 0): Pair<Any?, Int> {
        val buf = ByteBuffer.wrap(bytes, offset, bytes.size - offset).order(ByteOrder.BIG_ENDIAN)
        val v = readValue(buf)
        return v to (buf.position() - offset)
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

    private fun readValue(buf: ByteBuffer): Any? {
        val b = buf.get().toInt() and 0xFF
        return when {
            b <= 0x7F -> b.toLong()
            b in 0x80..0x8F -> readMap(buf, b and 0x0F)
            b in 0x90..0x9F -> readArray(buf, b and 0x0F)
            b in 0xA0..0xBF -> readStr(buf, b and 0x1F)
            b >= 0xE0 -> (b - 256).toLong()
            else -> when (b) {
                0xC0 -> null
                0xC2 -> false
                0xC3 -> true
                0xC4 -> readBin(buf, buf.get().toInt() and 0xFF)
                0xC5 -> readBin(buf, buf.short.toInt() and 0xFFFF)
                0xC6 -> readBin(buf, buf.int)
                0xCA -> buf.float.toDouble()
                0xCB -> buf.double
                0xCC -> (buf.get().toInt() and 0xFF).toLong()
                0xCD -> (buf.short.toInt() and 0xFFFF).toLong()
                0xCE -> buf.int.toLong() and 0xFFFFFFFFL
                0xCF -> buf.long
                0xD0 -> buf.get().toLong()
                0xD1 -> buf.short.toLong()
                0xD2 -> buf.int.toLong()
                0xD3 -> buf.long
                0xD9 -> readStr(buf, buf.get().toInt() and 0xFF)
                0xDA -> readStr(buf, buf.short.toInt() and 0xFFFF)
                0xDB -> readStr(buf, buf.int)
                0xDC -> readArray(buf, buf.short.toInt() and 0xFFFF)
                0xDD -> readArray(buf, buf.int)
                0xDE -> readMap(buf, buf.short.toInt() and 0xFFFF)
                0xDF -> readMap(buf, buf.int)
                else -> throw IllegalArgumentException("Unsupported MsgPack marker: 0x${b.toString(16)}")
            }
        }
    }

    private fun readBin(buf: ByteBuffer, len: Int): ByteArray {
        val out = ByteArray(len)
        buf.get(out)
        return out
    }

    private fun readStr(buf: ByteBuffer, len: Int): String =
        String(readBin(buf, len), Charsets.UTF_8)

    private fun readArray(buf: ByteBuffer, len: Int): List<Any?> =
        List(len) { readValue(buf) }

    private fun readMap(buf: ByteBuffer, len: Int): Map<Any?, Any?> {
        val map = LinkedHashMap<Any?, Any?>(len)
        repeat(len) {
            val k = readValue(buf)
            val v = readValue(buf)
            map[k] = v
        }
        return map
    }
}
