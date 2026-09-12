package com.mythron.aethertides.shared.math

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * xorshift128+. Seeded from the match seed so both players and the server roll the same
 * numbers for the same match. java.util.Random is deliberately avoided: we want full
 * control of the bit derivation so replays and rollback stay identical everywhere.
 */
class Rng(seed: Long) {
    private var s0: Long = seed xor -0x61c8864680b583ebL
    private var s1: Long = (seed shl 21) xor 0x2545F4914F6CDD1DL

    init {
        if (s0 == 0L) s0 = -0x61c8864680b583ebL
        if (s1 == 0L) s1 = 0x6A09E667F3BCC909L
        repeat(8) { nextLong() }
    }

    fun nextLong(): Long {
        var x = s0
        val y = s1
        s0 = y
        x = x xor (x shl 23)
        s1 = x xor y xor (x ushr 17) xor (y ushr 26)
        return s1 + y
    }

    fun nextInt(): Int = (nextLong() ushr 32).toInt()

    /** Uniform in [0, bound). */
    fun nextInt(bound: Int): Int {
        if (bound <= 0) return 0
        return ((nextLong() ushr 33) % bound).toInt()
    }

    fun nextInt(lo: Int, hi: Int): Int = if (hi <= lo) lo else lo + nextInt(hi - lo)

    /** Uniform in [0,1) with 24 mantissa bits, identical on every JVM. */
    fun nextFloat(): Float = ((nextLong() ushr 40).toInt()) / 16777216f

    fun range(lo: Float, hi: Float): Float = lo + (hi - lo) * nextFloat()

    fun chance(p: Float): Boolean = nextFloat() < p

    fun sign(): Float = if (nextLong() < 0L) -1f else 1f

    /** Box-Muller. Used for spray scatter and debris. */
    fun gaussian(): Float {
        var u = nextFloat()
        if (u < 1e-7f) u = 1e-7f
        val v = nextFloat()
        return sqrt(-2f * ln(u)) * cos(MathX.TAU * v)
    }

    fun <T> pick(list: List<T>): T = list[nextInt(list.size)]
}
