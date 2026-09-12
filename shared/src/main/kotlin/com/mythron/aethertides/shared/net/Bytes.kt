package com.mythron.aethertides.shared.net

import com.mythron.aethertides.shared.math.MathX

/**
 * Minimal big-endian binary writer/reader.
 *
 * Hand rolled rather than JSON because snapshots go out twenty times a second to a phone that
 * may be on mobile data. A full snapshot of a busy match lands around 1.2 KB here; the same
 * state as JSON is roughly eight times that.
 */
class ByteWriter(initial: Int = 2048) {
    var buf = ByteArray(initial)
        private set
    var pos = 0
        private set

    private fun need(n: Int) {
        if (pos + n <= buf.size) return
        var cap = buf.size * 2
        while (cap < pos + n) cap *= 2
        buf = buf.copyOf(cap)
    }

    fun reset(): ByteWriter { pos = 0; return this }

    fun u8(v: Int): ByteWriter { need(1); buf[pos++] = (v and 0xFF).toByte(); return this }
    fun i8(v: Int): ByteWriter = u8(v)

    fun u16(v: Int): ByteWriter {
        need(2)
        buf[pos++] = ((v ushr 8) and 0xFF).toByte()
        buf[pos++] = (v and 0xFF).toByte()
        return this
    }

    fun i16(v: Int): ByteWriter = u16(v)

    fun i32(v: Int): ByteWriter {
        need(4)
        buf[pos++] = ((v ushr 24) and 0xFF).toByte()
        buf[pos++] = ((v ushr 16) and 0xFF).toByte()
        buf[pos++] = ((v ushr 8) and 0xFF).toByte()
        buf[pos++] = (v and 0xFF).toByte()
        return this
    }

    fun i64(v: Long): ByteWriter {
        i32((v ushr 32).toInt())
        i32(v.toInt())
        return this
    }

    fun f32(v: Float): ByteWriter = i32(v.toRawBits())

    fun bool(v: Boolean): ByteWriter = u8(if (v) 1 else 0)

    /** Quantise a bounded float into 16 bits. Used for velocities, angles and normalised bars. */
    fun q16(v: Float, min: Float, max: Float): ByteWriter {
        val t = MathX.clamp01((v - min) / (max - min))
        return u16((t * 65535f).toInt())
    }

    /** Quantise a 0..1 value into 8 bits. */
    fun q8(v: Float): ByteWriter = u8((MathX.clamp01(v) * 255f).toInt())

    fun str(s: String): ByteWriter {
        val b = s.encodeToByteArray()
        val n = minOf(b.size, 255)
        u8(n)
        need(n)
        System.arraycopy(b, 0, buf, pos, n)
        pos += n
        return this
    }

    /** Up to 64 KB; used for auth tokens, which run to a kilobyte or so. */
    fun lstr(s: String): ByteWriter {
        val b = s.encodeToByteArray()
        val n = minOf(b.size, 65535)
        u16(n)
        need(n)
        System.arraycopy(b, 0, buf, pos, n)
        pos += n
        return this
    }

    fun bytes(): ByteArray = buf.copyOf(pos)
}

class ByteReader(private val buf: ByteArray, private var pos: Int = 0) {

    val remaining: Int get() = buf.size - pos
    fun hasMore(): Boolean = pos < buf.size

    fun u8(): Int = buf[pos++].toInt() and 0xFF
    fun i8(): Int = buf[pos++].toInt()

    fun u16(): Int {
        val a = buf[pos++].toInt() and 0xFF
        val b = buf[pos++].toInt() and 0xFF
        return (a shl 8) or b
    }

    fun i32(): Int {
        val a = buf[pos++].toInt() and 0xFF
        val b = buf[pos++].toInt() and 0xFF
        val c = buf[pos++].toInt() and 0xFF
        val d = buf[pos++].toInt() and 0xFF
        return (a shl 24) or (b shl 16) or (c shl 8) or d
    }

    fun i64(): Long {
        val hi = i32().toLong() and 0xFFFFFFFFL
        val lo = i32().toLong() and 0xFFFFFFFFL
        return (hi shl 32) or lo
    }

    fun f32(): Float = Float.fromBits(i32())

    fun bool(): Boolean = u8() != 0

    fun q16(min: Float, max: Float): Float {
        val t = u16() / 65535f
        return min + (max - min) * t
    }

    fun q8(): Float = u8() / 255f

    fun str(): String {
        val n = u8()
        if (pos + n > buf.size) throw IndexOutOfBoundsException("string overruns frame")
        val s = String(buf, pos, n, Charsets.UTF_8)
        pos += n
        return s
    }

    fun lstr(): String {
        val n = u16()
        if (pos + n > buf.size) throw IndexOutOfBoundsException("string overruns frame")
        val s = String(buf, pos, n, Charsets.UTF_8)
        pos += n
        return s
    }
}
