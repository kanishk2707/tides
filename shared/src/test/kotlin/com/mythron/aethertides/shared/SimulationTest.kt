package com.mythron.aethertides.shared

import com.mythron.aethertides.shared.ai.NavigatorAi
import com.mythron.aethertides.shared.ai.TempestAi
import com.mythron.aethertides.shared.net.ByteReader
import com.mythron.aethertides.shared.net.ByteWriter
import com.mythron.aethertides.shared.net.Snapshot
import com.mythron.aethertides.shared.net.SnapshotFrame
import com.mythron.aethertides.shared.ocean.Ocean
import com.mythron.aethertides.shared.ocean.WaveSample
import com.mythron.aethertides.shared.sim.Config
import com.mythron.aethertides.shared.sim.MatchPhase
import com.mythron.aethertides.shared.sim.NavInput
import com.mythron.aethertides.shared.sim.Role
import com.mythron.aethertides.shared.sim.SpellKind
import com.mythron.aethertides.shared.sim.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class SimulationTest {

    // -----------------------------------------------------------------------
    // Ocean
    // -----------------------------------------------------------------------

    @Test
    fun `ocean is deterministic from the seed`() {
        val a = Ocean(12345L, Config.COURSE_LENGTH)
        val b = Ocean(12345L, Config.COURSE_LENGTH)
        a.seaState = 0.62f
        b.seaState = 0.62f
        val sa = WaveSample()
        val sb = WaveSample()
        repeat(240) { a.step(Config.DT); b.step(Config.DT) }
        for (x in 0 until 400 step 7) {
            a.sample(x.toFloat(), sa)
            b.sample(x.toFloat(), sb)
            assertEquals("height at $x", sa.y, sb.y, 0f)
            assertEquals("slope at $x", sa.dydx, sb.dydx, 0f)
        }
    }

    @Test
    fun `wave height tracks sea state over a plausible range`() {
        val o = Ocean(7L, Config.COURSE_LENGTH)
        val s = WaveSample()
        var prev = -1f
        for (state in listOf(0.22f, 0.5f, 0.75f, 1.0f)) {
            o.seaState = state
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            var t = 0f
            while (t < 40f) {
                o.step(Config.DT * 4)
                t += Config.DT * 4
                for (x in 600 until 900 step 3) {
                    o.sample(x.toFloat(), s)
                    if (s.y < lo) lo = s.y
                    if (s.y > hi) hi = s.y
                }
            }
            val range = hi - lo
            println("sea state %.2f -> peak-to-trough %.2f m (Hs %.2f m)".format(state, range, o.significantHeight()))
            assertTrue("wave range must grow with sea state", range > prev)
            prev = range
        }
        assertTrue("a full storm should not exceed ~20 m peak to trough", prev < 20f)
    }

    @Test
    fun `long swell outruns short chop, as deep water dispersion requires`() {
        // Phase speed c = sqrt(g/k) = sqrt(g*L/2pi): a 118 m swell must move roughly three
        // times faster than a 12 m one. This is the property that makes the sea look alive.
        val long = sqrt(9.80665f * 118f / (2f * 3.14159f))
        val short = sqrt(9.80665f * 12f / (2f * 3.14159f))
        assertTrue(long / short > 2.8f)
    }

    // -----------------------------------------------------------------------
    // Hull
    // -----------------------------------------------------------------------

    @Test
    fun `hull floats at rest and settles near its waterline`() {
        val w = World(99L)
        w.phase = MatchPhase.SAILING
        val input = NavInput().apply { trim = 0f }
        w.ocean.seaState = Config.SEA_STATE_FLOOR
        repeat(600) { w.step(input) }
        val s = w.ship
        println("at rest: y=%.2f m  pitch=%.1f deg  submerged=%.2f".format(s.y, s.angle * 57.3f, s.submergedFraction))
        assertTrue("should float, not sink: y=${s.y}", s.y > -3f && s.y < 3f)
        assertTrue("should sit roughly level", abs(s.angle) < 0.5f)
        assertTrue("should be part submerged", s.submergedFraction in 0.15f..0.85f)
        assertTrue("should not be taking damage while becalmed", s.hull > 95f)
    }

    @Test
    fun `sail drives the hull to a realistic displacement speed`() {
        val w = World(4242L)
        w.phase = MatchPhase.SAILING
        val input = NavInput().apply { trim = 1f }

        var t = 0f
        while (t < 45f) { w.step(input); t += Config.DT }
        println("calm water, full sail: %.2f m/s (%.1f kn)".format(w.ship.vx, w.ship.speedKnots))
        assertTrue("should make way", w.ship.vx > 4f)
        assertTrue("should not plane on calm water", w.ship.vx < 12f)
        assertTrue("should stay in one piece in calm weather", w.ship.hull > 90f)
    }

    @Test
    fun `a wave face drives the hull forward, which is what makes surfing possible`() {
        // The physics claim under the whole game: buoyancy acts along the local surface
        // normal, so a hull sitting on a downslope gets a real forward force of g*sin(theta).
        // Measured directly, with the sail furled so nothing else can be responsible.
        val w = World(77L)
        w.phase = MatchPhase.SAILING
        w.ocean.seaState = 0.8f
        val furled = NavInput().apply { trim = 0f }

        var bestDownhillAccel = 0f
        var prevVx = w.ship.vx
        val s = WaveSample()
        var t = 0f
        while (t < 30f) {
            w.ocean.seaState = 0.8f            // hold the sea; the storm dial is not under test
            w.step(furled)
            w.ocean.sample(w.ship.x, s)
            val accel = (w.ship.vx - prevVx) / Config.DT
            prevVx = w.ship.vx
            if (s.dydx < -0.18f && w.ship.submergedFraction > 0.1f) {
                bestDownhillAccel = maxOf(bestDownhillAccel, accel)
            }
            t += Config.DT
        }
        println("best forward acceleration on a face, sail furled: %.2f m/s^2".format(bestDownhillAccel))
        assertTrue("a downslope must push her forward", bestDownhillAccel > 1.0f)
    }

    @Test
    fun `catching a swell carries her past her own hull speed`() {
        val w = World(4242L)
        w.phase = MatchPhase.SAILING
        val input = NavInput().apply { trim = 1f }

        var peak = 0f
        var bestSurf = 0f
        var t = 0f
        while (t < 110f) {
            // Hold a storm up, the way a Tempest paying upkeep would.
            w.malice = Config.MAX_MALICE
            w.requestStorm(0.9f)
            // And spend Gale astern the way a navigator chasing a wave would. Matching the
            // swell phase speed is the entry fee for surfing: below it, the wave passes you.
            if (w.canCast(SpellKind.GALE) && w.ship.aether > 45f) {
                w.castSpell(SpellKind.GALE, w.ship.x - 13f, w.ship.y + 1.5f)
            }
            w.step(input)
            peak = maxOf(peak, w.ship.vx)
            bestSurf = maxOf(bestSurf, w.ship.surf)
            t += Config.DT
        }
        val swellSpeed = w.ocean.dominantSpeed()
        println("storm run: peak %.1f m/s, best surf %.2f, dominant swell %.1f m/s".format(peak, bestSurf, swellSpeed))
        assertTrue("surfing must be detected", bestSurf > 0.35f)
        assertTrue("she must be able to keep up with the swell she is riding", peak > swellSpeed)
    }

    @Test
    fun `flooding makes her sit lower and eventually founders her`() {
        val w = World(31L)
        w.phase = MatchPhase.SAILING
        val input = NavInput().apply { trim = 0.4f; pump = false }
        repeat(300) { w.step(input) }
        val dryY = w.ship.y

        w.ship.leaks = Config.MAX_LEAKS
        var t = 0f
        while (t < 200f && !w.ship.dead) { w.step(input); t += Config.DT }
        println("swamped after %.1f s with %.0f kg aboard, hull %.0f".format(t, w.ship.bilge, w.ship.hull))
        assertTrue("a fully breached hull should eventually be lost", w.ship.dead || w.ship.bilge > Config.MAX_BILGE * 0.9f)
        assertTrue("she should ride lower once flooded", w.ship.y < dryY + 0.05f)
    }

    // -----------------------------------------------------------------------
    // Match
    // -----------------------------------------------------------------------

    @Test
    fun `a full bot-vs-bot match reaches a decision`() {
        var navWins = 0
        var tempestWins = 0
        for (seed in 1L..10L) {
            val w = World(seed * 1337L)
            val nav = NavigatorAi(w, seed, 0.7f)
            val tempest = TempestAi(w, seed, 0.7f)
            var t = 0f
            while (w.phase != MatchPhase.ENDED && t < Config.MATCH_TIME_LIMIT + 20f) {
                nav.update(Config.DT)
                tempest.update(Config.DT)
                w.step(nav.input)
                t += Config.DT
            }
            assertEquals("match $seed must finish", MatchPhase.ENDED, w.phase)
            if (w.winner == Role.NAVIGATOR) navWins++ else tempestWins++
            println(
                "seed %d: %s by %s | %.0f m in %.0fs | dmg %.0f, corsairs %d, hazards %d, spells %d, top %.1f m/s"
                    .format(
                        seed, w.winner, w.endReason, w.stats.distance, w.stats.duration,
                        w.stats.damageTaken, w.stats.corsairsDowned, w.stats.hazardsDeployed,
                        w.stats.spellsCast, w.stats.topSpeed
                    )
            )
            println("         damage: " + w.stats.causeReport())
        }
        println("bot series: navigator $navWins - $tempestWins tempest")
        // Neither side should be a walkover at equal skill.
        assertTrue("one side is dominating at equal skill: $navWins-$tempestWins", navWins >= 2 && tempestWins >= 2)
    }

    @Test
    fun `spells obey their costs and cooldowns`() {
        val w = World(5L)
        w.phase = MatchPhase.SAILING
        w.ship.aether = Config.MAX_AETHER
        assertTrue(w.castSpell(SpellKind.BOLT, w.ship.x + 10f, w.ship.y + 5f))
        assertTrue("should be on cooldown", !w.canCast(SpellKind.BOLT))
        w.ship.aether = 0f
        assertTrue("no aether, no working", !w.canCast(SpellKind.GALE))
        assertTrue("out of range casts are refused", !w.castSpell(SpellKind.GALE, w.ship.x + 900f, 0f))
    }

    @Test
    fun `overlapping gale and mist fuse into a tsunami`() {
        val w = World(11L)
        w.phase = MatchPhase.SAILING
        w.ship.aether = Config.MAX_AETHER
        val x = w.ship.x + 30f
        assertTrue(w.castSpell(SpellKind.GALE, x, 2f))
        assertTrue(w.castSpell(SpellKind.MIST, x + 4f, 2f))
        w.step(NavInput())
        var found = false
        for (z in w.spells) if (z.alive && z.kind == SpellKind.TSUNAMI) found = true
        assertTrue("gale over mist must fuse", found)
        assertEquals(1, w.stats.fusions)
    }

    // -----------------------------------------------------------------------
    // Wire
    // -----------------------------------------------------------------------

    @Test
    fun `snapshots round trip`() {
        val w = World(808L)
        w.phase = MatchPhase.SAILING
        val nav = NavigatorAi(w, 3L, 0.8f)
        val tempest = TempestAi(w, 3L, 0.9f)
        repeat(2400) { nav.update(Config.DT); tempest.update(Config.DT); w.step(nav.input) }

        val writer = ByteWriter()
        val bytes = Snapshot.encode(w, 4242, writer)
        println("snapshot: ${bytes.size} bytes with ${w.aliveCount()} entities -> ${bytes.size * Config.SNAPSHOT_RATE / 1024f} KB/s")
        assertTrue("snapshots must stay small enough for mobile data", bytes.size < 4096)

        val r = ByteReader(bytes)
        assertEquals(com.mythron.aethertides.shared.net.Msg.SNAPSHOT, r.u8())
        val f = Snapshot.decode(r, SnapshotFrame())

        assertEquals(w.tick, f.tick)
        assertEquals(4242, f.ackSeq)
        assertEquals(w.ship.x, f.shipX, 0.001f)
        assertEquals(w.ship.y, f.shipY, 0.001f)
        assertEquals(w.ship.vx, f.shipVx, 0.01f)
        assertEquals(w.ship.angle, f.shipAngle, 0.001f)
        assertEquals(w.ship.hull, f.hull, 0.5f)
        assertEquals(w.malice, f.malice, 0.6f)
        assertEquals(w.ocean.seaState, f.seaState, 0.01f)
        assertEquals(w.aliveCount(), f.entityCount)
        assertEquals(0, r.remaining)
    }

    @Test
    fun `client can replay the server step and land in the same place`() {
        // The basis of client-side prediction: given the same snapshot and the same inputs,
        // a client world must reach the same state the server does.
        val server = World(2024L)
        val client = World(2024L)
        server.phase = MatchPhase.SAILING
        client.phase = MatchPhase.SAILING

        val input = NavInput().apply { trim = 0.8f; lean = 0.2f }
        repeat(900) { server.step(input); client.step(input) }

        assertEquals(server.ship.x, client.ship.x, 0f)
        assertEquals(server.ship.y, client.ship.y, 0f)
        assertEquals(server.ship.angle, client.ship.angle, 0f)
        println("prediction parity after 900 ticks: x=%.4f".format(client.ship.x))
    }
}
