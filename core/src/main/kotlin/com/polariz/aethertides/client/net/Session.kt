package com.polariz.aethertides.client.net

import com.polariz.aethertides.shared.math.MathX
import com.polariz.aethertides.shared.net.EntityRec
import com.polariz.aethertides.shared.net.EventRec
import com.polariz.aethertides.shared.net.Snapshot
import com.polariz.aethertides.shared.net.SnapshotFrame
import com.polariz.aethertides.shared.ocean.Ocean
import com.polariz.aethertides.shared.ocean.Wind
import com.polariz.aethertides.shared.sim.Config
import com.polariz.aethertides.shared.sim.MatchPhase
import com.polariz.aethertides.shared.sim.NavInput
import com.polariz.aethertides.shared.sim.Role
import com.polariz.aethertides.shared.sim.World
import kotlin.math.abs

/** What a finished round reports back to the UI. */
class RoundResult {
    @JvmField var round = 0
    @JvmField var winnerRole = 0
    @JvmField var reason = 0
    @JvmField var seriesOver = false
    @JvmField var won = false
    @JvmField var myScore = 0f
    @JvmField var round0 = -1f
    @JvmField var round1 = -1f
    @JvmField var distance = 0f
    @JvmField var duration = 0f
    @JvmField var damageTaken = 0f
    @JvmField var corsairsDowned = 0
    @JvmField var hazardsDeployed = 0
    @JvmField var spellsCast = 0
    @JvmField var fusions = 0
    @JvmField var absorbed = 0
    @JvmField var bestAir = 0f
    @JvmField var topSpeed = 0f
    @JvmField var timeSurfing = 0f
}

/**
 * A live match, however it is being produced.
 *
 * Offline against a bot and online against a person go through exactly the same pipe: the
 * world is encoded to a snapshot, decoded into frames, interpolated, and rendered. Offline
 * simply skips the socket. That is deliberate -- it means the single-player game is a genuine
 * rehearsal for the multiplayer one, with the same feel and the same latency behaviour, and it
 * means the netcode is exercised every time anyone plays at all.
 */
abstract class Session(val seed: Long) {

    /** Which side the local player is on. Can change between rounds when roles swap. */
    var role: Role = Role.NAVIGATOR
        protected set

    /** Client-side mirror, used for sampling the water and for prediction. */
    val world = World(seed)

    /** The two authoritative frames we are interpolating between. */
    protected val older = SnapshotFrame()
    protected val newer = SnapshotFrame()

    /** The frame the renderer actually reads. */
    val render = SnapshotFrame()

    private var haveOlder = false
    private var haveNewer = false

    /** Render clock, running INTERP_DELAY behind the newest server time. */
    private var renderTime = 0f
    private var serverTime = 0f

    var result: RoundResult? = null
        protected set
    var opponentName: String = "The Tempest"
        protected set
    var opponentIsBot: Boolean = true
        protected set
    var pingMs: Int = 0
        protected set

    abstract val connected: Boolean
    abstract val statusText: String

    /** Events published by the newest frame; drained by the renderer once each. */
    private val pendingEvents = ArrayList<EventRec>(48)

    // --- prediction --------------------------------------------------------
    private val predInput = NavInput()
    private val inputHistory = arrayOfNulls<NavInput>(128)
    private var inputSeq = 0
    private var predAccumulator = 0f
    /** Visual correction carried while the predicted hull eases back onto the server's. */
    private var corrX = 0f
    private var corrY = 0f
    private var corrAngle = 0f

    // =======================================================================

    abstract fun poll(dt: Float)
    abstract fun sendInput(seq: Int, trim: Float, lean: Float, pump: Boolean, brace: Boolean)
    abstract fun sendCommand(type: Int, a: Int, x: Float, y: Float)
    abstract fun leave()

    /** Local helm state, set by the HUD every frame. */
    fun setHelm(trim: Float, lean: Float, pump: Boolean, brace: Boolean) {
        predInput.trim = trim
        predInput.lean = lean
        predInput.pump = pump
        predInput.brace = brace
    }

    fun update(dt: Float) {
        poll(dt)

        if (!haveNewer) return

        // Advance the render clock and hold it in the interpolation window. If we drift out --
        // a stall, a backgrounded app, a burst of packet loss -- snap rather than crawl.
        renderTime += dt
        val target = serverTime - Config.INTERP_DELAY
        if (abs(renderTime - target) > 0.5f) renderTime = target
        else renderTime = MathX.approach(renderTime, target, 2.5f, dt)

        interpolate()
        syncWorld()
        predict(dt)
    }

    // -----------------------------------------------------------------------

    protected fun acceptFrame(f: SnapshotFrame) {
        copyFrame(newer, older)
        haveOlder = haveNewer
        copyFrame(f, newer)
        haveNewer = true
        serverTime = newer.time
        if (!haveOlder) {
            copyFrame(newer, older)
            haveOlder = true
            renderTime = serverTime - Config.INTERP_DELAY
        }

        pendingEvents.clear()
        for (i in 0 until newer.eventCount) {
            val src = newer.events[i]
            val e = EventRec()
            e.kind = src.kind; e.x = src.x; e.y = src.y
            e.x2 = src.x2; e.y2 = src.y2
            e.magnitude = src.magnitude; e.hasLine = src.hasLine
            pendingEvents.add(e)
        }

        reconcile(newer)
    }

    /** Take the events published since the last call. */
    fun drainEvents(out: MutableList<EventRec>) {
        out.addAll(pendingEvents)
        pendingEvents.clear()
    }

    // -----------------------------------------------------------------------
    // Interpolation
    // -----------------------------------------------------------------------

    private fun interpolate() {
        val span = newer.time - older.time
        val a = if (span > 1e-4f) MathX.clamp01((renderTime - older.time) / span) else 1f

        render.tick = newer.tick
        render.time = MathX.lerp(older.time, newer.time, a)
        render.phase = newer.phase
        render.phaseTimer = MathX.lerp(older.phaseTimer, newer.phaseTimer, a)

        render.shipX = MathX.lerp(older.shipX, newer.shipX, a)
        render.shipY = MathX.lerp(older.shipY, newer.shipY, a)
        render.shipVx = MathX.lerp(older.shipVx, newer.shipVx, a)
        render.shipVy = MathX.lerp(older.shipVy, newer.shipVy, a)
        render.shipAngle = older.shipAngle + MathX.angleDelta(older.shipAngle, newer.shipAngle) * a
        render.shipAngVel = MathX.lerp(older.shipAngVel, newer.shipAngVel, a)

        render.hull = MathX.lerp(older.hull, newer.hull, a)
        render.mast = MathX.lerp(older.mast, newer.mast, a)
        render.aether = MathX.lerp(older.aether, newer.aether, a)
        render.bilge = MathX.lerp(older.bilge, newer.bilge, a)
        render.leaks = newer.leaks
        render.trim = MathX.lerp(older.trim, newer.trim, a)
        render.lean = MathX.lerp(older.lean, newer.lean, a)
        render.surf = MathX.lerp(older.surf, newer.surf, a)
        render.submerged = MathX.lerp(older.submerged, newer.submerged, a)
        render.apparentWind = MathX.lerp(older.apparentWind, newer.apparentWind, a)
        render.braceCooldown = newer.braceCooldown
        render.airborne = newer.airborne
        render.braced = newer.braced
        render.pumping = newer.pumping
        render.knockedDown = newer.knockedDown
        render.krakenActive = newer.krakenActive

        render.malice = MathX.lerp(older.malice, newer.malice, a)
        render.fury = MathX.lerp(older.fury, newer.fury, a)
        render.seaState = MathX.lerp(older.seaState, newer.seaState, a)
        render.stormTarget = newer.stormTarget
        render.windSpeed = MathX.lerp(older.windSpeed, newer.windSpeed, a)
        render.windDir = newer.windDir
        render.snaredSpell = newer.snaredSpell
        render.snareTimer = MathX.lerp(older.snareTimer, newer.snareTimer, a)
        for (i in 0 until 4) render.spellCooldown[i] = newer.spellCooldown[i]
        for (i in render.deployCooldown.indices) render.deployCooldown[i] = newer.deployCooldown[i]

        render.oceanTime = MathX.lerp(older.oceanTime, newer.oceanTime, a)
        render.windTime = MathX.lerp(older.windTime, newer.windTime, a)
        for (i in 0 until Ocean.MAX_PULSES) {
            val p = render.pulse[i]
            val o = older.pulse[i]
            val n = newer.pulse[i]
            // Only interpolate a slot that is the same pulse in both frames.
            val same = o[4] > 0f && n[4] > 0f
            for (k in 0 until 6) p[k] = if (same) MathX.lerp(o[k], n[k], a) else n[k]
        }
        for (i in 0 until Ocean.MAX_CALMS) {
            val p = render.calm[i]
            val n = newer.calm[i]
            for (k in 0 until 4) p[k] = n[k]
        }
        for (i in 0 until Wind.MAX_SQUALLS) {
            val p = render.squall[i]
            val n = newer.squall[i]
            for (k in 0 until 6) p[k] = n[k]
        }

        // Entities: match by id so a pooled slot being reused does not make something teleport
        // across the screen.
        render.entityCount = newer.entityCount
        for (i in 0 until newer.entityCount) {
            val n = newer.entities[i]
            val r = render.entities[i]
            val o = older.findEntity(n.id)
            r.id = n.id
            r.kind = n.kind
            r.hp = n.hp
            r.stunned = n.stunned
            r.grabbing = n.grabbing
            r.phase = n.phase
            if (o != null && o.kind == n.kind) {
                r.x = MathX.lerp(o.x, n.x, a)
                r.y = MathX.lerp(o.y, n.y, a)
                r.vx = MathX.lerp(o.vx, n.vx, a)
                r.vy = MathX.lerp(o.vy, n.vy, a)
                r.angle = o.angle + MathX.angleDelta(o.angle, n.angle) * a
            } else {
                r.x = n.x; r.y = n.y; r.vx = n.vx; r.vy = n.vy; r.angle = n.angle
            }
        }

        render.spellCount = newer.spellCount
        for (i in 0 until newer.spellCount) {
            val n = newer.spellZones[i]
            val r = render.spellZones[i]
            r.id = n.id; r.kind = n.kind; r.radius = n.radius; r.t = n.t
            var matched = false
            for (j in 0 until older.spellCount) {
                val o = older.spellZones[j]
                if (o.id == n.id) {
                    r.x = MathX.lerp(o.x, n.x, a)
                    r.y = MathX.lerp(o.y, n.y, a)
                    matched = true
                    break
                }
            }
            if (!matched) { r.x = n.x; r.y = n.y }
        }
    }

    /** Push the interpolated water state into the client world so the renderer can sample it. */
    private fun syncWorld() {
        world.ocean.seaState = render.seaState
        world.ocean.syncTime(render.oceanTime)
        for (i in 0 until Ocean.MAX_PULSES) {
            val p = render.pulse[i]
            world.ocean.setPulse(i, p[0], p[1], p[2], p[3], p[4], p[5])
        }
        for (i in 0 until Ocean.MAX_CALMS) {
            val c = render.calm[i]
            world.ocean.setCalm(i, c[0], c[1], c[2], c[3])
        }
        world.wind.syncTime(render.windTime)
        world.wind.baseSpeed = render.windSpeed
        world.wind.baseDir = render.windDir
        for (i in 0 until Wind.MAX_SQUALLS) {
            val s = render.squall[i]
            world.wind.setSquall(i, s[0], s[1], s[2], s[3], s[4], s[5])
        }
        world.phase = render.phase
        world.malice = render.malice
        world.fury = render.fury
        world.snaredSpell = render.snaredSpell
        for (i in 0 until 4) world.spellCooldown[i] = render.spellCooldown[i]
        for (i in world.deployCooldown.indices) world.deployCooldown[i] = render.deployCooldown[i]
        world.ship.aether = render.aether
    }

    // -----------------------------------------------------------------------
    // Prediction
    // -----------------------------------------------------------------------

    /**
     * Step the local hull forward with the input the player is giving right now, so the helm
     * answers immediately regardless of ping, then replay everything the server has not yet
     * acknowledged.
     *
     * The predicted hull is only ever *blended* into the render frame, never substituted for
     * it, so a wrong prediction resolves as a short correction rather than a jump.
     */
    private fun predict(dt: Float) {
        if (role != Role.NAVIGATOR || render.phase != MatchPhase.SAILING) return

        predAccumulator += dt
        var steps = 0
        while (predAccumulator >= Config.DT && steps < 4) {
            predAccumulator -= Config.DT
            steps++
            inputSeq++
            val slot = inputSeq % inputHistory.size
            val stored = inputHistory[slot] ?: NavInput().also { inputHistory[slot] = it }
            stored.set(predInput)
            stored.seq = inputSeq
            sendInput(inputSeq, predInput.trim, predInput.lean, predInput.pump, predInput.brace)
            // Brace is an edge, not a level: clear it once sent.
            predInput.brace = false
            world.ship.step(Config.DT, stored, null)
        }

        // Ease the outstanding correction away over about a fifth of a second.
        corrX = MathX.approach(corrX, 0f, 9f, dt)
        corrY = MathX.approach(corrY, 0f, 9f, dt)
        corrAngle = MathX.approach(corrAngle, 0f, 9f, dt)

        render.shipX = world.ship.x + corrX
        render.shipY = world.ship.y + corrY
        render.shipAngle = world.ship.angle + corrAngle
        render.shipVx = world.ship.vx
        render.shipVy = world.ship.vy
        render.trim = world.ship.trim
        render.lean = world.ship.lean
        render.surf = world.ship.surf
        render.submerged = world.ship.submergedFraction
        render.airborne = world.ship.airborne
    }

    /** Fold an authoritative frame back into the predicted hull. */
    private fun reconcile(f: SnapshotFrame) {
        if (role != Role.NAVIGATOR) {
            Snapshot.applyTo(f, world, applyShip = true)
            return
        }

        val beforeX = world.ship.x
        val beforeY = world.ship.y
        val beforeA = world.ship.angle

        Snapshot.applyTo(f, world, applyShip = true)

        // Replay the inputs the server had not seen when it produced this frame.
        var s = f.ackSeq + 1
        var replayed = 0
        while (s <= inputSeq && replayed < 24) {
            val stored = inputHistory[s % inputHistory.size]
            if (stored != null && stored.seq == s) {
                world.ship.step(Config.DT, stored, null)
                replayed++
            }
            s++
        }

        // Whatever is left is error. Carry it as a visual offset and bleed it off, unless it is
        // large -- a hit we did not predict -- in which case take the correction immediately.
        val ex = beforeX - world.ship.x
        val ey = beforeY - world.ship.y
        val ea = MathX.angleDelta(world.ship.angle, beforeA)
        if (MathX.len(ex, ey) < 6f) {
            corrX = MathX.clamp(corrX + ex, -6f, 6f)
            corrY = MathX.clamp(corrY + ey, -6f, 6f)
            corrAngle = MathX.clamp(corrAngle + ea, -0.7f, 0.7f)
        } else {
            corrX = 0f; corrY = 0f; corrAngle = 0f
        }
    }

    protected fun resetPrediction() {
        corrX = 0f; corrY = 0f; corrAngle = 0f
        predAccumulator = 0f
        inputSeq = 0
        haveOlder = false
        haveNewer = false
        result = null
        for (i in inputHistory.indices) inputHistory[i] = null
    }

    // -----------------------------------------------------------------------

    private fun copyFrame(src: SnapshotFrame, dst: SnapshotFrame) {
        dst.tick = src.tick; dst.time = src.time; dst.phase = src.phase
        dst.phaseTimer = src.phaseTimer; dst.ackSeq = src.ackSeq
        dst.shipX = src.shipX; dst.shipY = src.shipY
        dst.shipVx = src.shipVx; dst.shipVy = src.shipVy
        dst.shipAngle = src.shipAngle; dst.shipAngVel = src.shipAngVel
        dst.hull = src.hull; dst.mast = src.mast; dst.aether = src.aether
        dst.bilge = src.bilge; dst.leaks = src.leaks
        dst.trim = src.trim; dst.lean = src.lean; dst.surf = src.surf
        dst.submerged = src.submerged; dst.apparentWind = src.apparentWind
        dst.braceCooldown = src.braceCooldown
        dst.airborne = src.airborne; dst.braced = src.braced
        dst.pumping = src.pumping; dst.knockedDown = src.knockedDown
        dst.malice = src.malice; dst.fury = src.fury
        dst.seaState = src.seaState; dst.stormTarget = src.stormTarget
        dst.windSpeed = src.windSpeed; dst.windDir = src.windDir
        dst.snaredSpell = src.snaredSpell; dst.snareTimer = src.snareTimer
        dst.krakenActive = src.krakenActive
        dst.oceanTime = src.oceanTime; dst.windTime = src.windTime
        System.arraycopy(src.spellCooldown, 0, dst.spellCooldown, 0, src.spellCooldown.size)
        System.arraycopy(src.deployCooldown, 0, dst.deployCooldown, 0, src.deployCooldown.size)
        for (i in src.pulse.indices) System.arraycopy(src.pulse[i], 0, dst.pulse[i], 0, 6)
        for (i in src.calm.indices) System.arraycopy(src.calm[i], 0, dst.calm[i], 0, 4)
        for (i in src.squall.indices) System.arraycopy(src.squall[i], 0, dst.squall[i], 0, 6)
        dst.entityCount = src.entityCount
        for (i in 0 until src.entityCount) copyEntity(src.entities[i], dst.entities[i])
        dst.spellCount = src.spellCount
        for (i in 0 until src.spellCount) {
            val s = src.spellZones[i]; val d = dst.spellZones[i]
            d.id = s.id; d.kind = s.kind; d.x = s.x; d.y = s.y; d.radius = s.radius; d.t = s.t
        }
        dst.eventCount = 0
    }

    private fun copyEntity(s: EntityRec, d: EntityRec) {
        d.id = s.id; d.kind = s.kind
        d.x = s.x; d.y = s.y; d.vx = s.vx; d.vy = s.vy
        d.angle = s.angle; d.hp = s.hp
        d.stunned = s.stunned; d.grabbing = s.grabbing; d.phase = s.phase
    }
}
