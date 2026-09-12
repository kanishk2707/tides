package com.polariz.aethertides.server

import com.polariz.aethertides.shared.ai.NavigatorAi
import com.polariz.aethertides.shared.ai.TempestAi
import com.polariz.aethertides.shared.math.MathX
import com.polariz.aethertides.shared.net.ByteWriter
import com.polariz.aethertides.shared.net.Msg
import com.polariz.aethertides.shared.net.Packets
import com.polariz.aethertides.shared.net.Snapshot
import com.polariz.aethertides.shared.sim.Command
import com.polariz.aethertides.shared.sim.Config
import com.polariz.aethertides.shared.sim.DeployKind
import com.polariz.aethertides.shared.sim.EndReason
import com.polariz.aethertides.shared.sim.MatchPhase
import com.polariz.aethertides.shared.sim.NavInput
import com.polariz.aethertides.shared.sim.Role
import com.polariz.aethertides.shared.sim.SpellKind
import com.polariz.aethertides.shared.sim.World
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * One match between two seats.
 *
 * The room owns the only authoritative [World]. Clients send inputs and intents; every cost,
 * cooldown, range check and legality rule is evaluated here. A seat may be a bot, which is how
 * offline practice and drop-outs are handled without a second code path.
 *
 * A match is two rounds over the *same* seed with the roles swapped, and the better navigator
 * run wins. That format is what makes an asymmetric game fair: both players sail exactly the
 * same sea against exactly the same opposition, so the result measures the players rather than
 * the balance of the two roles.
 */
class Room(
    val id: Int,
    val seed: Long,
    /** Called once per completed series with (room, winner, loser). Ratings live upstream. */
    private val onSeriesEnd: (Room, Seat, Seat) -> Unit,
    private val onFinished: (Room) -> Unit
) {

    class Seat(val player: Player?, val isBot: Boolean, var botSkill: Float = 0.65f) {
        val commands = ConcurrentLinkedQueue<Command>()
        val input = NavInput()
        var lastSeq = 0
        var roundScore = FloatArray(2) { -1f }
        var wantsRematch = false
        /** How far and how long this seat's own navigator run went. For the profile. */
        var navDistance = 0f
        var navDuration = 0f
        val name: String get() = player?.name ?: "The Tempest"
        val rating: Int get() = player?.rating ?: 1200
    }

    lateinit var navigatorSeat: Seat
        private set
    lateinit var tempestSeat: Seat
        private set

    var world = World(seed)
        private set

    var round = 0
        private set
    var closed = false
        private set

    private var navBot: NavigatorAi? = null
    private var tempestBot: TempestAi? = null

    private val writer = ByteWriter(4096)
    private var tickCounter = 0
    private var endHoldTimer = 0f

    /** Round scores, indexed [seatIndex][round]. Higher is a better navigator run. */
    private val seats = ArrayList<Seat>(2)

    fun seat(role: Role): Seat = if (role == Role.NAVIGATOR) navigatorSeat else tempestSeat

    fun start(a: Seat, b: Seat, aRole: Role) {
        seats.clear(); seats.add(a); seats.add(b)
        assignRoles(a, b, aRole)
        beginRound(0)
    }

    private fun assignRoles(a: Seat, b: Seat, aRole: Role) {
        if (aRole == Role.NAVIGATOR) {
            navigatorSeat = a; tempestSeat = b
        } else {
            navigatorSeat = b; tempestSeat = a
        }
        a.player?.role = aRole
        b.player?.role = aRole.other()
    }

    private fun beginRound(r: Int) {
        round = r
        // Same seed both rounds: identical water, identical course. Only the players differ.
        world = World(seed)
        world.reset()
        navBot = if (navigatorSeat.isBot) NavigatorAi(world, seed + r, navigatorSeat.botSkill) else null
        tempestBot = if (tempestSeat.isBot) TempestAi(world, seed + r * 31, tempestSeat.botSkill) else null
        endHoldTimer = 0f
        navigatorSeat.commands.clear()
        tempestSeat.commands.clear()
        navigatorSeat.wantsRematch = false
        tempestSeat.wantsRematch = false

        for (s in seats) {
            val p = s.player ?: continue
            val opponent = if (s === navigatorSeat) tempestSeat else navigatorSeat
            p.send(
                Packets.matchFound(
                    id, p.role.id, seed, opponent.name, opponent.rating, opponent.isBot
                )
            )
        }
    }

    // =======================================================================
    // Tick
    // =======================================================================

    fun tick(dt: Float) {
        if (closed) return

        drainCommands(navigatorSeat, Role.NAVIGATOR)
        drainCommands(tempestSeat, Role.TEMPEST)

        navBot?.let { it.update(dt); navigatorSeat.input.set(it.input) }
        tempestBot?.update(dt)

        world.step(navigatorSeat.input, dt)

        tickCounter++
        if (tickCounter % Config.TICKS_PER_SNAPSHOT == 0) broadcastSnapshot()

        if (world.phase == MatchPhase.ENDED) {
            endHoldTimer += dt
            // Hold briefly so the final moment plays out on both screens before the card.
            if (endHoldTimer > 2.2f) finishRound()
        }
    }

    private fun drainCommands(seat: Seat, role: Role) {
        while (true) {
            val c = seat.commands.poll() ?: break
            applyCommand(c, role)
        }
    }

    /**
     * The trust boundary. A client can ask for anything; nothing here takes the client's word
     * for whether it is affordable, off cooldown, in range, or even for this role.
     */
    private fun applyCommand(c: Command, role: Role) {
        when (c.type) {
            Command.CAST_SPELL -> {
                if (role != Role.NAVIGATOR) return
                val kind = SpellKind.of(c.a)
                if (!kind.isBase) return
                world.castSpell(kind, c.x, c.y)
            }
            Command.DEPLOY -> {
                if (role != Role.TEMPEST) return
                val kind = DeployKind.of(c.a)
                if (kind == DeployKind.KRAKEN) return          // only via FURY
                world.deploy(kind, c.x, c.y, c.y.toInt())
            }
            Command.STORM_DIAL -> {
                if (role != Role.TEMPEST) return
                world.requestStorm(c.x)
            }
            Command.FURY -> {
                if (role != Role.TEMPEST) return
                world.releaseFury()
            }
            Command.SNARE_PICK -> {
                if (role != Role.TEMPEST) return
                world.deploy(DeployKind.AETHER_SNARE, world.ship.x + 150f, 0f, c.a)
            }
            Command.REMATCH -> seat(role).wantsRematch = true
            else -> {}
        }
    }

    private fun broadcastSnapshot() {
        val navBytes = Snapshot.encode(world, navigatorSeat.lastSeq, writer)
        navigatorSeat.player?.send(navBytes)
        // Both roles see the same authoritative state; only the HUD differs. Fog of war would
        // mean two encodes, and would also make the Tempest guess at what they are doing.
        tempestSeat.player?.send(navBytes)
    }

    // =======================================================================
    // Rounds and series
    // =======================================================================

    /**
     * Score a navigator run. Reaching the shore always beats not reaching it; among finishes,
     * faster wins; among failures, further wins.
     */
    private fun scoreRound(): Float {
        val w = world
        return if (w.winner == Role.NAVIGATOR && w.endReason == EndReason.SHORE_REACHED) {
            Config.COURSE_LENGTH + (Config.MATCH_TIME_LIMIT - w.elapsed) * 10f
        } else {
            w.stats.distance
        }
    }

    private fun finishRound() {
        val score = scoreRound()
        navigatorSeat.roundScore[round] = score
        navigatorSeat.navDistance = world.stats.distance
        navigatorSeat.navDuration = world.stats.duration

        val seriesOver = round >= 1
        var seriesWinnerSeat: Seat? = null
        if (seriesOver) {
            val a = seats[0]
            val b = seats[1]
            val aBest = a.roundScore.max()
            val bBest = b.roundScore.max()
            seriesWinnerSeat = if (aBest >= bBest) a else b
        }

        for (s in seats) {
            val p = s.player ?: continue
            val mine = if (s === navigatorSeat) score else -1f
            val theirs = if (s === navigatorSeat) -1f else score
            p.send(buildRoundEnd(s, mine, theirs, seriesOver, seriesWinnerSeat === s))
        }

        if (seriesOver) {
            val w = seriesWinnerSeat
            if (w != null) {
                val l = seats.first { it !== w }
                try { onSeriesEnd(this, w, l) } catch (e: Exception) {
                    System.err.println("series end hook failed: " + e)
                }
            }
            close()
        } else {
            // Swap the seats and run the same course again.
            val a = seats[0]
            val b = seats[1]
            val aWasNav = a === navigatorSeat
            assignRoles(a, b, if (aWasNav) Role.TEMPEST else Role.NAVIGATOR)
            beginRound(1)
        }
    }

    private fun buildRoundEnd(
        seat: Seat, myScore: Float, oppScore: Float, seriesOver: Boolean, won: Boolean
    ): ByteArray {
        val w = world
        val st = w.stats
        return ByteWriter(160)
            .u8(Msg.MATCH_END)
            .u8(round)
            .u8(w.winner?.id ?: 0)
            .u8(w.endReason.id)
            .bool(seriesOver)
            .bool(won)
            .f32(if (myScore >= 0f) myScore else oppScore)
            .f32(seat.roundScore[0])
            .f32(seat.roundScore[1])
            .f32(st.distance)
            .f32(st.duration)
            .f32(st.damageTaken)
            .i32(st.corsairsDowned)
            .i32(st.hazardsDeployed)
            .i32(st.spellsCast)
            .i32(st.fusions)
            .i32(st.absorbed)
            .f32(st.bestAir)
            .f32(st.topSpeed)
            .f32(st.timeSurfing)
            .bytes()
    }

    // =======================================================================
    // Lifecycle
    // =======================================================================

    /** A seat went away. Hand it to a bot so the remaining player still gets a finished game. */
    fun onPlayerLost(player: Player) {
        if (closed) return
        val seat = seats.firstOrNull { it.player === player } ?: return
        val other = seats.firstOrNull { it !== seat }
        other?.player?.send(Packets.opponentLeft())

        val replacement = Seat(null, isBot = true, botSkill = 0.6f)
        val idx = seats.indexOf(seat)
        seats[idx] = replacement
        if (seat === navigatorSeat) {
            navigatorSeat = replacement
            navBot = NavigatorAi(world, seed + round, replacement.botSkill)
        } else {
            tempestSeat = replacement
            tempestBot = TempestAi(world, seed + round * 31, replacement.botSkill)
        }

        if (seats.none { it.player != null }) close()
    }

    fun close() {
        if (closed) return
        closed = true
        onFinished(this)
    }

    /** The room threw mid-tick. Tell whoever is still here, then close without scoring. */
    fun abort() {
        for (s in seats) s.player?.send(Packets.opponentLeft())
        close()
    }

    fun hasHumans(): Boolean = seats.any { it.player != null }
}
