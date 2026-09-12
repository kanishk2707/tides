package com.mythron.aethertides.shared.ai

import com.mythron.aethertides.shared.math.MathX
import com.mythron.aethertides.shared.math.Rng
import com.mythron.aethertides.shared.ocean.WaveSample
import com.mythron.aethertides.shared.sim.Config
import com.mythron.aethertides.shared.sim.Entity
import com.mythron.aethertides.shared.sim.EntityKind
import com.mythron.aethertides.shared.sim.MatchPhase
import com.mythron.aethertides.shared.sim.NavInput
import com.mythron.aethertides.shared.sim.SpellKind
import com.mythron.aethertides.shared.sim.Spells
import com.mythron.aethertides.shared.sim.World
import kotlin.math.abs

/**
 * The Navigator bot.
 *
 * Used when a human plays the Tempest offline, and as a stand-in if a navigator drops so the
 * Tempest still gets a finished game.
 *
 * It plays the role the way the role is meant to be played: speed is defence. Every second
 * spent slow is another deployment the Tempest can afford, so the bot keeps the sail loaded,
 * spends Gale astern when the water is clear, and only breaks off to answer a threat it has
 * decided it cannot outrun.
 */
class NavigatorAi(private val world: World, seed: Long, var difficulty: Float = 0.6f) {

    private val rng = Rng(seed xor 0x0FF1CEL)
    private val here = WaveSample()
    private val ahead = WaveSample()

    val input = NavInput()

    private var castTimer = 0f
    private var reactTimer = 0f
    private var wantTrim = 0.7f
    private var wantLean = 0f

    private fun reaction(): Float = MathX.lerp(0.55f, 0.12f, difficulty)

    /** How far ahead it bothers to look, metres. A weak bot is short sighted. */
    private fun horizon(): Float = MathX.lerp(70f, 165f, difficulty)

    fun update(dt: Float) {
        if (world.phase != MatchPhase.SAILING) {
            input.trim = 0f
            input.lean = 0f
            return
        }
        val s = world.ship

        reactTimer -= dt
        if (reactTimer <= 0f) {
            reactTimer = reaction()
            decideSailing()
        }

        input.trim = MathX.approach(input.trim, wantTrim, 4.5f, dt)
        input.lean = MathX.approach(input.lean, wantLean, 6f, dt)
        input.pump = s.bilge > Config.MAX_BILGE * 0.25f && s.aether > 28f
        input.brace = false
        input.seq++

        // Brace for an impact it has decided it cannot avoid.
        val threat = nearestThreat(horizon())
        if (threat != null) {
            val closing = MathX.clamp(s.vx - threat.vx, 1f, 40f)
            val tti = (threat.x - s.bowX()) / closing
            if (tti in 0f..0.45f && s.braceCooldown <= 0f && s.aether > Config.BRACE_COST) {
                if (rng.chance(0.35f + 0.6f * difficulty)) input.brace = true
            }
        }

        castTimer -= dt
        if (castTimer <= 0f) {
            castTimer = MathX.lerp(1.3f, 0.35f, difficulty) * rng.range(0.8f, 1.25f)
            considerSpell()
        }
    }

    private fun decideSailing() {
        val s = world.ship
        world.ocean.sample(s.x, here)
        world.ocean.sample(s.x + Config.HULL_LENGTH * 1.5f, ahead)

        wantTrim = when {
            s.apparentWind > Config.MAST_STRESS_WIND * 0.9f -> 0.4f
            s.mast < 45f -> 0.55f
            else -> 1f
        }
        if (s.airborne) wantTrim *= 0.75f

        // Deep water hazards only bite in a trough, so when something is sitting low ahead the
        // bot tries to be high and light over the top of it.
        val submergedThreat = nearestOfKind(EntityKind.REEF_SPIKE, 110f)
        val liftWanted = submergedThreat != null &&
                abs(submergedThreat.x - s.bowX()) < 70f

        wantLean = when {
            s.airborne -> MathX.clamp(-s.angle * 1.9f - 0.12f, -1f, 1f)
            s.knockedDown -> MathX.clamp(-s.angle * 2.2f, -1f, 1f)
            // Weight aft on the way up a face launches her off the crest.
            liftWanted && ahead.dydx > 0.12f -> -0.85f
            ahead.dydx > 0.22f -> 0.7f
            ahead.dydx < -0.25f -> -0.5f
            else -> -0.1f
        }
    }

    private fun nearestThreat(range: Float): Entity? {
        val s = world.ship
        var best: Entity? = null
        var bestD = range
        world.forEachAlive { e ->
            if (e.kind.isPickup || e.kind == EntityKind.DEBRIS) return@forEachAlive
            if (e.x < s.x - 10f) return@forEachAlive
            val d = e.x - s.bowX()
            if (d in -10f..bestD) { bestD = d; best = e }
        }
        return best
    }

    private fun nearestOfKind(kind: EntityKind, range: Float): Entity? {
        val s = world.ship
        var best: Entity? = null
        var bestD = range
        world.forEachAlive { e ->
            if (e.kind != kind) return@forEachAlive
            val d = e.x - s.bowX()
            if (d in -6f..bestD) { bestD = d; best = e }
        }
        return best
    }

    /**
     * Threat triage, then a speed decision.
     *
     * Ordered by what actually ends runs: a tentacle that will not let go, then a flight of
     * corsairs, then anything floating in the lane. If none of that is close, the aether is
     * better spent on going faster.
     */
    private fun considerSpell() {
        val s = world.ship
        val reach = 95f

        var corsairs = 0
        var skimmers = 0
        var mines = 0
        var clusterX = 0f
        var clusterY = 0f
        var clusterN = 0
        var nearestSurface: Entity? = null
        var nearestSurfaceD = reach

        world.forEachAlive { e ->
            val rel = e.x - s.x
            if (rel < -14f || rel > reach) return@forEachAlive
            when {
                e.kind.isCorsair -> {
                    corsairs++
                    if (e.kind == EntityKind.SKIMMER) skimmers++
                    clusterX += e.x; clusterY += e.y; clusterN++
                }
                e.kind == EntityKind.DRIFT_MINE || e.kind == EntityKind.ICE_FLOE -> {
                    if (e.kind == EntityKind.DRIFT_MINE) { mines++; clusterX += e.x; clusterY += e.y; clusterN++ }
                    if (rel < nearestSurfaceD) { nearestSurfaceD = rel; nearestSurface = e }
                }
                else -> {}
            }
        }
        if (clusterN > 0) { clusterX /= clusterN; clusterY /= clusterN }

        val tentacle = nearestOfKind(EntityKind.TENTACLE, reach)
        val surface = nearestSurface
        val reef = nearestOfKind(EntityKind.REEF_SPIKE, reach)

        when {
            tentacle != null && world.canCast(SpellKind.BOLT) ->
                world.castSpell(SpellKind.BOLT, tentacle.x, tentacle.y)

            corsairs >= 2 && world.canCast(SpellKind.BOLT) ->
                world.castSpell(SpellKind.BOLT, clusterX, clusterY)

            skimmers > 0 && world.canCast(SpellKind.MIST) ->
                world.castSpell(SpellKind.MIST, clusterX, clusterY)

            mines >= 2 && world.canCast(SpellKind.VOID) ->
                world.castSpell(SpellKind.VOID, clusterX, clusterY)

            // A reef head cannot be blown aside or shattered. Only a well lifts it.
            reef != null && world.canCast(SpellKind.VOID) ->
                world.castSpell(SpellKind.VOID, reef.x, reef.y + 1f)

            // Anything loose in the lane gets blown out of it. Gale is cheap and fast enough
            // to be the workhorse answer.
            surface != null && nearestSurfaceD < 55f && world.canCast(SpellKind.GALE) ->
                world.castSpell(SpellKind.GALE, surface.x, surface.y + 1.5f)

            corsairs == 1 && world.canCast(SpellKind.BOLT) ->
                world.castSpell(SpellKind.BOLT, clusterX, clusterY)

            // Clear water: convert aether into speed, which is the real defence.
            world.canCast(SpellKind.GALE) &&
                    s.aether > Spells[SpellKind.GALE].cost + 26f &&
                    s.vx < 19f && difficulty > 0.3f ->
                world.castSpell(SpellKind.GALE, s.x - 13f, s.y + 1.5f)

            else -> {}
        }
    }
}
