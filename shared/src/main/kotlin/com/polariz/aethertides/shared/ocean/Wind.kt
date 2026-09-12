package com.polariz.aethertides.shared.ocean

import com.polariz.aethertides.shared.math.MathX
import com.polariz.aethertides.shared.math.Noise
import com.polariz.aethertides.shared.math.V2
import kotlin.math.abs
import kotlin.math.ln

/**
 * The wind field.
 *
 * Wind is not a single number: it has a vertical shear profile (a log law, so the masthead
 * sees noticeably more than the deck), a gust structure that drifts downwind, and local
 * squall cells the Tempest can drop to reverse the airflow and stall a sail.
 *
 * The navigator feels all three. Trimming for a gust that has already passed is a real
 * mistake you can make in this game.
 */
class Wind(private val seed: Long) {

    companion object {
        const val MAX_SQUALLS = 4

        /** Roughness length for open sea, metres. Standard value in the log wind profile. */
        const val Z0 = 0.0002f

        /** Reference height for the quoted wind speed, metres (the meteorological standard). */
        const val Z_REF = 10f
    }

    /** Mean wind at 10 m, m/s. Rises with sea state; the Tempest storm dial drives both. */
    var baseSpeed = 7.5f

    /** +1 blows toward the shore (a following wind), -1 blows the ship back. */
    var baseDir = 1f

    var time = 0f
        private set

    private val squallX = FloatArray(MAX_SQUALLS)
    private val squallR = FloatArray(MAX_SQUALLS)
    private val squallStrength = FloatArray(MAX_SQUALLS)
    private val squallLife = FloatArray(MAX_SQUALLS)
    private val squallMaxLife = FloatArray(MAX_SQUALLS)
    private val squallDrift = FloatArray(MAX_SQUALLS)

    private val noiseSeed = (seed ushr 16).toInt()

    fun step(dt: Float) {
        time += dt
        for (i in 0 until MAX_SQUALLS) {
            if (squallLife[i] > 0f) {
                squallLife[i] -= dt
                squallX[i] += squallDrift[i] * dt
            }
        }
    }

    /** Squall cell: rain, reduced visibility, and airflow that fights the sail. */
    fun addSquall(x: Float, radius: Float, strength: Float, life: Float, drift: Float) {
        var slot = -1
        var shortest = Float.MAX_VALUE
        for (i in 0 until MAX_SQUALLS) {
            if (squallLife[i] <= 0f) { slot = i; break }
            if (squallLife[i] < shortest) { shortest = squallLife[i]; slot = i }
        }
        if (slot < 0) return
        squallX[slot] = x
        squallR[slot] = radius
        squallStrength[slot] = strength
        squallLife[slot] = life
        squallMaxLife[slot] = life
        squallDrift[slot] = drift
    }

    fun clearTransients() {
        for (i in 0 until MAX_SQUALLS) squallLife[i] = 0f
    }

    /**
     * Log wind profile. Ratio of wind at height z to the quoted 10 m value. Below about a
     * metre the sea surface shelters the flow entirely, which is why a reefed sail is slow.
     */
    fun shear(z: Float): Float {
        val h = MathX.clamp(z, 0.35f, 90f)
        return ln(h / Z0) / ln(Z_REF / Z0)
    }

    /** Gust multiplier at a point. Drifts downwind at roughly the mean wind speed. */
    fun gust(x: Float): Float {
        val advect = time * baseSpeed * 0.35f
        val slow = Noise.fbm((x - advect) * 0.0065f, time * 0.05f, 3, noiseSeed)
        val fast = Noise.fbm((x - advect) * 0.031f, time * 0.22f, 2, noiseSeed + 991)
        return 1f + slow * 0.34f + fast * 0.17f
    }

    /** Total squall influence at x, 0 outside every cell. */
    fun squallAt(x: Float): Float {
        var s = 0f
        for (i in 0 until MAX_SQUALLS) {
            if (squallLife[i] <= 0f) continue
            val d = abs(x - squallX[i])
            if (d >= squallR[i]) continue
            val lifeT = squallLife[i] / squallMaxLife[i]
            val env = MathX.smoothstep(0f, 0.18f, lifeT) * MathX.smoothstep(0f, 0.25f, 1f - (1f - lifeT))
            s += squallStrength[i] * MathX.falloff(d, squallR[i]) * MathX.clamp01(env + 0.35f)
        }
        return MathX.clamp(s, 0f, 2.2f)
    }

    /**
     * Full wind vector at a point. Inside a squall the flow reverses and gains a strong
     * downdraught, which is what flattens a boat that is carrying too much sail.
     */
    fun sample(x: Float, z: Float, out: V2): V2 {
        val sq = squallAt(x)
        val speed = baseSpeed * shear(z) * gust(x)
        // A squall first kills the following wind, then blows the other way.
        val dirMix = baseDir * (1f - MathX.clamp01(sq) * 2f)
        val extra = 1f + sq * 0.85f
        out.x = speed * dirMix * extra
        out.y = -sq * speed * 0.30f + Noise.signed(x * 0.02f, time * 0.3f, noiseSeed + 17) * speed * 0.06f
        return out
    }

    /** 0 to 1 rain intensity, for the weather renderer and for visibility. */
    fun rainAt(x: Float, seaState: Float): Float {
        val base = MathX.smoothstep(0.45f, 0.95f, seaState) * 0.75f
        return MathX.clamp01(base + squallAt(x) * 0.9f)
    }

    fun squallAlive(i: Int) = squallLife[i] > 0f
    fun squallX(i: Int) = squallX[i]
    fun squallR(i: Int) = squallR[i]
    fun squallStrength(i: Int) = squallStrength[i]

    // --- replication ------------------------------------------------------
    fun syncTime(t: Float) { time = t }

    fun squallLife(i: Int) = squallLife[i]
    fun squallMaxLife(i: Int) = squallMaxLife[i]
    fun squallDrift(i: Int) = squallDrift[i]

    fun setSquall(i: Int, x: Float, r: Float, strength: Float, life: Float, maxLife: Float, drift: Float) {
        squallX[i] = x; squallR[i] = r; squallStrength[i] = strength
        squallLife[i] = life; squallMaxLife[i] = if (maxLife > 0f) maxLife else 1f
        squallDrift[i] = drift
    }
}
