package com.mythron.aethertides.client.net

import com.mythron.aethertides.shared.net.ByteReader
import com.mythron.aethertides.shared.net.Denied
import com.mythron.aethertides.shared.net.Limits
import com.mythron.aethertides.shared.net.Msg
import com.mythron.aethertides.shared.net.Packets
import com.mythron.aethertides.shared.net.Snapshot
import com.mythron.aethertides.shared.net.SnapshotFrame
import com.mythron.aethertides.shared.sim.Role
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import org.java_websocket.protocols.Protocol
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue

/** The server's view of who we are, as sent in WELCOME and updated by PROFILE. */
class ProfileInfo {
    @JvmField var userId = ""
    @JvmField var name = "Sailor"
    @JvmField var rating = 1200
    @JvmField var played = 0
    @JvmField var won = 0
    @JvmField var guest = true
    @JvmField var lastRatingDelta = 0
}

/**
 * Online play.
 *
 * The socket runs on its own thread and does nothing but queue byte arrays; everything is
 * decoded on the render thread inside [poll]. That keeps all game state single-threaded, which
 * is worth far more than the microseconds a parallel decode would save.
 *
 * The first frame out is HELLO with the auth token. Nothing else is sent until WELCOME comes
 * back, and everything the server tells us about ourselves in that WELCOME overrides whatever
 * we had locally: the server's profile is the truth, the local one is a cache.
 */
class OnlineSession(
    private val url: String,
    private val token: String,
    private val requestedName: String,
    private val queueMode: Int,
    private val preferredRole: Int,
    private val privateCode: String,
    seed: Long
) : Session(seed) {

    enum class State { CONNECTING, AUTHENTICATING, QUEUED, IN_MATCH, DISCONNECTED, REJECTED, DELETED }

    @Volatile var state = State.CONNECTING
        private set
    @Volatile private var queueCount = 0
    @Volatile private var queueEta = 0
    @Volatile var deniedReason = 0
        private set
    @Volatile var matchSeed: Long = seed
        private set
    @Volatile var opponentLeft = false
        private set

    val profile = ProfileInfo()
    /** Set when a PROFILE arrives so the UI can show the rating move once. */
    @Volatile var profileUpdated = false

    private val inbox = ConcurrentLinkedQueue<ByteArray>()
    private val decodeInto = SnapshotFrame()
    private var pingTimer = 0f
    private var socket: WebSocketClient? = null

    override val connected: Boolean
        get() = state == State.QUEUED || state == State.IN_MATCH

    override val statusText: String
        get() = when (state) {
            State.CONNECTING -> "CONNECTING"
            State.AUTHENTICATING -> "SIGNING IN"
            State.QUEUED -> if (queueCount >= 2) "PAIRING" else
                "SEARCHING - ${queueEta}s TO PRACTICE MATCH"
            State.IN_MATCH -> "$opponentName  -  ${pingMs}ms"
            State.DISCONNECTED -> "CONNECTION LOST"
            State.DELETED -> "ACCOUNT DELETED"
            State.REJECTED -> when (deniedReason) {
                Denied.VERSION_MISMATCH -> "UPDATE REQUIRED"
                Denied.AUTH_REQUIRED, Denied.AUTH_FAILED -> "SIGN-IN REJECTED"
                Denied.SERVER_FULL -> "SERVER FULL - TRY AGAIN"
                Denied.CODE_TOO_SHORT -> "CODE MUST BE ${Limits.PRIVATE_CODE_MIN}+ CHARACTERS"
                else -> "REFUSED BY SERVER"
            }
        }

    fun connect() {
        val client = object : WebSocketClient(
            URI(url), Draft_6455(emptyList(), listOf(Protocol("")), Limits.MAX_SERVER_FRAME)
        ) {
            override fun onOpen(handshake: ServerHandshake) {
                state = State.AUTHENTICATING
                send(Packets.hello(token, requestedName))
            }

            override fun onMessage(message: String) {}

            override fun onMessage(bytes: ByteBuffer) {
                if (bytes.remaining() > Limits.MAX_SERVER_FRAME) return
                val b = ByteArray(bytes.remaining())
                bytes.get(b)
                inbox.add(b)
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                if (state != State.REJECTED && state != State.DELETED) state = State.DISCONNECTED
            }

            override fun onError(ex: Exception?) {
                if (state != State.REJECTED && state != State.DELETED) state = State.DISCONNECTED
            }
        }
        client.connectionLostTimeout = 20
        // The socket must never keep the process alive on its own: when the game exits, so
        // does the connection. Otherwise a desktop build hangs on close with a match open.
        client.isDaemon = true
        socket = client
        try {
            client.connect()
        } catch (e: Exception) {
            state = State.DISCONNECTED
        }
    }

    // -----------------------------------------------------------------------

    override fun poll(dt: Float) {
        while (true) {
            val bytes = inbox.poll() ?: break
            try {
                handle(ByteReader(bytes))
            } catch (_: Exception) {
                // A malformed frame is not worth dropping the match over.
            }
        }

        pingTimer += dt
        if (pingTimer > 2f && state != State.DISCONNECTED) {
            pingTimer = 0f
            send(Packets.ping(System.currentTimeMillis()))
        }
    }

    private fun handle(r: ByteReader) {
        when (r.u8()) {
            Msg.WELCOME -> {
                r.i32(); r.i64()
                profile.userId = r.str()
                profile.name = r.str()
                profile.rating = r.i32()
                profile.played = r.i32()
                profile.won = r.i32()
                profile.guest = r.bool()
                profileUpdated = true
                // Now, and only now, are we somebody the server will talk to.
                send(Packets.queue(queueMode, preferredRole, privateCode))
                state = State.QUEUED
            }

            Msg.PROFILE -> {
                profile.name = r.str()
                profile.rating = r.i32()
                profile.played = r.i32()
                profile.won = r.i32()
                profile.lastRatingDelta = r.i32()
                profileUpdated = true
            }

            Msg.QUEUE_STATUS -> {
                queueCount = r.i32()
                queueEta = r.i32()
            }

            Msg.MATCH_FOUND -> {
                r.i32()
                role = Role.of(r.u8())
                matchSeed = r.i64()
                opponentName = r.str()
                r.i32()
                opponentIsBot = r.bool()
                state = State.IN_MATCH
                opponentLeft = false
                resetPrediction()
            }

            Msg.SNAPSHOT -> acceptFrame(Snapshot.decode(r, decodeInto))

            Msg.MATCH_END -> {
                val res = RoundResult()
                res.round = r.u8()
                res.winnerRole = r.u8()
                res.reason = r.u8()
                res.seriesOver = r.bool()
                res.won = r.bool()
                res.myScore = r.f32()
                res.round0 = r.f32()
                res.round1 = r.f32()
                res.distance = r.f32()
                res.duration = r.f32()
                res.damageTaken = r.f32()
                res.corsairsDowned = r.i32()
                res.hazardsDeployed = r.i32()
                res.spellsCast = r.i32()
                res.fusions = r.i32()
                res.absorbed = r.i32()
                res.bestAir = r.f32()
                res.topSpeed = r.f32()
                res.timeSurfing = r.f32()
                result = res
            }

            Msg.PONG -> {
                val sent = r.i64()
                r.i64()
                pingMs = (System.currentTimeMillis() - sent).toInt().coerceIn(0, 9999)
            }

            Msg.OPPONENT_LEFT -> opponentLeft = true

            Msg.ACCOUNT_DELETED -> state = State.DELETED

            Msg.DENIED -> {
                deniedReason = r.u8()
                when (deniedReason) {
                    Denied.VERSION_MISMATCH, Denied.AUTH_REQUIRED, Denied.AUTH_FAILED,
                    Denied.SERVER_FULL, Denied.CODE_TOO_SHORT -> state = State.REJECTED
                }
            }
        }
    }

    private fun send(bytes: ByteArray) {
        val s = socket ?: return
        try {
            if (s.isOpen) s.send(bytes)
        } catch (_: Exception) {
            state = State.DISCONNECTED
        }
    }

    override fun sendInput(seq: Int, trim: Float, lean: Float, pump: Boolean, brace: Boolean) {
        if (state != State.IN_MATCH || role != Role.NAVIGATOR) return
        send(Packets.navInput(seq, trim, lean, pump, brace))
    }

    override fun sendCommand(type: Int, a: Int, x: Float, y: Float) {
        if (state != State.IN_MATCH) return
        send(Packets.command(type, a, x, y))
    }

    /** Ask the server to erase the account. The reply is ACCOUNT_DELETED, then the socket closes. */
    fun requestAccountDeletion() {
        send(Packets.deleteAccount())
    }

    fun clearResult() {
        result = null
    }

    override fun leave() {
        send(Packets.leave())
        try { socket?.closeBlocking() } catch (_: Exception) {}
        socket = null
        if (state != State.DELETED) state = State.DISCONNECTED
    }
}
