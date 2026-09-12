package com.polariz.aethertides.client.net

import com.polariz.aethertides.shared.ai.NavigatorAi
import com.polariz.aethertides.shared.ai.TempestAi
import com.polariz.aethertides.shared.net.ByteReader
import com.polariz.aethertides.shared.net.ByteWriter
import com.polariz.aethertides.shared.net.Snapshot
import com.polariz.aethertides.shared.net.SnapshotFrame
import com.polariz.aethertides.shared.sim.Command
import com.polariz.aethertides.shared.sim.Config
import com.polariz.aethertides.shared.sim.DeployKind
import com.polariz.aethertides.shared.sim.EndReason
import com.polariz.aethertides.shared.sim.MatchPhase
import com.polariz.aethertides.shared.sim.NavInput
import com.polariz.aethertides.shared.sim.Role
import com.polariz.aethertides.shared.sim.SpellKind
import com.polariz.aethertides.shared.sim.World

/**
 * Offline play.
 *
 * A complete server, in process. It runs the authoritative world at the same fixed 60 Hz, ships
 * snapshots to itself at the same 20 Hz, and encodes them through the same binary codec -- so
 * the offline game exercises the whole multiplayer pipeline, including interpolation and
 * prediction. Practice here is practice for the real thing, and the netcode is being tested
 * every time anybody plays.
 *
 * The one thing it adds is a bot in the empty seat, and the same two-round role swap the
 * server runs, so a solo player learns both halves of the game.
 */
class LocalSession(
    seed: Long,
    startRole: Role,
    var difficulty: Float
) : Session(seed) {

    private var sim = World(seed)
    private var navBot: NavigatorAi? = null
    private var tempestBot: TempestAi? = null

    private val writer = ByteWriter(4096)
    private val decodeInto = SnapshotFrame()
    private val navInput = NavInput()
    private val queued = ArrayList<Command>(8)

    private var accumulator = 0f
    private var tickCounter = 0
    private var endHold = 0f
    private var round = 0
    private val roundScore = floatArrayOf(-1f, -1f)
    private var lastAckSeq = 0

    override val connected: Boolean get() = true
    override val statusText: String get() = if (round == 0) "PRACTICE" else "PRACTICE - ROUND 2"

    init {
        role = startRole
        opponentIsBot = true
        opponentName = if (startRole == Role.NAVIGATOR) "The Tempest" else "The Navigator"
        beginRound(0, startRole)
    }

    private fun beginRound(r: Int, myRole: Role) {
        round = r
        role = myRole
        sim = World(seed)
        sim.reset()
        // The bot takes whichever seat the player is not in.
        navBot = if (myRole == Role.TEMPEST) NavigatorAi(sim, seed + r, difficulty) else null
        tempestBot = if (myRole == Role.NAVIGATOR) TempestAi(sim, seed + r * 31, difficulty) else null
        opponentName = if (myRole == Role.NAVIGATOR) "The Tempest" else "The Navigator"
        accumulator = 0f
        tickCounter = 0
        endHold = 0f
        queued.clear()
        lastAckSeq = 0
        world.reset()
        resetPrediction()
    }

    // -----------------------------------------------------------------------

    override fun poll(dt: Float) {
        // Clamp so a stall (app resumed, GC pause) cannot make the sim run away.
        accumulator += dt.coerceAtMost(0.25f)
        while (accumulator >= Config.DT) {
            accumulator -= Config.DT
            step()
        }
    }

    private fun step() {
        drainCommands()

        navBot?.let { it.update(Config.DT); navInput.set(it.input) }
        tempestBot?.update(Config.DT)

        sim.step(navInput, Config.DT)

        tickCounter++
        if (tickCounter % Config.TICKS_PER_SNAPSHOT == 0) {
            val bytes = Snapshot.encode(sim, lastAckSeq, writer)
            val r = ByteReader(bytes)
            r.u8()                      // message id
            acceptFrame(Snapshot.decode(r, decodeInto))
        }

        if (sim.phase == MatchPhase.ENDED) {
            endHold += Config.DT
            if (endHold > 2.2f) finishRound()
        }
    }

    private fun drainCommands() {
        for (c in queued) {
            when (c.type) {
                Command.CAST_SPELL -> if (role == Role.NAVIGATOR) {
                    val k = SpellKind.of(c.a)
                    if (k.isBase) sim.castSpell(k, c.x, c.y)
                }
                Command.DEPLOY -> if (role == Role.TEMPEST) {
                    val k = DeployKind.of(c.a)
                    if (k != DeployKind.KRAKEN) sim.deploy(k, c.x, c.y, c.y.toInt())
                }
                Command.STORM_DIAL -> if (role == Role.TEMPEST) sim.requestStorm(c.x)
                Command.FURY -> if (role == Role.TEMPEST) sim.releaseFury()
                Command.SNARE_PICK -> if (role == Role.TEMPEST) {
                    sim.deploy(DeployKind.AETHER_SNARE, sim.ship.x + 150f, 0f, c.a)
                }
            }
        }
        queued.clear()
    }

    override fun sendInput(seq: Int, trim: Float, lean: Float, pump: Boolean, brace: Boolean) {
        if (role != Role.NAVIGATOR) return
        navInput.trim = trim
        navInput.lean = lean
        navInput.pump = pump
        navInput.seq = seq
        lastAckSeq = seq
        if (brace) sim.tryBrace()
    }

    override fun sendCommand(type: Int, a: Int, x: Float, y: Float) {
        queued.add(Command(type, a, x, y))
    }

    override fun leave() {
        // Nothing to hang up.
    }

    // -----------------------------------------------------------------------

    private fun scoreRound(): Float =
        if (sim.winner == Role.NAVIGATOR && sim.endReason == EndReason.SHORE_REACHED) {
            Config.COURSE_LENGTH + (Config.MATCH_TIME_LIMIT - sim.elapsed) * 10f
        } else {
            sim.stats.distance
        }

    private fun finishRound() {
        val score = scoreRound()
        // Whoever sailed this round owns the score.
        val mineThisRound = role == Role.NAVIGATOR
        roundScore[round] = score

        val r = RoundResult()
        r.round = round
        r.winnerRole = sim.winner?.id ?: 0
        r.reason = sim.endReason.id
        r.seriesOver = round >= 1
        r.myScore = score
        r.round0 = roundScore[0]
        r.round1 = roundScore[1]
        r.distance = sim.stats.distance
        r.duration = sim.stats.duration
        r.damageTaken = sim.stats.damageTaken
        r.corsairsDowned = sim.stats.corsairsDowned
        r.hazardsDeployed = sim.stats.hazardsDeployed
        r.spellsCast = sim.stats.spellsCast
        r.fusions = sim.stats.fusions
        r.absorbed = sim.stats.absorbed
        r.bestAir = sim.stats.bestAir
        r.topSpeed = sim.stats.topSpeed
        r.timeSurfing = sim.stats.timeSurfing

        if (r.seriesOver) {
            // Round 0 was sailed by one side, round 1 by the other. Better run takes it.
            val myRun = if (mineThisRound) roundScore[1] else roundScore[0]
            val theirRun = if (mineThisRound) roundScore[0] else roundScore[1]
            r.won = myRun >= theirRun
        } else {
            r.won = sim.winner == role
        }
        result = r
    }

    /** Start round two with the roles swapped. Called by the UI after the round card. */
    fun nextRound() {
        if (round >= 1) return
        beginRound(1, role.other())
        result = null
    }

    /** Play the whole thing again from a fresh sea. */
    fun restart(newSeed: Long, asRole: Role): LocalSession =
        LocalSession(newSeed, asRole, difficulty)

    val simPhase: MatchPhase get() = sim.phase
}
