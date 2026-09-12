package com.polariz.aethertides.shared.math

import kotlin.math.floor

/**
 * Hash based value noise plus fbm. Drives wind gusts, squall drift, the rain field and the
 * micro chop layered on top of the analytic swell. Integer hashing only, so it is
 * deterministic on every device.
 */
object Noise {

    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1274126177
        h = (h xor (h shr 13)) * 1274126177
        h = h xor (h shr 16)
        return (h and 0x00FFFFFF) / 16777216f
    }

    private fun fade(t: Float): Float = t * t * (3f - 2f * t)

    /** Value noise in [0,1]. */
    fun value(x: Float, y: Float, seed: Int = 0): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val u = fade(x - xi)
        val v = fade(y - yi)
        val a = hash(xi, yi, seed)
        val b = hash(xi + 1, yi, seed)
        val c = hash(xi, yi + 1, seed)
        val d = hash(xi + 1, yi + 1, seed)
        val ab = a + (b - a) * u
        val cd = c + (d - c) * u
        return ab + (cd - ab) * v
    }

    /** Signed value noise in [-1,1]. */
    fun signed(x: Float, y: Float, seed: Int = 0): Float = value(x, y, seed) * 2f - 1f

    /** Fractal brownian motion: octaves layers at half amplitude and double frequency. */
    fun fbm(x: Float, y: Float, octaves: Int = 4, seed: Int = 0): Float {
        var sum = 0f
        var amp = 0.5f
        var fx = x
        var fy = y
        var norm = 0f
        for (i in 0 until octaves) {
            sum += amp * signed(fx, fy, seed + i * 131)
            norm += amp
            amp *= 0.5f
            fx *= 2.03f
            fy *= 2.01f
        }
        return if (norm > 0f) sum / norm else 0f
    }
}
