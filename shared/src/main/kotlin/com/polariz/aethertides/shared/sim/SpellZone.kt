package com.polariz.aethertides.shared.sim

import com.polariz.aethertides.shared.math.MathX
import com.polariz.aethertides.shared.ocean.WaveSample
import kotlin.math.abs
import kotlin.math.sign

/** A live aether working on the water. Pooled like entities; never allocated mid match. */
class SpellZone {
    @JvmField var id = 0
    @JvmField var kind = SpellKind.GALE
    @JvmField var alive = false
    @JvmField var x = 0f
    @JvmField var y = 0f
    @JvmField var vx = 0f
    @JvmField var vy = 0f
    @JvmField var radius = 0f
    @JvmField var life = 0f
    @JvmField var maxLife = 0f
    @JvmField var phase = 0f
    /** Set once a zone has been consumed by a fusion so it cannot fuse twice in one tick. */
    @JvmField var fused = false
    /** Set for the tick a zone applies its one-shot effect (bolt, tsunami). */
    @JvmField var discharged = false

    fun reset(k: SpellKind, px: Float, py: Float) {
        val def = Spells[k]
        kind = k
        alive = true
        x = px; y = py
        vx = 0f; vy = 0f
        radius = def.radius
        life = def.duration
        maxLife = def.duration
        phase = 0f
        fused = false
        discharged = false
    }

    fun t(): Float = if (maxLife > 0f) MathX.clamp01(life / maxLife) else 0f
}

/**
 * What each working actually does to the world.
 *
 * The base four are deliberately non-overlapping in role: Gale is displacement, Mist is
 * denial, Void is absorption, Bolt is removal. The interesting play is in the three fusions,
 * which only happen if you place two zones so they overlap before either expires -- a cost in
 * aether and in timing, paid for with an effect neither spell has alone.
 */
object SpellEffects {

    private val ws = WaveSample()

    fun update(z: SpellZone, w: World, dt: Float) {
        z.life -= dt
        z.phase += dt
        z.x += z.vx * dt
        z.y += z.vy * dt
        if (z.life <= 0f) { z.alive = false; return }

        when (z.kind) {
            SpellKind.GALE -> gale(z, w, dt)
            SpellKind.MIST -> mist(z, w, dt)
            SpellKind.VOID -> vortex(z, w, dt)
            SpellKind.BOLT -> bolt(z, w, dt)
            SpellKind.VOLTAIC_MIST -> voltaic(z, w, dt)
            SpellKind.SINGULARITY -> singularity(z, w, dt)
            SpellKind.TSUNAMI -> tsunami(z, w, dt)
        }
    }

    /** Called once when the zone is created, for spells with an immediate world effect. */
    fun onCast(z: SpellZone, w: World) {
        when (z.kind) {
            SpellKind.MIST -> w.ocean.addCalm(z.x, z.radius * 1.6f, 0.72f, z.maxLife)
            SpellKind.VOLTAIC_MIST -> w.ocean.addCalm(z.x, z.radius * 1.5f, 0.6f, z.maxLife)
            SpellKind.TSUNAMI -> {
                // A swell of your own: wide, fast and rideable, unlike the Tempest rogue wave.
                w.ocean.addPulse(z.x - 26f, 3.6f, 26f, 17f, 6.5f)
                w.event(EventKind.ROGUE_WAVE, z.x, z.y, 1f)
            }
            SpellKind.SINGULARITY -> z.vx = 26f
            else -> {}
        }
    }

    // --- base four ---------------------------------------------------------

    private fun gale(z: SpellZone, w: World, dt: Float) {
        val force = Spells[SpellKind.GALE].force
        w.forEachAlive { e ->
            val d = MathX.dist(e.x, e.y, z.x, z.y)
            if (d >= z.radius) return@forEachAlive
            if (e.kind == EntityKind.REEF_SPIKE || e.kind == EntityKind.KRAKEN) return@forEachAlive
            val f = MathX.falloff(d, z.radius) * force
            val dx = if (abs(e.x - z.x) < 0.01f) 1f else (e.x - z.x)
            val dy = (e.y - z.y)
            val l = MathX.len(dx, dy).coerceAtLeast(0.01f)
            e.vx += dx / l * f * dt
            e.vy += dy / l * f * dt * 1.35f
            // Tentacles cannot be blown off, but the grip is loosened.
            if (e.kind == EntityKind.TENTACLE) e.timer = maxOf(0f, e.timer - dt * 0.8f)
        }

        // Cast astern, it fills the sail. This is the navigator movement tool, and the reason
        // Gale is cheap: using it well is a sailing decision, not a targeting one.
        val s = w.ship
        val behind = s.x - z.x
        if (behind in 0f..z.radius * 2.2f && abs(s.y - z.y) < z.radius * 1.6f) {
            val f = MathX.falloff(behind, z.radius * 2.2f)
            s.thrustMult *= 1f + 1.5f * f
            s.applyImpulse(f * s.mass() * 0.9f * dt, f * s.mass() * 0.18f * dt)
        }
    }

    private fun mist(z: SpellZone, w: World, dt: Float) {
        w.forEachAlive { e ->
            val d = MathX.dist(e.x, e.y, z.x, z.y)
            if (d >= z.radius) return@forEachAlive
            val f = MathX.falloff(d, z.radius)
            e.slow *= 1f - 0.62f * f
            e.vx -= e.vx * f * 2.2f * dt
            e.vy -= e.vy * f * 2.2f * dt
            // Skimmers fly by ground effect. Take the horizon away and they go in.
            if (e.kind == EntityKind.SKIMMER && f > 0.25f) {
                w.event(EventKind.SPLASH, e.x, e.y, 0.7f)
                w.corsairDown(e)
            }
        }
        // Flat water is also fast water for a hull that is not trying to jump.
        val s = w.ship
        if (abs(s.x - z.x) < z.radius) s.dragMult *= 0.86f
    }

    private fun vortex(z: SpellZone, w: World, dt: Float) {
        val force = Spells[SpellKind.VOID].force
        w.forEachAlive { e ->
            val d = MathX.dist(e.x, e.y, z.x, z.y)
            if (d >= z.radius) return@forEachAlive
            if (e.kind == EntityKind.KRAKEN) return@forEachAlive
            // A gravity well is the one working that can tear a reef head off the seabed.
            // Gale cannot move it and lightning will not break it -- Void is its answer, and
            // knowing that is most of what makes a reef field survivable.
            if (e.kind == EntityKind.REEF_SPIKE) {
                if (d < z.radius * 0.8f) {
                    w.event(EventKind.EXPLOSION, e.x, e.y, 0.5f)
                    w.spawnDebris(e.x, e.y, 3)
                    w.stats.absorbed++
                    e.kill()
                }
                return@forEachAlive
            }
            val f = MathX.falloff(d, z.radius) * force
            var dx = z.x - e.x
            var dy = z.y - e.y
            val l = MathX.len(dx, dy).coerceAtLeast(0.01f)
            dx /= l; dy /= l
            // Radial pull plus a tangential shear, so things spiral instead of collapsing to
            // a point and clipping through each other.
            e.vx += (dx - dy * 0.85f) * f * dt
            e.vy += (dy + dx * 0.85f) * f * dt
            if (d < 3.2f) {
                when (e.kind) {
                    EntityKind.BOMB, EntityKind.DRIFT_MINE -> {
                        w.event(EventKind.EXPLOSION, z.x, z.y, 0.45f)
                        e.kill()
                        w.stats.absorbed++
                    }
                    EntityKind.SEEKER, EntityKind.SKIMMER, EntityKind.BOMBER -> w.corsairDown(e)
                    else -> {}
                }
            }
        }
    }

    private fun bolt(z: SpellZone, w: World, dt: Float) {
        if (z.discharged) return
        z.discharged = true
        // Lightning arcs from target to target. Each jump costs a little reach, so a tight
        // flight of corsairs dies in one cast and a spread one does not.
        var srcX = z.x
        var srcY = z.y
        var reach = z.radius
        var jumps = 0
        while (jumps < 5 && reach > 3f) {
            var best: Entity? = null
            var bestD = Float.MAX_VALUE
            w.forEachAlive { e ->
                if (e.kind == EntityKind.AETHER_MOTE || e.kind == EntityKind.SALVAGE_CRATE ||
                    e.kind == EntityKind.DEBRIS || e.kind == EntityKind.REEF_SPIKE
                ) return@forEachAlive
                val d = MathX.dist(e.x, e.y, srcX, srcY)
                if (d < reach && d < bestD) { bestD = d; best = e }
            }
            val target = best ?: break
            w.event(EventKind.LIGHTNING, srcX, srcY, 0f)
            w.eventLine(srcX, srcY, target.x, target.y)
            when (target.kind) {
                EntityKind.SEEKER, EntityKind.SKIMMER, EntityKind.BOMBER -> w.corsairDown(target)
                EntityKind.BOMB -> { w.explode(target.x, target.y, 5f, 0f, DamageCause.BOMB, 4000f); target.kill() }
                EntityKind.DRIFT_MINE -> Behaviour.detonateMine(target, w)
                EntityKind.TENTACLE -> {
                    target.hp -= 1f
                    target.stun = 2.4f
                    target.timer = 0f
                    w.event(EventKind.TENTACLE_BREAK, target.x, target.y, 0f)
                    if (target.hp <= 0f) target.kill()
                }
                EntityKind.ICE_FLOE -> { target.hp -= 1f; if (target.hp <= 0f) { w.spawnDebris(target.x, target.y, 4); target.kill() } }
                EntityKind.KRAKEN -> { target.timer += 1.2f; target.stun = 0.8f }
                else -> target.kill()
            }
            srcX = target.x
            srcY = target.y
            reach *= 0.72f
            jumps++
        }
        if (jumps == 0) w.event(EventKind.LIGHTNING, z.x, z.y, 0f)
    }

    // --- fusions -----------------------------------------------------------

    private fun voltaic(z: SpellZone, w: World, dt: Float) {
        w.forEachAlive { e ->
            val d = MathX.dist(e.x, e.y, z.x, z.y)
            if (d >= z.radius) return@forEachAlive
            val f = MathX.falloff(d, z.radius)
            e.slow *= 1f - 0.85f * f
            if (e.kind.isCorsair) {
                e.stun = maxOf(e.stun, 0.6f)
                e.hp -= 2.4f * f * dt
                if (e.hp <= 0f) w.corsairDown(e)
            }
            if (e.kind == EntityKind.TENTACLE) {
                e.stun = maxOf(e.stun, 0.7f)
                e.timer = 0f
                e.hp -= 1.4f * f * dt
                if (e.hp <= 0f) { w.event(EventKind.TENTACLE_BREAK, e.x, e.y, 0f); e.kill() }
            }
            if (e.kind == EntityKind.DRIFT_MINE && f > 0.4f) Behaviour.detonateMine(e, w)
            if (e.kind == EntityKind.KRAKEN) e.timer += dt * 0.8f
        }
        if (z.phase % 0.45f < dt) w.event(EventKind.LIGHTNING, z.x + (z.phase * 37f) % z.radius - z.radius * 0.5f, z.y, 0f)
    }

    private fun singularity(z: SpellZone, w: World, dt: Float) {
        w.ocean.sample(z.x, ws)
        z.y = MathX.approach(z.y, ws.y + 4f, 2.5f, dt)
        w.forEachAlive { e ->
            val d = MathX.dist(e.x, e.y, z.x, z.y)
            if (d >= z.radius) return@forEachAlive
            if (e.kind == EntityKind.KRAKEN) { e.timer += dt * 1.4f; return@forEachAlive }
            if (e.kind == EntityKind.REEF_SPIKE) {
                if (d < z.radius * 0.7f) { w.spawnDebris(e.x, e.y, 3); e.kill() }
                return@forEachAlive
            }
            val f = MathX.falloff(d, z.radius) * Spells[SpellKind.SINGULARITY].force
            var dx = z.x - e.x
            var dy = z.y - e.y
            val l = MathX.len(dx, dy).coerceAtLeast(0.01f)
            e.vx += dx / l * f * dt
            e.vy += dy / l * f * dt
            if (d < 4.5f) {
                w.stats.absorbed++
                if (e.kind == EntityKind.DRIFT_MINE) w.event(EventKind.EXPLOSION, e.x, e.y, 0.35f)
                if (e.kind.isCorsair) w.corsairDown(e) else e.kill()
            }
        }
        // It scours the water it passes over.
        w.ocean.addCalm(z.x, 12f, 0.45f, 0.4f)
    }

    private fun tsunami(z: SpellZone, w: World, dt: Float) {
        z.vx = 17f
        w.ocean.sample(z.x, ws)
        z.y = ws.y
        w.forEachAlive { e ->
            val d = abs(e.x - z.x)
            if (d >= z.radius) return@forEachAlive
            if (e.kind == EntityKind.KRAKEN) return@forEachAlive
            val f = MathX.falloff(d, z.radius)
            if (e.kind == EntityKind.REEF_SPIKE) {
                if (f > 0.55f) { w.spawnDebris(e.x, e.y, 3); e.kill() }
                return@forEachAlive
            }
            // Everything loose on the surface gets thrown downcourse and under.
            e.vx += f * Spells[SpellKind.TSUNAMI].force * dt
            e.vy += f * 12f * dt
            if (e.kind == EntityKind.DRIFT_MINE && f > 0.75f) Behaviour.detonateMine(e, w)
            if (e.kind == EntityKind.ICE_FLOE && f > 0.7f) { w.spawnDebris(e.x, e.y, 4); e.kill() }
            if (e.kind == EntityKind.TENTACLE && f > 0.6f) { e.timer = 0f; e.stun = 1.4f }
        }
        // Ahead of the wall the water is drawn back, behind it she gets a hard shove.
        val s = w.ship
        val rel = s.x - z.x
        if (rel > -6f && rel < z.radius) {
            val f = MathX.falloff(abs(rel), z.radius)
            s.thrustMult *= 1f + 0.9f * f
            s.applyImpulse(f * s.mass() * 0.55f * dt, 0f)
        }
    }

    /**
     * Fusion check. Two live base zones whose discs overlap by more than a third of their
     * combined radius collapse into a single greater working at the midpoint.
     */
    fun checkFusions(w: World) {
        val zones = w.spells
        for (i in zones.indices) {
            val a = zones[i]
            if (!a.alive || a.fused || !a.kind.isBase) continue
            for (j in i + 1 until zones.size) {
                val b = zones[j]
                if (!b.alive || b.fused || !b.kind.isBase) continue
                val fusion = Spells.synergy(a.kind, b.kind) ?: continue
                val d = MathX.dist(a.x, a.y, b.x, b.y)
                if (d > (a.radius + b.radius) * 0.62f) continue

                a.fused = true; b.fused = true
                a.alive = false; b.alive = false
                val mx = (a.x + b.x) * 0.5f
                val my = (a.y + b.y) * 0.5f
                val z = w.spawnSpell(fusion, mx, my)
                if (z != null) {
                    if (fusion == SpellKind.SINGULARITY) {
                        // The lance is fired along the line from the gale to the void.
                        val gale = if (a.kind == SpellKind.GALE) a else b
                        val well = if (a.kind == SpellKind.VOID) a else b
                        z.vx = sign(well.x - gale.x).let { if (it == 0f) 1f else it } * 26f
                    }
                    w.event(EventKind.SYNERGY, mx, my, fusion.id.toFloat())
                    w.stats.fusions++
                }
                break
            }
        }
    }
}
