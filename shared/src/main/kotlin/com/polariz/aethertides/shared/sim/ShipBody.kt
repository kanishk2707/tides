package com.polariz.aethertides.shared.sim

import com.polariz.aethertides.shared.math.MathX
import com.polariz.aethertides.shared.math.V2
import com.polariz.aethertides.shared.ocean.Ocean
import com.polariz.aethertides.shared.ocean.WaveSample
import com.polariz.aethertides.shared.ocean.Wind
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * The ship.
 *
 * Not a sprite that follows the water line: an actual rigid body that floats. Eleven stations
 * along the keel each sample the surface, work out how deep they are, and push up with the
 * weight of the water they displace. Buoyancy at the bow before the stern is what pitches her
 * over a crest. Weight of bilge water is what makes a leaking hull sit lower and handle worse.
 * Nothing about the motion is scripted.
 *
 * Consequences that fall out of the model rather than being coded as special cases:
 *  - she pitches bow up climbing a face and bow down over the top
 *  - she launches off a steep crest and the crew can trim her attitude in the air
 *  - she lands badly and takes slam damage if the bow is down
 *  - she can outrun her own hull speed only by surfing a face
 *  - she founders, slowly and visibly, as water accumulates
 */
class ShipBody(private val ocean: Ocean, private val wind: Wind) {

    // --- pose -------------------------------------------------------------
    @JvmField var x = 40f
    @JvmField var y = 0f
    @JvmField var vx = 0f
    @JvmField var vy = 0f
    /** Pitch in radians. Positive is bow up. */
    @JvmField var angle = 0f
    @JvmField var angVel = 0f

    // --- condition --------------------------------------------------------
    @JvmField var hull = Config.MAX_HULL
    @JvmField var mast = Config.MAX_MAST
    @JvmField var aether = Config.MAX_AETHER
    /** Kilograms of seawater aboard. */
    @JvmField var bilge = 0f
    @JvmField var leaks = 0

    // --- control ----------------------------------------------------------
    @JvmField var trim = 0.65f
    @JvmField var lean = 0f
    @JvmField var pumping = false
    @JvmField var braceTimer = 0f
    @JvmField var braceCooldown = 0f

    // --- derived, for HUD, audio and the renderer -------------------------
    @JvmField var submergedFraction = 0f
    @JvmField var airborne = false
    @JvmField var airTime = 0f
    @JvmField var bestAirTime = 0f
    /** 0..1, how well she is being driven by the wave face right now. */
    @JvmField var surf = 0f
    @JvmField var apparentWind = 0f
    @JvmField var speedKnots = 0f
    @JvmField var capsizeTimer = 0f
    @JvmField var knockedDown = false
    @JvmField var dead = false
    /** Vertical impact speed of the last landing, m/s. The renderer turns it into spray. */
    @JvmField var lastSlam = 0f
    @JvmField var bowWaterY = 0f
    /** Seconds since the last hit. Feeds the carpenter and the HUD damage flash. */
    @JvmField var sinceDamage = 99f
    @JvmField var sternWaterY = 0f

    /** External impulses accumulated by spells, whirlpools and explosions between steps. */
    private var extFx = 0f
    private var extFy = 0f
    private var extTorque = 0f
    /** Multiplier applied to sail thrust this tick (gale boost, kraken grip). */
    @JvmField var thrustMult = 1f
    /** Multiplier applied to translational drag (mist, maelstrom, tentacle hold). */
    @JvmField var dragMult = 1f

    private val sample = WaveSample()
    private val windVec = V2()

    /** Station offsets along the keel, bow positive, in local metres. */
    private val stationX = FloatArray(Config.HULL_POINTS)
    private val stationBeam = FloatArray(Config.HULL_POINTS)

    /** Displacement hull speed, m/s. Beyond this, wave making drag bites hard. */
    private val hullSpeed: Float

    init {
        val n = Config.HULL_POINTS
        val half = Config.HULL_LENGTH * 0.5f
        for (i in 0 until n) {
            val t = (i / (n - 1f)) * 2f - 1f          // -1 at the stern, +1 at the bow
            stationX[i] = t * half
            // Fuller amidships, fine at the ends -- a real waterline, not a box.
            stationBeam[i] = Config.HULL_BEAM * (0.26f + 0.74f * exp(0.6f * ln(MathX.clamp(1f - t * t, 1e-3f, 1f))))
        }
        // v = 1.34 * sqrt(Lwl_ft) knots, converted to metres per second.
        hullSpeed = 1.34f * sqrt(Config.HULL_LENGTH * 3.28084f) * 0.5144f * Config.HULL_SPEED_MULT
        y = Config.FREEBOARD * 0.2f
    }

    fun reset() {
        x = 40f; y = 0.3f; vx = 4f; vy = 0f; angle = 0f; angVel = 0f
        hull = Config.MAX_HULL; mast = Config.MAX_MAST; aether = Config.MAX_AETHER
        bilge = 0f; leaks = 0
        trim = 0.65f; lean = 0f; pumping = false
        braceTimer = 0f; braceCooldown = 0f
        airborne = false; airTime = 0f; bestAirTime = 0f; surf = 0f
        capsizeTimer = 0f; knockedDown = false; dead = false; lastSlam = 0f
        sinceDamage = 99f
        extFx = 0f; extFy = 0f; extTorque = 0f; thrustMult = 1f; dragMult = 1f
    }

    fun mass(): Float = Config.DRY_MASS + bilge

    fun inertia(): Float {
        val m = mass()
        val l = Config.HULL_LENGTH
        val d = Config.HULL_DEPTH
        return m * (l * l + d * d) / 12f
    }

    /** World position of a point given in hull-local coordinates. */
    fun localToWorldX(lx: Float, ly: Float): Float = x + MathX.rotX(lx, ly, angle)
    fun localToWorldY(lx: Float, ly: Float): Float = y + MathX.rotY(lx, ly, angle)

    fun bowX(): Float = localToWorldX(Config.HULL_LENGTH * 0.5f, 0f)
    fun sternX(): Float = localToWorldX(-Config.HULL_LENGTH * 0.5f, 0f)
    fun mastTopX(): Float = localToWorldX(0f, Config.MAST_HEIGHT)
    fun mastTopY(): Float = localToWorldY(0f, Config.MAST_HEIGHT)

    fun applyImpulse(ix: Float, iy: Float) { extFx += ix; extFy += iy }

    /** Impulse at a world point, so off-centre hits also spin her. */
    fun applyImpulseAt(ix: Float, iy: Float, wx: Float, wy: Float) {
        extFx += ix
        extFy += iy
        extTorque += MathX.cross(wx - x, wy - y, ix, iy)
    }

    fun applyTorque(t: Float) { extTorque += t }

    /** Returns the damage actually taken after bracing. */
    fun damage(amount: Float): Float {
        val m = if (braceTimer > 0f) Config.BRACE_DAMAGE_MULT else 1f
        val dealt = amount * m
        hull = MathX.clamp(hull - dealt, 0f, Config.MAX_HULL)
        sinceDamage = 0f
        return dealt
    }

    fun addLeak() {
        if (leaks < Config.MAX_LEAKS) leaks++
    }

    fun spendAether(cost: Float): Boolean {
        if (aether < cost) return false
        aether -= cost
        return true
    }

    // -----------------------------------------------------------------------
    // Integration
    // -----------------------------------------------------------------------

    /**
     * Advance one fixed step. Split into two half steps because the buoyancy spring is stiff
     * and a hard landing can otherwise push the solver past its stability limit.
     */
    fun step(dt: Float, input: NavInput, sink: EventSink?) {
        trim = MathX.clamp01(input.trim)
        lean = MathX.clamp(input.lean, -1f, 1f)
        pumping = input.pump

        if (braceCooldown > 0f) braceCooldown -= dt
        if (braceTimer > 0f) braceTimer -= dt

        val half = dt * 0.5f
        integrate(half, sink)
        integrate(half, sink)

        postStep(dt, sink)

        extFx = 0f; extFy = 0f; extTorque = 0f
        thrustMult = 1f; dragMult = 1f
    }

    private fun integrate(dt: Float, sink: EventSink?) {
        val m = mass()
        val inv = 1f / m
        val invI = 1f / inertia()

        var fx = extFx
        var fy = extFy - m * MathX.G
        var torque = extTorque

        var submergedVolume = 0f
        var totalVolume = 0f
        var waterVx = 0f
        var waterVy = 0f
        var slopeSum = 0f
        var samples = 0

        val sliceLen = Config.HULL_LENGTH / Config.HULL_POINTS
        // Crew weight shift moves the effective centre of gravity fore and aft.
        val leanArm = lean * Config.HULL_LENGTH * 0.19f
        torque += -leanArm * m * MathX.G * 0.42f * cos(angle)

        for (i in 0 until Config.HULL_POINTS) {
            val lx = stationX[i]
            // Sample at the keel line of this station.
            val ly = -Config.HULL_DEPTH * 0.5f
            val wx = localToWorldX(lx, ly)
            val wy = localToWorldY(lx, ly)

            ocean.sample(wx, sample)
            if (i == Config.HULL_POINTS - 1) bowWaterY = sample.y
            if (i == 0) sternWaterY = sample.y
            slopeSum += sample.dydx
            samples++

            // Reference volume for the submerged-fraction readout.
            totalVolume += sliceLen * stationBeam[i] * Config.HULL_DEPTH * 0.8f

            val submersion = sample.y - wy
            if (submersion <= 0f) continue

            val depth = MathX.clamp(submersion, 0f, Config.HULL_DEPTH)
            val frac = depth / Config.HULL_DEPTH
            // Section area grows with draft: a hull is fine at the keel and full at the rail,
            // so the first half metre of immersion buys far less buoyancy than the last.
            val block = 0.22f + 0.48f * frac
            val vol = sliceLen * stationBeam[i] * depth * block
            submergedVolume += vol

            // Archimedes. The pressure gradient inside a wave acts along the local surface
            // normal rather than straight up, and that tilt is precisely the force that
            // carries a hull down a wave face. Apply buoyancy vertically and surfing simply
            // does not exist -- which is exactly what the first run of the physics test showed.
            val buoy = MathX.RHO_SEA * MathX.G * vol
            val nInv = 1f / sqrt(1f + sample.dydx * sample.dydx)
            val bx = -sample.dydx * nInv * buoy
            val by = nInv * buoy
            fx += bx
            fy += by
            torque += MathX.cross(wx - x, wy - y, bx, by)

            // Velocity of this point of the hull, including rotation.
            val rx = wx - x
            val ry = wy - y
            val px = vx - angVel * ry
            val py = vy + angVel * rx

            // Relative to the water, which is itself moving in its orbital path. This term is
            // the whole reason surfing works: on the face of a wave the water is moving
            // forward and it carries you with it.
            val relx = px - sample.vx
            val rely = py - sample.vy

            val areaLong = sliceLen * stationBeam[i]
            val dragX = -Config.DRAG_LONG * frac * relx * (0.6f + 0.4f * abs(relx) * 0.1f) * dragMult
            val dragY = -Config.DRAG_VERT * frac * rely * dragMult * areaLong * 0.17f

            fx += dragX
            fy += dragY
            torque += MathX.cross(rx, ry, dragX, dragY)

            waterVx += sample.vx
            waterVy += sample.vy
        }

        val subFrac = if (totalVolume > 0f) submergedVolume / totalVolume else 0f
        submergedFraction = subFrac
        if (samples > 0) { waterVx /= samples; waterVy /= samples }
        val meanSlope = if (samples > 0) slopeSum / samples else 0f

        // --- sail --------------------------------------------------------
        val mastFrac = mast / Config.MAX_MAST
        val rigged = 0.34f + 0.66f * mastFrac
        val mastMidY = localToWorldY(0f, Config.MAST_HEIGHT * 0.55f)
        val mastMidX = localToWorldX(0f, Config.MAST_HEIGHT * 0.55f)
        wind.sample(mastMidX, mastMidY, windVec)
        val awx = windVec.x - vx
        val awy = windVec.y - vy
        apparentWind = MathX.len(awx, awy)

        // A sail that is not vertical spills wind; heeled past 90 degrees it does nothing.
        val attitude = MathX.clamp01(cos(angle))
        val effTrim = trim * rigged * attitude
        val thrust = 0.5f * MathX.RHO_AIR * Config.SAIL_AREA * Config.SAIL_EFFICIENCY *
                effTrim * awx * abs(awx) * thrustMult
        fx += thrust
        // Driving force high above the centre of mass digs the bow in -- that is why she
        // trims down by the head under full sail.
        torque += MathX.cross(mastMidX - x, mastMidY - y, thrust, 0f)

        // Bare hull windage, small but it matters in a squall.
        fx += 0.5f * MathX.RHO_AIR * 8f * awx * abs(awx) * 0.08f

        // --- wave making resistance --------------------------------------
        // A displacement hull is trapped in the trough of its own bow wave at hull speed.
        // The only way past it is to let a real wave do the work, so the penalty is relieved
        // when she is being carried down a face.
        val over = abs(vx) - hullSpeed
        if (over > 0f && subFrac > 0.05f) {
            val faceAssist = MathX.clamp01(-meanSlope * sign(vx) * 3.2f)
            val penalty = Config.DRAG_LONG * 2.6f * over * over * subFrac * (1f - 0.86f * faceAssist)
            fx -= sign(vx) * penalty
        }

        // --- integrate ----------------------------------------------------
        // Clamp accelerations: a bad landing can spike buoyancy for a single step and we would
        // rather lose a little energy than have the solver explode.
        val ax = MathX.clamp(fx * inv, -140f, 140f)
        val ay = MathX.clamp(fy * inv, -180f, 180f)
        val aa = MathX.clamp(torque * invI, -26f, 26f)

        vx += ax * dt
        vy += ay * dt
        angVel += aa * dt

        // Angular drag rises sharply once she is in the water.
        angVel -= angVel * MathX.clamp01(Config.ANGULAR_DRAG * (0.35f + subFrac) * dt * 6f)
        // Airborne, the rig acts like a weathervane and slowly levels her.
        if (subFrac < 0.02f) angVel -= angVel * 0.55f * dt

        x += vx * dt
        y += vy * dt
        angle = MathX.wrapAngle(angle + angVel * dt)
    }

    /** Bookkeeping that happens once per full step, not per half step. */
    private fun postStep(dt: Float, sink: EventSink?) {
        speedKnots = abs(vx) / 0.5144f

        // --- airborne / landing ------------------------------------------
        val wasAirborne = airborne
        airborne = submergedFraction < 0.015f
        if (airborne) {
            airTime += dt
            if (!wasAirborne && airTime > 0.05f) sink?.event(EventKind.AIRBORNE, x, y, 0f)
        } else {
            if (wasAirborne && airTime > 0.25f) {
                bestAirTime = maxOf(bestAirTime, airTime)
                lastSlam = abs(vy)
                sink?.event(EventKind.LANDING, x, y, lastSlam)
                // Slam damage scales with impact speed and with how flat she lands. Coming
                // down bow first on a hard chine is what breaks boats.
                val flat = MathX.clamp01(1f - abs(angle) * 0.9f)
                val excess = lastSlam - Config.SLAM_THRESHOLD
                if (excess > 0f) {
                    val dealt = damage(excess * Config.SLAM_DAMAGE_PER_MS * (0.55f + 0.45f * (1f - flat)))
                    if (dealt > Config.BREACH_THRESHOLD) {
                        addLeak()
                        sink?.event(EventKind.BREACH, x, y, dealt)
                    }
                }
            }
            airTime = 0f
        }

        // --- surfing ------------------------------------------------------
        ocean.sample(x, sample)
        // She is surfing when she is on the front face of a wave that is moving with her and
        // she is going at least as fast as the water beneath her.
        val downhill = MathX.clamp01(-sample.dydx * 2.2f)
        val carried = MathX.clamp01((vx - sample.vx * 0.35f) / 6f)
        surf = MathX.clamp01(downhill * carried * (0.3f + 0.7f * MathX.clamp01(submergedFraction * 3f)))

        // --- aether -------------------------------------------------------
        var regen = Config.AETHER_REGEN
        regen += Config.AETHER_SURF_BONUS * surf
        if (airborne) regen += Config.AETHER_AIR_BONUS * MathX.clamp01(airTime * 1.6f)
        aether = MathX.clamp(aether + regen * dt, 0f, Config.MAX_AETHER)

        // --- flooding -----------------------------------------------------
        // Leaks flood steadily; taking green water over the rail floods much faster.
        var inflow = leaks * Config.LEAK_FLOW
        val deckY = localToWorldY(0f, Config.FREEBOARD)
        val over = sample.y - deckY
        if (over > 0f) inflow += Config.GREEN_WATER_FLOW * MathX.clamp(over, 0f, 2f)
        if (pumping && aether > 0f) {
            val cost = Config.PUMP_AETHER_PER_SEC * dt
            if (aether >= cost) {
                aether -= cost
                inflow -= Config.PUMP_RATE
            }
        }
        bilge = MathX.clamp(bilge + inflow * dt, 0f, Config.MAX_BILGE * 1.35f)
        // Past the limit she is no longer a boat, she is a slow submarine.
        if (bilge >= Config.MAX_BILGE) {
            hull = MathX.clamp(hull - 6.5f * dt, 0f, Config.MAX_HULL)
        }

        // --- the carpenter -------------------------------------------------
        sinceDamage += dt
        if (sinceDamage > Config.HULL_REPAIR_DELAY && hull < Config.MAX_HULL && !dead) {
            // Repairs are slower on a hull that is already full of water.
            val swamped = 1f - 0.6f * MathX.clamp01(bilge / Config.MAX_BILGE)
            hull = MathX.clamp(hull + Config.HULL_REPAIR_RATE * swamped * dt, 0f, Config.MAX_HULL)
        }

        // --- rig ----------------------------------------------------------
        // Carrying too much sail in too much wind is how you lose a mast.
        val stress = apparentWind * trim
        if (stress > Config.MAST_STRESS_WIND && mast > 0f) {
            val before = mast
            mast = MathX.clamp(mast - (stress - Config.MAST_STRESS_WIND) * Config.MAST_STRESS_RATE * dt,
                0f, Config.MAX_MAST)
            if (before > 0f && mast <= 0f) sink?.event(EventKind.MAST_BREAK, mastTopX(), mastTopY(), 0f)
        } else if (mast < Config.MAX_MAST && submergedFraction > 0.1f) {
            val before = mast
            mast = MathX.clamp(mast + Config.MAST_REPAIR * dt, 0f, Config.MAX_MAST)
            if (before <= 0f && mast > 0f) sink?.event(EventKind.MAST_REPAIR, x, y, 0f)
        }

        // --- knockdown and capsize ---------------------------------------
        val a = abs(angle)
        knockedDown = a > Config.KNOCKDOWN_ANGLE
        if (a > Config.CAPSIZE_ANGLE) {
            if (capsizeTimer <= 0f) sink?.event(EventKind.CAPSIZE, x, y, 0f)
            capsizeTimer += dt
            // Inverted, she floods fast.
            bilge = MathX.clamp(bilge + 900f * dt, 0f, Config.MAX_BILGE * 1.35f)
            if (capsizeTimer > Config.CAPSIZE_GRACE) dead = true
        } else {
            if (capsizeTimer > 0.4f) sink?.event(EventKind.RIGHTED, x, y, 0f)
            capsizeTimer = 0f
        }

        if (hull <= 0f) dead = true
    }

    fun tryBrace(sink: EventSink?): Boolean {
        if (braceCooldown > 0f || aether < Config.BRACE_COST) return false
        aether -= Config.BRACE_COST
        braceTimer = Config.BRACE_DURATION
        braceCooldown = Config.BRACE_COOLDOWN
        sink?.event(EventKind.BRACE, x, y, 0f)
        return true
    }

    /** Progress along the voyage, 0 to 1. */
    fun progress(): Float = MathX.clamp01(x / Config.COURSE_LENGTH)

    fun league(): Int = MathX.clampI((x / Config.LEAGUE_LENGTH).toInt() + 1, 1, Config.LEAGUES)
}

/** Anything that wants to hear about one-shot events during a step. */
fun interface EventSink {
    fun event(kind: EventKind, x: Float, y: Float, magnitude: Float)
}
