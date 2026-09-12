package com.polariz.aethertides.shared.sim

import com.polariz.aethertides.shared.math.MathX
import com.polariz.aethertides.shared.math.Rng
import com.polariz.aethertides.shared.ocean.Ocean
import com.polariz.aethertides.shared.ocean.WaveSample
import com.polariz.aethertides.shared.ocean.Wind
import kotlin.math.abs
import kotlin.math.sign

/** A one-shot effect notification. Pooled; the client drains the buffer each snapshot. */
class GameEvent {
    @JvmField var kind = EventKind.SPLASH
    @JvmField var x = 0f
    @JvmField var y = 0f
    @JvmField var x2 = 0f
    @JvmField var y2 = 0f
    @JvmField var magnitude = 0f
    @JvmField var hasLine = false
}

/** End-of-match numbers. Shown on the result card and fed to rating. */
class MatchStats {
    @JvmField var distance = 0f
    @JvmField var duration = 0f
    @JvmField var damageTaken = 0f
    @JvmField var corsairsDowned = 0
    @JvmField var hazardsDeployed = 0
    @JvmField var spellsCast = 0
    @JvmField var fusions = 0
    @JvmField var absorbed = 0
    @JvmField var motes = 0
    @JvmField var bestAir = 0f
    @JvmField var topSpeed = 0f
    @JvmField var timeSurfing = 0f
    /** Hull damage broken down by what caused it. The balance dial you actually read. */
    @JvmField val byCause = FloatArray(DamageCause.entries.size)

    fun reset() {
        distance = 0f; duration = 0f; damageTaken = 0f
        corsairsDowned = 0; hazardsDeployed = 0; spellsCast = 0
        fusions = 0; absorbed = 0; motes = 0
        bestAir = 0f; topSpeed = 0f; timeSurfing = 0f
        java.util.Arrays.fill(byCause, 0f)
    }

    fun causeReport(): String = DamageCause.entries
        .filter { byCause[it.ordinal] > 0.5f }
        .sortedByDescending { byCause[it.ordinal] }
        .joinToString(", ") { "%s %.0f".format(it.name.lowercase(), byCause[it.ordinal]) }
}

/** Why the match ended. */
enum class EndReason(val id: Int) {
    NONE(0), SHORE_REACHED(1), HULL_LOST(2), CAPSIZED(3), FOUNDERED(4), TIME_UP(5), FORFEIT(6);

    companion object {
        fun of(id: Int) = entries.firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * The match.
 *
 * One instance is the single source of truth on the server. Each client also runs one, fed
 * with the same seed, and reconciles it against arriving snapshots. Because the ocean, the
 * hull solver and every behaviour here are deterministic and allocation free, a client can
 * step it forward from the last confirmed snapshot to hide latency and land on the same
 * answer the server does.
 */
class World(val seed: Long) {

    val ocean = Ocean(seed, Config.COURSE_LENGTH)
    val wind = Wind(seed)
    val ship = ShipBody(ocean, wind)
    val rng = Rng(seed xor 0x1D0DEC0DEL)
    val stats = MatchStats()

    val entities = Array(Config.MAX_ENTITIES) { Entity() }
    val spells = Array(Config.MAX_SPELLS) { SpellZone() }

    private val eventPool = Array(64) { GameEvent() }
    private var eventCount = 0
    private var nextId = 1

    // --- match state ------------------------------------------------------
    var phase = MatchPhase.WAITING
    var phaseTimer = 0f
    var elapsed = 0f
    var winner: Role? = null
    var endReason = EndReason.NONE
    var tick = 0L

    // --- navigator --------------------------------------------------------
    val spellCooldown = FloatArray(4)
    /** Index of the spell currently bound by a snare, or -1. */
    var snaredSpell = -1
    var snareTimer = 0f

    // --- tempest ----------------------------------------------------------
    var malice = Config.MAX_MALICE * 0.55f
    var fury = 0f
    val deployCooldown = FloatArray(DeployKind.entries.size)
    /** Sea state the Tempest is paying to hold. Decays when they stop paying. */
    var stormTarget = Config.SEA_STATE_FLOOR
    var krakenActive = false

    private var moteTimer = Config.MOTE_INTERVAL
    private var lastLeague = 1
    private val ws = WaveSample()

    private val navInput = NavInput()

    init {
        reset()
    }

    fun reset() {
        ship.reset()
        ocean.clearTransients()
        ocean.seaState = Config.SEA_STATE_FLOOR
        wind.clearTransients()
        wind.baseSpeed = 7.5f
        wind.baseDir = 1f
        for (e in entities) e.alive = false
        for (s in spells) s.alive = false
        eventCount = 0
        nextId = 1
        elapsed = 0f
        tick = 0L
        winner = null
        endReason = EndReason.NONE
        phase = MatchPhase.COUNTDOWN
        phaseTimer = Config.COUNTDOWN
        for (i in spellCooldown.indices) spellCooldown[i] = 0f
        for (i in deployCooldown.indices) deployCooldown[i] = 0f
        snaredSpell = -1
        snareTimer = 0f
        malice = Config.MAX_MALICE * 0.55f
        fury = 0f
        stormTarget = Config.SEA_STATE_FLOOR
        krakenActive = false
        moteTimer = Config.MOTE_INTERVAL
        lastLeague = 1
        stats.reset()
    }

    // =======================================================================
    // Step
    // =======================================================================

    fun step(input: NavInput, dt: Float = Config.DT) {
        tick++
        eventCount = 0

        when (phase) {
            MatchPhase.WAITING -> return
            MatchPhase.COUNTDOWN -> {
                // The sea runs during the countdown so both players can read the swell before
                // the start. Nobody is ambushed by conditions they never saw.
                ocean.step(dt)
                wind.step(dt)
                navInput.trim = 0f
                ship.step(dt, navInput, ::event)
                phaseTimer -= dt
                if (phaseTimer <= 0f) phase = MatchPhase.SAILING
                return
            }
            MatchPhase.ENDED -> {
                ocean.step(dt)
                wind.step(dt)
                ship.step(dt, navInput, ::event)
                stepEntities(dt)
                return
            }
            MatchPhase.SAILING -> {}
        }

        elapsed += dt

        // --- weather ------------------------------------------------------
        stepStorm(dt)
        ocean.step(dt)
        wind.step(dt)

        // --- resources ----------------------------------------------------
        for (i in spellCooldown.indices) if (spellCooldown[i] > 0f) spellCooldown[i] -= dt
        for (i in deployCooldown.indices) if (deployCooldown[i] > 0f) deployCooldown[i] -= dt
        if (snareTimer > 0f) {
            snareTimer -= dt
            if (snareTimer <= 0f) snaredSpell = -1
        }
        malice = MathX.clamp(malice + Config.MALICE_REGEN * dt, 0f, Config.MAX_MALICE)
        if (!krakenActive) fury = MathX.clamp(fury + Config.FURY_PER_SEC * dt, 0f, Config.MAX_FURY)

        // --- spells then entities ----------------------------------------
        SpellEffects.checkFusions(this)
        for (z in spells) if (z.alive) SpellEffects.update(z, this, dt)

        stepEntities(dt)
        syncKraken()

        // --- the ship ------------------------------------------------------
        ship.step(dt, input, ::event)

        // --- drifting gifts ------------------------------------------------
        moteTimer -= dt
        if (moteTimer <= 0f) {
            moteTimer = Config.MOTE_INTERVAL * rng.range(0.75f, 1.3f)
            spawnGift()
        }

        // --- bookkeeping ---------------------------------------------------
        stats.distance = maxOf(stats.distance, ship.x)
        stats.duration = elapsed
        stats.topSpeed = maxOf(stats.topSpeed, abs(ship.vx))
        stats.bestAir = maxOf(stats.bestAir, ship.bestAirTime)
        if (ship.surf > 0.35f) stats.timeSurfing += dt

        val lg = ship.league()
        if (lg != lastLeague) {
            lastLeague = lg
            event(EventKind.LEAGUE, ship.x, ship.y, lg.toFloat())
        }

        checkEnd()
    }

    private fun stepEntities(dt: Float) {
        for (e in entities) if (e.alive) Behaviour.update(e, this, dt)
    }

    /**
     * The storm dial. The Tempest pays upkeep to hold a rough sea; let the payment lapse and
     * it settles back. Rough water hurts the navigator, but it is also the only water worth
     * surfing, so pushing it to the top is not automatically correct.
     */
    private fun stepStorm(dt: Float) {
        val want = MathX.clamp(stormTarget, Config.SEA_STATE_FLOOR, 1f)
        if (want > ocean.seaState) {
            val upkeep = Config.STORM_UPKEEP * dt * (0.5f + want)
            if (malice >= upkeep) {
                malice -= upkeep
                ocean.seaState = MathX.moveToward(ocean.seaState, want, Config.STORM_SLEW * dt)
            } else {
                stormTarget = ocean.seaState
            }
        } else {
            ocean.seaState = MathX.moveToward(ocean.seaState, want, Config.STORM_DECAY * dt * 2f)
        }
        // Wind and sea are coupled: you do not get one without the other.
        wind.baseSpeed = 8.0f + 20f * ocean.seaState
    }

    private fun checkEnd() {
        if (phase != MatchPhase.SAILING) return
        if (ship.x >= Config.COURSE_LENGTH) {
            finish(Role.NAVIGATOR, EndReason.SHORE_REACHED)
        } else if (ship.dead) {
            val reason = when {
                ship.capsizeTimer > 0f -> EndReason.CAPSIZED
                ship.bilge >= Config.MAX_BILGE -> EndReason.FOUNDERED
                else -> EndReason.HULL_LOST
            }
            finish(Role.TEMPEST, reason)
        } else if (elapsed >= Config.MATCH_TIME_LIMIT) {
            // Still afloat when the weather blows itself out. That is a win.
            finish(Role.NAVIGATOR, EndReason.TIME_UP)
        }
    }

    fun finish(who: Role, reason: EndReason) {
        if (phase == MatchPhase.ENDED) return
        phase = MatchPhase.ENDED
        winner = who
        endReason = reason
        stats.duration = elapsed
        stats.distance = maxOf(stats.distance, ship.x)
    }

    // =======================================================================
    // Navigator actions
    // =======================================================================

    fun canCast(kind: SpellKind): Boolean {
        if (!kind.isBase) return false
        if (phase != MatchPhase.SAILING) return false
        if (snaredSpell == kind.id) return false
        if (spellCooldown[kind.id] > 0f) return false
        return ship.aether >= Spells[kind].cost
    }

    /** Returns true if the working was made. The server is the only caller that matters. */
    fun castSpell(kind: SpellKind, x: Float, y: Float): Boolean {
        if (!canCast(kind)) return false
        // Defence in depth: the server refuses non-finite input at the socket, but nothing
        // below this line can tolerate NaN, and a comparison against NaN is always false --
        // so the range gate on its own would wave it through.
        if (!x.isFinite() || !y.isFinite()) return false
        val def = Spells[kind]
        // Range gate: you shape the water around your own ship, not across the horizon.
        if (abs(x - ship.x) > 120f) return false
        if (!ship.spendAether(def.cost)) return false
        spellCooldown[kind.id] = def.cooldown
        val z = spawnSpell(kind, x, y) ?: return false
        SpellEffects.onCast(z, this)
        event(EventKind.SPELL_CAST, x, y, kind.id.toFloat())
        stats.spellsCast++
        return true
    }

    fun tryBrace(): Boolean = phase == MatchPhase.SAILING && ship.tryBrace(::event)

    // =======================================================================
    // Tempest actions
    // =======================================================================

    fun canDeploy(kind: DeployKind, x: Float): Boolean {
        if (!x.isFinite()) return false
        if (phase != MatchPhase.SAILING) return false
        val def = Deployables[kind]
        if (kind == DeployKind.KRAKEN) return fury >= Config.MAX_FURY && !krakenActive
        if (deployCooldown[kind.id] > 0f) return false
        if (malice < def.cost) return false
        val lead = x - ship.bowX()
        return lead >= def.minLead && lead <= def.maxLead
    }

    /** `spellToSnare` is only read for AETHER_SNARE. */
    fun deploy(kind: DeployKind, x: Float, y: Float, spellToSnare: Int = -1): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        if (!canDeploy(kind, x)) return false
        val def = Deployables[kind]
        malice -= def.cost
        deployCooldown[kind.id] = def.cooldown
        stats.hazardsDeployed++

        ocean.sample(x, ws)
        when (kind) {
            DeployKind.REEF_SPIKE -> {
                // Anchored well below mean sea level, so it only reaches the keel when the
                // hull is down in a trough. Riding the crests over a reef field is the
                // counterplay, and it is a real skill.
                spawn(EntityKind.REEF_SPIKE, x, -2.0f)
            }
            DeployKind.DRIFT_MINE -> spawn(EntityKind.DRIFT_MINE, x, ws.y + 0.4f)
            DeployKind.ICE_FLOE -> spawn(EntityKind.ICE_FLOE, x, ws.y)
            DeployKind.MAELSTROM -> spawn(EntityKind.MAELSTROM, x, ws.y)
            DeployKind.TENTACLE -> spawn(EntityKind.TENTACLE, x, ws.y)
            DeployKind.CORSAIRS -> spawnFlight(x)
            DeployKind.ROGUE_WAVE -> {
                // Sent back up the course, so it meets her head on.
                ocean.addPulse(x + 120f, 4.4f + 2.6f * ocean.seaState, 15f, -19f, 9f)
                event(EventKind.ROGUE_WAVE, x, ws.y, -1f)
            }
            DeployKind.SQUALL -> {
                wind.addSquall(x, Deployables[kind].maxLead * 0.28f, 1.15f, 11f, -7f)
                event(EventKind.DEPLOY, x, ws.y + 26f, kind.id.toFloat())
            }
            DeployKind.AETHER_SNARE -> {
                val pick = if (spellToSnare in 0..3) spellToSnare else rng.nextInt(4)
                snaredSpell = pick
                snareTimer = Config.SNARE_DURATION
                event(EventKind.SNARE, ship.x, ship.y, pick.toFloat())
            }
            DeployKind.KRAKEN -> {}
        }
        if (kind != DeployKind.AETHER_SNARE && kind != DeployKind.SQUALL) {
            event(EventKind.DEPLOY, x, ws.y, kind.id.toFloat())
        }
        return true
    }

    fun releaseFury(): Boolean {
        if (fury < Config.MAX_FURY || krakenActive || phase != MatchPhase.SAILING) return false
        fury = 0f
        krakenActive = true
        val k = spawn(EntityKind.KRAKEN, ship.x + 46f, ship.y) ?: return false
        k.life = Config.KRAKEN_DURATION
        event(EventKind.KRAKEN_RISE, k.x, k.y, 0f)
        // The sea answers to it.
        stormTarget = maxOf(stormTarget, 0.78f)
        return true
    }

    fun requestStorm(v: Float) {
        // clamp() maps NaN to the floor; infinity clamps normally. Either way: finite.
        stormTarget = MathX.clamp(v, Config.SEA_STATE_FLOOR, 1f)
    }

    /** Credit the Tempest for holding her in irons. */
    fun tempestStallCredit(dt: Float) {
        malice = MathX.clamp(malice + Config.MALICE_STALL_BONUS * dt, 0f, Config.MAX_MALICE)
    }

    // =======================================================================
    // World services used by behaviours
    // =======================================================================

    fun spawn(kind: EntityKind, x: Float, y: Float): Entity? {
        for (e in entities) {
            if (!e.alive) {
                e.reset(kind, x, y)
                e.id = nextId++
                return e
            }
        }
        return null
    }

    fun spawnSpell(kind: SpellKind, x: Float, y: Float): SpellZone? {
        for (z in spells) {
            if (!z.alive) {
                z.reset(kind, x, y)
                z.id = nextId++
                return z
            }
        }
        // All slots busy: recycle the one closest to expiring rather than dropping the cast.
        var oldest: SpellZone? = null
        for (z in spells) if (oldest == null || z.life < oldest.life) oldest = z
        oldest?.let {
            it.reset(kind, x, y)
            it.id = nextId++
            return it
        }
        return null
    }

    private fun spawnFlight(x: Float) {
        val n = rng.nextInt(Config.CORSAIR_FLIGHT_MIN, Config.CORSAIR_FLIGHT_MAX + 1)
        val flockId = nextId
        ocean.sample(x, ws)
        for (i in 0 until n) {
            // A mixed flight: mostly seekers, with a bomber high and a skimmer low.
            val kind = when {
                i == 0 -> EntityKind.BOMBER
                i == 1 -> EntityKind.SKIMMER
                else -> EntityKind.SEEKER
            }
            val sy = when (kind) {
                EntityKind.BOMBER -> ws.y + 17f
                EntityKind.SKIMMER -> ws.y + 3f
                else -> ws.y + 11f + rng.range(-3f, 5f)
            }
            val e = spawn(kind, x + rng.range(-9f, 9f), sy) ?: return
            e.flock = flockId
            e.vx = -6f
        }
    }

    private fun spawnGift() {
        ocean.sample(ship.x + 180f, ws)
        // A crate when she is hurting, otherwise aether. Never enough to bail her out,
        // just enough to reward sailing a line that picks it up.
        val wantCrate = ship.hull < 55f && rng.chance(0.45f)
        val kind = if (wantCrate) EntityKind.SALVAGE_CRATE else EntityKind.AETHER_MOTE
        val e = spawn(kind, ship.x + rng.range(150f, 260f), ws.y + rng.range(1f, 7f))
        e?.vx = -1.5f
    }

    fun spawnDebris(x: Float, y: Float, n: Int) {
        for (i in 0 until n) {
            val d = spawn(EntityKind.DEBRIS, x, y) ?: return
            d.vx = rng.range(-9f, 9f)
            d.vy = rng.range(2f, 13f)
            d.angVel = rng.range(-7f, 7f)
        }
    }

    /** Kill a corsair with credit and effects. */
    fun corsairDown(e: Entity) {
        if (!e.alive) return
        e.kill()
        stats.corsairsDowned++
        event(EventKind.CORSAIR_DOWN, e.x, e.y, 0f)
    }

    /**
     * Radial blast. Damages the ship if she is inside it, throws her, and shoves loose
     * entities. Impulse is in newton-seconds at the centre.
     */
    fun explode(x: Float, y: Float, radius: Float, damage: Float, cause: DamageCause, impulse: Float) {
        val d = MathX.dist(ship.x, ship.y, x, y)
        if (d < radius + Config.HULL_LENGTH * 0.4f) {
            val f = MathX.falloff(maxOf(0f, d - Config.HULL_LENGTH * 0.35f), radius)
            if (damage > 0f) {
                val dealt = damageShip(damage * f, cause)
                if (dealt > Config.BREACH_THRESHOLD && rng.chance(Config.BREACH_CHANCE)) {
                    ship.addLeak()
                    event(EventKind.BREACH, ship.x, ship.y, dealt)
                }
            }
            var dx = ship.x - x
            var dy = ship.y - y
            val l = MathX.len(dx, dy).coerceAtLeast(0.5f)
            dx /= l; dy /= l
            ship.applyImpulseAt(dx * impulse * f, (dy * 0.55f + 0.65f) * impulse * f, x, y)
            ship.angVel += sign(dx) * f * 1.15f
        }

        forEachAlive { e ->
            val ed = MathX.dist(e.x, e.y, x, y)
            if (ed >= radius * 1.6f) return@forEachAlive
            val f = MathX.falloff(ed, radius * 1.6f)
            var dx = e.x - x
            var dy = e.y - y
            val l = MathX.len(dx, dy).coerceAtLeast(0.4f)
            e.vx += dx / l * f * 34f
            e.vy += dy / l * f * 34f + f * 6f
            if (e.kind.isCorsair && f > 0.55f) corsairDown(e)
        }
    }

    /** Single entry point for hull damage so the Tempest economy always sees it. */
    fun damageShip(amount: Float, cause: DamageCause): Float {
        if (phase != MatchPhase.SAILING) return 0f
        val dealt = ship.damage(amount)
        stats.damageTaken += dealt
        stats.byCause[cause.ordinal] += dealt
        malice = MathX.clamp(malice + dealt * Config.MALICE_PER_DAMAGE, 0f, Config.MAX_MALICE)
        if (!krakenActive) fury = MathX.clamp(fury + dealt * Config.FURY_PER_DAMAGE, 0f, Config.MAX_FURY)
        return dealt
    }

    fun forEachAlive(f: (Entity) -> Unit) {
        for (e in entities) if (e.alive) f(e)
    }

    fun aliveCount(): Int {
        var n = 0
        for (e in entities) if (e.alive) n++
        return n
    }

    fun aliveSpellCount(): Int {
        var n = 0
        for (z in spells) if (z.alive) n++
        return n
    }

    // --- events -----------------------------------------------------------

    fun event(kind: EventKind, x: Float, y: Float, magnitude: Float) {
        if (eventCount >= eventPool.size) return
        val e = eventPool[eventCount++]
        e.kind = kind; e.x = x; e.y = y; e.magnitude = magnitude; e.hasLine = false
    }

    /** Attach a second point to the event just pushed, for lightning arcs. */
    fun eventLine(x1: Float, y1: Float, x2: Float, y2: Float) {
        if (eventCount == 0) return
        val e = eventPool[eventCount - 1]
        e.x = x1; e.y = y1; e.x2 = x2; e.y2 = y2; e.hasLine = true
    }

    fun eventCount(): Int = eventCount
    fun eventAt(i: Int): GameEvent = eventPool[i]

    /** Kraken bookkeeping is done here rather than in the behaviour so it survives a despawn. */
    fun syncKraken() {
        if (!krakenActive) return
        var found = false
        for (e in entities) if (e.alive && e.kind == EntityKind.KRAKEN) { found = true; break }
        if (!found) krakenActive = false
    }
}
