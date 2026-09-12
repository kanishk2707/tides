package com.polariz.aethertides.shared.ai

import com.polariz.aethertides.shared.math.MathX
import com.polariz.aethertides.shared.math.Rng
import com.polariz.aethertides.shared.ocean.WaveSample
import com.polariz.aethertides.shared.sim.Config
import com.polariz.aethertides.shared.sim.DeployKind
import com.polariz.aethertides.shared.sim.Deployables
import com.polariz.aethertides.shared.sim.EntityKind
import com.polariz.aethertides.shared.sim.MatchPhase
import com.polariz.aethertides.shared.sim.World
import kotlin.math.abs

/**
 * The Tempest bot.
 *
 * Plays the same game a human Tempest plays: it has the same malice, the same cooldowns, the
 * same placement rules, and it can only see what a human could see. Difficulty changes how
 * long it takes to react and how well it leads the target -- never how much it is allowed to
 * spend. A bot that cheats on resources is not practice for the real thing.
 */
class TempestAi(private val world: World, seed: Long, var difficulty: Float = 0.6f) {

    private val rng = Rng(seed xor 0x7E3B07L)
    private val ws = WaveSample()

    private var thinkTimer = 0f
    /** How often the navigator has leaned on each of the four base spells. Feeds the snare. */
    private val spellUse = FloatArray(4)
    private val lastSpellCd = FloatArray(4)

    /** Reaction interval, seconds. A low difficulty bot is slow to answer what it sees. */
    private fun thinkInterval(): Float = MathX.lerp(2.1f, 0.55f, difficulty)

    /** How accurately it leads a moving ship. */
    private fun leadError(): Float = MathX.lerp(38f, 4f, difficulty)

    fun update(dt: Float) {
        if (world.phase != MatchPhase.SAILING) return

        // Watch which spells the navigator actually relies on.
        for (i in 0 until 4) {
            if (world.spellCooldown[i] > lastSpellCd[i] + 0.01f) spellUse[i] += 1f
            lastSpellCd[i] = world.spellCooldown[i]
            spellUse[i] *= 1f - 0.04f * dt
        }

        thinkTimer -= dt
        if (thinkTimer > 0f) return
        thinkTimer = thinkInterval() * rng.range(0.8f, 1.25f)

        think(dt)
    }

    private fun think(dt: Float) {
        val s = world.ship
        val progress = s.progress()

        // Fury is worth holding until it can finish the job or break a good run.
        if (world.fury >= Config.MAX_FURY && (s.hull < 62f || s.surf > 0.5f || progress > 0.62f)) {
            if (world.releaseFury()) return
        }

        // The storm dial. Ramp it with the voyage so late leagues are genuinely worse, and
        // push harder when the navigator is comfortable.
        val wantStorm = MathX.clamp(
            0.3f + progress * 0.42f + difficulty * 0.2f + (if (s.hull > 70f) 0.1f else -0.05f),
            Config.SEA_STATE_FLOOR, 0.95f
        )
        if (abs(wantStorm - world.stormTarget) > 0.05f && world.malice > 45f) {
            world.requestStorm(wantStorm)
        }

        // Do not spend down to nothing: keep a reserve so a good opening can be answered.
        val reserve = MathX.lerp(38f, 12f, difficulty)
        if (world.malice < reserve) return

        val choice = pickDeploy() ?: return
        val (kind, x) = choice
        world.ocean.sample(x, ws)
        world.deploy(kind, x, ws.y, snareTarget())
    }

    /** Where the bow will be in `t` seconds, with difficulty-scaled error. */
    private fun predictX(t: Float): Float {
        val s = world.ship
        val v = MathX.clamp(s.vx, 0f, 30f)
        return s.bowX() + v * t + rng.range(-leadError(), leadError()) * 0.5f
    }

    private fun snareTarget(): Int {
        var best = 0
        for (i in 1 until 4) if (spellUse[i] > spellUse[best]) best = i
        return best
    }

    /**
     * Utility scoring. Each option gets a score for how well it answers the current situation,
     * then the bot picks from the top few so it does not become predictable.
     */
    private fun pickDeploy(): Pair<DeployKind, Float>? {
        val s = world.ship
        var corsairsAlive = 0
        var hazardsAhead = 0
        world.forEachAlive { e ->
            if (e.kind.isCorsair) corsairsAlive++
            if (e.x > s.x && e.x < s.x + 220f && e.kind != EntityKind.AETHER_MOTE &&
                e.kind != EntityKind.SALVAGE_CRATE && e.kind != EntityKind.DEBRIS
            ) hazardsAhead++
        }

        var bestKind: DeployKind? = null
        var bestScore = 0.25f
        var bestX = 0f

        for (kind in Deployables.bar) {
            val def = Deployables[kind]
            // Time to target: far placements need more lead.
            val lead = MathX.lerp(def.minLead, def.maxLead * 0.55f, rng.nextFloat())
            val t = lead / MathX.clamp(abs(s.vx), 3f, 26f)
            val x = MathX.clamp(predictX(t), s.bowX() + def.minLead + 2f, s.bowX() + def.maxLead - 2f)
            if (!world.canDeploy(kind, x)) continue

            var score = 0.5f
            when (kind) {
                DeployKind.REEF_SPIKE -> {
                    // Reef is only dangerous where the hull will actually be low in the water.
                    world.ocean.sample(x, ws)
                    score += if (ws.y < -0.35f) 1.35f else 0.15f
                    score += if (s.surf > 0.4f) 0.5f else 0f      // a surfing hull sits deep
                }
                DeployKind.DRIFT_MINE -> {
                    score += 0.85f
                    // Mines are better in packs; chains are how they kill.
                    score += MathX.clamp(hazardsAhead * 0.16f, 0f, 0.55f)
                }
                DeployKind.ICE_FLOE -> score += 0.45f + MathX.clamp01(abs(s.vx) / 16f) * 0.9f
                DeployKind.MAELSTROM -> score += if (s.surf > 0.45f || abs(s.vx) > 11f) 1.5f else 0.2f
                DeployKind.TENTACLE -> score += if (s.hull < 55f) 1.5f else 0.5f
                DeployKind.CORSAIRS -> score += if (corsairsAlive <= 1) 1.15f else 0.1f
                DeployKind.ROGUE_WAVE -> {
                    score += if (abs(s.vx) > 10f) 1.25f else 0.35f
                    score += if (s.airborne) 0.6f else 0f
                }
                DeployKind.SQUALL -> score += if (s.trim > 0.7f) 1.4f else 0.25f
                DeployKind.AETHER_SNARE -> {
                    // Only worth it once they have shown you what they depend on.
                    val top = spellUse[snareTarget()]
                    score += MathX.clamp(top * 0.35f, 0f, 1.5f)
                }
                DeployKind.KRAKEN -> continue
            }

            // Prefer to spend malice that is about to be wasted at the cap.
            if (world.malice > Config.MAX_MALICE * 0.85f) score += 0.4f
            // Cheap options when poor.
            score -= (def.cost / Config.MAX_MALICE) * (1f - MathX.clamp01(world.malice / Config.MAX_MALICE)) * 1.4f
            score *= rng.range(0.82f, 1.18f)

            if (score > bestScore) { bestScore = score; bestKind = kind; bestX = x }
        }

        return bestKind?.let { it to bestX }
    }
}
