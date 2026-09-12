package com.mythron.aethertides.server

import com.mythron.aethertides.shared.net.ByteReader
import com.mythron.aethertides.shared.net.Denied
import com.mythron.aethertides.shared.net.Limits
import com.mythron.aethertides.shared.net.Msg
import com.mythron.aethertides.shared.net.Packets
import com.mythron.aethertides.shared.net.QueueMode
import com.mythron.aethertides.shared.sim.Command
import com.mythron.aethertides.shared.sim.Config
import com.mythron.aethertides.shared.sim.Role
import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.protocols.Protocol
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.pow

/** One connected client. */
class Player(val id: Int, val socket: WebSocket, val ip: String) {
    var name: String = "Sailor"
    var rating: Int = 1200
    var role: Role = Role.NAVIGATOR
    var room: Room? = null
    var queued = false
    var queueMode = QueueMode.QUICK
    var preferredRole = -1
    var privateCode = ""
    var lastSeen = System.nanoTime()

    /** Set once HELLO has been accepted. Nothing but PING is honoured before that. */
    @Volatile var authenticated = false
    @Volatile var helloSeen = false
    var userId: String = ""
    var guest = true
    var profile: Profile? = null
    var token: String = ""

    private var commandBudget = 0f
    private var budgetStamp = System.nanoTime()

    fun send(bytes: ByteArray) {
        try {
            if (socket.isOpen) socket.send(bytes)
        } catch (_: Exception) {
            // A dead socket is handled by onClose; nothing useful to do here.
        }
    }

    /** Returns false when the client is sending more actions than a human could. */
    fun spendCommand(): Boolean {
        val now = System.nanoTime()
        val dt = (now - budgetStamp) / 1e9f
        budgetStamp = now
        commandBudget = minOf(12f, commandBudget + dt * 8f)
        if (commandBudget < 1f) return false
        commandBudget -= 1f
        return true
    }

    fun close(reason: String) {
        try { socket.close(1000, reason) } catch (_: Exception) {}
    }
}

/**
 * The match server.
 *
 * One thread runs every room at a fixed 60 Hz; websocket callbacks only enqueue. Anything
 * that touches match state from another thread -- an auth result arriving, a socket closing
 * -- is posted to the simulation thread and applied between ticks. That single rule is what
 * makes the whole thing safe to reason about: there is exactly one writer.
 *
 * Every number a client sends is checked for finiteness at this boundary. The simulation is
 * built on clamps, and a clamp cannot stop NaN.
 */
class GameServer(private val cfg: ServerConfig, private val supabase: Supabase?) :
    WebSocketServer(InetSocketAddress(cfg.port), 2, frameLimitedDrafts()) {

    private val players = ConcurrentHashMap<WebSocket, Player>()
    private val rooms = Collections.synchronizedList(ArrayList<Room>())
    private val matchmaker = Matchmaker(cfg.botFallbackSeconds)
    private val nextPlayerId = AtomicInteger(1)
    private val nextRoomId = AtomicInteger(1)
    private val perIp = ConcurrentHashMap<String, AtomicInteger>()

    /** Work handed to the simulation thread from anywhere else. */
    private val simTasks = ConcurrentLinkedQueue<() -> Unit>()

    /** Blocking calls to the identity provider run here, never on the sim thread. */
    private val io = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "aether-io").apply { isDaemon = true }
    }

    @Volatile private var running = true
    @Volatile var simAlive = false
        private set
    private lateinit var loopThread: Thread
    private val startedAt = System.currentTimeMillis()

    val authFailures = AtomicLong()
    val rejectedFull = AtomicLong()

    companion object {
        /** Refuse frames larger than any legitimate client message, before they are allocated. */
        fun frameLimitedDrafts(): List<Draft> =
            listOf(Draft_6455(emptyList(), listOf(Protocol("")), Limits.MAX_CLIENT_FRAME))
    }

    // =======================================================================
    // Connection lifecycle (websocket thread)
    // =======================================================================

    override fun onStart() {
        connectionLostTimeout = 30
        loopThread = Thread({ loop() }, "aether-sim").apply { isDaemon = false; start() }
        println("AETHER TIDES server: ${cfg.describe()} protocol=${Config.PROTOCOL_VERSION}")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        val ip = conn.remoteSocketAddress?.address?.hostAddress ?: "?"

        if (players.size >= cfg.maxPlayers) {
            rejectedFull.incrementAndGet()
            try { conn.send(Packets.denied(Denied.SERVER_FULL)) } catch (_: Exception) {}
            conn.close(1013, "full")
            return
        }
        val count = perIp.computeIfAbsent(ip) { AtomicInteger() }
        if (count.incrementAndGet() > cfg.maxPerIp) {
            count.decrementAndGet()
            rejectedFull.incrementAndGet()
            try { conn.send(Packets.denied(Denied.SERVER_FULL)) } catch (_: Exception) {}
            conn.close(1013, "too many connections")
            return
        }

        val p = Player(nextPlayerId.getAndIncrement(), conn, ip)
        players[conn] = p
        // The welcome waits for HELLO: a client is nobody until it has said who it is.
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        val p = players.remove(conn) ?: return
        perIp[p.ip]?.let { if (it.decrementAndGet() <= 0) perIp.remove(p.ip, it) }
        simTasks.add {
            matchmaker.remove(p)
            p.room?.onPlayerLost(p)
            p.room = null
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        if (conn == null) System.err.println("server error: ${ex.message}")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        // Text frames are not part of the protocol. Ignore rather than disconnect, so a
        // browser poking the port does not take a slot down.
    }

    override fun onMessage(conn: WebSocket, message: ByteBuffer) {
        val p = players[conn] ?: return
        p.lastSeen = System.nanoTime()
        if (message.remaining() > Limits.MAX_CLIENT_FRAME) {
            p.close("frame too large")
            return
        }
        val bytes = ByteArray(message.remaining())
        message.get(bytes)
        try {
            handle(p, ByteReader(bytes))
        } catch (e: Exception) {
            // A malformed frame from an authenticated client is a bug in a client build; from
            // an unauthenticated one it is probing. Either way it does not get a second frame.
            if (!p.authenticated) p.close("bad frame")
        }
    }

    // =======================================================================
    // Message handling (websocket thread; mutations posted to sim thread)
    // =======================================================================

    private fun handle(p: Player, r: ByteReader) {
        val id = r.u8()

        if (id == Msg.PING) {
            p.send(Packets.pong(r.i64(), System.currentTimeMillis()))
            return
        }

        if (id == Msg.HELLO) {
            hello(p, r)
            return
        }

        if (!p.authenticated) {
            p.send(Packets.denied(Denied.AUTH_REQUIRED))
            return
        }

        when (id) {
            Msg.QUEUE -> {
                val mode = r.u8()
                val role = r.i8()
                val code = Limits.sanitizeCode(r.str())
                if (mode == QueueMode.PRIVATE && code.length < Limits.PRIVATE_CODE_MIN) {
                    p.send(Packets.denied(Denied.CODE_TOO_SHORT))
                    return
                }
                simTasks.add {
                    if (p.room != null) return@add
                    p.queueMode = mode
                    p.preferredRole = role
                    p.privateCode = code
                    matchmaker.add(p)
                }
            }

            Msg.CANCEL_QUEUE -> simTasks.add { matchmaker.remove(p) }

            Msg.NAV_INPUT -> {
                // Quantised bytes on the wire, so these are finite by construction.
                val seq = r.i32()
                val trim = r.q8()
                val lean = r.q8() * 2f - 1f
                val flags = r.u8()
                simTasks.add {
                    val room = p.room ?: return@add
                    if (p.role != Role.NAVIGATOR) return@add
                    val seat = room.seat(Role.NAVIGATOR)
                    if (seq <= seat.lastSeq && abs(seq - seat.lastSeq) < 1000) return@add
                    seat.lastSeq = seq
                    seat.input.trim = trim
                    seat.input.lean = lean
                    seat.input.pump = (flags and 1) != 0
                    seat.input.seq = seq
                    if ((flags and 2) != 0) room.world.tryBrace()
                }
            }

            Msg.COMMAND -> {
                if (!p.spendCommand()) { p.send(Packets.denied(Denied.RATE_LIMITED)); return }
                val type = r.u8()
                val a = r.i32()
                val x = r.f32()
                val y = r.f32()
                // The one place raw floats enter from outside. A NaN here would survive every
                // clamp downstream and corrupt the match for both players.
                if (!x.isFinite() || !y.isFinite()) { p.send(Packets.denied(Denied.ILLEGAL_ACTION)); return }
                if (abs(x) > 1e6f || abs(y) > 1e6f) { p.send(Packets.denied(Denied.ILLEGAL_ACTION)); return }
                simTasks.add {
                    val room = p.room ?: run { p.send(Packets.denied(Denied.NOT_IN_MATCH)); return@add }
                    room.seat(p.role).commands.add(Command(type, a, x, y))
                }
            }

            Msg.REMATCH -> simTasks.add { p.room?.seat(p.role)?.wantsRematch = true }

            Msg.LEAVE -> simTasks.add {
                matchmaker.remove(p)
                p.room?.onPlayerLost(p)
                p.room = null
            }

            Msg.DELETE_ACCOUNT -> deleteAccount(p)
        }
    }

    /**
     * Identity. The token is handed to the provider on an I/O thread; the answer is applied on
     * the simulation thread. Until it arrives the connection is a stranger.
     */
    private fun hello(p: Player, r: ByteReader) {
        if (p.helloSeen) return
        p.helloSeen = true

        val version = r.i32()
        if (version != Config.PROTOCOL_VERSION) {
            p.send(Packets.denied(Denied.VERSION_MISMATCH))
            p.close("version")
            return
        }
        val token = r.lstr()
        val requestedName = Limits.sanitizeName(r.str()).ifBlank { "Sailor" }

        if (token.isBlank()) {
            if (!cfg.allowGuests) {
                p.send(Packets.denied(Denied.AUTH_REQUIRED))
                p.close("auth required")
                return
            }
            simTasks.add { admit(p, userId = "guest-${p.id}", guest = true, profile = null, name = requestedName) }
            return
        }

        val sb = supabase
        if (sb == null) {
            // No provider configured: tokens cannot be checked, so they are not trusted.
            if (!cfg.allowGuests) { p.send(Packets.denied(Denied.AUTH_FAILED)); p.close("no auth"); return }
            simTasks.add { admit(p, "guest-${p.id}", true, null, requestedName) }
            return
        }

        io.execute {
            val identity = sb.verify(token)
            if (identity == null) {
                authFailures.incrementAndGet()
                p.send(Packets.denied(Denied.AUTH_FAILED))
                p.close("auth failed")
                return@execute
            }
            val profile = sb.loadOrCreate(identity.userId, requestedName)
            // If the name the client asked for is new and valid, honour it. The sanitised
            // form is what gets stored and what everyone else sees.
            if (profile != null && requestedName != "Sailor" && profile.displayName != requestedName) {
                if (Limits.nameIsValid(requestedName) && sb.updateName(profile.userId, requestedName)) {
                    profile.displayName = requestedName
                }
            }
            p.token = token
            simTasks.add { admit(p, identity.userId, false, profile, profile?.displayName ?: requestedName) }
        }
    }

    /** Sim thread. Finalise a connection that has proven who it is. */
    private fun admit(p: Player, userId: String, guest: Boolean, profile: Profile?, name: String) {
        if (!p.socket.isOpen) return
        p.userId = userId
        p.guest = guest
        p.profile = profile
        p.name = name
        p.rating = profile?.rating ?: 1200
        p.authenticated = true
        p.send(
            Packets.welcome(
                p.id, System.currentTimeMillis(), userId, name,
                p.rating, profile?.played ?: 0, profile?.won ?: 0, guest
            )
        )
    }

    private fun deleteAccount(p: Player) {
        val sb = supabase
        if (p.guest || sb == null) {
            p.send(Packets.accountDeleted())
            p.close("deleted")
            return
        }
        simTasks.add {
            matchmaker.remove(p)
            p.room?.onPlayerLost(p)
            p.room = null
        }
        io.execute {
            val ok = sb.deleteUser(p.userId)
            if (ok) {
                sb.forget(p.token)
                p.send(Packets.accountDeleted())
                println("account deleted: ${p.userId}")
            } else {
                p.send(Packets.denied(Denied.ILLEGAL_ACTION))
            }
            p.close("deleted")
        }
    }

    // =======================================================================
    // Simulation thread
    // =======================================================================

    private fun loop() {
        simAlive = true
        val stepNanos = (1_000_000_000L / Config.TICK_RATE)
        var next = System.nanoTime()
        var matchTimer = 0f

        try {
            while (running) {
                val now = System.nanoTime()
                if (now < next) {
                    val sleep = (next - now) / 1_000_000L
                    if (sleep > 1) {
                        try { Thread.sleep(sleep - 1) } catch (_: InterruptedException) { return }
                    }
                    continue
                }
                next += stepNanos
                if (now - next > stepNanos * 10) next = now + stepNanos

                drainTasks()

                val dt = Config.DT
                synchronized(rooms) {
                    var i = 0
                    while (i < rooms.size) {
                        val room = rooms[i]
                        try {
                            room.tick(dt)
                        } catch (e: Exception) {
                            // One broken room must never take the others with it. Close it,
                            // tell its players, and carry on.
                            System.err.println("room ${room.id} failed: $e")
                            e.printStackTrace()
                            try { room.abort() } catch (_: Exception) {}
                        }
                        if (room.closed) {
                            room.seat(Role.NAVIGATOR).player?.room = null
                            room.seat(Role.TEMPEST).player?.room = null
                            rooms.removeAt(i)
                        } else i++
                    }
                }

                matchTimer += dt
                if (matchTimer >= 0.5f) {
                    matchTimer = 0f
                    try { matchmaker.pump(::createRoom) } catch (e: Exception) {
                        System.err.println("matchmaker failed: $e")
                    }
                }
            }
        } finally {
            simAlive = false
        }
    }

    private fun drainTasks() {
        var n = 0
        while (n < 2000) {
            val t = simTasks.poll() ?: break
            try { t() } catch (e: Exception) { System.err.println("task failed: $e") }
            n++
        }
    }

    private fun createRoom(a: Player?, b: Player?, aRole: Role, botSkill: Float) {
        val seed = System.nanoTime() xor (nextRoomId.get().toLong() shl 24)
        val room = Room(nextRoomId.getAndIncrement(), seed, ::onSeriesEnd) { r ->
            r.seat(Role.NAVIGATOR).player?.room = null
            r.seat(Role.TEMPEST).player?.room = null
        }
        val seatA = Room.Seat(a, a == null, botSkill)
        val seatB = Room.Seat(b, b == null, botSkill)
        a?.room = room
        b?.room = room
        room.start(seatA, seatB, aRole)
        synchronized(rooms) { rooms.add(room) }
        println("room ${room.id}: ${a?.name ?: "bot"} vs ${b?.name ?: "bot"}")
    }

    // =======================================================================
    // Ratings
    // =======================================================================

    /**
     * Series over. Ratings move by plain Elo, K = 32, only for authenticated players and only
     * against a human: beating the bot counts as a match but not as a rating event.
     */
    private fun onSeriesEnd(room: Room, winner: Room.Seat, loser: Room.Seat) {
        val sb = supabase ?: return
        val seats = listOf(winner, loser)
        val humans = seats.filter { it.player != null && !it.player.guest && it.player.profile != null }
        if (humans.isEmpty()) return
        val rated = humans.size == 2

        for (seat in humans) {
            val p = seat.player!!
            val prof = p.profile!!
            val won = seat === winner
            val opponent = seats.firstOrNull { it !== seat }?.player
            val before = prof.rating
            var after = before
            if (rated && opponent != null) {
                val expected = 1.0 / (1.0 + 10.0.pow((opponent.rating - before) / 400.0))
                after = (before + 32.0 * ((if (won) 1.0 else 0.0) - expected)).toInt().coerceIn(100, 4000)
            }
            prof.played += 1
            if (won) prof.won += 1
            prof.bestDistance = maxOf(prof.bestDistance, seat.navDistance)
            prof.rating = after
            p.rating = after

            val oppId = opponent?.takeIf { !it.guest }?.userId
            io.execute {
                sb.recordResult(prof, oppId, won, after, seat.navDistance, seat.navDuration, room.seed)
                p.send(Packets.profile(prof.displayName, after, prof.played, prof.won, after - before))
            }
        }
    }

    // =======================================================================

    fun shutdown() {
        running = false
        io.shutdownNow()
        stop(1000)
    }

    fun health(): Health.Snapshot = Health.Snapshot(
        alive = simAlive && loopThread.isAlive,
        players = players.size,
        rooms = rooms.size,
        queued = matchmaker.size(),
        uptimeSeconds = (System.currentTimeMillis() - startedAt) / 1000,
        authFailures = authFailures.get(),
        rejectedFull = rejectedFull.get()
    )

    fun stats(): String = "players=${players.size} rooms=${rooms.size} queued=${matchmaker.size()}"
}
