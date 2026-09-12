package com.polariz.aethertides.shared.net

import com.polariz.aethertides.shared.math.MathX
import com.polariz.aethertides.shared.ocean.Ocean
import com.polariz.aethertides.shared.ocean.Wind
import com.polariz.aethertides.shared.sim.Config
import com.polariz.aethertides.shared.sim.DeployKind
import com.polariz.aethertides.shared.sim.EntityKind
import com.polariz.aethertides.shared.sim.EventKind
import com.polariz.aethertides.shared.sim.MatchPhase
import com.polariz.aethertides.shared.sim.SpellKind
import com.polariz.aethertides.shared.sim.World

/** One replicated entity. Fixed width so a frame costs a predictable number of bytes. */
class EntityRec {
    @JvmField var id = 0
    @JvmField var kind = EntityKind.DEBRIS
    @JvmField var x = 0f
    @JvmField var y = 0f
    @JvmField var vx = 0f
    @JvmField var vy = 0f
    @JvmField var angle = 0f
    @JvmField var hp = 1f
    @JvmField var stunned = false
    @JvmField var grabbing = false
    @JvmField var phase = 0f
}

class SpellRec {
    @JvmField var id = 0
    @JvmField var kind = SpellKind.GALE
    @JvmField var x = 0f
    @JvmField var y = 0f
    @JvmField var radius = 0f
    @JvmField var t = 0f
}

class EventRec {
    @JvmField var kind = EventKind.SPLASH
    @JvmField var x = 0f
    @JvmField var y = 0f
    @JvmField var x2 = 0f
    @JvmField var y2 = 0f
    @JvmField var magnitude = 0f
    @JvmField var hasLine = false
}

/**
 * A complete picture of the match at one server tick.
 *
 * Clients keep the last two frames and render between them, which turns a 20 Hz feed into
 * smooth motion at whatever the display runs at. The navigator additionally re-predicts their
 * own hull forward from the newest frame so the helm feels instant regardless of ping.
 */
class SnapshotFrame {
    @JvmField var tick = 0L
    @JvmField var time = 0f            // server match time, seconds
    @JvmField var phase = MatchPhase.WAITING
    @JvmField var phaseTimer = 0f

    // ship
    @JvmField var shipX = 0f
    @JvmField var shipY = 0f
    @JvmField var shipVx = 0f
    @JvmField var shipVy = 0f
    @JvmField var shipAngle = 0f
    @JvmField var shipAngVel = 0f
    @JvmField var hull = 100f
    @JvmField var mast = 100f
    @JvmField var aether = 100f
    @JvmField var bilge = 0f
    @JvmField var leaks = 0
    @JvmField var trim = 0f
    @JvmField var lean = 0f
    @JvmField var surf = 0f
    @JvmField var submerged = 0f
    @JvmField var airborne = false
    @JvmField var braced = false
    @JvmField var pumping = false
    @JvmField var knockedDown = false
    @JvmField var apparentWind = 0f
    /** Seconds until she can brace again. The player has to be able to see this. */
    @JvmField var braceCooldown = 0f

    // economies
    @JvmField var malice = 0f
    @JvmField var fury = 0f
    @JvmField var seaState = 0f
    @JvmField var stormTarget = 0f
    @JvmField var windSpeed = 0f
    @JvmField var windDir = 1f
    @JvmField var snaredSpell = -1
    @JvmField var snareTimer = 0f
    @JvmField var krakenActive = false
    @JvmField var ackSeq = 0

    @JvmField val spellCooldown = FloatArray(4)
    @JvmField val deployCooldown = FloatArray(DeployKind.entries.size)

    // ocean transients
    @JvmField var oceanTime = 0f
    @JvmField val pulse = Array(Ocean.MAX_PULSES) { FloatArray(6) }   // x, amp, width, speed, life, maxLife
    @JvmField val calm = Array(Ocean.MAX_CALMS) { FloatArray(4) }     // x, r, strength, life
    @JvmField var windTime = 0f
    @JvmField val squall = Array(Wind.MAX_SQUALLS) { FloatArray(6) }  // x, r, strength, life, maxLife, drift

    @JvmField var entityCount = 0
    @JvmField val entities = Array(Config.MAX_ENTITIES) { EntityRec() }

    @JvmField var spellCount = 0
    @JvmField val spellZones = Array(Config.MAX_SPELLS) { SpellRec() }

    @JvmField var eventCount = 0
    @JvmField val events = Array(48) { EventRec() }

    fun findEntity(id: Int): EntityRec? {
        for (i in 0 until entityCount) if (entities[i].id == id) return entities[i]
        return null
    }
}

/**
 * Snapshot codec.
 *
 * Positions go as float32 because the hull rides them and a visible quantisation step in the
 * water line would be obvious. Velocities, angles and every 0..1 bar are quantised, which is
 * where most of the saving comes from.
 */
object Snapshot {

    fun encode(w: World, ackSeq: Int, out: ByteWriter): ByteArray {
        out.reset()
        out.u8(Msg.SNAPSHOT)
        out.i64(w.tick)
        out.f32(w.elapsed)
        out.u8(w.phase.id)
        out.q16(w.phaseTimer, 0f, 16f)
        out.i32(ackSeq)

        val s = w.ship
        out.f32(s.x); out.f32(s.y)
        out.q16(s.vx, -60f, 60f); out.q16(s.vy, -60f, 60f)
        out.q16(s.angle, -MathX.PI, MathX.PI)
        out.q16(s.angVel, -18f, 18f)
        out.q8(s.hull / Config.MAX_HULL)
        out.q8(s.mast / Config.MAX_MAST)
        out.q8(s.aether / Config.MAX_AETHER)
        out.q8(s.bilge / Config.MAX_BILGE)
        out.u8(s.leaks)
        out.q8(s.trim)
        out.q8((s.lean + 1f) * 0.5f)
        out.q8(s.surf)
        out.q8(s.submergedFraction)
        out.q16(s.apparentWind, 0f, 60f)
        out.q8(s.braceCooldown / Config.BRACE_COOLDOWN)
        var flags = 0
        if (s.airborne) flags = flags or 1
        if (s.braceTimer > 0f) flags = flags or 2
        if (s.pumping) flags = flags or 4
        if (s.knockedDown) flags = flags or 8
        if (w.krakenActive) flags = flags or 16
        out.u8(flags)

        out.q8(w.malice / Config.MAX_MALICE)
        out.q8(w.fury / Config.MAX_FURY)
        out.q8(w.ocean.seaState)
        out.q8(w.stormTarget)
        out.q16(w.wind.baseSpeed, 0f, 45f)
        out.i8(if (w.wind.baseDir >= 0f) 1 else -1)
        out.i8(w.snaredSpell)
        out.q8(w.snareTimer / Config.SNARE_DURATION)

        for (i in 0 until 4) out.q8(w.spellCooldown[i] / 12f)
        for (i in w.deployCooldown.indices) out.q8(w.deployCooldown[i] / 20f)

        // --- ocean transients --------------------------------------------
        out.f32(w.ocean.time)
        for (i in 0 until Ocean.MAX_PULSES) {
            val alive = w.ocean.pulseAlive(i)
            out.bool(alive)
            if (!alive) continue
            out.f32(w.ocean.pulseX(i))
            out.q16(w.ocean.pulseAmp(i), 0f, 14f)
            out.q16(w.ocean.pulseWidth(i), 0f, 80f)
            out.q16(w.ocean.pulseSpeed(i), -40f, 40f)
            out.q16(w.ocean.pulseLife(i), 0f, 30f)
            out.q16(w.ocean.pulseMaxLife(i), 0f, 30f)
        }
        for (i in 0 until Ocean.MAX_CALMS) {
            val alive = w.ocean.calmAlive(i)
            out.bool(alive)
            if (!alive) continue
            out.f32(w.ocean.calmX(i))
            out.q16(w.ocean.calmR(i), 0f, 120f)
            out.q8(w.ocean.calmStrength(i))
            out.q16(w.ocean.calmLife(i), 0f, 30f)
        }
        out.f32(w.wind.time)
        for (i in 0 until Wind.MAX_SQUALLS) {
            val alive = w.wind.squallAlive(i)
            out.bool(alive)
            if (!alive) continue
            out.f32(w.wind.squallX(i))
            out.q16(w.wind.squallR(i), 0f, 200f)
            out.q8(w.wind.squallStrength(i) / 2.5f)
            out.q16(w.wind.squallLife(i), 0f, 40f)
            out.q16(w.wind.squallMaxLife(i), 0f, 40f)
            out.q16(w.wind.squallDrift(i), -30f, 30f)
        }

        // --- entities -----------------------------------------------------
        var n = 0
        for (e in w.entities) if (e.alive) n++
        out.u8(minOf(n, 255))
        var written = 0
        for (e in w.entities) {
            if (!e.alive || written >= 255) continue
            written++
            out.u16(e.id and 0xFFFF)
            out.u8(e.kind.id)
            out.f32(e.x); out.f32(e.y)
            out.q16(e.vx, -70f, 70f); out.q16(e.vy, -70f, 70f)
            out.q16(e.angle, -MathX.PI, MathX.PI)
            out.q8(if (e.maxHp > 0f) e.hp / e.maxHp else 1f)
            var f = 0
            if (e.stun > 0f) f = f or 1
            if (e.kind == EntityKind.TENTACLE && e.timer > 0f) f = f or 2
            out.u8(f)
            out.q16(e.phase % 64f, -1f, 64f)
        }

        // --- spell zones ---------------------------------------------------
        var sn = 0
        for (z in w.spells) if (z.alive) sn++
        out.u8(sn)
        for (z in w.spells) {
            if (!z.alive) continue
            out.u16(z.id and 0xFFFF)
            out.u8(z.kind.id)
            out.f32(z.x); out.f32(z.y)
            out.q16(z.radius, 0f, 80f)
            out.q8(z.t())
        }

        // --- events ---------------------------------------------------------
        val ec = minOf(w.eventCount(), 48)
        out.u8(ec)
        for (i in 0 until ec) {
            val ev = w.eventAt(i)
            out.u8(ev.kind.id)
            out.f32(ev.x); out.f32(ev.y)
            out.q16(ev.magnitude, -8f, 32f)
            out.bool(ev.hasLine)
            if (ev.hasLine) { out.f32(ev.x2); out.f32(ev.y2) }
        }

        return out.bytes()
    }

    /** Decode into a reusable frame. The message id byte has already been consumed. */
    fun decode(r: ByteReader, f: SnapshotFrame): SnapshotFrame {
        f.tick = r.i64()
        f.time = r.f32()
        f.phase = MatchPhase.of(r.u8())
        f.phaseTimer = r.q16(0f, 16f)
        f.ackSeq = r.i32()

        f.shipX = r.f32(); f.shipY = r.f32()
        f.shipVx = r.q16(-60f, 60f); f.shipVy = r.q16(-60f, 60f)
        f.shipAngle = r.q16(-MathX.PI, MathX.PI)
        f.shipAngVel = r.q16(-18f, 18f)
        f.hull = r.q8() * Config.MAX_HULL
        f.mast = r.q8() * Config.MAX_MAST
        f.aether = r.q8() * Config.MAX_AETHER
        f.bilge = r.q8() * Config.MAX_BILGE
        f.leaks = r.u8()
        f.trim = r.q8()
        f.lean = r.q8() * 2f - 1f
        f.surf = r.q8()
        f.submerged = r.q8()
        f.apparentWind = r.q16(0f, 60f)
        f.braceCooldown = r.q8() * Config.BRACE_COOLDOWN
        val flags = r.u8()
        f.airborne = (flags and 1) != 0
        f.braced = (flags and 2) != 0
        f.pumping = (flags and 4) != 0
        f.knockedDown = (flags and 8) != 0
        f.krakenActive = (flags and 16) != 0

        f.malice = r.q8() * Config.MAX_MALICE
        f.fury = r.q8() * Config.MAX_FURY
        f.seaState = r.q8()
        f.stormTarget = r.q8()
        f.windSpeed = r.q16(0f, 45f)
        f.windDir = r.i8().toFloat()
        f.snaredSpell = r.i8()
        f.snareTimer = r.q8() * Config.SNARE_DURATION

        for (i in 0 until 4) f.spellCooldown[i] = r.q8() * 12f
        for (i in f.deployCooldown.indices) f.deployCooldown[i] = r.q8() * 20f

        f.oceanTime = r.f32()
        for (i in 0 until Ocean.MAX_PULSES) {
            val p = f.pulse[i]
            if (!r.bool()) { p[4] = 0f; continue }
            p[0] = r.f32()
            p[1] = r.q16(0f, 14f)
            p[2] = r.q16(0f, 80f)
            p[3] = r.q16(-40f, 40f)
            p[4] = r.q16(0f, 30f)
            p[5] = r.q16(0f, 30f)
        }
        for (i in 0 until Ocean.MAX_CALMS) {
            val c = f.calm[i]
            if (!r.bool()) { c[3] = 0f; continue }
            c[0] = r.f32()
            c[1] = r.q16(0f, 120f)
            c[2] = r.q8()
            c[3] = r.q16(0f, 30f)
        }
        f.windTime = r.f32()
        for (i in 0 until Wind.MAX_SQUALLS) {
            val s = f.squall[i]
            if (!r.bool()) { s[3] = 0f; continue }
            s[0] = r.f32()
            s[1] = r.q16(0f, 200f)
            s[2] = r.q8() * 2.5f
            s[3] = r.q16(0f, 40f)
            s[4] = r.q16(0f, 40f)
            s[5] = r.q16(-30f, 30f)
        }

        f.entityCount = r.u8()
        for (i in 0 until f.entityCount) {
            val e = f.entities[i]
            e.id = r.u16()
            e.kind = EntityKind.of(r.u8())
            e.x = r.f32(); e.y = r.f32()
            e.vx = r.q16(-70f, 70f); e.vy = r.q16(-70f, 70f)
            e.angle = r.q16(-MathX.PI, MathX.PI)
            e.hp = r.q8()
            val ef = r.u8()
            e.stunned = (ef and 1) != 0
            e.grabbing = (ef and 2) != 0
            e.phase = r.q16(-1f, 64f)
        }

        f.spellCount = r.u8()
        for (i in 0 until f.spellCount) {
            val z = f.spellZones[i]
            z.id = r.u16()
            z.kind = SpellKind.of(r.u8())
            z.x = r.f32(); z.y = r.f32()
            z.radius = r.q16(0f, 80f)
            z.t = r.q8()
        }

        f.eventCount = minOf(r.u8(), f.events.size)
        for (i in 0 until f.eventCount) {
            val ev = f.events[i]
            ev.kind = EventKind.of(r.u8())
            ev.x = r.f32(); ev.y = r.f32()
            ev.magnitude = r.q16(-8f, 32f)
            ev.hasLine = r.bool()
            if (ev.hasLine) { ev.x2 = r.f32(); ev.y2 = r.f32() }
        }
        return f
    }

    /**
     * Push authoritative state into a client-side world so it can keep predicting from there.
     * Entities and spells are not written back: the renderer draws those straight from the
     * interpolated frames instead, which avoids two systems fighting over the same objects.
     */
    fun applyTo(f: SnapshotFrame, w: World, applyShip: Boolean) {
        w.tick = f.tick
        w.elapsed = f.time
        w.phase = f.phase
        w.phaseTimer = f.phaseTimer
        w.malice = f.malice
        w.fury = f.fury
        w.stormTarget = f.stormTarget
        w.snaredSpell = f.snaredSpell
        w.snareTimer = f.snareTimer
        w.krakenActive = f.krakenActive
        for (i in 0 until 4) w.spellCooldown[i] = f.spellCooldown[i]
        for (i in w.deployCooldown.indices) w.deployCooldown[i] = f.deployCooldown[i]

        w.ocean.seaState = f.seaState
        w.ocean.syncTime(f.oceanTime)
        for (i in 0 until Ocean.MAX_PULSES) {
            val p = f.pulse[i]
            w.ocean.setPulse(i, p[0], p[1], p[2], p[3], p[4], p[5])
        }
        for (i in 0 until Ocean.MAX_CALMS) {
            val c = f.calm[i]
            w.ocean.setCalm(i, c[0], c[1], c[2], c[3])
        }
        w.wind.syncTime(f.windTime)
        w.wind.baseSpeed = f.windSpeed
        w.wind.baseDir = f.windDir
        for (i in 0 until Wind.MAX_SQUALLS) {
            val s = f.squall[i]
            w.wind.setSquall(i, s[0], s[1], s[2], s[3], s[4], s[5])
        }

        if (applyShip) {
            val s = w.ship
            s.x = f.shipX; s.y = f.shipY
            s.vx = f.shipVx; s.vy = f.shipVy
            s.angle = f.shipAngle; s.angVel = f.shipAngVel
            s.hull = f.hull; s.mast = f.mast; s.aether = f.aether
            s.bilge = f.bilge; s.leaks = f.leaks
        }
    }
}
