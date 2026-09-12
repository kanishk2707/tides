package com.polariz.aethertides.shared.math

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/** Everything here is Float and branch-stable so server and client agree step for step. */
object MathX {
    const val PI = 3.14159265358979f
    const val TAU = PI * 2f
    const val DEG = PI / 180f

    /** Standard gravity. The whole simulation is in metres, seconds, kilograms. */
    const val G = 9.80665f

    /** Density of seawater at 15C, kg/m^3. Used by the buoyancy integrator. */
    const val RHO_SEA = 1025f

    /** Density of air, kg/m^3. Used for sail thrust and hull windage. */
    const val RHO_AIR = 1.225f

    /**
     * NaN-safe. Every comparison with NaN is false, so a naive clamp passes NaN straight
     * through -- and one NaN in the sea state poisons every wave sample after it, permanently,
     * for both players. Anything non-finite collapses to the lower bound here.
     */
    fun clamp(v: Float, lo: Float, hi: Float): Float =
        if (v.isNaN()) lo else if (v < lo) lo else if (v > hi) hi else v
    fun clampI(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v
    fun clamp01(v: Float): Float = clamp(v, 0f, 1f)

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Frame rate independent exponential approach; rate is roughly the fraction closed per second. */
    fun approach(current: Float, target: Float, rate: Float, dt: Float): Float {
        val t = 1f - exp(-rate * dt)
        return current + (target - current) * t
    }

    fun moveToward(current: Float, target: Float, maxDelta: Float): Float {
        if (target.isNaN() || current.isNaN()) return if (current.isNaN()) 0f else current
        val d = target - current
        return if (abs(d) <= maxDelta) target else current + sign(d) * maxDelta
    }

    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        if (edge1 == edge0) return if (x < edge0) 0f else 1f
        val t = clamp01((x - edge0) / (edge1 - edge0))
        return t * t * (3f - 2f * t)
    }

    /** 1 at the centre, 0 at radius, smooth in between. The standard spell falloff. */
    fun falloff(dist: Float, radius: Float): Float {
        if (dist >= radius) return 0f
        val t = 1f - dist / radius
        return t * t
    }

    fun wrapAngle(a: Float): Float {
        var x = a
        while (x > PI) x -= TAU
        while (x < -PI) x += TAU
        return x
    }

    fun angleDelta(from: Float, to: Float): Float = wrapAngle(to - from)

    fun len(x: Float, y: Float): Float = sqrt(x * x + y * y)
    fun len2(x: Float, y: Float): Float = x * x + y * y

    fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float = len(bx - ax, by - ay)
    fun dist2(ax: Float, ay: Float, bx: Float, by: Float): Float = len2(bx - ax, by - ay)

    fun angleOf(x: Float, y: Float): Float = atan2(y, x)

    /** Rotate a local offset into world space. */
    fun rotX(x: Float, y: Float, a: Float): Float = x * cos(a) - y * sin(a)
    fun rotY(x: Float, y: Float, a: Float): Float = x * sin(a) + y * cos(a)

    /** 2D cross product: the scalar torque arm used throughout the rigid body solver. */
    fun cross(ax: Float, ay: Float, bx: Float, by: Float): Float = ax * by - ay * bx

    fun remap(v: Float, inMin: Float, inMax: Float, outMin: Float, outMax: Float): Float {
        if (inMax == inMin) return outMin
        return outMin + (outMax - outMin) * clamp01((v - inMin) / (inMax - inMin))
    }
}

/** Mutable 2D vector. Mutable on purpose: the sim steps 60x a second and must not churn the GC. */
class V2(@JvmField var x: Float = 0f, @JvmField var y: Float = 0f) {
    fun set(nx: Float, ny: Float): V2 { x = nx; y = ny; return this }
    fun set(o: V2): V2 { x = o.x; y = o.y; return this }
    fun add(nx: Float, ny: Float): V2 { x += nx; y += ny; return this }
    fun add(o: V2): V2 { x += o.x; y += o.y; return this }
    fun sub(o: V2): V2 { x -= o.x; y -= o.y; return this }
    fun scl(s: Float): V2 { x *= s; y *= s; return this }
    fun mulAdd(o: V2, s: Float): V2 { x += o.x * s; y += o.y * s; return this }
    fun zero(): V2 { x = 0f; y = 0f; return this }
    fun len(): Float = MathX.len(x, y)
    fun len2(): Float = MathX.len2(x, y)
    fun nor(): V2 { val l = len(); if (l > 1e-6f) { x /= l; y /= l }; return this }
    fun limit(max: Float): V2 {
        val l2 = len2()
        if (l2 > max * max && l2 > 1e-12f) { val s = max / sqrt(l2); x *= s; y *= s }
        return this
    }
    fun dot(o: V2): Float = x * o.x + y * o.y
    fun angle(): Float = atan2(y, x)
    fun cpy(): V2 = V2(x, y)
}
