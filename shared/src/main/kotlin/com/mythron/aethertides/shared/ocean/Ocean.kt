package com.mythron.aethertides.shared.ocean

import com.mythron.aethertides.shared.math.MathX
import com.mythron.aethertides.shared.math.Noise
import com.mythron.aethertides.shared.math.Rng
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** One sample of the water surface. Reused by the caller to keep the step allocation free. */
class WaveSample {
    /** Surface elevation above mean sea level, metres. */
    @JvmField var y = 0f
    /** Surface gradient dy/dx. atan of this is the local water slope the hull rides. */
    @JvmField var dydx = 0f
    /** Orbital (particle) velocity of the water at the surface, m/s. */
    @JvmField var vx = 0f
    @JvmField var vy = 0f
    /** 0 in a trough, 1 on a breaking crest. Drives foam, spray and surf bonus. */
    @JvmField var crest = 0f
    /** Local steepness magnitude, used for breaking detection. */
    @JvmField var steepness = 0f
}

/**
 * The sea.
 *
 * A sum of Gerstner (trochoidal) components. Each component obeys the deep water dispersion
 * relation omega = sqrt(g*k), which is why long swell visibly outruns short chop instead of
 * everything sliding at one fake speed. That single detail is most of what makes the water
 * read as real.
 *
 * The surface is parametric:
 *     X(u) = u + sum( d*Q*A*cos(theta) )
 *     Y(u) = sum( A*sin(theta) ),  theta = d*k*u - omega*t + phase
 *
 * so sampling a height at a world x means inverting X. A few fixed point iterations converge
 * quickly as long as total steepness stays below 1, which the spectrum builder enforces.
 *
 * On top of that sit two gameplay fields:
 *  - pulses: travelling solitary swells (the Tempest rogue wave, the navigator tsunami)
 *  - calms:  local amplitude suppression (the Mist spell flattening the sea)
 */
class Ocean(private val seed: Long, private val courseLength: Float) {

    companion object {
        const val COMPONENTS = 7

        /** Mean depth offshore, metres. Only the shoaling model cares. */
        const val DEEP = 60f

        /** Where the seabed starts rising toward the beach. */
        const val SHOAL_START = 420f

        const val MAX_PULSES = 6
        const val MAX_CALMS = 6
    }

    // --- spectrum ---------------------------------------------------------
    private val amp = FloatArray(COMPONENTS)
    private val baseAmp = FloatArray(COMPONENTS)
    private val k = FloatArray(COMPONENTS)
    private val omega = FloatArray(COMPONENTS)
    private val phase = FloatArray(COMPONENTS)
    private val dir = FloatArray(COMPONENTS)
    private val steep = FloatArray(COMPONENTS)

    /** 0 = glassy, 1 = survival conditions. The Tempest drives this up, Mist pulls it down. */
    var seaState = 0.28f
        set(value) {
            val v = MathX.clamp01(value)
            if (abs(v - field) > 1e-4f) {
                field = v
                rescale()
            }
        }

    var time = 0f
        private set

    // --- transient fields -------------------------------------------------
    private val pulseX = FloatArray(MAX_PULSES)
    private val pulseAmp = FloatArray(MAX_PULSES)
    private val pulseWidth = FloatArray(MAX_PULSES)
    private val pulseSpeed = FloatArray(MAX_PULSES)
    private val pulseLife = FloatArray(MAX_PULSES)
    private val pulseMaxLife = FloatArray(MAX_PULSES)

    private val calmX = FloatArray(MAX_CALMS)
    private val calmR = FloatArray(MAX_CALMS)
    private val calmStrength = FloatArray(MAX_CALMS)
    private val calmLife = FloatArray(MAX_CALMS)

    init {
        val rng = Rng(seed xor 0x5EA15EEDL)
        // Wavelengths follow a decaying spectrum: swell carrying most of the energy, then
        // progressively shorter wind chop.
        //
        // The absolute scale here is a *framing* decision as much as a physical one. A real
        // ocean swell runs 100 m and more between crests, but the playfield is about sixty
        // metres wide, so a spectrum that long renders as one slow ramp with no visible wave
        // in it at all -- which is exactly how this looked the first time it was drawn. At
        // seventy metres and down, two or three crests are in frame at once and the sea reads
        // as a sea. The dominant component still obeys the dispersion relation, so it travels
        // at about 10.6 m/s: fast enough to be worth chasing, slow enough to be catchable.
        val lengths = floatArrayOf(72f, 47f, 31f, 20f, 13f, 8.5f, 5.5f)
        for (i in 0 until COMPONENTS) {
            val l = lengths[i] * rng.range(0.92f, 1.08f)
            k[i] = MathX.TAU / l
            omega[i] = sqrt(MathX.G * k[i])
            phase[i] = rng.range(0f, MathX.TAU)
            // Most energy travels with the prevailing weather; two components run back as a
            // cross swell so crests interfere and the sea never looks like a repeating loop.
            dir[i] = if (i == 3 || i == 5) -1f else 1f
            // Amplitude by L^0.62 rather than the L^0.85 a fully developed sea would give.
            // Flattening the spectrum puts more energy in the middle bands, which is where the
            // visible structure of the surface lives at this scale.
            baseAmp[i] = 0.034f * kotlin.math.exp(0.62f * kotlin.math.ln(l))
            steep[i] = MathX.lerp(0.95f, 0.55f, i / (COMPONENTS - 1f))
        }
        rescale()
    }

    /**
     * Rebuild amplitudes for the current sea state and clamp total steepness. Gerstner waves
     * self intersect above steepness 1, which looks like the water turning inside out, so the
     * sum is normalised to stay just below breaking.
     */
    private fun rescale() {
        // Wave height grows faster than linearly with wind: this curve takes a 0.28 sea state
        // to roughly 1 m swell and a 1.0 sea state to roughly 8 m.
        val gain = 0.18f + 3.1f * seaState * seaState + 0.55f * seaState
        var total = 0f
        for (i in 0 until COMPONENTS) {
            // Short chop fades out first when the sea calms down.
            val band = MathX.lerp(1f, MathX.clamp01(seaState * 1.6f), i / (COMPONENTS - 1f))
            amp[i] = baseAmp[i] * gain * band
            total += steep[i] * amp[i] * k[i]
        }
        if (total > 0.86f) {
            val s = 0.86f / total
            for (i in 0 until COMPONENTS) steep[i] *= s
        }
    }

    fun step(dt: Float) {
        time += dt
        for (i in 0 until MAX_PULSES) {
            if (pulseLife[i] > 0f) {
                pulseLife[i] -= dt
                pulseX[i] += pulseSpeed[i] * dt
                if (pulseLife[i] <= 0f) pulseAmp[i] = 0f
            }
        }
        for (i in 0 until MAX_CALMS) {
            if (calmLife[i] > 0f) calmLife[i] -= dt
        }
    }

    /**
     * Inject a travelling solitary swell. A rogue wave from the Tempest uses a tall narrow
     * pulse; the navigator tsunami synergy uses a wider, faster one that is rideable.
     */
    fun addPulse(x: Float, amplitude: Float, width: Float, speed: Float, life: Float) {
        var slot = -1
        var weakest = Float.MAX_VALUE
        for (i in 0 until MAX_PULSES) {
            if (pulseLife[i] <= 0f) { slot = i; break }
            if (pulseAmp[i] < weakest) { weakest = pulseAmp[i]; slot = i }
        }
        if (slot < 0) return
        pulseX[slot] = x
        pulseAmp[slot] = amplitude
        pulseWidth[slot] = width
        pulseSpeed[slot] = speed
        pulseLife[slot] = life
        pulseMaxLife[slot] = life
    }

    /** Flatten the sea locally. Strength 1 makes it glass. */
    fun addCalm(x: Float, radius: Float, strength: Float, life: Float) {
        var slot = -1
        var shortest = Float.MAX_VALUE
        for (i in 0 until MAX_CALMS) {
            if (calmLife[i] <= 0f) { slot = i; break }
            if (calmLife[i] < shortest) { shortest = calmLife[i]; slot = i }
        }
        if (slot < 0) return
        calmX[slot] = x
        calmR[slot] = radius
        calmStrength[slot] = strength
        calmLife[slot] = life
    }

    fun clearTransients() {
        for (i in 0 until MAX_PULSES) { pulseLife[i] = 0f; pulseAmp[i] = 0f }
        for (i in 0 until MAX_CALMS) calmLife[i] = 0f
    }

    /** Water depth below mean sea level. Constant offshore, ramping to zero at the beach. */
    fun depthAt(x: Float): Float {
        val toShore = courseLength - x
        if (toShore >= SHOAL_START) return DEEP
        if (toShore <= 0f) return 0.4f
        val t = toShore / SHOAL_START
        return 0.4f + (DEEP - 0.4f) * t * t
    }

    /**
     * Shoaling gain. As depth falls below half a wavelength the group slows and the wave
     * piles up, so surf near the beach is taller and considerably steeper than offshore
     * swell of the same energy. Green's law gives the depth^-0.25 term.
     */
    private fun shoalGain(x: Float): Float {
        val d = depthAt(x)
        if (d >= DEEP * 0.55f) return 1f
        val g = exp(-0.25f * kotlin.math.ln(MathX.clamp(d / (DEEP * 0.55f), 0.06f, 1f)))
        return MathX.clamp(g, 1f, 2.3f)
    }

    private fun calmFactor(x: Float): Float {
        var f = 1f
        for (i in 0 until MAX_CALMS) {
            if (calmLife[i] <= 0f) continue
            val d = abs(x - calmX[i])
            if (d >= calmR[i]) continue
            val fade = MathX.clamp01(calmLife[i] * 0.6f)
            f *= 1f - calmStrength[i] * MathX.falloff(d, calmR[i]) * fade
        }
        return MathX.clamp(f, 0.05f, 1f)
    }

    /** Height contribution of the travelling pulses, plus its gradient and vertical rate. */
    private fun pulseAt(x: Float, out: FloatArray) {
        var h = 0f
        var g = 0f
        var vy = 0f
        for (i in 0 until MAX_PULSES) {
            if (pulseLife[i] <= 0f) continue
            val w = pulseWidth[i]
            val dx = x - pulseX[i]
            if (abs(dx) > w * 3.2f) continue
            // Fade in over the first 15% of life and out over the last 30% so pulses do not
            // pop into existence under the hull.
            val lifeT = pulseLife[i] / pulseMaxLife[i]
            val env = MathX.smoothstep(0f, 0.3f, lifeT) * MathX.smoothstep(1f, 0.85f, lifeT)
            val a = pulseAmp[i] * env
            val e = exp(-(dx * dx) / (2f * w * w))
            h += a * e
            g += a * e * (-dx / (w * w))
            // d/dt of the envelope as it translates at pulseSpeed.
            vy += a * e * (dx / (w * w)) * pulseSpeed[i]
        }
        out[0] = h
        out[1] = g
        out[2] = vy
    }

    private val pulseScratch = FloatArray(3)

    /** Invert X(u) = x. Three fixed point iterations; steepness is capped below 1 so it converges. */
    private fun solveU(x: Float, gain: Float): Float {
        var u = x
        for (iter in 0 until 3) {
            var sum = 0f
            for (i in 0 until COMPONENTS) {
                val theta = dir[i] * k[i] * u - omega[i] * time + phase[i]
                sum += dir[i] * steep[i] * amp[i] * gain * cos(theta)
            }
            u = x - sum
        }
        return u
    }

    /** Full surface sample at world x. This is the hot path: the hull calls it 11 times a step. */
    fun sample(x: Float, out: WaveSample) {
        val gain = shoalGain(x) * calmFactor(x)
        val u = solveU(x, gain)

        var y = 0f
        var dydu = 0f
        var dxdu = 1f
        var vx = 0f
        var vy = 0f
        var steepSum = 0f

        for (i in 0 until COMPONENTS) {
            val a = amp[i] * gain
            val theta = dir[i] * k[i] * u - omega[i] * time + phase[i]
            val s = sin(theta)
            val c = cos(theta)
            y += a * s
            dydu += a * k[i] * dir[i] * c
            dxdu -= steep[i] * a * k[i] * s
            // Particle velocity on a Gerstner surface.
            vy += -a * omega[i] * c
            vx += dir[i] * steep[i] * a * omega[i] * s
            steepSum += steep[i] * a * k[i] * abs(c)
        }

        pulseAt(x, pulseScratch)
        y += pulseScratch[0]
        val pulseSlope = pulseScratch[1]
        vy += pulseScratch[2]

        // Micro chop: sub-metre ripple that never shows up in the analytic spectrum but is
        // what stops a calm sea from looking like polished plastic.
        val chopAmp = 0.055f + 0.42f * seaState
        val chop = Noise.fbm(x * 0.22f, time * 0.55f, 3, 7717) * chopAmp * calmFactor(x)
        y += chop
        val chopSlope = (Noise.fbm((x + 0.6f) * 0.22f, time * 0.55f, 3, 7717) -
                Noise.fbm((x - 0.6f) * 0.22f, time * 0.55f, 3, 7717)) * chopAmp / 1.2f

        out.y = y
        out.dydx = (if (abs(dxdu) > 1e-4f) dydu / dxdu else dydu) + pulseSlope + chopSlope
        out.vx = vx
        out.vy = vy
        out.steepness = steepSum
        // A crest is where the surface is high and the front face is steep. Breaking water is
        // exactly where foam, spray and the surfing bonus belong.
        val high = MathX.smoothstep(0.25f, 0.95f, y / (1.2f + 5f * seaState))
        val sharp = MathX.smoothstep(0.32f, 0.8f, steepSum)
        out.crest = MathX.clamp01(high * 0.55f + sharp * 0.75f)
    }

    private val scratch = WaveSample()

    /** Convenience height-only sample. Still does the full solve, so prefer sample() in loops. */
    fun heightAt(x: Float): Float {
        sample(x, scratch)
        return scratch.y
    }

    /** Significant wave height estimate, metres. What the HUD shows as sea state. */
    fun significantHeight(): Float {
        var sum = 0f
        for (i in 0 until COMPONENTS) sum += amp[i] * amp[i]
        return 4f * sqrt(sum * 0.5f)
    }

    /** Phase speed of the dominant swell, m/s. The speed a surfer has to match to stay on a face. */
    fun dominantSpeed(): Float = omega[0] / k[0]

    fun activePulseCount(): Int {
        var n = 0
        for (i in 0 until MAX_PULSES) if (pulseLife[i] > 0f) n++
        return n
    }

    /** Read-only accessors so the renderer can draw the pulses as visible swell walls. */
    fun pulseX(i: Int) = pulseX[i]
    fun pulseAmp(i: Int) = if (pulseLife[i] > 0f) pulseAmp[i] else 0f
    fun pulseWidth(i: Int) = pulseWidth[i]
    fun pulseAlive(i: Int) = pulseLife[i] > 0f
    fun calmAlive(i: Int) = calmLife[i] > 0f
    fun calmX(i: Int) = calmX[i]
    fun calmR(i: Int) = calmR[i]

    // --- replication ------------------------------------------------------
    // The sea is deterministic from the seed, but pulses and calms are created by player
    // actions, so the server mirrors those slots into every snapshot. Clients then draw
    // exactly the water the server is simulating.

    fun syncTime(t: Float) { time = t }

    fun pulseSpeed(i: Int) = pulseSpeed[i]
    fun pulseLife(i: Int) = pulseLife[i]
    fun pulseMaxLife(i: Int) = pulseMaxLife[i]
    fun calmStrength(i: Int) = calmStrength[i]
    fun calmLife(i: Int) = calmLife[i]

    fun setPulse(i: Int, x: Float, amp: Float, width: Float, speed: Float, life: Float, maxLife: Float) {
        pulseX[i] = x; pulseAmp[i] = amp; pulseWidth[i] = width
        pulseSpeed[i] = speed; pulseLife[i] = life
        pulseMaxLife[i] = if (maxLife > 0f) maxLife else 1f
    }

    fun setCalm(i: Int, x: Float, r: Float, strength: Float, life: Float) {
        calmX[i] = x; calmR[i] = r; calmStrength[i] = strength; calmLife[i] = life
    }
}
