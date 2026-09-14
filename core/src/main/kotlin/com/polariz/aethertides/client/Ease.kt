package com.polariz.aethertides.client

import com.polariz.aethertides.shared.math.MathX
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Easing curves.
 *
 * Nothing in this game is allowed to move linearly. A linear fade reads as a dissolve applied
 * to a picture; an eased one reads as a thing arriving. The house rules:
 *
 *   arrivals   out-cubic or out-expo -- fast at first, settling
 *   departures in-cubic -- reluctant, then gone
 *   emphasis   out-back, for the small overshoot that makes a press feel physical
 *   pulses     breathe, a sine that never reaches its own extremes
 *
 * All curves take and return 0..1 except [breathe] and [spring], which are documented.
 */
object Ease {

    fun outCubic(t: Float): Float {
        val u = 1f - MathX.clamp01(t)
        return 1f - u * u * u
    }

    fun inCubic(t: Float): Float {
        val u = MathX.clamp01(t)
        return u * u * u
    }

    fun inOutCubic(t: Float): Float {
        val u = MathX.clamp01(t)
        return if (u < 0.5f) 4f * u * u * u else 1f - (-2f * u + 2f).pow(3) * 0.5f
    }

    fun outExpo(t: Float): Float {
        val u = MathX.clamp01(t)
        return if (u >= 1f) 1f else 1f - 2f.pow(-10f * u)
    }

    fun outQuint(t: Float): Float {
        val u = 1f - MathX.clamp01(t)
        return 1f - u * u * u * u * u
    }

    /** Overshoots past 1 and settles back. [amount] 1.7 is the classic; 0.9 is polite. */
    fun outBack(t: Float, amount: Float = 1.35f): Float {
        val u = MathX.clamp01(t) - 1f
        return 1f + (amount + 1f) * u * u * u + amount * u * u
    }

    /** A damped oscillation that starts at 0, overshoots, and settles on 1. */
    fun outElastic(t: Float, periods: Float = 3f, damping: Float = 6f): Float {
        val u = MathX.clamp01(t)
        if (u <= 0f) return 0f
        if (u >= 1f) return 1f
        return 1f - 2f.pow(-damping * u) * cos(u * periods * MathX.TAU * 0.5f)
    }

    /**
     * A breath, in 0..1, that never quite reaches either end.
     *
     * @param phase free-running seconds
     * @param hz    cycles per second
     * @param depth how far from the middle it swings
     */
    fun breathe(phase: Float, hz: Float, depth: Float = 0.5f): Float =
        0.5f + sin(phase * hz * MathX.TAU) * depth * 0.5f

    /** Rise then fall across 0..1, peaking at [peak]. For one-shot flashes. */
    fun pop(t: Float, peak: Float = 0.28f): Float {
        val u = MathX.clamp01(t)
        return if (u < peak) outCubic(u / peak) else 1f - inCubic((u - peak) / (1f - peak))
    }
}
