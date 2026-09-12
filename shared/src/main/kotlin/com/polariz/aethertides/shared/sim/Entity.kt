package com.polariz.aethertides.shared.sim

import com.polariz.aethertides.shared.math.MathX
import com.polariz.aethertides.shared.ocean.WaveSample
import kotlin.math.abs
import kotlin.math.sign

/**
 * Everything in the world that is not the ship.
 *
 * One flat class rather than a subclass per hazard: the world holds a fixed pool of these,
 * the snapshot codec writes them as fixed-width records, and nothing allocates mid match.
 * Behaviour branches on `kind` in [Behaviour].
 */
class Entity {
    @JvmField var id = 0
    @JvmField var kind = EntityKind.DEBRIS
    @JvmField var alive = false

    @JvmField var x = 0f
    @JvmField var y = 0f
    @JvmField var vx = 0f
    @JvmField var vy = 0f
    @JvmField var angle = 0f
    @JvmField var angVel = 0f

    @JvmField var hp = 1f
    @JvmField var maxHp = 1f
    @JvmField var radius = 1f
    @JvmField var age = 0f
    /** Seconds remaining; negative means it lives until something kills it. */
    @JvmField var life = -1f

    /** Per-kind scratch: bomb fuse, tentacle grip, mine arming delay, kraken swipe timer. */
    @JvmField var timer = 0f
    /** Per-kind secondary scratch, mostly animation phase. */
    @JvmField var phase = 0f
    /** Stun seconds remaining. Voltaic mist and lightning write this. */
    @JvmField var stun = 0f
    /** Movement slow multiplier applied by mist and void. Reset each tick. */
    @JvmField var slow = 1f
    /** Flock id so corsairs from one deployment stay together. */
    @JvmField var flock = 0
    /** True once it has done its damage and is only waiting to be reaped. */
    @JvmField var spent = false

    fun reset(k: EntityKind, px: Float, py: Float) {
        kind = k
        alive = true
        x = px; y = py
        vx = 0f; vy = 0f
        angle = 0f; angVel = 0f
        age = 0f; life = -1f
        timer = 0f; phase = 0f; stun = 0f; slow = 1f
        spent = false
        flock = 0
        when (k) {
            EntityKind.DRIFT_MINE -> { hp = 1f; radius = 1.35f; timer = 0.8f }
            EntityKind.REEF_SPIKE -> { hp = 1f; radius = 2.2f }
            EntityKind.ICE_FLOE -> { hp = 2f; radius = 4.2f }
            EntityKind.MAELSTROM -> { hp = 1f; radius = 16f; life = 13f }
            EntityKind.TENTACLE -> { hp = 3f; radius = 3.4f; life = 26f }
            EntityKind.SEEKER -> { hp = 1f; radius = 1.5f; life = 26f }
            EntityKind.BOMBER -> { hp = 2f; radius = 2.4f; timer = 2.4f; life = 24f; phase = 4f }
            EntityKind.SKIMMER -> { hp = 1f; radius = 1.3f; life = 24f }
            EntityKind.BOMB -> { hp = 1f; radius = 0.9f; timer = Config.BOMB_FUSE }
            EntityKind.AETHER_MOTE -> { hp = 1f; radius = 1.6f; life = 36f }
            EntityKind.SALVAGE_CRATE -> { hp = 1f; radius = 1.5f; life = 46f }
            EntityKind.KRAKEN -> { hp = 999f; radius = 11f; life = Config.KRAKEN_DURATION; timer = 3.6f }
            EntityKind.DEBRIS -> { hp = 1f; radius = 0.7f; life = 9f }
        }
        maxHp = hp
    }

    fun kill() { alive = false }
}

/**
 * Entity behaviour.
 *
 * Split out of [World] so the world step reads as a sequence of phases instead of one
 * thousand-line switch. Everything here may read the world but only writes the entity it was
 * handed, the ship, and the event sink.
 */
object Behaviour {

    private val ws = WaveSample()

    fun update(e: Entity, w: World, dt: Float) {
        e.age += dt
        if (e.life > 0f) {
            e.life -= dt
            if (e.life <= 0f) { e.kill(); return }
        }
        if (e.stun > 0f) e.stun -= dt

        when (e.kind) {
            EntityKind.DRIFT_MINE -> mine(e, w, dt)
            EntityKind.REEF_SPIKE -> reef(e, w, dt)
            EntityKind.ICE_FLOE -> floe(e, w, dt)
            EntityKind.MAELSTROM -> maelstrom(e, w, dt)
            EntityKind.TENTACLE -> tentacle(e, w, dt)
            EntityKind.SEEKER -> seeker(e, w, dt)
            EntityKind.BOMBER -> bomber(e, w, dt)
            EntityKind.SKIMMER -> skimmer(e, w, dt)
            EntityKind.BOMB -> bomb(e, w, dt)
            EntityKind.AETHER_MOTE -> mote(e, w, dt)
            EntityKind.SALVAGE_CRATE -> crate(e, w, dt)
            EntityKind.KRAKEN -> kraken(e, w, dt)
            EntityKind.DEBRIS -> debris(e, w, dt)
        }

        e.slow = 1f
        if (e.x < w.ship.x - Config.CULL_BEHIND) e.kill()
    }

    // --- floating helpers --------------------------------------------------

    /**
     * Make a small object ride the surface. Critically-damped spring to the water line plus
     * the water orbital velocity, so mines and crates get carried along the wave the same way
     * the ship does and the whole surface reads as one moving body of water.
     */
    private fun float(e: Entity, w: World, dt: Float, buoyancy: Float, drift: Float) {
        w.ocean.sample(e.x, ws)
        val target = ws.y + e.radius * 0.15f
        val dy = target - e.y
        e.vy += dy * buoyancy * dt
        e.vy -= (e.vy - ws.vy) * MathX.clamp01(6.5f * dt)
        e.vx -= (e.vx - ws.vx * drift) * MathX.clamp01(2.4f * dt)
        e.x += e.vx * dt * e.slow
        e.y += e.vy * dt
        // Ride the local slope so floating things are visibly tilted on a face.
        val want = MathX.angleOf(1f, ws.dydx)
        e.angle += MathX.angleDelta(e.angle, want) * MathX.clamp01(5f * dt)
    }

    private fun hitsHull(e: Entity, w: World, pad: Float = 0f): Boolean {
        // Test against the hull as a capsule along the keel rather than a fat circle, so
        // near misses past the bow actually miss.
        val s = w.ship
        val half = Config.HULL_LENGTH * 0.5f
        val ax = s.localToWorldX(-half, 0f)
        val ay = s.localToWorldY(-half, 0f)
        val bx = s.localToWorldX(half, 0f)
        val by = s.localToWorldY(half, 0f)
        val abx = bx - ax
        val aby = by - ay
        val len2 = abx * abx + aby * aby
        val t = if (len2 < 1e-5f) 0f else MathX.clamp01(((e.x - ax) * abx + (e.y - ay) * aby) / len2)
        val cx = ax + abx * t
        val cy = ay + aby * t
        val r = e.radius + Config.HULL_DEPTH * 0.55f + pad
        return MathX.dist2(e.x, e.y, cx, cy) <= r * r
    }

    // --- hazards -----------------------------------------------------------

    private fun mine(e: Entity, w: World, dt: Float) {
        if (e.timer > 0f) e.timer -= dt          // arming delay, so a gale can still clear it
        float(e, w, dt, 34f, 1f)
        e.phase += dt
        if (e.timer <= 0f && hitsHull(e, w)) {
            detonateMine(e, w)
        }
    }

    fun detonateMine(e: Entity, w: World) {
        if (e.spent) return
        e.spent = true
        e.kill()
        w.explode(e.x, e.y, 9.5f, Config.DMG_MINE, DamageCause.MINE, 24000f)
        w.event(EventKind.EXPLOSION, e.x, e.y, 1f)
        // Chain reaction: mines set off their neighbours, which is how a mine field kills.
        w.forEachAlive { o ->
            if (o !== e && o.kind == EntityKind.DRIFT_MINE && !o.spent &&
                MathX.dist2(o.x, o.y, e.x, e.y) < 13f * 13f
            ) {
                o.timer = 0f
                o.life = 0.12f + MathX.dist(o.x, o.y, e.x, e.y) * 0.02f
                o.kind = EntityKind.DRIFT_MINE
                o.phase = -1f      // marks it as chaining, for the renderer
            }
        }
    }

    private fun reef(e: Entity, w: World, dt: Float) {
        // Anchored to the seabed: fixed depth below mean sea level, does not ride the swell.
        // In a trough it breaks the surface and is visible; from a crest you never see it.
        e.phase += dt
        if (e.spent) return
        if (hitsHull(e, w)) {
            e.spent = true
            val dealt = w.damageShip(Config.DMG_REEF, DamageCause.REEF)
            w.ship.applyImpulseAt(-w.ship.vx * w.ship.mass() * 0.35f, 2600f, e.x, e.y)
            w.ship.angVel += 0.9f
            if (dealt > Config.BREACH_THRESHOLD) {
                w.ship.addLeak()
                w.event(EventKind.BREACH, e.x, e.y, dealt)
            }
            w.event(EventKind.HULL_IMPACT, e.x, e.y, dealt)
            e.life = 0.6f
        }
    }

    private fun floe(e: Entity, w: World, dt: Float) {
        float(e, w, dt, 26f, 0.85f)
        if (e.spent) return
        if (hitsHull(e, w)) {
            e.hp -= 1f
            e.spent = true
            e.timer = 0.55f
            val dealt = w.damageShip(Config.DMG_FLOE, DamageCause.FLOE)
            val push = -sign(w.ship.vx) * w.ship.mass() * abs(w.ship.vx) * 0.55f
            w.ship.applyImpulseAt(push, 1200f, e.x, e.y)
            w.event(EventKind.HULL_IMPACT, e.x, e.y, dealt)
            if (e.hp <= 0f) {
                w.event(EventKind.EXPLOSION, e.x, e.y, 0.4f)
                w.spawnDebris(e.x, e.y, 5)
                e.kill()
            }
        }
        if (e.spent) {
            e.timer -= dt
            if (e.timer <= 0f) e.spent = false    // re-arm so it can be hit again
        }
    }

    private fun maelstrom(e: Entity, w: World, dt: Float) {
        e.phase += dt * 2.4f
        w.ocean.sample(e.x, ws)
        e.y = ws.y
        val s = w.ship
        val d = MathX.dist(s.x, s.y, e.x, e.y)
        if (d < e.radius) {
            val f = MathX.falloff(d, e.radius)
            // Pull toward the centre, drag her down, and spin her.
            val dirx = (e.x - s.x)
            val pull = f * 46000f
            s.applyImpulse(sign(dirx) * pull * dt * 60f * 0.016f, -f * 17000f * dt * 60f * 0.016f)
            s.dragMult *= 1f + 1.9f * f
            s.angVel += sign(dirx) * f * 0.9f * dt
            if (s.speedKnots < 6f) w.tempestStallCredit(dt * f)
        }
        // It eats floating hazards and gifts alike.
        w.forEachAlive { o ->
            if (o === e) return@forEachAlive
            val od = MathX.dist(o.x, o.y, e.x, e.y)
            if (od < e.radius && o.kind != EntityKind.KRAKEN) {
                val f = MathX.falloff(od, e.radius)
                o.vx += sign(e.x - o.x) * f * 22f * dt
                o.vy -= f * 6f * dt
                if (od < 2.5f && o.kind.isPickup) o.kill()
            }
        }
    }

    private fun tentacle(e: Entity, w: World, dt: Float) {
        w.ocean.sample(e.x, ws)
        e.y = ws.y
        e.phase += dt * 3f
        val s = w.ship
        val grabbing = e.timer > 0f
        val d = abs(s.x - e.x)
        if (!grabbing && d < e.radius + Config.HULL_LENGTH * 0.5f && e.stun <= 0f) {
            e.timer = 1f
            w.event(EventKind.TENTACLE_GRAB, e.x, e.y, 0f)
        }
        if (grabbing) {
            if (e.stun > 0f) {
                e.timer = 0f
                return
            }
            // Hold her back, drag her down by the rail, and squeeze.
            e.x = MathX.approach(e.x, s.x, 4f, dt)
            s.dragMult *= 2.6f
            s.thrustMult *= 0.45f
            s.applyImpulse(-s.mass() * 0.45f * dt * 6f, -s.mass() * 0.22f * dt * 6f)
            s.angVel -= 0.35f * dt
            w.damageShip(Config.DMG_TENTACLE_PER_SEC * dt, DamageCause.TENTACLE)
            w.tempestStallCredit(dt)
        }
    }

    private fun kraken(e: Entity, w: World, dt: Float) {
        w.ocean.sample(e.x, ws)
        e.y = MathX.approach(e.y, ws.y - 2f, 3f, dt)
        e.phase += dt
        val s = w.ship
        // Stalks just ahead of the bow.
        val want = s.x + 26f
        e.x = MathX.approach(e.x, want, 1.35f, dt)
        e.timer -= dt
        if (e.timer <= 0f) {
            e.timer = 4.3f
            if (abs(s.x - e.x) < 34f) {
                w.explode(s.x + 4f, s.y, 14f, Config.DMG_KRAKEN, DamageCause.KRAKEN, 46000f)
                w.event(EventKind.EXPLOSION, s.x, s.y, 1.6f)
                w.ocean.addPulse(s.x - 22f, 3.4f, 17f, 13f, 3.2f)
            }
        }
    }

    // --- corsairs ----------------------------------------------------------

    /**
     * Reynolds flocking. Separation keeps them from stacking into one sprite, alignment and
     * cohesion hold the flight together, and the seek term is what each archetype wants.
     */
    private fun flockSteer(e: Entity, w: World, dt: Float, maxSpeed: Float, maxForce: Float,
                           wantX: Float, wantY: Float) {
        var sepX = 0f; var sepY = 0f
        var aliX = 0f; var aliY = 0f
        var cohX = 0f; var cohY = 0f
        var n = 0
        w.forEachAlive { o ->
            if (o === e || !o.kind.isCorsair || o.flock != e.flock) return@forEachAlive
            val d2 = MathX.dist2(o.x, o.y, e.x, e.y)
            if (d2 > 26f * 26f) return@forEachAlive
            n++
            aliX += o.vx; aliY += o.vy
            cohX += o.x; cohY += o.y
            if (d2 < 7.5f * 7.5f && d2 > 1e-4f) {
                val d = kotlin.math.sqrt(d2)
                sepX += (e.x - o.x) / d / d
                sepY += (e.y - o.y) / d / d
            }
        }

        var ax = (wantX - e.x)
        var ay = (wantY - e.y)
        val dl = MathX.len(ax, ay)
        if (dl > 1e-4f) { ax = ax / dl * maxSpeed; ay = ay / dl * maxSpeed }

        if (n > 0) {
            aliX /= n; aliY /= n
            cohX = cohX / n - e.x; cohY = cohY / n - e.y
            ax = ax * 1.0f + aliX * 0.42f + cohX * 0.35f + sepX * 26f
            ay = ay * 1.0f + aliY * 0.42f + cohY * 0.35f + sepY * 26f
        }

        var sx = ax - e.vx
        var sy = ay - e.vy
        val sl = MathX.len(sx, sy)
        if (sl > maxForce) { sx = sx / sl * maxForce; sy = sy / sl * maxForce }

        val k = if (e.stun > 0f) 0.06f else 1f
        e.vx += sx * dt * 60f * 0.016f * k
        e.vy += sy * dt * 60f * 0.016f * k

        val sp = MathX.len(e.vx, e.vy)
        val lim = maxSpeed * e.slow * (if (e.stun > 0f) 0.15f else 1f)
        if (sp > lim && sp > 1e-4f) { e.vx = e.vx / sp * lim; e.vy = e.vy / sp * lim }

        e.x += e.vx * dt
        e.y += e.vy * dt
        e.angle = MathX.angleOf(e.vx, e.vy)

        // Anything that touches the water at speed is gone.
        w.ocean.sample(e.x, ws)
        if (e.y < ws.y) {
            w.event(EventKind.SPLASH, e.x, ws.y, 0.6f)
            w.corsairDown(e)
        }
    }

    private fun seeker(e: Entity, w: World, dt: Float) {
        val s = w.ship
        // Climbs, then dives on the deck.
        val diving = abs(e.x - s.x) < 42f
        val ty = if (diving) s.y + 1f else s.y + 10f
        flockSteer(e, w, dt, 21f, 2.6f, s.x + 3f, ty)
        if (hitsHull(e, w, 0.5f)) {
            w.damageShip(Config.DMG_SEEKER, DamageCause.CORSAIR)
            w.ship.applyImpulseAt(e.vx * 300f, e.vy * 300f, e.x, e.y)
            w.event(EventKind.HULL_IMPACT, e.x, e.y, Config.DMG_SEEKER)
            w.corsairDown(e)
        }
    }

    private fun bomber(e: Entity, w: World, dt: Float) {
        val s = w.ship
        // Holds station just above the masthead and waits to be overhead. Any higher and it
        // is off the top of a phone screen, which is not a threat, it is a surprise.
        flockSteer(e, w, dt, 11f, 1.5f, s.x + 6f, s.y + 15f)
        e.timer -= dt
        // phase doubles as the magazine. A bomber carries four and then it is just a target.
        if (e.timer <= 0f && e.phase >= 1f && abs(e.x - s.x) < 12f && e.stun <= 0f) {
            e.timer = 5f
            e.phase -= 1f
            val b = w.spawn(EntityKind.BOMB, e.x, e.y - 2f) ?: return
            b.vx = e.vx * 0.9f
            b.vy = e.vy
        }
    }

    private fun skimmer(e: Entity, w: World, dt: Float) {
        val s = w.ship
        w.ocean.sample(e.x, ws)
        // Hugs the water, which makes it fast and lethal but trivially drowned by mist.
        flockSteer(e, w, dt, 30f, 3.4f, s.x + 2f, ws.y + 2.2f)
        if (hitsHull(e, w, 0.3f)) {
            w.damageShip(Config.DMG_SKIMMER, DamageCause.CORSAIR)
            w.event(EventKind.HULL_IMPACT, e.x, e.y, Config.DMG_SKIMMER)
            w.corsairDown(e)
        }
    }

    private fun bomb(e: Entity, w: World, dt: Float) {
        e.timer -= dt
        e.vy -= MathX.G * dt
        e.vx *= 1f - 0.35f * dt
        e.x += e.vx * dt
        e.y += e.vy * dt
        e.angle = MathX.angleOf(e.vx, e.vy)
        w.ocean.sample(e.x, ws)
        val hitWater = e.y <= ws.y
        if (hitsHull(e, w) || hitWater || e.timer <= 0f) {
            if (hitWater && !hitsHull(e, w, 3f)) {
                w.event(EventKind.SPLASH, e.x, ws.y, 0.8f)
                e.kill()
                return
            }
            w.explode(e.x, e.y, 7f, Config.DMG_BOMB, DamageCause.BOMB, 13000f)
            w.event(EventKind.EXPLOSION, e.x, e.y, 0.7f)
            e.kill()
        }
    }

    // --- pickups -----------------------------------------------------------

    private fun mote(e: Entity, w: World, dt: Float) {
        float(e, w, dt, 22f, 1.1f)
        e.phase += dt * 2f
        e.y += kotlin.math.sin(e.phase) * 0.35f * dt * 8f
        if (hitsHull(e, w, 2.5f)) {
            w.ship.aether = MathX.clamp(w.ship.aether + Config.MOTE_VALUE, 0f, Config.MAX_AETHER)
            w.event(EventKind.PICKUP, e.x, e.y, Config.MOTE_VALUE)
            w.stats.motes++
            e.kill()
        }
    }

    private fun crate(e: Entity, w: World, dt: Float) {
        float(e, w, dt, 28f, 1f)
        if (hitsHull(e, w, 1.5f)) {
            val s = w.ship
            s.hull = MathX.clamp(s.hull + Config.CRATE_HULL_REPAIR, 0f, Config.MAX_HULL)
            if (s.leaks > 0) s.leaks--
            w.event(EventKind.PICKUP, e.x, e.y, Config.CRATE_HULL_REPAIR)
            e.kill()
        }
    }

    private fun debris(e: Entity, w: World, dt: Float) {
        e.vy -= MathX.G * 0.45f * dt
        e.x += e.vx * dt
        e.y += e.vy * dt
        e.angle += e.angVel * dt
        w.ocean.sample(e.x, ws)
        if (e.y < ws.y) {
            e.y = ws.y
            e.vy *= -0.25f
            e.vx *= 0.7f
        }
    }
}
